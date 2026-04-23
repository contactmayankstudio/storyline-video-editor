package com.video.engine

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.audio.AudioClip
import com.video.engine.audio.AudioGainKeyframe
import com.video.engine.audio.AudioClipStore
import com.video.engine.audio.AudioImportController
import com.video.engine.audio.VoiceoverController
import com.video.engine.effects.EffectParams
import com.video.engine.overlay.OverlayStore
import com.video.engine.overlay.TextOverlay
import com.video.engine.overlay.TextOverlayView
import com.video.engine.pro.model.ClipSegment
import com.video.engine.pro.model.TrackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.video.engine.pro.model.TrackType
import com.video.engine.pro.timeline.MultiTrackTimelineView
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.stickers.StickerOverlayView
import com.video.engine.timeline.MultiClipTimeline
import com.video.engine.timeline.TimelineManager
import com.video.engine.transition.TransitionType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Professional Video Editor UI - MainActivity
 *
 * Architecture:
 * ============
 * This activity uses a production-style editor UI pattern where:
 * - UI layer (Kotlin/XML): Thin, handles layout + button callbacks
 * - Native layer (C++/JNI): All GPU rendering, threading, heavy lifting
 *
 * Controls:
 * - Play/Pause: Starts/stops playback
 * - Timeline: Horizontal scrollable clips with scrubbing
 * - Export: Resolution/FPS selection with progress dialog
 * - Text/Effects: Interactive overlay system
 */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val bannerRefreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            refreshTopBannerPlacement()
        }
    }

    private fun safeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, duration).show()
            }
        }
    }

    private fun activeCanvasTimelineView() =
        findViewById<com.video.engine.pro.timeline.TimelineCanvasView?>(R.id.timelineCanvasView)
            ?.takeIf { it.visibility == View.VISIBLE }

    private fun activeMultiTrackTimelineView() =
        multiTrackTimelineView?.takeIf { it.visibility == View.VISIBLE }

    private fun trackDisplayName(trackType: TrackType): String = trackType.displayName()

    private fun isTrackLocked(trackType: TrackType): Boolean =
        trackLockedOverrides[trackType] == true

    private fun trackVisibilityStateSnapshot(): Map<TrackType, Boolean> =
        TrackType.displayOrder().associateWith { trackType -> trackVisibilityOverrides[trackType] ?: true }

    private fun trackLockStateSnapshot(): Map<TrackType, Boolean> =
        TrackType.displayOrder().associateWith { trackType -> trackLockedOverrides[trackType] ?: false }

    private fun hasProjectContent(): Boolean {
        return timelineManager?.getClips()?.isNotEmpty() == true ||
            allTextOverlays().isNotEmpty() ||
            StickerClipStore.all().isNotEmpty() ||
            AudioClipStore.all().isNotEmpty()
    }

    private fun ensureTrackEditable(trackType: TrackType, actionName: String? = null): Boolean {
        if (!isTrackLocked(trackType)) return true
        val suffix = actionName?.let { " for $it" }.orEmpty()
        safeToast("${trackDisplayName(trackType)} track locked$suffix", Toast.LENGTH_SHORT)
        return false
    }

    private fun normalizeTrackZOrder(trackType: TrackType, requestedZ: Int): Int {
        return when (trackType) {
            TrackType.LAYER -> requestedZ.coerceIn(100, 199)
            TrackType.OVERLAY -> requestedZ.coerceAtLeast(200)
            TrackType.TEXT -> requestedZ.coerceAtLeast(400)
            else -> requestedZ
        }
    }

    // ANR-safe executeCommand: always runs on background thread, posts UI callback on main thread
    private fun execCmd(action: String, params: Map<String, Any> = emptyMap(), onResult: ((com.video.engine.NativeBridge.CommandResult) -> Unit)? = null) {
        commandExecutor.execute {
            val result = runCatching { NativeBridge.executeCommand(action, params) }.getOrNull()
                ?: NativeBridge.CommandResult(success = false, action = action, message = "error")
            if (onResult != null) mainHandler.post { onResult(result) }
        }
    }
    private var lastLayoutFetchMs = 0L
    private var lastTimelineScrollMs = 0L
    private var lastPreviewAudioSyncSignature = ""
    private data class ClipTimingSnapshot(
        val startTimeMs: Long,
        val durationMs: Long,
        val sourceInMs: Long,
        val sourceOutMs: Long,
    )

    private data class ClipPreviewTransform(
        val zoom: Float = 1.0f,
        val panXPx: Float = 0f,
        val panYPx: Float = 0f,
        val rotationDeg: Float = 0f,
        val mirrorX: Boolean = false,
    )

    private data class AspectRatioOption(
        val label: String,
        val width: Int,
        val height: Int,
    )

    private data class TextOverlayPreset(
        val label: String,
        val hint: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val color: Int,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val uppercase: Boolean = false,
        val defaultDurationMs: Int = 3000,
        val fontName: String? = null,
    )

    private enum class UndoDomain {
        EDITOR,
        TIMELINE,
    }

    private enum class ClipKind {
        VIDEO,
        OVERLAY,
        AUDIO,
        TEXT,
        STICKER,
        NONE,
    }

    private data class ClipToolbarItem(
        val buttonId: Int,
        val labelId: Int,
        val defaultLabel: String,
    )

    companion object {
        private const val TAG = "[UI]"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TEST_VIDEO_PATH = "/data/local/tmp/test.mp4"
        private const val PICK_VIDEO_REQUEST = 200
        private const val PICK_AUDIO_REQUEST = 201
        private const val EXPORT_NOTIFICATION_CHANNEL_ID = "video_export"
        private const val EXPORT_NOTIFICATION_ID = 1001
        private const val HARDWARE_TELEMETRY_REFRESH_MS = 750L
        private const val AUTOMATION_SMOKE_DURATION_MS = 15_000L
    }

    // UI References
    private var previewView: VideoPreviewView? = null
    private var previewContainerView: FrameLayout? = null
    private var timelineRecyclerView: RecyclerView? = null
    private var timelineCurrentTimeText: TextView? = null
    private var previewAspectRatioText: TextView? = null
    private var startScreenOverlayView: View? = null
    private var startRecentProjectsList: androidx.recyclerview.widget.RecyclerView? = null
    private var startRecentProjectsEmptyText: TextView? = null
    private var editorTopBannerContainer: FrameLayout? = null
    private var startTopBannerContainer: FrameLayout? = null
    private var clipToolbarContextLabel: TextView? = null
    private var hardwareTelemetrySummaryText: TextView? = null
    private var hardwareTelemetryReasonText: TextView? = null
    private var multiTrackTimelineView: MultiTrackTimelineView? = null
    private var notificationManager: NotificationManager? = null
    private var adsController: AdsController? = null
    private var activeTopBannerHostId: Int? = null

    // Effect sliders
    private var effectSlidersContainer: LinearLayout? = null
    private var brightnessSeekBar: SeekBar? = null
    private var contrastSeekBar: SeekBar? = null
    private var brightnessLabel: TextView? = null
    private var contrastLabel: TextView? = null

    // Timeline manager
    private var timelineManager: TimelineManager? = null
    private var layerController: LayerController? = null
    private var overlayController: OverlayController? = null
    private var importController: ImportController? = null
    private var exportController: ExportController? = null
    private var projectController: ProjectController? = null
    private var projectStateSerializer: ProjectStateSerializer? = null
    private var uiChromeController: UiChromeController? = null
    private var transitionController: TransitionController? = null
    private var playbackController: PlaybackController? = null
    private var appShellController: AppShellController? = null
    private var lastAutomationToken: String? = null
    private var audioImportController: AudioImportController? = null
    private var voiceoverController: VoiceoverController? = null
    private var rewardedUnlockController: RewardedUnlockController? = null
    private var previewAudioPlayer: PreviewAudioPlayer? = null
    private lateinit var debugTelemetryManager: DebugTelemetryManager

    // Text overlays
    private var nextTextOverlayId = 1
    private var overlayContainer: FrameLayout? = null

    // Per-clip effects
    private val clipEffects = mutableMapOf<Int, EffectParams>()
    private val timeline = MultiClipTimeline()
    // Active overlay views mapped by overlay id
    private val overlayViews = mutableMapOf<Int, TextOverlayView>()

    // Sticker overlays
    private val stickerOverlayViews = mutableMapOf<Int, StickerOverlayView>()
    private var nextStickerId = 1
    private var nextAudioClipId = 1

    // Transitions between clips
    private var currentTransitionId: Long = -1L

    // Playback state
    private var isPlaying = false
    private var currentTimeMs = 0L
    private var videoDurationMs = 0L
    private var selectedTimelineClipKey: String? = null
    private var previewTransformScaleDetector: ScaleGestureDetector? = null
    private var previewTransformTouchSlop = 0
    private var previewTransformLastX = 0f
    private var previewTransformLastY = 0f
    private var previewTransformDragging = false
    private var previewTransformPinching = false
    private var previewTransformGestureClipId: Int? = null
    private var previewTransformBase = ClipPreviewTransform()
    private var previewTransformScaleAccumulator = 1.0f

    private val undoDomains = ArrayDeque<UndoDomain>()
    private val redoDomains = ArrayDeque<UndoDomain>()
    private val videoClipTimingOverrides = mutableMapOf<Int, ClipTimingSnapshot>()
    private val clipPreviewTransforms = mutableMapOf<Int, ClipPreviewTransform>()
    private val audioClipGainOverrides = mutableMapOf<Int, Float>()
    private val videoClipGainOverrides = mutableMapOf<Int, Float>()
    private val videoClipReverseOverrides = mutableMapOf<Int, Boolean>()
    private val videoClipFreezeOverrides = mutableMapOf<Int, Pair<Long, Long>>()
    private val videoClipCurveProfiles = mutableMapOf<Int, String>()
    private val videoClipKeyframes = mutableMapOf<Int, MutableList<Long>>()
    private val stickerClipKeyframes = mutableMapOf<Int, MutableList<Long>>()
    private val nativeClipAudioGainKeyframes = mutableMapOf<Int, List<AudioGainKeyframe>>()
    private val duckingEnabledForKey = mutableMapOf<String, Boolean>()
    private val trackVisibilityOverrides = mutableMapOf<TrackType, Boolean>()
    private val trackLockedOverrides = mutableMapOf<TrackType, Boolean>()
    private val hardwareTelemetryHandler = Handler(Looper.getMainLooper())
    private var lastTimelineSeekTelemetryElapsedMs = 0L
    private var autoSaveRestorePromptShown = false
    private var pendingVideoReplaceClipId: Int? = null
    private var pendingAudioReplaceClipId: Int? = null
    private val aspectRatioOptions = listOf(
        AspectRatioOption(label = "16:9", width = 16, height = 9),
        AspectRatioOption(label = "21:9", width = 21, height = 9),
        AspectRatioOption(label = "1:1", width = 1, height = 1),
        AspectRatioOption(label = "9:16", width = 9, height = 16),
        AspectRatioOption(label = "4:5", width = 4, height = 5),
        AspectRatioOption(label = "5:4", width = 5, height = 4),
        AspectRatioOption(label = "4:3", width = 4, height = 3),
        AspectRatioOption(label = "3:4", width = 3, height = 4),
        AspectRatioOption(label = "3:2", width = 3, height = 2),
        AspectRatioOption(label = "2:3", width = 2, height = 3),
        AspectRatioOption(label = "2:1", width = 2, height = 1),
    )
    private val textOverlayPresets = listOf(
        TextOverlayPreset(
            label = "Caption",
            hint = "Tell the next beat",
            x = 0.5f,
            y = 0.82f,
            fontSize = 42f,
            color = 0xFFFFFFFF.toInt(),
            bold = true,
            defaultDurationMs = 2200,
            fontName = "sans-serif",
        ),
        TextOverlayPreset(
            label = "Title",
            hint = "OPENING TITLE",
            x = 0.5f,
            y = 0.26f,
            fontSize = 58f,
            color = 0xFFFFFFFF.toInt(),
            bold = true,
            uppercase = true,
            defaultDurationMs = 2600,
            fontName = "serif",
        ),
        TextOverlayPreset(
            label = "Lower 3rd",
            hint = "Name / place",
            x = 0.28f,
            y = 0.76f,
            fontSize = 32f,
            color = 0xFFFFD54F.toInt(),
            bold = true,
            defaultDurationMs = 3800,
            fontName = "sans-serif",
        ),
        TextOverlayPreset(
            label = "Subtitle",
            hint = "Explain the moment",
            x = 0.5f,
            y = 0.88f,
            fontSize = 34f,
            color = 0xFFFFFFFF.toInt(),
            bold = true,
            defaultDurationMs = 2600,
            fontName = "sans-serif",
        ),
        TextOverlayPreset(
            label = "Hook",
            hint = "STOP THE SCROLL",
            x = 0.5f,
            y = 0.18f,
            fontSize = 54f,
            color = 0xFF00E5FF.toInt(),
            bold = true,
            uppercase = true,
            defaultDurationMs = 2000,
            fontName = "sans-serif",
        ),
        TextOverlayPreset(
            label = "CTA",
            hint = "WATCH TILL THE END",
            x = 0.5f,
            y = 0.82f,
            fontSize = 36f,
            color = 0xFFFFB74D.toInt(),
            bold = true,
            uppercase = true,
            defaultDurationMs = 2200,
            fontName = "sans-serif",
        ),
        TextOverlayPreset(
            label = "Quote",
            hint = "\"Your strongest line\"",
            x = 0.5f,
            y = 0.34f,
            fontSize = 40f,
            color = 0xFFB3E5FC.toInt(),
            italic = true,
            defaultDurationMs = 3200,
            fontName = "serif",
        ),
        TextOverlayPreset(
            label = "Label",
            hint = "Scene note",
            x = 0.22f,
            y = 0.62f,
            fontSize = 28f,
            color = 0xFFB2FF59.toInt(),
            italic = true,
            defaultDurationMs = 2800,
            fontName = "monospace",
        ),
        TextOverlayPreset(
            label = "Badge",
            hint = "NEW",
            x = 0.18f,
            y = 0.16f,
            fontSize = 28f,
            color = 0xFFFF8A65.toInt(),
            bold = true,
            uppercase = true,
            defaultDurationMs = 2400,
            fontName = "sans-serif",
        ),
        TextOverlayPreset(
            label = "Basic",
            hint = "Hello World",
            x = 0.5f,
            y = 0.30f,
            fontSize = 36f,
            color = 0xFFFFFFFF.toInt(),
            defaultDurationMs = 3000,
            fontName = "sans-serif",
        ),
    )
    private var selectedAspectRatioIndex = 0
    private var lastAppliedAspectWidth = -1
    private var lastAppliedAspectHeight = -1
    private val nativeClipTrackType = mutableMapOf<Int, TrackType>()
    private val nativeClipLane = mutableMapOf<Int, Int>()
    private val nativeClipZOrder = mutableMapOf<Int, Int>()
    private val nativeClipStartMs = mutableMapOf<Int, Long>()
    private val nativeClipDurationMs = mutableMapOf<Int, Long>()
    private val nativeClipSourceInMs = mutableMapOf<Int, Long>()
    private val nativeClipSourceOutMs = mutableMapOf<Int, Long>()
    private val nativeClipSourcePath = mutableMapOf<Int, String>()
    private val nativeClipPlaybackSpeed = mutableMapOf<Int, Float>()
    private val nativeClipReversePlayback = mutableMapOf<Int, Boolean>()
    private val nativeClipFreezeFrameEnabled = mutableMapOf<Int, Boolean>()
    private val nativeClipFreezeFrameTimeMs = mutableMapOf<Int, Long>()
    private val nativeClipFreezeFrameDurationMs = mutableMapOf<Int, Long>()
    private val nativeClipCurveSpeedProfile = mutableMapOf<Int, String>()
    private val nativeClipCurveSpeedStrength = mutableMapOf<Int, Float>()
    private val clipToolbarItems = listOf(
        ClipToolbarItem(R.id.clipDeleteButton, R.id.clipDeleteLabel, "Delete"),
        ClipToolbarItem(R.id.clipSplitButton, R.id.clipSplitLabel, "Split"),
        ClipToolbarItem(R.id.clipVolumeButton, R.id.clipVolumeLabel, "Volume"),
        ClipToolbarItem(R.id.clipSpeedButton, R.id.clipSpeedLabel, "Speed"),
        ClipToolbarItem(R.id.clipPanZoomButton, R.id.clipPanZoomLabel, "Motion"),
        ClipToolbarItem(R.id.clipFilterButton, R.id.clipFilterLabel, "Effects"),
        ClipToolbarItem(R.id.clipBrightnessButton, R.id.clipBrightnessLabel, "Color"),
        ClipToolbarItem(R.id.clipTransitionButton, R.id.clipTransitionLabel, "Transition"),
        ClipToolbarItem(R.id.clipGraphicsButton, R.id.clipGraphicsLabel, "Graphics"),
        ClipToolbarItem(R.id.clipExtractAudioButton, R.id.clipExtractAudioLabel, "Isolate"),
        ClipToolbarItem(R.id.clipReplaceButton, R.id.clipReplaceLabel, "Replace"),
        ClipToolbarItem(R.id.clipDuplicateButton, R.id.clipDuplicateLabel, "Duplicate"),
        ClipToolbarItem(R.id.clipAddLayerButton, R.id.clipAddLayerLabel, "Stack"),
        ClipToolbarItem(R.id.clipRotateMirrorButton, R.id.clipRotateMirrorLabel, "Rotate"),
        ClipToolbarItem(R.id.clipKeyframeButton, R.id.clipKeyframeLabel, "Keyframe"),
        ClipToolbarItem(R.id.clipReverseButton, R.id.clipReverseLabel, "Reverse"),
        ClipToolbarItem(R.id.clipFreezeFrameButton, R.id.clipFreezeFrameLabel, "Freeze"),
        ClipToolbarItem(R.id.clipDuckingButton, R.id.clipDuckingLabel, "Ducking"),
        ClipToolbarItem(R.id.clipCurveSpeedButton, R.id.clipCurveSpeedLabel, "Curve"),
        ClipToolbarItem(R.id.clipChromaKeyButton, R.id.clipChromaKeyLabel, "Key"),
        ClipToolbarItem(R.id.clipCutoutButton, R.id.clipCutoutLabel, "Cutout"),
        ClipToolbarItem(R.id.clipTrimButton, R.id.clipTrimLabel, "Trim"),
    )
    private var hardwareTelemetryTickerRunning = false
    private val hardwareTelemetryRunnable = object : Runnable {
        override fun run() {
            updateHardwareTelemetryPanel()
            if (hardwareTelemetryTickerRunning) {
                hardwareTelemetryHandler.postDelayed(this, HARDWARE_TELEMETRY_REFRESH_MS)
            }
        }
    }

    private fun allTextOverlays(): List<TextOverlay> = OverlayStore.all()
    private var editorState: EditorState? = null

    private fun applyAdaptivePreviewProfile() {
        val profile = DeviceDetector.getQualityProfile()
        NativeBridge.setPreviewPolicy(
            ghostPreviewEnabled = true,
            ghostLongEdgePx = DeviceDetector.getRecommendedGhostLongEdgePx(),
            adaptiveFrameDropEnabled = true,
            targetPreviewFps = profile.previewFps,
            minPreviewFps = DeviceDetector.getRecommendedMinPreviewFps(),
        )
        NativeBridge.setPerformancePolicy(
            dirtyRegionEnabled = true,
            predictiveCachingEnabled = true,
            predictiveLookAroundMs = DeviceDetector.getRecommendedPredictiveLookAroundMs(),
            predictiveSampleStepMs = DeviceDetector.getRecommendedPredictiveSampleStepMs(),
            predictiveCacheMaxFrames = DeviceDetector.getRecommendedPredictiveCacheMaxFrames(),
        )
    }

    private fun setupStartScreen() {
        startScreenOverlayView = findViewById(R.id.startScreenOverlay)
        val newProjectBtn = findViewById<android.view.View>(R.id.startNewProjectButton)
        val openProjectBtn = findViewById<android.view.View>(R.id.startOpenProjectButton)
        val importMediaBtn = findViewById<android.view.View>(R.id.startImportMediaButton)
        startRecentProjectsList = findViewById(R.id.startRecentProjectsList)
        startRecentProjectsEmptyText = findViewById(R.id.startRecentProjectsEmptyText)
        clipToolbarContextLabel = findViewById(R.id.audioEditLabel)

        newProjectBtn.setOnClickListener {
            startBlankProject(showToast = true)
        }

        openProjectBtn.setOnClickListener {
            projectController?.showLoadProjectDialog()
        }

        importMediaBtn.setOnClickListener {
            setStartScreenVisible(false)
            NativeBridge.executeCommand("RESET_TIMELINE", emptyMap())
            clearEditorShellState()
            openVideoTrackImport()
        }

        startRecentProjectsList?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        startRecentProjectsList?.adapter = ProjectListAdapter(emptyList()) { }
        refreshRecentProjects()
    }

    private fun setStartScreenVisible(visible: Boolean) {
        val overlay = startScreenOverlayView ?: findViewById<View?>(R.id.startScreenOverlay) ?: return
        startScreenOverlayView = overlay
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            playbackController?.nativePause()
            effectSlidersContainer?.visibility = View.GONE
            clearSelectedTimelineItem()
            refreshRecentProjects()
            overlay.bringToFront()
        }
        scheduleTopBannerPlacement()
    }

    private fun buildRecentProjectFiles(): List<File> {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val projectFiles = File(baseDir, "projects")
            .listFiles { file -> file.isFile && file.extension == "vne" }
            ?.toList()
            .orEmpty()
        val autoSaveFile = File(baseDir, "autosave/autosave.vne")
            .takeIf { it.isFile }
        return buildList {
            addAll(projectFiles)
            if (autoSaveFile != null) add(autoSaveFile)
        }.sortedByDescending { it.lastModified() }
    }

    private fun refreshTopBannerPlacement() {
        val ads = adsController ?: return
        val showHomeBanner = startScreenOverlayView?.visibility == View.VISIBLE
        if (!showHomeBanner) {
            startTopBannerContainer?.let(ads::releaseBanner)
            editorTopBannerContainer?.let(ads::releaseBanner)
            editorTopBannerContainer?.visibility = View.GONE
            activeTopBannerHostId = null
            return
        }
        val target = startTopBannerContainer
        val inactive = editorTopBannerContainer
        if (activeTopBannerHostId == target?.id) {
            target?.visibility = View.VISIBLE
            inactive?.let(ads::releaseBanner)
            return
        }
        inactive?.let(ads::releaseBanner)
        target?.let {
            ads.attachTopBanner(it)
            activeTopBannerHostId = it.id
        }
    }

    private fun scheduleTopBannerPlacement(delayMs: Long = 900L) {
        mainHandler.removeCallbacks(bannerRefreshRunnable)
        mainHandler.postDelayed(bannerRefreshRunnable, delayMs)
    }

    private fun refreshRecentProjects() {
        val projectsList = startRecentProjectsList ?: return
        Thread {
            val projectFiles = buildRecentProjectFiles()
            projectsList.post {
                if (isFinishing || isDestroyed) return@post
                val hasProjects = projectFiles.isNotEmpty()
                startRecentProjectsEmptyText?.visibility = if (hasProjects) View.GONE else View.VISIBLE
                projectsList.visibility = if (hasProjects) View.VISIBLE else View.GONE
                projectsList.adapter = ProjectListAdapter(projectFiles) { project ->
                    projectController?.loadProject(project.absolutePath)
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashAutoFix.reportCrash(this, throwable)
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
        NativeBridge.warmUpEngineAsync()
        com.video.engine.pro.timeline.TimelineThumbnailCache.init(this)
        com.video.engine.pro.timeline.AudioWaveformCache.init(this)

        // Initialize handlers
        // CrashHandler.init(this)
        // CrashHandler.setLastAction("onCreate")
        DeviceDetector.init(this)
        // AnalyticsManager.getInstance(this).logAppOpen()

        Log.d(TAG, "onCreate - preparing shell")

        setContentView(R.layout.activity_main)
        editorTopBannerContainer = findViewById(R.id.editorTopBannerContainer)
        startTopBannerContainer = findViewById(R.id.startTopBannerContainer)
        adsController = AdsController(this)
        rewardedUnlockController = RewardedUnlockController(this)
        setupStartScreen()
        scheduleTopBannerPlacement(delayMs = 1200L)
        mainHandler.postDelayed({ rewardedUnlockController?.preload() }, 1800L)
        window.decorView.postDelayed({ CrashAutoFix.retryPending(this) }, 12000L)
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
        }
        debugTelemetryManager = DebugTelemetryManager(this)
        NativeBridge.setTelemetrySink { debugTelemetryManager.recordNativeBridgeTelemetry(it) }
        NativeBridge.clearNativeCommandTelemetry()
        recordTelemetryEvent(
            category = "session",
            name = "main_activity_created",
            payload = JSONObject().put("savedInstanceState", savedInstanceState != null),
        )

        // Get UI references
        val previewContainer = findViewById<FrameLayout>(R.id.previewContainer)
        val previewStageHost = findViewById<FrameLayout>(R.id.previewStageHost)
        previewContainerView = previewStageHost
        val bottomContainer = findViewById<View>(R.id.bottomContainer)
        val timelineLayout = findViewById<View>(R.id.timelineLayout)
        timelineCurrentTimeText = findViewById(R.id.timelineCurrentTimeText)
        timelineCurrentTimeText?.visibility = View.VISIBLE
        timelineCurrentTimeText?.text = getString(R.string.time_zero)
        previewAspectRatioText = null
        clipToolbarContextLabel?.visibility = View.GONE
        hardwareTelemetrySummaryText = findViewById(R.id.hardwareBufferTelemetrySummary)
        hardwareTelemetryReasonText = findViewById(R.id.hardwareBufferTelemetryReason)
        multiTrackTimelineView = findViewById(R.id.multiTrackTimelineView)
        multiTrackTimelineView?.setZoomPxPerSecond(NativeBridge.getTimelineZoomPxPerSecond())

        val canvasView = findViewById<com.video.engine.pro.timeline.TimelineCanvasView>(R.id.timelineCanvasView)
        canvasView?.setZoomPxPerSecond(NativeBridge.getTimelineZoomPxPerSecond())
        canvasView?.listener = object : com.video.engine.pro.timeline.TimelineCanvasView.Listener {
            override fun onClipUpdatePreview(update: com.video.engine.pro.timeline.ClipUpdate) {
                val videoClipId = parseNativeClipId(update.clipId) ?: return
                videoClipTimingOverrides[videoClipId] = ClipTimingSnapshot(
                    startTimeMs = update.startTimeMs, durationMs = update.durationMs,
                    sourceInMs = update.sourceInMs, sourceOutMs = update.sourceOutMs,
                )
            }
            override fun onClipUpdateCommitted(update: com.video.engine.pro.timeline.ClipUpdate) {
                val videoClipId = parseNativeClipId(update.clipId) ?: return
                val mtUpdate = MultiTrackTimelineView.ClipUpdate(
                    clipId = update.clipId, trackType = update.trackType,
                    startTimeMs = update.startTimeMs, durationMs = update.durationMs,
                    sourceInMs = update.sourceInMs, sourceOutMs = update.sourceOutMs,
                    originalStartTimeMs = update.originalStartTimeMs, originalDurationMs = update.originalDurationMs,
                    originalSourceInMs = update.originalSourceInMs, originalSourceOutMs = update.originalSourceOutMs,
                    gestureKind = when (update.gestureKind) {
                        com.video.engine.pro.timeline.ClipGestureKind.TRIM_START -> MultiTrackTimelineView.ClipGestureKind.TRIM_START
                        com.video.engine.pro.timeline.ClipGestureKind.TRIM_END -> MultiTrackTimelineView.ClipGestureKind.TRIM_END
                        com.video.engine.pro.timeline.ClipGestureKind.MOVE -> MultiTrackTimelineView.ClipGestureKind.MOVE
                    },
                )
                // Reuse existing commit logic via the MultiTrackTimelineView listener path
                // Use async command to avoid blocking main thread (ANR fix)
                runCatching {
                    NativeBridge.executeCommandAsync(
                        action = "UPDATE_CLIP_TIMING",
                        params = mapOf(
                            "clipId" to videoClipId,
                            "newStartTimeMs" to update.startTimeMs,
                            "newDurationMs" to update.durationMs,
                            "newSourceInMs" to update.sourceInMs,
                            "newSourceOutMs" to update.sourceOutMs,
                            "originalStartTimeMs" to update.originalStartTimeMs,
                            "originalDurationMs" to update.originalDurationMs,
                            "originalSourceInMs" to update.originalSourceInMs,
                            "originalSourceOutMs" to update.originalSourceOutMs,
                            "previewOnly" to false,
                            "applyMagnetic" to (update.trackType == TrackType.VIDEO),
                        ),
                    )
                }
                lastLayoutFetchMs = 0L
                refreshMainTimelineTracks()
            }
            override fun onZoomChanged(pxPerSecond: Float) {
                runCatching { NativeBridge.setTimelineZoomPxPerSecond(pxPerSecond) }
            }
            override fun onClipSelected(clipId: String?) {
                selectedTimelineClipKey = normalizeTimelineSelectionKey(clipId)
                updateBottomToolbarMode()
            }
            override fun onPlayheadScrub(timeMs: Long) {
                currentTimeMs = timeMs
                playbackController?.scrubTo(timeMs)
            }
            override fun onTrackImportRequested(trackType: TrackType) {
                when (trackType) {
                    TrackType.VIDEO -> openVideoTrackImport()
                    TrackType.OVERLAY -> openOverlayTrackImport()
                    TrackType.LAYER -> openLayerTrackImport()
                    TrackType.TEXT -> showAddTextDialog()
                    TrackType.AUDIO -> openAudioTrackImport()
                }
            }
            override fun onTrackVisibilityChanged(trackType: TrackType, isVisible: Boolean) {
                applyTrackVisibilityChange(trackType, isVisible)
            }
            override fun onTrackLockedChanged(trackType: TrackType, isLocked: Boolean) {
                applyTrackLockChange(trackType, isLocked)
            }
        }
        canvasView?.onSplitAtPlayhead = { timeMs ->
            currentTimeMs = timeMs
            mainHandler.post { performSelectedClipSplitAction() }
        }
        canvasView?.onClipLongPress = { clipId, _, _ ->
            selectedTimelineClipKey = normalizeTimelineSelectionKey(clipId)
            updateBottomToolbarMode()
            ModernSheet.show(this, "Clip Actions") {
                chips("", listOf("Split", "Delete", "Duplicate", "Reverse", "Speed"), -1) { _, opt ->
                    when (opt) {
                        "Split" -> performSelectedClipSplitAction()
                        "Delete" -> performSelectedClipDeleteAction()
                        "Duplicate" -> performSelectedClipDuplicateAction()
                        "Reverse" -> performSelectedClipReverseAction()
                        "Speed" -> performSelectedClipSpeedAction()
                    }
                }
            }
        }
        findViewById<View?>(R.id.previewHud)?.visibility = View.GONE
        findViewById<View?>(R.id.hardwareBufferTelemetryPanel)?.visibility = View.GONE
        applyAdaptivePreviewProfile()
        previewAudioPlayer = PreviewAudioPlayer(
            context = this,
            previewViewProvider = { previewView },
        )
        findViewById<android.view.View?>(R.id.previewPlayPauseButton)?.bringToFront()
        notificationManager = getSystemService(NotificationManager::class.java)
        setupBottomToolbarModes()
        previewContainer.setOnClickListener { clearSelectedTimelineItem() }
        bottomContainer.setOnClickListener { clearSelectedTimelineItem() }
        timelineLayout.setOnClickListener { clearSelectedTimelineItem() }

        // Get effect slider UI references
        effectSlidersContainer = findViewById(R.id.effectSlidersContainer)
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar)
        contrastSeekBar = findViewById(R.id.contrastSeekBar)
        brightnessLabel = findViewById(R.id.brightnessLabel)
        contrastLabel = findViewById(R.id.contrastLabel)
        setupEffectSliderControls()


        // Create VideoPreviewView
        previewView = VideoPreviewView(this)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        previewStageHost.addView(previewView, params)

        // Check if native library is loaded
        if (!VideoPreviewView.isNativeLibraryLoaded()) {
            Log.w(TAG, "WARNING: Native library not available. Building the C++ video engine is required.")
            safeToast("Storyline native renderer not found. Build the C++ module to enable GPU rendering.", Toast.LENGTH_LONG)
        }

        // Overlay container
        overlayContainer = FrameLayout(this)
        val overlayLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        previewStageHost.addView(overlayContainer, overlayLp)
        previewStageHost.clipToOutline = true
        setupPreviewTransformGestures()
        setupAspectRatioButton()
        previewContainer.post { applyPreviewAspectRatio() }
        findViewById<android.view.View?>(R.id.previewHud)?.bringToFront()
        findViewById<android.view.View?>(R.id.hardwareBufferTelemetryPanel)?.bringToFront()
        findViewById<android.view.View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        findViewById<android.view.View?>(R.id.previewPlayPauseButton)?.bringToFront()
        findViewById<android.view.View?>(R.id.hardwareBufferTelemetryPanel)?.setOnClickListener {
            previewView?.resetHardwareBufferTelemetry()
            updateHardwareTelemetryPanel()
            safeToast("HB telemetry reset", Toast.LENGTH_SHORT)
        }
        previewView?.resetHardwareBufferTelemetry()
        updateHardwareTelemetryPanel()

        // Ensure overlay container is ready: attach global layout listener if needed
        overlayContainer?.viewTreeObserver?.addOnGlobalLayoutListener {
            // no-op: ensures measured size available for placing overlay views
        }

        // Setup UI
        playbackController = PlaybackController(
            activity = this,
            previewViewProvider = { previewView },
            timelineRecyclerViewProvider = { timelineRecyclerView },
            timelineCurrentTimeTextProvider = { timelineCurrentTimeText },
            multiTrackTimelineViewProvider = { multiTrackTimelineView },
            timelineManagerProvider = { timelineManager },
            setTimelineManager = { timelineManager = it },
            playheadTimeMsProvider = { currentPlayheadMs() },
            currentTimeMsProvider = { currentTimeMs },
            setCurrentTimeMs = { currentTimeMs = it },
            isPlayingProvider = { isPlaying },
            setIsPlaying = { isPlaying = it },
            setVideoDurationMs = { videoDurationMs = it },
            totalDurationMsProvider = { timelineManager?.getTotalDurationMs() ?: 0L },
            onSelectionChanged = selectionChanged@{ selectedClipId ->
                if (selectedClipId == null) {
                    val existingAudioId = selectedAudioClipId()
                    if (existingAudioId != null && AudioClipStore.get(existingAudioId) != null) {
                        Log.d(TAG, "Ignoring null playback selection while audio clip remains selected: $existingAudioId")
                        updateBottomToolbarMode()
                        updateUndoRedoButtons()
                        return@selectionChanged
                    }
                }
                selectedTimelineClipKey = selectedClipId?.let(::selectionKeyForNativeClipId)
                updateBottomToolbarMode()
                updateUndoRedoButtons()
                if (selectedClipId != null) {
                    Log.d(TAG, "Selected clip changed: $selectedClipId")
                }
            },
            onTransitionRequested = { outgoingClipId, incomingClipId ->
                transitionController?.showTransitionEditor(outgoingClipId, incomingClipId)
            },
            onPlayRequested = { timeMs -> previewAudioPlayer?.playFrom(timeMs) },
            onPauseRequested = { previewAudioPlayer?.pause() },
            onSeekRequested = { timeMs, continuePlaying -> previewAudioPlayer?.seekTo(timeMs, continuePlaying) },
        )
        playbackController?.setupTimeline()
        // Wire canvas playhead to playback time
        playbackController?.onPlaybackTimeChanged = { timeMs ->
            activeCanvasTimelineView()?.setPlayheadMs(timeMs)
            previewAudioPlayer?.syncToVideoClock(timeMs, continuePlaying = isPlaying)
        }
        // On surface recreate (after picker), seek to restore frame
        previewView?.onSurfaceReady = {
            val hasClips = timelineManager?.getClips()?.isNotEmpty() == true
            if (hasClips) {
                Thread {
                    Thread.sleep(100)
                    NativeBridge.executeCommand("SEEK", mapOf("timeMs" to currentTimeMs))
                }.start()
            }
        }
        setupMainMultiTrackTimeline()
        editorState = EditorState(
            timelineManagerProvider = { timelineManager },
            overlaysProvider = { allTextOverlays() },
            stickersProvider = { StickerClipStore.all() },
            audioClipsProvider = { AudioClipStore.all() },
            onMoveClipLayer = { clipId, index -> previewView?.moveLayer(clipId, index) },
            onApplyTextState = { overlay -> applyTextOverlayState(overlay) },
            onApplyStickerState = { clip -> applyStickerLayerState(clip) },
            onRefreshOverlayStack = { refreshOverlayStack() },
        )
        layerController = LayerController(
            editorStateProvider = { editorState },
            timelineManagerProvider = { timelineManager },
            onToggleClipVisibility = { clipId, visible -> previewView?.toggleLayerVisibility(clipId, visible) },
            onRemoveClip = { clipId ->
                val result = runCatching {
                    NativeBridge.executeCommand(
                        action = "DELETE_CLIP",
                        params = mapOf("clipId" to clipId),
                    )
                }.getOrNull()
                if (result?.success != true) {
                    previewView?.removeClip(clipId)
                }
            },
            onRemoveText = { overlayId ->
                previewView?.removeTextOverlay(overlayId)
                removeOverlayView(overlayId)
            },
            onRemoveSticker = { stickerId -> removeStickerOverlayView(stickerId) },
            onRemoveAudio = { _ -> },
            onApplyTextState = { overlay -> applyTextOverlayState(overlay) },
            onApplyStickerState = { clip -> applyStickerLayerState(clip) },
            onRecordEditorUndo = {
                editorState?.recordLayerSnapshot()
                recordUndoDomain(UndoDomain.EDITOR)
            },
            onRecordTimelineUndo = { recordUndoDomain(UndoDomain.TIMELINE) },
            onApplyTimelineShell = { applyTimelineStateToShell() },
            onTimelineContentChanged = { refreshMainTimelineTracks() },
        )
        overlayController = OverlayController(
            activity = this,
            previewViewProvider = { previewView },
            overlayContainerProvider = { overlayContainer },
            isPlayingProvider = { isPlaying },
            onPausePlayback = {
                isPlaying = false
                playbackController?.pauseRendering()
            },
            currentTimeMsProvider = { currentTimeMs },
            nextTextOverlayIdProvider = { nextTextOverlayId },
            setNextTextOverlayId = { nextTextOverlayId = it },
            nextStickerIdProvider = { nextStickerId },
            setNextStickerId = { nextStickerId = it },
            nextCompositeLayerIndexProvider = { editorState?.nextCompositeLayerIndex() ?: 0 },
            overlayViews = overlayViews,
            stickerOverlayViews = stickerOverlayViews,
            onRefreshOverlayStack = { refreshOverlayStack() },
            onTimelineContentChanged = { refreshMainTimelineTracks() },
        )
        importController = ImportController(
            activity = this,
            previewViewProvider = { previewView },
            timelineProvider = { timeline },
            timelineManagerProvider = { timelineManager },
            onImportedClip = { clipId, importPath, importedDurationMs, trackType ->
                val fileExtension = importPath.substringAfterLast(".", "mp4").lowercase()
                videoDurationMs = maxOf(videoDurationMs, importedDurationMs)
                videoClipTimingOverrides.remove(clipId)
                nativeClipTrackType[clipId] = trackType
                importController?.setNextImportTrackType(TrackType.VIDEO)
                lastLayoutFetchMs = 0L
                syncTimelineShellFromNative(selectedClipId = clipId)
                val clipStartMs = nativeClipStartMs[clipId] ?: currentTimeMs
                val revealTimeMs = when (trackType) {
                    TrackType.AUDIO -> currentTimeMs
                    else -> clipStartMs
                }
                currentTimeMs = revealTimeMs
                previewView?.let { NativeBridge.seekToTime(it, revealTimeMs) }
                timelineManager?.dispatchScrub(revealTimeMs)
                val clipKey = selectionKeyForNativeClipId(clipId)
                selectedTimelineClipKey = clipKey
                multiTrackTimelineView?.setSelectedClipId(clipKey)
                multiTrackTimelineView?.revealClip(clipKey)
                multiTrackTimelineView?.setCurrentTimeMs(revealTimeMs)
                Log.d(TAG, "Clip import complete: id=$clipId track=$trackType duration=${importedDurationMs}ms ext=$fileExtension")
                val toastLabel = when (trackType) {
                    TrackType.OVERLAY -> "Overlay"
                    TrackType.LAYER -> "Layer"
                    else -> "Clip"
                }
                safeToast("$toastLabel added (ID: $clipId)", Toast.LENGTH_SHORT)
                revealTimeMs
            },
        )
        audioImportController = AudioImportController(
            activity = this,
            nextAudioClipIdProvider = { nextAudioClipId },
            setNextAudioClipId = { nextAudioClipId = it },
            onImportedAudio = { audioClip ->
                if (isPlaying) {
                    isPlaying = false
                    playbackController?.pauseRendering()
                }
                nativeClipTrackType[audioClip.id] = TrackType.AUDIO
                val targetGain = audioClipGainOverrides[audioClip.id] ?: audioClip.gain.coerceIn(0f, 2f)
                audioClip.gain = targetGain
                audioClip.muted = targetGain <= 0.001f
                audioClipGainOverrides[audioClip.id] = targetGain
                execCmd(
                    action = "SET_CLIP_VOLUME",
                    params = mapOf("clipId" to audioClip.id, "volume" to targetGain.toDouble()),
                )
                currentTimeMs = audioClip.startTimeMs.coerceAtLeast(0L)
                val selectedAudioKey = "audio-${audioClip.id}"
                selectedTimelineClipKey = selectedAudioKey
                if (previewView != null) {
                    lastLayoutFetchMs = 0L
                    syncTimelineShellFromNative(selectedClipId = audioClip.id)
                } else {
                    refreshMainTimelineTracks()
                }
                multiTrackTimelineView?.setCurrentTimeMs(currentTimeMs)
                multiTrackTimelineView?.setSelectedClipId(selectedAudioKey)
                multiTrackTimelineView?.revealClip(selectedAudioKey)
                previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = false)
                Log.d(
                    TAG,
                    "Audio import complete: id=${audioClip.id} name=${audioClip.displayName} duration=${audioClip.durationMs}ms path=${audioClip.sourcePath} peaks=${audioClip.peakLevels.size}",
                )
                if (audioClip.peakLevels.isEmpty()) {
                    safeToast("Audio lane updated: ${audioClip.displayName}", Toast.LENGTH_SHORT)
                }
            },
            nativeClipCreator = { path, startTimeMs, layerIndex ->
                previewView?.let { pv ->
                    NativeBridge.addClip(
                        previewView = pv,
                        videoPath = path,
                        trackType = TrackType.AUDIO.name,
                        startTimeMs = startTimeMs,
                        trackLane = layerIndex,
                        zOrder = layerIndex,
                    ).takeIf { it > 0 }
                }
            },
        )
        voiceoverController = VoiceoverController(
            activity = this,
            permissionRequestCode = PERMISSION_REQUEST_CODE,
            currentTimeMsProvider = { currentTimeMs },
            onRecordingComplete = { path, startTimeMs, durationMs ->
                audioImportController?.importFromPath(
                    path = path,
                    startTimeMs = startTimeMs,
                    durationOverrideMs = durationMs,
                    displayNameOverride = "Voiceover",
                )
            },
        )
        exportController = ExportController(
            activity = this,
            previewViewProvider = { previewView },
            videoDurationMsProvider = { videoDurationMs },
            isPlayingProvider = { isPlaying },
            onPausePlayback = {
                isPlaying = false
                playbackController?.pauseRendering()
            },
            notificationManagerProvider = { notificationManager },
            isWatermarkUnlockedProvider = { rewardedUnlockController?.isWatermarkUnlocked() == true },
            onRequestWatermarkUnlock = { callback ->
                rewardedUnlockController?.requestWatermarkUnlock(callback) ?: callback(false)
            },
            onExportSuccess = {
                adsController?.showPostExportInterstitial()
            },
        )
        projectController = ProjectController(
            activity = this,
            previewViewProvider = { previewView },
            hasProjectContentProvider = { hasProjectContent() },
            isPlayingProvider = { isPlaying },
            onPausePlayback = {
                isPlaying = false
                playbackController?.pauseRendering()
            },
            onSaveUiState = { projectFile, projectName -> saveUiState(projectFile, projectName) },
            onPrepareLoadedProject = { clearEditorShellState() },
            onProjectLoaded = { filePath, pv ->
                setStartScreenVisible(false)
                timeline.syncFromEngine(pv)
                timelineManager?.syncClips(timeline.getClips(), recordHistory = false, clearHistory = true)
                videoDurationMs = maxOf(
                    pv.getDuration(),
                    timelineManager?.getTotalDurationMs() ?: 0L,
                )
                loadUiState(File(filePath))
                refreshMainTimelineTracks()
            },
            onProjectLoadFinished = {
                setStartScreenVisible(false)
                timelineManager?.notifyDatasetChanged()
                refreshMainTimelineTracks()
                updateUndoRedoButtons()
            },
        )
        projectStateSerializer = ProjectStateSerializer(
            timeline = timeline,
            timelineManagerProvider = { timelineManager },
            previewViewProvider = { previewView },
            editorStateProvider = { editorState },
            allTextOverlaysProvider = { allTextOverlays() },
            nextTextOverlayIdProvider = { nextTextOverlayId },
            nextStickerIdProvider = { nextStickerId },
            nextAudioClipIdProvider = { nextAudioClipId },
            selectedAspectRatioIndexProvider = { selectedAspectRatioIndex },
            playheadTimeMsProvider = { currentPlayheadMs() },
            timelineZoomPxPerSecondProvider = { NativeBridge.getTimelineZoomPxPerSecond() },
            trackVisibilityProvider = { trackVisibilityStateSnapshot() },
            trackLockedProvider = { trackLockStateSnapshot() },
            onAddOverlayView = { overlay -> addOverlayView(overlay) },
            onAddStickerOverlayView = { clip -> addStickerOverlayView(clip) },
        )
        transitionController = TransitionController(
            activity = this,
            previewViewProvider = { previewView },
            timelineManagerProvider = { timelineManager },
            currentTimeMsProvider = { currentTimeMs },
            onRecordTimelineUndo = { recordUndoDomain(UndoDomain.TIMELINE) },
            setCurrentTransitionId = { currentTransitionId = it },
        )
        uiChromeController = UiChromeController(
            activity = this,
            previewViewProvider = { previewView },
            timelineManagerProvider = { timelineManager },
            editorStateProvider = { editorState },
            layerControllerProvider = { layerController },
            overlayControllerProvider = { overlayController },
            currentTimeMsProvider = { currentTimeMs },
            isPlayingProvider = { isPlaying },
            setIsPlaying = { isPlaying = it },
            onNativePlay = { playbackController?.nativePlay() },
            onNativePause = { playbackController?.nativePause() },
            onPauseRendering = { playbackController?.pauseRendering() },
            onShowExportDialog = { showExportDialog() },
            onShowSaveProjectDialog = { showSaveProjectDialog() },
            onShowLoadProjectDialog = { showEditorHome() },
            onUndo = undoLambda@{
                when (undoDomains.removeLastOrNull()) {
                    UndoDomain.EDITOR -> {
                        if (editorState?.undo() == true) {
                            redoDomains.addLast(UndoDomain.EDITOR)
                        }
                    }
                    UndoDomain.TIMELINE -> {
                        execCmd("UNDO") { result ->
                            if (result.success) {
                                syncTimelineShellFromNative()
                                redoDomains.addLast(UndoDomain.TIMELINE)
                            }
                            updateUndoRedoButtons()
                        }
                        return@undoLambda
                    }
                    null -> Unit
                }
                updateUndoRedoButtons()
            },
            onRedo = redoLambda@{
                when (redoDomains.removeLastOrNull()) {
                    UndoDomain.EDITOR -> {
                        if (editorState?.redo() == true) {
                            undoDomains.addLast(UndoDomain.EDITOR)
                        }
                    }
                    UndoDomain.TIMELINE -> {
                        execCmd("REDO") { result ->
                            if (result.success) {
                                syncTimelineShellFromNative()
                                undoDomains.addLast(UndoDomain.TIMELINE)
                            }
                            updateUndoRedoButtons()
                        }
                        return@redoLambda
                    }
                    null -> Unit
                }
                updateUndoRedoButtons()
            },
            onOpenVideoImportPicker = { openVideoTrackImport() },
            onOpenOverlayImportPicker = { openOverlayTrackImport() },
            onOpenLayerImportPicker = { openLayerTrackImport() },
            onQuickImport = {
                if (!ensureTrackEditable(TrackType.VIDEO, "import")) {
                    false
                } else {
                    importController?.importQuickSample() == true
                }
            },
            onQuickOverlayImport = {
                if (!ensureTrackEditable(TrackType.OVERLAY, "import")) {
                    false
                } else {
                    importController?.setNextImportTrackType(TrackType.OVERLAY)
                    importController?.importQuickSample() == true
                }
            },
            onQuickLayerImport = {
                if (!ensureTrackEditable(TrackType.LAYER, "import")) {
                    false
                } else {
                    importController?.setNextImportTrackType(TrackType.LAYER)
                    importController?.importQuickSample() == true
                }
            },
            onShowAudioPicker = { openAudioTrackImport() },
            onQuickAudioImport = {
                if (!ensureTrackEditable(TrackType.AUDIO, "import")) {
                    false
                } else {
                    audioImportController?.importQuickSample() == true
                }
            },
            onAddTextPreset = { label -> addTextPresetFromToolbar(label) },
            onOpenSelectedTextStudio = { showSelectedTextStudio() },
            onSplitAudioAtPlayhead = { splitAudioAtPlayhead() },
            clipEffects = clipEffects,
            onTransitionRequested = { outgoing, incoming ->
                transitionController?.showTransitionEditor(outgoing, incoming)
            },
            onApplyTransitionPreset = { type, durationMs ->
                applyToolbarTransitionPreset(type, durationMs)
            },
            onRemoveTransitionPreset = { removeToolbarTransitionPreset() },
            onVoiceoverRequested = { voiceoverController?.showPanel() },
            onShowTextComposer = { showAddTextDialog() },
        )
        uiChromeController?.setupPlayPauseButton()
        uiChromeController?.setupExportButton()
        uiChromeController?.setupToolbarButtons()
        refreshRecentProjects()
        appShellController = AppShellController(
            activity = this,
            permissionRequestCode = PERMISSION_REQUEST_CODE,
            onCreateNotificationChannel = { createNotificationChannel() },
        )
        appShellController?.setup()

        // Load test video
        // TODO: JNI implementation deferred - requires native_preview.cpp implementation
        // previewView?.loadVideo(TEST_VIDEO_PATH)

        // Query duration
        playbackController?.scheduleInitialDurationRefresh()
        refreshMainTimelineTracks()
        maybeHandleAutomationIntent(intent)
        if (intent?.getStringExtra("adb_action").isNullOrBlank()) {
            mainHandler.post { maybePromptAutoSaveRestore() }
        }
    }

    override fun onBackPressed() {
        if (handleBackNavigationToHome()) return
        super.onBackPressed()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBackNavigationToHome()) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleBackNavigationToHome(): Boolean {
        if (startScreenOverlayView?.visibility == View.VISIBLE) return false
        showEditorHome()
        return true
    }

    private fun setupAspectRatioButton() {
        val aspectButton = findViewById<ImageView?>(R.id.aspectRatioButton) ?: return
        aspectButton.setOnClickListener { showAspectRatioPickerDialog() }
    }

    private fun showAspectRatioPickerDialog() {
        val labels = aspectRatioOptions.map { it.label }
        ModernSheet.show(this, "Crop Ratio") {
            chips("Canvas", labels, selectedAspectRatioIndex) { i, _ ->
                selectedAspectRatioIndex = i.coerceIn(0, aspectRatioOptions.lastIndex)
                applyPreviewAspectRatio()
            }
        }
    }

    private fun applyPreviewAspectRatio() {
        val container = previewContainerView ?: return
        val preview = previewView ?: return
        val overlay = overlayContainer ?: return
        if (container.width <= 0 || container.height <= 0) {
            container.post { applyPreviewAspectRatio() }
            return
        }

        val option = aspectRatioOptions[selectedAspectRatioIndex]
        previewAspectRatioText?.text = option.label
        val targetRatio = option.width.toFloat() / option.height.toFloat()
        val availableWidth = container.width
        val availableHeight = container.height

        var targetWidth = availableWidth
        var targetHeight = (targetWidth / targetRatio).roundToInt()
        if (targetHeight > availableHeight) {
            targetHeight = availableHeight
            targetWidth = (targetHeight * targetRatio).roundToInt()
        }

        val needsResize = targetWidth != lastAppliedAspectWidth || targetHeight != lastAppliedAspectHeight
        if (needsResize) {
            preview.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
            overlay.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
            lastAppliedAspectWidth = targetWidth
            lastAppliedAspectHeight = targetHeight
        }
        preview.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        findViewById<View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        findViewById<View?>(R.id.previewPlayPauseButton)?.bringToFront()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleAutomationIntent(intent)
    }



    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        playbackController?.onResume()  // handles previewView.onResume() internally
        importController?.maybeImportPendingClip()
        startHardwareTelemetryTicker()
        adsController?.onResume()
        scheduleTopBannerPlacement(delayMs = 1200L)
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        stopHardwareTelemetryTicker()
        mainHandler.removeCallbacks(bannerRefreshRunnable)
        playbackController?.onPause()  // handles previewView.onPause() internally
        exportController?.dismissProgressDialog()
        adsController?.onPause()
        projectController?.autoSave()
        super.onPause()
    }

    override fun onDestroy() {
        stopHardwareTelemetryTicker()
        mainHandler.removeCallbacks(refreshTimelineRunnable)
        previewAudioPlayer?.release()
        voiceoverController?.release()
        rewardedUnlockController?.onDestroy()
        captureDebugSnapshot("activity_destroy")
        NativeBridge.setTelemetrySink(null)
        if (::debugTelemetryManager.isInitialized) {
            debugTelemetryManager.close()
        }
        commandExecutor.shutdownNow()
        super.onDestroy()
        exportController?.cleanup()
        adsController?.onDestroy()
    }

    private fun recordTelemetryEvent(category: String, name: String, payload: JSONObject = JSONObject()) {
        if (!::debugTelemetryManager.isInitialized) return
        debugTelemetryManager.record(category, name, payload)
    }

    private fun captureDebugSnapshot(label: String) {
        if (!::debugTelemetryManager.isInitialized) return
        debugTelemetryManager.captureSnapshot(label, buildDebugTelemetrySnapshot(label))
    }

    private fun buildDebugTelemetrySnapshot(reason: String): JSONObject {
        val playbackJson = JSONObject()
            .put("isPlaying", isPlaying)
            .put("currentTimeMs", currentTimeMs)
            .put("playheadMs", currentPlayheadMs())
            .put("videoDurationMs", videoDurationMs)

        val selectionJson = JSONObject()
            .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL)
            .put("selectedClipKind", selectedClipKind().name)

        val timelineJson = JSONObject()
            .put("zoomPxPerSecond", NativeBridge.getTimelineZoomPxPerSecond())
            .put("nativeClipCount", nativeClipTrackType.size)
            .put("audioClipCount", AudioClipStore.all().size)
            .put("textClipCount", allTextOverlays().size)
            .put("stickerClipCount", StickerClipStore.all().size)
            .put("undoDepth", undoDomains.size)
            .put("redoDepth", redoDomains.size)

        val root = JSONObject()
            .put("reason", reason)
            .put("capturedAtMs", System.currentTimeMillis())
            .put("playback", playbackJson)
            .put("selection", selectionJson)
            .put("timeline", timelineJson)
            .put("nativeClips", buildNativeClipTelemetry())
            .put("audioClips", buildAudioClipTelemetry())
            .put("textClips", buildTextClipTelemetry())
            .put("stickerClips", buildStickerClipTelemetry())
            .put(
                "nativeCommandJournal",
                JSONArray().apply { NativeBridge.snapshotCommandJournal().forEach { put(it) } },
            )
            .put("nativeCommandTelemetry", NativeBridge.snapshotNativeCommandTelemetry())

        previewView?.getHardwareBufferTelemetryJson()
            ?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { root.put("hardwareBufferTelemetry", it) }
            }
        return root
    }

    private fun buildNativeClipTelemetry(): JSONArray {
        val array = JSONArray()
        nativeClipTrackType.keys.sorted().forEach { clipId ->
            array.put(
                JSONObject()
                    .put("clipId", clipId)
                    .put("trackType", nativeClipTrackType[clipId]?.name ?: "VIDEO")
                    .put("trackLane", nativeClipLane[clipId] ?: 0)
                    .put("zOrder", nativeClipZOrder[clipId] ?: 0)
                    .put("startTimeMs", nativeClipStartMs[clipId] ?: 0L)
                    .put("durationMs", nativeClipDurationMs[clipId] ?: 0L)
                    .put("sourceInMs", nativeClipSourceInMs[clipId] ?: 0L)
                    .put("sourceOutMs", nativeClipSourceOutMs[clipId] ?: 0L)
                    .put("sourcePath", nativeClipSourcePath[clipId].orEmpty()),
            )
        }
        return array
    }

    private fun buildAudioClipTelemetry(): JSONArray {
        val array = JSONArray()
        AudioClipStore.all().forEach { clip ->
            array.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("displayName", clip.displayName)
                    .put("sourcePath", clip.sourcePath)
                    .put("startTimeMs", clip.startTimeMs)
                    .put("durationMs", clip.durationMs)
                    .put("fadeInMs", clip.fadeInMs)
                    .put("fadeOutMs", clip.fadeOutMs)
                    .put("gainKeyframeCount", clip.gainKeyframes.size)
                    .put("layerIndex", clip.layerIndex)
                    .put("visible", clip.visible)
                    .put("muted", clip.muted),
            )
        }
        return array
    }

    private fun normalizeAudioGainKeyframes(
        keyframes: List<AudioGainKeyframe>,
        clipDurationMs: Long,
    ): List<AudioGainKeyframe> {
        val safeDurationMs = clipDurationMs.coerceAtLeast(1L)
        val normalized = mutableListOf<AudioGainKeyframe>()
        keyframes.forEach { keyframe ->
            normalized += AudioGainKeyframe(
                timeMs = keyframe.timeMs.coerceIn(0L, safeDurationMs - 1L),
                gain = keyframe.gain.coerceIn(0f, 2f),
            )
        }
        return normalized.sortedBy { it.timeMs }.distinctBy { it.timeMs }
    }

    private fun sampleAudioGainEnvelope(
        keyframes: List<AudioGainKeyframe>,
        localTimeMs: Long,
    ): Float {
        if (keyframes.isEmpty()) return 1.0f
        val normalized = keyframes.sortedBy { it.timeMs }
        val clampedLocalTimeMs = localTimeMs.coerceAtLeast(0L)
        if (clampedLocalTimeMs <= normalized.first().timeMs) {
            return normalized.first().gain.coerceIn(0f, 2f)
        }
        if (clampedLocalTimeMs >= normalized.last().timeMs) {
            return normalized.last().gain.coerceIn(0f, 2f)
        }
        for (index in 1 until normalized.size) {
            val left = normalized[index - 1]
            val right = normalized[index]
            if (clampedLocalTimeMs > right.timeMs) continue
            val spanMs = (right.timeMs - left.timeMs).coerceAtLeast(1L)
            val progress = (clampedLocalTimeMs - left.timeMs).toFloat() / spanMs.toFloat()
            return (left.gain + ((right.gain - left.gain) * progress)).coerceIn(0f, 2f)
        }
        return 1.0f
    }

    private fun upsertAudioGainKeyframe(
        keyframes: List<AudioGainKeyframe>,
        timeMs: Long,
        gain: Float,
        clipDurationMs: Long,
    ): List<AudioGainKeyframe> {
        return normalizeAudioGainKeyframes(
            keyframes.filterNot { it.timeMs == timeMs } + AudioGainKeyframe(timeMs, gain),
            clipDurationMs,
        )
    }

    private fun removeAudioGainKeyframe(
        keyframes: List<AudioGainKeyframe>,
        timeMs: Long,
        clipDurationMs: Long,
    ): List<AudioGainKeyframe> {
        return normalizeAudioGainKeyframes(
            keyframes.filterNot { it.timeMs == timeMs },
            clipDurationMs,
        )
    }

    private fun applyAudioGainKeyframes(
        audioId: Int,
        clip: com.video.engine.audio.AudioClip,
        keyframes: List<AudioGainKeyframe>,
    ) {
        val normalized = normalizeAudioGainKeyframes(keyframes, clip.durationMs)
        clip.gainKeyframes = normalized
        nativeClipAudioGainKeyframes[audioId] = normalized
        runCatching {
            NativeBridge.executeCommand(
                action = "SET_CLIP_AUDIO_KEYFRAMES",
                params = mapOf(
                    "clipId" to audioId,
                    "keyframesCsv" to NativeBridge.serializeAudioGainKeyframesCsv(normalized),
                ),
            )
        }
        refreshMainTimelineTracks()
        previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = isPlaying)
    }

    private fun updateAudioKeyframePoint(
        audioId: Int,
        clip: com.video.engine.audio.AudioClip,
        localTimeMs: Long,
        gain: Float,
    ) {
        applyAudioGainKeyframes(
            audioId,
            clip,
            upsertAudioGainKeyframe(clip.gainKeyframes, localTimeMs, gain, clip.durationMs),
        )
    }

    private fun deleteAudioKeyframePoint(
        audioId: Int,
        clip: com.video.engine.audio.AudioClip,
        localTimeMs: Long,
    ) {
        applyAudioGainKeyframes(
            audioId,
            clip,
            removeAudioGainKeyframe(clip.gainKeyframes, localTimeMs, clip.durationMs),
        )
    }

    private fun showAudioKeyframeSheet(
        audioId: Int,
        clip: com.video.engine.audio.AudioClip,
        localTimeMs: Long,
        initialGain: Float,
    ) {
        ModernSheet.show(this, "Audio Keyframe") {
            slider(
                "Level",
                0f,
                2f,
                initialGain.coerceIn(0f, 2f),
                { "%.0f%%".format(it * 100f) },
            ) { value ->
                updateAudioKeyframePoint(audioId, clip, localTimeMs, value)
            }
            chips("Point", listOf("Delete Point", "100%", "Clear All")) { _, option ->
                when (option) {
                    "Delete Point" -> deleteAudioKeyframePoint(audioId, clip, localTimeMs)
                    "100%" -> updateAudioKeyframePoint(audioId, clip, localTimeMs, 1f)
                    "Clear All" -> applyAudioGainKeyframes(audioId, clip, emptyList())
                }
            }
        }
    }

    private fun formatFadeLabel(value: Float): String {
        val millis = value.toInt().coerceAtLeast(0)
        return if (millis >= 1000) {
            String.format(Locale.US, "%.2fs", millis / 1000f)
        } else {
            "${millis}ms"
        }
    }

    private fun buildTextClipTelemetry(): JSONArray {
        val array = JSONArray()
        allTextOverlays().forEach { overlay ->
            array.put(
                JSONObject()
                    .put("id", overlay.id)
                    .put("text", overlay.text)
                    .put("startTimeMs", overlay.startTimeMs)
                    .put("endTimeMs", overlay.endTimeMs)
                    .put("layerIndex", overlay.layerIndex)
                    .put("visible", overlay.visible),
            )
        }
        return array
    }

    private fun buildStickerClipTelemetry(): JSONArray {
        val array = JSONArray()
        StickerClipStore.all().forEach { clip ->
            array.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("type", clip.type)
                    .put("startTimeMs", clip.startTimeMs)
                    .put("durationMs", clip.durationMs)
                    .put("layerIndex", clip.layerIndex)
                    .put("visible", clip.visible),
            )
        }
        return array
    }

    private fun buildClipUpdateTelemetry(update: MultiTrackTimelineView.ClipUpdate): JSONObject {
        return JSONObject()
            .put("clipId", update.clipId)
            .put("trackType", update.trackType.name)
            .put("gestureKind", update.gestureKind.name)
            .put("startTimeMs", update.startTimeMs)
            .put("durationMs", update.durationMs)
            .put("sourceInMs", update.sourceInMs)
            .put("sourceOutMs", update.sourceOutMs)
            .put("originalStartTimeMs", update.originalStartTimeMs)
            .put("originalDurationMs", update.originalDurationMs)
            .put("originalSourceInMs", update.originalSourceInMs)
            .put("originalSourceOutMs", update.originalSourceOutMs)
    }

    private fun startHardwareTelemetryTicker() {
        if (hardwareTelemetryTickerRunning) return
        if (findViewById<View?>(R.id.hardwareBufferTelemetryPanel)?.visibility != View.VISIBLE) return
        hardwareTelemetryTickerRunning = true
        hardwareTelemetryHandler.post(hardwareTelemetryRunnable)
    }

    private fun stopHardwareTelemetryTicker() {
        hardwareTelemetryTickerRunning = false
        hardwareTelemetryHandler.removeCallbacks(hardwareTelemetryRunnable)
    }

    private fun updateHardwareTelemetryPanel() {
        if (findViewById<View?>(R.id.hardwareBufferTelemetryPanel)?.visibility != View.VISIBLE) {
            return
        }
        val preview = previewView ?: return
        val summaryView = hardwareTelemetrySummaryText ?: return
        val reasonView = hardwareTelemetryReasonText ?: return

        val telemetryJson = preview.getHardwareBufferTelemetryJson()
        runCatching {
            JSONObject(telemetryJson)
        }.onSuccess { obj ->
            val attempts = obj.optLong("attempts", 0L)
            val successes = obj.optLong("successes", 0L)
            val fallbacks = obj.optLong("fallbacks", 0L)
            val successRatio = obj.optDouble("successRatioPct", 0.0)
            val fallbackRatio = obj.optDouble("fallbackRatioPct", 0.0)

            summaryView.text = String.format(
                Locale.US,
                "A:%d S:%d(%.1f%%) F:%d(%.1f%%)",
                attempts,
                successes,
                successRatio,
                fallbacks,
                fallbackRatio
            )

            val fallbackReasons = listOf(
                "invalid" to obj.optLong("invalidArgs", 0L),
                "bridge" to obj.optLong("bridgeUnavailable", 0L),
                "egl" to obj.optLong("eglUnavailable", 0L),
                "eglCurrent" to obj.optLong("eglMakeCurrentFailed", 0L),
                "overlay" to obj.optLong("overlayMissing", 0L),
                "import" to obj.optLong("importFailed", 0L),
                "ext" to obj.optLong("extensionMissing", 0L),
                "client" to obj.optLong("nativeClientBufferFailed", 0L),
                "create" to obj.optLong("createImageFailed", 0L),
                "bind" to obj.optLong("bindImageFailed", 0L),
            )
            val topReason = fallbackReasons.maxByOrNull { it.second }
            reasonView.text = if (topReason != null && topReason.second > 0L) {
                "top: ${topReason.first}=${topReason.second}"
            } else {
                "top: none"
            }
        }.onFailure { error ->
            summaryView.text = "A:0 S:0(0.0%) F:0(0.0%)"
            reasonView.text = "top: parse_error"
            Log.w(TAG, "Failed to parse hardware telemetry: ${error.message}")
        }
    }

    // ========== PLAYBACK CONTROLS ==========

    // ========== EXPORT ==========

    private fun createNotificationChannel() {
        exportController?.createNotificationChannel()
    }

    private fun showExportDialog() {
        exportController?.showExportDialog()
    }

    private fun showEditorHome() {
        setStartScreenVisible(true)
    }

    private fun maybePromptAutoSaveRestore() {
        if (autoSaveRestorePromptShown) return
        if (hasProjectContent()) return
        val controller = projectController ?: return
        val summary = controller.describeAutoSave() ?: return
        autoSaveRestorePromptShown = true
        AlertDialog.Builder(this)
            .setTitle("Resume Last Session")
            .setMessage(summary)
            .setPositiveButton("Resume") { _, _ ->
                if (controller.restoreAutoSave() != true) {
                    autoSaveRestorePromptShown = false
                    safeToast("Autosave restore failed", Toast.LENGTH_SHORT)
                }
            }
            .setNegativeButton("Discard") { _, _ ->
                controller.discardAutoSave()
            }
            .setOnCancelListener { }
            .show()
    }

    private fun textOverlayPresetByLabel(label: String): TextOverlayPreset? {
        return textOverlayPresets.firstOrNull { it.label == label }
    }

    private fun buildTextOverlayFromPreset(
        preset: TextOverlayPreset,
        rawText: String,
        durationOverrideMs: Int? = null,
    ): TextOverlay {
        val startTimeMs = currentPlayheadMs().coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val durationMs = (durationOverrideMs ?: preset.defaultDurationMs).coerceAtLeast(600)
        val resolvedText = rawText.trim().ifEmpty { preset.hint }
        val finalText = if (preset.uppercase) resolvedText.uppercase(Locale.getDefault()) else resolvedText
        val overlayId = nextTextOverlayId++
        return TextOverlay(
            id = overlayId,
            text = finalText,
            startTimeMs = startTimeMs,
            endTimeMs = (startTimeMs + durationMs).coerceAtMost(Int.MAX_VALUE),
            x = preset.x,
            y = preset.y,
            scale = 1.0f,
            rotation = 0.0f,
            opacity = 1.0f,
            color = preset.color,
            fontSize = preset.fontSize,
            fontName = preset.fontName,
            bold = preset.bold,
            italic = preset.italic,
            layerIndex = editorState?.nextCompositeLayerIndex() ?: 0,
            visible = true,
        )
    }

    private fun addTextOverlay(overlay: TextOverlay, message: String = "Text added") {
        val preview = previewView
        if (preview == null) {
            Log.e(TAG, "PreviewView not available")
            safeToast("Preview view not available", Toast.LENGTH_SHORT)
            return
        }
        if (isPlaying) {
            isPlaying = false
            playbackController?.pauseRendering()
        }
        editorState?.recordLayerSnapshot()
        recordUndoDomain(UndoDomain.EDITOR)
        OverlayStore.put(overlay)
        addTextOverlayToPreview(overlay)
        preview.setActiveTextOverlayId(overlay.id)
        selectedTimelineClipKey = "text-${overlay.id}"
        refreshMainTimelineTracks()
        safeToast(message, Toast.LENGTH_SHORT)
    }

    private fun addTextPresetFromToolbar(label: String) {
        if (!ensureTrackEditable(TrackType.TEXT, "text")) return
        val preset = textOverlayPresetByLabel(label) ?: return
        addTextOverlay(
            buildTextOverlayFromPreset(
                preset = preset,
                rawText = preset.hint,
            ),
            message = "${preset.label} added",
        )
    }

    private fun resolveToolbarTransitionPair(): Pair<Int, Int>? {
        val manager = timelineManager ?: return null
        val clips = manager.getClips()
        if (clips.size < 2) return null
        val selected = manager.getSelectedClipId()
        val idx = clips.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
        val outgoing = clips.getOrNull(idx)?.id ?: return null
        val incoming = clips.getOrNull(idx + 1)?.id ?: clips.getOrNull(idx - 1)?.id ?: return null
        return outgoing to incoming
    }

    private fun applyToolbarTransitionPreset(type: TransitionType, durationMs: Int): Boolean {
        val (outgoing, incoming) = resolveToolbarTransitionPair() ?: return false
        return transitionController?.applyQuickTransition(outgoing, incoming, type, durationMs) == true
    }

    private fun removeToolbarTransitionPreset(): Boolean {
        val (outgoing, _) = resolveToolbarTransitionPair() ?: return false
        return transitionController?.removeTransitionByOutgoingClip(outgoing) == true
    }

    private fun showAddTextDialog() {
        if (!ensureTrackEditable(TrackType.TEXT, "text")) return
        var customDurationMs: Int? = null
        ModernSheet.show(this, "Add Text") {
            textInput("Text", "Write a title, caption, or label") { }
            slider("Duration", 1f, 8f, 3f, { "%.1fs".format(it) }) { value ->
                customDurationMs = (value * 1000f).roundToInt()
            }
            divider()
            chips("Primary", listOf("Caption", "Title", "Lower 3rd", "Subtitle"), -1) { _, option ->
                val preset = textOverlayPresetByLabel(option) ?: return@chips
                addTextOverlay(
                    buildTextOverlayFromPreset(
                        preset = preset,
                        rawText = getTextInput(),
                        durationOverrideMs = customDurationMs,
                    ),
                    message = "${preset.label} added",
                )
            }
            chips("More", listOf("Hook", "CTA", "Quote", "Label", "Badge", "Basic"), -1) { _, option ->
                val preset = textOverlayPresetByLabel(option) ?: return@chips
                addTextOverlay(
                    buildTextOverlayFromPreset(
                        preset = preset,
                        rawText = getTextInput(),
                        durationOverrideMs = customDurationMs,
                    ),
                    message = "${preset.label} added",
                )
            }
        }
    }

    private fun addNewTextOverlay(text: String) {
        if (!ensureTrackEditable(TrackType.TEXT, "text")) return
        val preset = textOverlayPresetByLabel("Basic") ?: return
        addTextOverlay(
            buildTextOverlayFromPreset(
                preset = preset,
                rawText = text,
            ),
            message = "Text added. Drag to move, pinch to scale.",
        )
    }

    private fun maybeHandleAutomationIntent(intent: Intent?) {
        val action = intent?.getStringExtra("adb_action")?.trim().orEmpty()
        Log.e(TAG, "maybeHandleAutomationIntent: action=$action extras=${intent?.extras?.keySet()?.joinToString(", ")}")
        if (action.isEmpty()) return
        val token = intent?.getStringExtra("adb_token")?.takeIf { it.isNotBlank() } ?: action
        if (lastAutomationToken == token) return
        window.decorView.postDelayed({
            performAutomationAction(action, intent, token, attempt = 0)
        }, 1200L)
    }

    private fun performAutomationAction(action: String, intent: Intent?, token: String, attempt: Int) {
        val ready =
            previewView != null &&
                importController != null &&
                exportController != null &&
                overlayController != null &&
                audioImportController != null
        if (!ready) {
            if (attempt < 12) {
                window.decorView.postDelayed({
                    performAutomationAction(action, intent, token, attempt + 1)
                }, 400L)
            }
            return
        }

        lastAutomationToken = token
        when (action) {
            "show_home" -> {
                Log.i(TAG, "[Automation] show_home")
                showEditorHome()
            }
            "tap_toolbar_back" -> {
                Log.i(TAG, "[Automation] tap_toolbar_back")
                if (!handleBackNavigationToHome()) {
                    findViewById<View?>(R.id.loadProjectButton)?.performClick()
                }
            }
            "open_export" -> {
                Log.i(TAG, "[Automation] open_export")
                showExportDialog()
            }
            "open_aspect_ratio" -> {
                Log.i(TAG, "[Automation] open_aspect_ratio")
                showAspectRatioPickerDialog()
            }
            "open_save" -> {
                Log.i(TAG, "[Automation] open_save")
                showSaveProjectDialog()
            }
            "open_media" -> {
                findViewById<View?>(R.id.cutButton)?.performClick()
            }
            "open_overlay" -> {
                findViewById<View?>(R.id.overlayImportButton)?.performClick()
            }
            "open_layer" -> {
                findViewById<View?>(R.id.layersButton)?.performClick()
            }
            "open_audio" -> {
                findViewById<View?>(R.id.audioButton)?.performClick()
            }
            "open_text" -> {
                findViewById<View?>(R.id.textButton)?.performClick()
            }
            "open_effects" -> {
                findViewById<View?>(R.id.effectsButton)?.performClick()
            }
            "open_stickers" -> {
                findViewById<View?>(R.id.stickersButton)?.performClick()
            }
            "open_transition" -> {
                findViewById<View?>(R.id.transitionButton)?.performClick()
            }
            "open_voiceover" -> {
                findViewById<View?>(R.id.voiceoverButton)?.performClick()
            }
            "open_color" -> {
                findViewById<View?>(R.id.colorGradingButton)?.performClick()
            }
            "quick_import_video" -> {
                importController?.setNextImportTrackType(TrackType.VIDEO)
                importController?.importQuickSample()
            }
            "reset_to_blank" -> {
                startBlankProject(showToast = false)
            }
            "import_video_path" -> {
                val path = intent?.getStringExtra("adb_path")
                if (path != null) {
                    importController?.setNextImportTrackType(TrackType.VIDEO)
                    importController?.importFromPath(path)
                }
            }
            "quick_import_audio" -> {
                audioImportController?.importQuickSample()
            }
            "play" -> {
                playbackController?.nativePlay()
            }
            "pause" -> {
                playbackController?.nativePause()
            }
            "set_playhead_ms" -> {
                val requestedMs = intent?.getStringExtra("adb_time_ms")?.toLongOrNull()
                    ?: intent?.extras?.get("adb_time_ms")?.toString()?.toLongOrNull()
                    ?: 0L
                setAutomationPlayhead(requestedMs)
            }
            "delete_selected" -> {
                performSelectedClipDeleteAction()
            }
            "import_video_and_play" -> {
                val path = intent?.getStringExtra("adb_path")
                if (path != null) {
                    importController?.setNextImportTrackType(TrackType.VIDEO)
                    importController?.importFromPath(path)
                    window.decorView.postDelayed({
                        playbackController?.nativePlay()
                    }, 5200L)
                }
            }
            "add_text" -> {
                addNewTextOverlay(intent?.getStringExtra("adb_text").orEmpty().ifBlank { "Hello World" })
            }
            "smoke_playback_quick" -> {
                startBlankProject(showToast = false)
                importController?.setNextImportTrackType(TrackType.VIDEO)
                val imported = importController?.importQuickSample() == true
                if (!imported) {
                    safeToast("Automation: no quick video found", Toast.LENGTH_SHORT)
                    return
                }
                scheduleAutomationSmokeVideoTrim()
                window.decorView.postDelayed({
                    playbackController?.nativePlay()
                }, 4200L)
            }
            "autosave_now" -> {
                projectController?.autoSave()
            }
            "restore_autosave" -> {
                val restored = projectController?.restoreAutoSave() == true
                Log.i(TAG, "[Automation] restore_autosave restored=$restored")
            }
            "discard_autosave" -> {
                projectController?.discardAutoSave()
            }
            "smoke_autosave_prepare" -> {
                val overlayText = intent?.getStringExtra("adb_text").orEmpty().ifBlank { "AutosaveSmoke" }
                startBlankProject(showToast = false)
                importController?.setNextImportTrackType(TrackType.VIDEO)
                val imported = importController?.importQuickSample() == true
                if (!imported) {
                    safeToast("Automation: no quick video found", Toast.LENGTH_SHORT)
                    return
                }
                scheduleAutomationSmokeVideoTrim()
                window.decorView.postDelayed({
                    audioImportController?.importQuickSample()
                }, 2200L)
                window.decorView.postDelayed({
                    scheduleAutomationSmokeAudioTrim()
                }, 3400L)
                window.decorView.postDelayed({
                    addNewTextOverlay(overlayText)
                }, 4200L)
                window.decorView.postDelayed({
                    projectController?.autoSave()
                }, 5600L)
            }
            "export_720" -> {
                performExport(1280, 720, 30, 3)
            }
            "smoke_export_720" -> {
                val overlayText = intent?.getStringExtra("adb_text").orEmpty().ifBlank { "Hello World" }
                startBlankProject(showToast = false)
                importController?.setNextImportTrackType(TrackType.VIDEO)
                val imported = importController?.importQuickSample() == true
                if (!imported) {
                    safeToast("Automation: no quick video found", Toast.LENGTH_SHORT)
                    return
                }
                scheduleAutomationSmokeVideoTrim()
                window.decorView.postDelayed({
                    audioImportController?.importQuickSample()
                }, 2200L)
                window.decorView.postDelayed({
                    scheduleAutomationSmokeAudioTrim()
                }, 3400L)
                window.decorView.postDelayed({
                    addNewTextOverlay(overlayText)
                }, 4200L)
                window.decorView.postDelayed({
                    performExport(1280, 720, 30, 3)
                }, 5600L)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        appShellController?.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            permissions.contains(android.Manifest.permission.RECORD_AUDIO)
        ) {
            voiceoverController?.onPermissionGranted()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (pendingVideoReplaceClipId != null && requestCode == PICK_VIDEO_REQUEST) {
            if (handleVideoReplaceResult(resultCode, data)) return
        }
        if (pendingAudioReplaceClipId != null && requestCode == PICK_AUDIO_REQUEST) {
            if (handleAudioReplaceResult(resultCode, data)) return
        }
        // Wait for surface to recreate after picker closes, then import
        val handledVideo = importController?.handlePickerResult(requestCode, PICK_VIDEO_REQUEST, resultCode, data) == true
        if (handledVideo) {
            // Surface recreates after picker — wait then force rebind
            mainHandler.postDelayed({
                previewView?.forceNativeSurfaceRebind()
            }, 600)
            return
        }
        audioImportController?.handlePickerResult(requestCode, PICK_AUDIO_REQUEST, resultCode, data)
    }

    private fun handleVideoReplaceResult(resultCode: Int, data: Intent?): Boolean {
        val targetClipId = pendingVideoReplaceClipId ?: return false
        pendingVideoReplaceClipId = null

        if (resultCode != Activity.RESULT_OK) {
            safeToast("Replace cancelled", Toast.LENGTH_SHORT)
            return true
        }

        val uri = data?.data ?: run {
            safeToast("No replacement file selected", Toast.LENGTH_SHORT)
            return true
        }

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val resolvedPath = resolveVideoImportPath(uri) ?: run {
            safeToast("Unable to read selected video", Toast.LENGTH_SHORT)
            return true
        }

        val result = runCatching {
            NativeBridge.executeCommand(
                action = "REPLACE_CLIP_SOURCE",
                params = mapOf(
                    "clipId" to targetClipId,
                    "videoPath" to resolvedPath,
                ),
            )
        }.getOrNull()

        if (result?.success == true) {
            val newClipId = result.data.optInt("newClipId", -1).takeIf { it > 0 }
            if (newClipId != null && newClipId != targetClipId) {
                videoClipReverseOverrides[targetClipId]?.let { videoClipReverseOverrides[newClipId] = it }
                videoClipFreezeOverrides[targetClipId]?.let { videoClipFreezeOverrides[newClipId] = it }
                videoClipCurveProfiles[targetClipId]?.let { videoClipCurveProfiles[newClipId] = it }
                videoClipGainOverrides[targetClipId]?.let { videoClipGainOverrides[newClipId] = it }
                videoClipKeyframes[targetClipId]?.let { keyframes ->
                    videoClipKeyframes[newClipId] = keyframes.toMutableList()
                }
                duckingEnabledForKey[targetClipId.toString()]?.let { duckingEnabledForKey[newClipId.toString()] = it }
                videoClipReverseOverrides.remove(targetClipId)
                videoClipFreezeOverrides.remove(targetClipId)
                videoClipCurveProfiles.remove(targetClipId)
                videoClipGainOverrides.remove(targetClipId)
                videoClipKeyframes.remove(targetClipId)
                duckingEnabledForKey.remove(targetClipId.toString())
            }
            syncTimelineShellFromNative(selectedClipId = newClipId ?: targetClipId)
            safeToast("Clip replaced", Toast.LENGTH_SHORT)
        } else {
            safeToast("Replace failed", Toast.LENGTH_SHORT)
            Log.w(TAG, "Replace failed clip=$targetClipId message=${result?.message}")
        }
        return true
    }

    private fun handleAudioReplaceResult(resultCode: Int, data: Intent?): Boolean {
        val targetAudioId = pendingAudioReplaceClipId ?: return false
        pendingAudioReplaceClipId = null

        if (resultCode != Activity.RESULT_OK) {
            safeToast("Replace cancelled", Toast.LENGTH_SHORT)
            return true
        }
        val sourceClip = AudioClipStore.get(targetAudioId) ?: return true
        val uri = data?.data ?: run {
            safeToast("No replacement audio selected", Toast.LENGTH_SHORT)
            return true
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val resolvedPath = resolveAudioImportPath(uri) ?: run {
            safeToast("Unable to read selected audio", Toast.LENGTH_SHORT)
            return true
        }
        val imported = audioImportController?.importFromPath(
            path = resolvedPath,
            startTimeMs = sourceClip.startTimeMs,
            layerIndexOverride = sourceClip.layerIndex,
        )
        if (imported == null) {
            safeToast("Audio replace failed", Toast.LENGTH_SHORT)
            return true
        }
        val oldDuckingKey = "audio-$targetAudioId"
        val newDuckingKey = "audio-${imported.id}"
        val replacementGain = audioClipGainOverrides[targetAudioId] ?: sourceClip.gain
        imported.gain = replacementGain
        imported.fadeInMs = sourceClip.fadeInMs.coerceAtLeast(0)
        imported.fadeOutMs = sourceClip.fadeOutMs.coerceAtLeast(0)
        imported.gainKeyframes = normalizeAudioGainKeyframes(sourceClip.gainKeyframes, imported.durationMs)
        imported.muted = replacementGain <= 0.001f
        audioClipGainOverrides[imported.id] = replacementGain
        runCatching {
            NativeBridge.executeCommand(
                action = "SET_CLIP_AUDIO_FADES",
                params = mapOf(
                    "clipId" to imported.id,
                    "fadeInMs" to imported.fadeInMs,
                    "fadeOutMs" to imported.fadeOutMs,
                ),
            )
        }
        runCatching {
            NativeBridge.executeCommand(
                action = "SET_CLIP_AUDIO_KEYFRAMES",
                params = mapOf(
                    "clipId" to imported.id,
                    "keyframesCsv" to NativeBridge.serializeAudioGainKeyframesCsv(imported.gainKeyframes),
                ),
            )
        }
        runCatching {
            NativeBridge.executeCommand(
                action = "DELETE_CLIP",
                params = mapOf("clipId" to targetAudioId),
            )
        }
        AudioClipStore.remove(targetAudioId)
        audioClipGainOverrides.remove(targetAudioId)
        duckingEnabledForKey[oldDuckingKey]?.let { duckingEnabledForKey[newDuckingKey] = it }
        duckingEnabledForKey.remove(oldDuckingKey)
        selectedTimelineClipKey = "audio-${imported.id}"
        lastLayoutFetchMs = 0L
        syncTimelineShellFromNative(selectedClipId = imported.id)
        safeToast("Audio replaced", Toast.LENGTH_SHORT)
        return true
    }

    private fun resolveVideoImportPath(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        if (uri.scheme != "content") {
            return uri.toString()
        }

        val importsDir = File(cacheDir, "imports").apply { mkdirs() }
        val fileName = queryDisplayName(uri)
            ?.takeIf { it.isNotBlank() }
            ?: "import_${System.currentTimeMillis()}.mp4"
        val targetFile = File(importsDir, sanitizeImportFileName(fileName))

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            targetFile.absolutePath
        } catch (error: Exception) {
            Log.e(TAG, "Failed to copy selected replacement video: ${error.message}")
            null
        }
    }

    private fun resolveAudioImportPath(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        if (uri.scheme != "content") {
            return uri.toString()
        }

        val importsDir = File(cacheDir, "audio_imports").apply { mkdirs() }
        val fileName = queryDisplayName(uri)
            ?.takeIf { it.isNotBlank() }
            ?: "audio_${System.currentTimeMillis()}.m4a"
        val targetFile = File(importsDir, sanitizeImportFileName(fileName))

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            targetFile.absolutePath
        } catch (error: Exception) {
            Log.e(TAG, "Failed to copy selected replacement audio: ${error.message}")
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to query display name: ${error.message}")
            null
        }
    }

    private fun sanitizeImportFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /**
     * Create and add a TextOverlayView for the given overlay.
     * Wires drag/scale/rotate callbacks to update native overlay.
     */
    private fun addOverlayView(overlay: TextOverlay) {
        overlayController?.addOverlayView(overlay)
    }

    /**
     * Remove the overlay view for a given overlay ID.
     */
    private fun removeOverlayView(overlayId: Int) {
        overlayController?.removeOverlayView(overlayId)
    }

    /**
     * Create and add a StickerOverlayView for the given sticker clip.
     * Wires drag/scale/rotate callbacks to update clip transform.
     */
    private fun addStickerOverlayView(clip: StickerClip) {
        overlayController?.addStickerOverlayView(clip)
    }

    private fun addTextOverlayToPreview(overlay: TextOverlay) {
        val preview = previewView ?: return
        val nativeId = preview.addTextOverlay(
            overlay.id,
            overlay.text,
            overlay.x,
            overlay.y,
            overlay.scale,
            overlay.rotation,
            overlay.color,
            overlay.fontSize,
            overlay.startTimeMs,
            overlay.endTimeMs,
        )
        if (nativeId <= 0L) {
            Log.w(TAG, "Unable to add text overlay to preview: id=${overlay.id}")
            return
        }
        runCatching {
            val (pixels, width, height) = TextBitmapHelper.createTextPixels(overlay)
            preview.setTextOverlayBitmap(overlay.id, pixels, width, height)
        }.onFailure { error ->
            Log.w(TAG, "Text bitmap upload failed: ${error.message}")
        }
        addOverlayView(overlay)
        applyTextOverlayState(overlay)
        applyTextOverlayPose(overlay)
    }

    private fun stickerLabelForClip(clip: StickerClip): String {
        if (clip.type == "sticker") {
            com.video.engine.stickers.StickerPacks.getAllPacks().values.forEach { pack ->
                pack.firstOrNull { it.id == clip.stickerId }?.let { return it.emojiOrSymbol }
            }
        }
        return if (clip.type == "image") "IMG" else "✨"
    }

    private fun applyTextOverlayPose(overlay: TextOverlay) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            val view = overlayViews[overlay.id] ?: return@post
            val container = overlayContainer ?: return@post
            val containerWidth = container.width.takeIf { it > 0 } ?: return@post
            val containerHeight = container.height.takeIf { it > 0 } ?: return@post
            val currentWidth = view.width.takeIf { it > 0 } ?: 200
            val currentHeight = view.height.takeIf { it > 0 } ?: 100
            val centerXPx = overlay.x.coerceIn(0f, 1f) * containerWidth
            val centerYPx = overlay.y.coerceIn(0f, 1f) * containerHeight
            val nextLeft = (centerXPx - (currentWidth * 0.5f)).toInt().coerceAtLeast(0)
            val nextTop = (centerYPx - (currentHeight * 0.5f)).toInt().coerceAtLeast(0)
            val lp = (view.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(
                currentWidth,
                currentHeight,
                android.view.Gravity.LEFT or android.view.Gravity.TOP,
            )
            lp.leftMargin = nextLeft
            lp.topMargin = nextTop
            view.layoutParams = lp
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = overlay.scale
            view.scaleY = overlay.scale
            view.rotation = overlay.rotation
            view.alpha = if (overlay.visible) overlay.opacity.coerceIn(0.12f, 1f) else 0.35f
            previewView?.updateTextOverlay(
                overlay.id,
                overlay.x,
                overlay.y,
                overlay.scale,
                overlay.rotation,
                overlay.color,
                overlay.fontSize,
                overlay.startTimeMs,
                overlay.endTimeMs,
            )
        }
    }

    private fun applyStickerOverlayPose(clip: StickerClip) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            val view = stickerOverlayViews[clip.id] ?: return@post
            val container = overlayContainer ?: return@post
            val containerWidth = container.width.takeIf { it > 0 } ?: return@post
            val containerHeight = container.height.takeIf { it > 0 } ?: return@post
            val currentWidth = view.width.takeIf { it > 0 } ?: 100
            val currentHeight = view.height.takeIf { it > 0 } ?: 100
            val centerXPx = clip.x.coerceIn(0f, 1f) * containerWidth
            val centerYPx = clip.y.coerceIn(0f, 1f) * containerHeight
            val nextLeft = (centerXPx - (currentWidth * 0.5f)).toInt().coerceAtLeast(0)
            val nextTop = (centerYPx - (currentHeight * 0.5f)).toInt().coerceAtLeast(0)
            val lp = (view.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(
                currentWidth,
                currentHeight,
                android.view.Gravity.LEFT or android.view.Gravity.TOP,
            )
            lp.leftMargin = nextLeft
            lp.topMargin = nextTop
            view.layoutParams = lp
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = if (clip.mirrorX) -clip.scale else clip.scale
            view.scaleY = clip.scale
            view.rotation = clip.rotation
            view.alpha = if (clip.visible) clip.opacity.coerceIn(0.12f, 1f) else 0.35f
            view.setSticker(stickerLabelForClip(clip), clip.durationMs)
        }
    }

    private fun applyTextOverlayState(overlay: TextOverlay) {
        overlayController?.applyTextOverlayState(overlay)
    }

    private fun applyStickerLayerState(clip: StickerClip) {
        overlayController?.applyStickerLayerState(clip)
    }

    private fun refreshPreviewAtPlayhead(resyncAudio: Boolean = true) {
        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        currentTimeMs = playheadMs
        previewView?.let { pv -> NativeBridge.seekToTime(pv, playheadMs) }
        if (resyncAudio) {
            previewAudioPlayer?.seekTo(playheadMs, continuePlaying = isPlaying)
        }
    }

    private fun setupEffectSliderControls() {
        brightnessSeekBar?.max = 200
        contrastSeekBar?.max = 200
        brightnessSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val clipId = selectedVideoClipId() ?: return
                val current = clipEffects[clipId] ?: EffectParams()
                applySelectedClipEffects(
                    clipId = clipId,
                    params = current.copy(brightness = ((progress - 100) / 100f).coerceIn(-1f, 1f)),
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        contrastSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val clipId = selectedVideoClipId() ?: return
                val current = clipEffects[clipId] ?: EffectParams()
                applySelectedClipEffects(
                    clipId = clipId,
                    params = current.copy(contrast = (progress / 100f).coerceIn(0f, 2f)),
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        refreshEffectSliderLabels(EffectParams())
    }

    private fun applySelectedClipEffects(clipId: Int, params: EffectParams) {
        clipEffects[clipId] = params
        previewView?.setClipEffects(clipId, params.brightness, params.contrast, params.saturation)
        refreshEffectSliderLabels(params)
        execCmd(
            action = "SET_CLIP_EFFECTS",
            params = mapOf(
                "clipId" to clipId,
                "brightness" to params.brightness,
                "contrast" to params.contrast,
                "saturation" to params.saturation,
            ),
        )
        previewView?.let { pv ->
            runCatching { NativeBridge.seekToTime(pv, currentPlayheadMs().coerceAtLeast(0L)) }
        }
    }

    private fun syncEffectSlidersForClip(clipId: Int?) {
        val params = clipId?.let { clipEffects[it] } ?: EffectParams()
        brightnessSeekBar?.progress = ((params.brightness + 1f) * 100f).roundToInt().coerceIn(0, 200)
        contrastSeekBar?.progress = (params.contrast * 100f).roundToInt().coerceIn(0, 200)
        refreshEffectSliderLabels(params)
    }

    private fun refreshEffectSliderLabels(params: EffectParams) {
        brightnessLabel?.text = "Brightness ${String.format(Locale.US, "%.2f", params.brightness)}"
        contrastLabel?.text = "Contrast ${String.format(Locale.US, "%.2f", params.contrast)}"
    }

    private fun clearEditorShellState() {
        recordTelemetryEvent("editor", "clear_shell_state")
        overlayViews.keys.toList().forEach { removeOverlayView(it) }
        stickerOverlayViews.keys.toList().forEach { removeStickerOverlayView(it) }
        OverlayStore.clear()
        StickerClipStore.all().map { it.id }.forEach { StickerClipStore.remove(it) }
        AudioClipStore.clear()
        previewAudioPlayer?.pause()
        timelineManager?.syncClips(emptyList(), recordHistory = false, clearHistory = true)
        timelineManager?.selectClip(null)
        videoClipTimingOverrides.clear()
        clipPreviewTransforms.clear()
        audioClipGainOverrides.clear()
        videoClipGainOverrides.clear()
        videoClipReverseOverrides.clear()
        videoClipFreezeOverrides.clear()
        videoClipCurveProfiles.clear()
        videoClipKeyframes.clear()
        stickerClipKeyframes.clear()
        duckingEnabledForKey.clear()
        nativeClipTrackType.clear()
        nativeClipLane.clear()
        nativeClipZOrder.clear()
        nativeClipStartMs.clear()
        nativeClipDurationMs.clear()
        nativeClipSourceInMs.clear()
        nativeClipSourceOutMs.clear()
        nativeClipSourcePath.clear()
        nativeClipPlaybackSpeed.clear()
        nativeClipReversePlayback.clear()
        nativeClipFreezeFrameEnabled.clear()
        nativeClipFreezeFrameTimeMs.clear()
        nativeClipFreezeFrameDurationMs.clear()
        nativeClipCurveSpeedProfile.clear()
        nativeClipCurveSpeedStrength.clear()
        trackVisibilityOverrides.clear()
        trackLockedOverrides.clear()
        selectedTimelineClipKey = null
        currentTimeMs = 0L
        videoDurationMs = 0L
        importController?.setNextImportTrackType(TrackType.VIDEO)
        undoDomains.clear()
        redoDomains.clear()
        nextTextOverlayId = 1
        nextStickerId = 1
        nextAudioClipId = 1
        lastPreviewAudioSyncSignature = ""
        NativeBridge.clearNativeCommandTelemetry()
        timelineCurrentTimeText?.text = getString(R.string.time_zero)
        effectSlidersContainer?.visibility = View.GONE
        multiTrackTimelineView?.setSelectedClipId(null)
        activeCanvasTimelineView()?.setSelectedClipId(null)
        applySelectedClipPreviewTransform()
        updateUndoRedoButtons()
        refreshMainTimelineTracks()
    }

    private fun startBlankProject(showToast: Boolean) {
        setStartScreenVisible(false)
        NativeBridge.executeCommand("RESET_TIMELINE", emptyMap())
        clearEditorShellState()
        previewView?.let { NativeBridge.seekToTime(it, 0L) }
        refreshMainTimelineTracks()
        if (showToast) {
            safeToast("New Project Created", Toast.LENGTH_SHORT)
        }
        Log.i(TAG, "[Automation] blank project ready")
    }

    private fun scheduleAutomationSmokeVideoTrim(attempt: Int = 0) {
        window.decorView.postDelayed({
            val trimmed = clampAutomationSmokeVideoDuration()
            if (!trimmed && attempt < 10) {
                scheduleAutomationSmokeVideoTrim(attempt + 1)
            }
        }, if (attempt == 0) 1400L else 500L)
    }

    private fun scheduleAutomationSmokeAudioTrim(attempt: Int = 0) {
        window.decorView.postDelayed({
            val changed = clampAutomationSmokeAudioDurations()
            if (!changed && attempt < 10) {
                scheduleAutomationSmokeAudioTrim(attempt + 1)
            }
        }, if (attempt == 0) 3400L else 500L)
    }

    private fun clampAutomationSmokeVideoDuration(maxDurationMs: Long = AUTOMATION_SMOKE_DURATION_MS): Boolean {
        val clipId =
            timelineManager
                ?.getClips()
                ?.firstOrNull { nativeClipTrackType[it.id] == TrackType.VIDEO }
                ?.id
                ?: nativeClipTrackType.entries.firstOrNull { it.value == TrackType.VIDEO }?.key
                ?: return false
        val currentTiming = selectedVideoTiming(clipId) ?: return false
        val sourceInMs = nativeClipSourceInMs[clipId] ?: 0L
        val originalSourceOutMs =
            (nativeClipSourceOutMs[clipId] ?: (sourceInMs + currentTiming.second)).coerceAtLeast(sourceInMs + 1L)
        val clampedDurationMs = minOf(currentTiming.second, maxDurationMs).coerceAtLeast(150L)
        val clampedSourceOutMs = (sourceInMs + clampedDurationMs).coerceAtMost(originalSourceOutMs)
        if (currentTiming.second <= clampedDurationMs && originalSourceOutMs <= clampedSourceOutMs) {
            return true
        }
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "UPDATE_CLIP_TIMING",
                params = mapOf(
                    "clipId" to clipId,
                    "newStartTimeMs" to currentTiming.first,
                    "newDurationMs" to clampedDurationMs,
                    "newSourceInMs" to sourceInMs,
                    "newSourceOutMs" to clampedSourceOutMs,
                    "originalStartTimeMs" to currentTiming.first,
                    "originalDurationMs" to currentTiming.second,
                    "originalSourceInMs" to sourceInMs,
                    "originalSourceOutMs" to originalSourceOutMs,
                    "previewOnly" to false,
                    "applyMagnetic" to true,
                ),
            )
        }.getOrNull() ?: return false
        if (!result.success) {
            Log.w(TAG, "[Automation] smoke video trim failed: clip=$clipId message=${result.message}")
            return false
        }
        nativeClipDurationMs[clipId] = clampedDurationMs
        nativeClipSourceOutMs[clipId] = clampedSourceOutMs
        syncTimelineShellFromNative(selectedClipId = clipId)
        refreshPreviewAtPlayhead()
        Log.i(TAG, "[Automation] smoke video clip trimmed clip=$clipId durationMs=$clampedDurationMs")
        return true
    }

    private fun clampAutomationSmokeAudioDurations(maxDurationMs: Long = AUTOMATION_SMOKE_DURATION_MS): Boolean {
        var changed = false
        AudioClipStore.all().forEach { clip ->
            val clampedDurationMs = minOf(clip.durationMs, maxDurationMs).coerceAtLeast(150L)
            if (clampedDurationMs < clip.durationMs) {
                clip.durationMs = clampedDurationMs
                changed = true
                Log.i(TAG, "[Automation] smoke audio clip trimmed clip=${clip.id} durationMs=$clampedDurationMs")
            }
        }
        if (changed) {
            NativeBridge.syncAudioClips()
        }
        return changed
    }

    private fun saveUiState(projectFile: File, projectName: String) {
        projectStateSerializer?.saveUiState(projectFile, projectName)
        refreshRecentProjects()
        recordTelemetryEvent(
            "project",
            "save_ui_state",
            JSONObject()
                .put("projectFile", projectFile.absolutePath)
                .put("projectName", projectName),
        )
        captureDebugSnapshot("project_saved")
    }

    private fun loadUiState(projectFile: File) {
        val loadedState = projectStateSerializer?.loadUiState(projectFile) ?: return
        nextTextOverlayId = loadedState.nextTextOverlayId
        nextStickerId = loadedState.nextStickerId
        nextAudioClipId = loadedState.nextAudioClipId.coerceAtLeast(1)
        trackVisibilityOverrides.clear()
        trackVisibilityOverrides.putAll(loadedState.trackVisibilityByType)
        trackLockedOverrides.clear()
        trackLockedOverrides.putAll(loadedState.trackLockedByType)
        loadedState.selectedAspectRatioIndex?.let { restoredIndex ->
            selectedAspectRatioIndex = restoredIndex.coerceIn(0, aspectRatioOptions.lastIndex)
            applyPreviewAspectRatio()
        }
        loadedState.timelineZoomPxPerSecond?.let { restoredZoom ->
            NativeBridge.setTimelineZoomPxPerSecond(restoredZoom)
            activeCanvasTimelineView()?.setZoomPxPerSecond(restoredZoom)
            multiTrackTimelineView?.setZoomPxPerSecond(restoredZoom)
        }
        loadedState.playheadTimeMs?.let { restoredTimeMs ->
            currentTimeMs = restoredTimeMs
            previewView?.let { NativeBridge.seekToTime(it, restoredTimeMs) }
            timelineManager?.dispatchScrub(restoredTimeMs)
            activeCanvasTimelineView()?.setPlayheadMs(restoredTimeMs)
            multiTrackTimelineView?.setCurrentTimeMs(restoredTimeMs)
        }
        recordTelemetryEvent(
            "project",
            "load_ui_state",
            JSONObject().put("projectFile", projectFile.absolutePath),
        )
        captureDebugSnapshot("project_loaded")
        refreshMainTimelineTracks()
    }

    private fun refreshOverlayStack() {
        val container = overlayContainer ?: return
        val orderedViews = mutableListOf<Pair<Int, android.view.View>>()
        allTextOverlays().forEach { overlay ->
            overlayViews[overlay.id]?.let { orderedViews += (400 + overlay.layerIndex) to it }
        }
        StickerClipStore.all().forEach { sticker ->
            stickerOverlayViews[sticker.id]?.let { orderedViews += (400 + sticker.layerIndex) to it }
        }
        orderedViews
            .sortedBy { it.first }
            .forEach { (_, view) -> view.bringToFront() }
        container.invalidate()
    }

    private fun withAutoSubTracks(clips: List<ClipSegment>): List<ClipSegment> {
        val laneEndTimes = mutableListOf<Long>()
        return clips
            .sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.zOrder })
            .map { clip ->
                val laneIndex = laneEndTimes.indexOfFirst { clip.startTimeMs >= it }.let { existing ->
                    if (existing >= 0) existing else laneEndTimes.size.also { laneEndTimes += 0L }
                }
                laneEndTimes[laneIndex] = clip.endTimeMs()
                clip.copy(metadata = clip.metadata + ("subTrack" to (laneIndex + 1).toString()))
            }
    }

    /**
     * Remove the sticker overlay view for a given clip ID.
     */
    private fun removeStickerOverlayView(clipId: Int) {
        overlayController?.removeStickerOverlayView(clipId)
    }

    // ========== TRANSITIONS ==========

    /**
     * Perform export with complete progress tracking and error handling.
     * This method orchestrates the full export pipeline:
     * 1. Validates settings
     * 2. Creates output file
     * 3. Starts export in background thread
     * 4. Shows progress dialog
     * 5. Handles completion/errors
     */
    fun getSelectedAspectRatioIndex(): Int = selectedAspectRatioIndex

    fun performExport(
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        bitrateMbps: Int = 3,
        exportTitle: String = "Storyline",
        requestedProfileLabel: String = "HD",
        includeWatermark: Boolean = true,
        videoCodec: String = "h264",
    ) {
        exportController?.performExport(
            width,
            height,
            fps,
            bitrateMbps,
            exportTitle,
            requestedProfileLabel,
            includeWatermark,
            videoCodec,
        )
    }

    // ============ Project Save/Load UI Methods ============

    /**
     * Show project save dialog.
     * User can enter project name and choose location.
     */
    private fun showSaveProjectDialog() {
        projectController?.showSaveProjectDialog()
    }

    /**
     * Show project load dialog (file picker).
     * Lists all saved projects and allows selection.
     */
    private fun showLoadProjectDialog() {
        projectController?.showLoadProjectDialog()
    }

    private fun applyTimelineStateToShell() {
        val managerClips = timelineManager?.getClips().orEmpty()
        timeline.clear()
        managerClips.forEach { clip ->
            timeline.addClip(clip.id, clip.durationMs, clip.title)
        }
        updateUndoRedoButtons()
        refreshMainTimelineTracks()
    }

    private fun mapNativeTrackType(trackTypeRaw: String?, zOrder: Int = 0): TrackType =
        TrackType.fromNativeRole(trackTypeRaw, zOrder)

    private fun buildTimelineShellClipsFromNativeCache(): List<com.video.engine.timeline.TimelineClip> {
        val nativeIds = linkedSetOf<Int>().apply {
            addAll(nativeClipTrackType.keys)
            addAll(nativeClipStartMs.keys)
            addAll(nativeClipDurationMs.keys)
            addAll(nativeClipSourcePath.keys)
        }
        return nativeIds
            .sortedWith(
                compareBy<Int>(
                    { nativeClipStartMs[it] ?: 0L },
                    { nativeClipZOrder[it] ?: 0 },
                    { it },
                ),
            )
            .map { clipId ->
                val trackType = nativeClipTrackType[clipId] ?: TrackType.VIDEO
                val title = when (trackType) {
                    TrackType.AUDIO -> {
                        AudioClipStore.get(clipId)?.displayName ?: "Audio $clipId"
                    }
                    TrackType.OVERLAY -> {
                        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
                        File(sourcePath).nameWithoutExtension.takeIf { it.isNotBlank() } ?: "Overlay $clipId"
                    }
                    TrackType.LAYER -> {
                        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
                        File(sourcePath).nameWithoutExtension.takeIf { it.isNotBlank() } ?: "Layer $clipId"
                    }
                    else -> {
                        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
                        File(sourcePath).nameWithoutExtension.takeIf { it.isNotBlank() } ?: "Clip $clipId"
                    }
                }
                com.video.engine.timeline.TimelineClip(
                    id = clipId,
                    durationMs = nativeClipDurationMs[clipId]?.coerceAtLeast(1L) ?: 1L,
                    title = title,
                )
            }
    }

    private fun refreshNativeClipLayoutCache(validClipIds: Set<Int>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLayoutFetchMs < 300L) {
            // Too soon — just trim stale keys and refresh UI with cached data
            nativeClipTrackType.keys.retainAll(validClipIds)
            nativeClipStartMs.keys.retainAll(validClipIds)
            nativeClipDurationMs.keys.retainAll(validClipIds)
            refreshMainTimelineTracks()
            return
        }
        lastLayoutFetchMs = now
        Thread {
            val result = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
            mainHandler.post {
                if (result?.success != true) {
                    nativeClipTrackType.keys.retainAll(validClipIds)
                    nativeClipLane.keys.retainAll(validClipIds)
                    nativeClipZOrder.keys.retainAll(validClipIds)
                    nativeClipStartMs.keys.retainAll(validClipIds)
                    nativeClipDurationMs.keys.retainAll(validClipIds)
                    nativeClipSourceInMs.keys.retainAll(validClipIds)
                    nativeClipSourceOutMs.keys.retainAll(validClipIds)
                    nativeClipSourcePath.keys.retainAll(validClipIds)
                    nativeClipPlaybackSpeed.keys.retainAll(validClipIds)
                    nativeClipReversePlayback.keys.retainAll(validClipIds)
                    nativeClipFreezeFrameEnabled.keys.retainAll(validClipIds)
                    nativeClipFreezeFrameTimeMs.keys.retainAll(validClipIds)
                    nativeClipFreezeFrameDurationMs.keys.retainAll(validClipIds)
                    nativeClipCurveSpeedProfile.keys.retainAll(validClipIds)
                    nativeClipCurveSpeedStrength.keys.retainAll(validClipIds)
                    nativeClipAudioGainKeyframes.keys.retainAll(validClipIds)
                    return@post
                }
                val clipsJson: JSONArray = result.data.optJSONArray("clips") ?: JSONArray()
                val nextTrackType = mutableMapOf<Int, TrackType>()
                val nextLane = mutableMapOf<Int, Int>()
                val nextZOrder = mutableMapOf<Int, Int>()
                val nextStartMs = mutableMapOf<Int, Long>()
                val nextDurationMs = mutableMapOf<Int, Long>()
                val nextSourceInMs = mutableMapOf<Int, Long>()
                val nextSourceOutMs = mutableMapOf<Int, Long>()
                val nextSourcePath = mutableMapOf<Int, String>()
                val nextVolumeGain = mutableMapOf<Int, Float>()
                val nextFadeInMs = mutableMapOf<Int, Int>()
                val nextFadeOutMs = mutableMapOf<Int, Int>()
                val nextDuckingEnabled = mutableMapOf<Int, Boolean>()
                val nextPlaybackSpeed = mutableMapOf<Int, Float>()
                val nextReversePlayback = mutableMapOf<Int, Boolean>()
                val nextFreezeFrameEnabled = mutableMapOf<Int, Boolean>()
                val nextFreezeFrameTimeMs = mutableMapOf<Int, Long>()
                val nextFreezeFrameDurationMs = mutableMapOf<Int, Long>()
                val nextCurveSpeedProfile = mutableMapOf<Int, String>()
                val nextCurveSpeedStrength = mutableMapOf<Int, Float>()
                val nextAudioGainKeyframes = mutableMapOf<Int, List<AudioGainKeyframe>>()
                for (index in 0 until clipsJson.length()) {
                    val clipJson = clipsJson.optJSONObject(index) ?: continue
                    val clipId = clipJson.optInt("clipId", -1)
                    if (clipId <= 0 || !validClipIds.contains(clipId)) continue
                    val clipZOrder = clipJson.optInt("zOrder", 0)
                    nextTrackType[clipId] = mapNativeTrackType(clipJson.optString("trackType", "VIDEO"), clipZOrder)
                    nextLane[clipId] = clipJson.optInt("trackLane", 0).coerceAtLeast(0)
                    nextZOrder[clipId] = clipZOrder
                    nextStartMs[clipId] = clipJson.optLong("startTimeMs", 0L).coerceAtLeast(0L)
                    val durationMs = clipJson.optLong("durationMs", 0L).coerceAtLeast(1L)
                    nextDurationMs[clipId] = durationMs
                    val sourceIn = clipJson.optLong("sourceInMs", 0L).coerceAtLeast(0L)
                    val sourceOut = clipJson.optLong("sourceOutMs", sourceIn + durationMs).coerceAtLeast(sourceIn + 1L)
                    nextSourceInMs[clipId] = sourceIn
                    nextSourceOutMs[clipId] = sourceOut
                    nextSourcePath[clipId] = clipJson.optString("originalSourcePath")
                        .ifBlank { clipJson.optString("sourcePath", "") }
                    nextVolumeGain[clipId] = clipJson.optDouble("volumeGain", 1.0).toFloat().coerceAtLeast(0f)
                    nextFadeInMs[clipId] = clipJson.optInt("fadeInMs", 0).coerceAtLeast(0)
                    nextFadeOutMs[clipId] = clipJson.optInt("fadeOutMs", 0).coerceAtLeast(0)
                    nextDuckingEnabled[clipId] = clipJson.optBoolean("duckingEnabled", false)
                    nextPlaybackSpeed[clipId] = clipJson.optDouble("playbackSpeed", 1.0).toFloat().coerceAtLeast(0.1f)
                    nextReversePlayback[clipId] = clipJson.optBoolean("reversePlayback", false)
                    nextFreezeFrameEnabled[clipId] = clipJson.optBoolean("freezeFrameEnabled", false)
                    nextFreezeFrameTimeMs[clipId] = clipJson.optLong("freezeFrameTimeMs", 0L).coerceAtLeast(0L)
                    nextFreezeFrameDurationMs[clipId] = clipJson.optLong("freezeFrameDurationMs", 0L).coerceAtLeast(0L)
                    nextCurveSpeedProfile[clipId] = clipJson.optString("curveSpeedProfile", "linear")
                    nextCurveSpeedStrength[clipId] = clipJson.optDouble("curveSpeedStrength", 1.0).toFloat().coerceAtLeast(0.1f)
                    val parsedAudioGainKeyframes = mutableListOf<AudioGainKeyframe>()
                    val keyframesJson = clipJson.optJSONArray("audioGainKeyframes") ?: JSONArray()
                    for (keyframeIndex in 0 until keyframesJson.length()) {
                        val keyframeJson = keyframesJson.optJSONObject(keyframeIndex) ?: continue
                        parsedAudioGainKeyframes += AudioGainKeyframe(
                            timeMs = keyframeJson.optLong("timeMs", 0L),
                            gain = keyframeJson.optDouble("gain", 1.0).toFloat(),
                        )
                    }
                    nextAudioGainKeyframes[clipId] = normalizeAudioGainKeyframes(
                        parsedAudioGainKeyframes,
                        durationMs,
                    )
                }
                mergeAudioStateIntoNativeCache(
                    nextTrackType = nextTrackType,
                    nextLane = nextLane,
                    nextZOrder = nextZOrder,
                    nextStartMs = nextStartMs,
                    nextDurationMs = nextDurationMs,
                    nextSourceInMs = nextSourceInMs,
                    nextSourceOutMs = nextSourceOutMs,
                    nextSourcePath = nextSourcePath,
                    nextVolumeGain = nextVolumeGain,
                    nextFadeInMs = nextFadeInMs,
                    nextFadeOutMs = nextFadeOutMs,
                    nextDuckingEnabled = nextDuckingEnabled,
                    nextPlaybackSpeed = nextPlaybackSpeed,
                    nextReversePlayback = nextReversePlayback,
                    nextFreezeFrameEnabled = nextFreezeFrameEnabled,
                    nextFreezeFrameTimeMs = nextFreezeFrameTimeMs,
                    nextFreezeFrameDurationMs = nextFreezeFrameDurationMs,
                    nextCurveSpeedProfile = nextCurveSpeedProfile,
                    nextCurveSpeedStrength = nextCurveSpeedStrength,
                    nextAudioGainKeyframes = nextAudioGainKeyframes,
                )
                nativeClipTrackType.clear(); nativeClipTrackType.putAll(nextTrackType)
                nativeClipLane.clear(); nativeClipLane.putAll(nextLane)
                nativeClipZOrder.clear(); nativeClipZOrder.putAll(nextZOrder)
                nativeClipStartMs.clear(); nativeClipStartMs.putAll(nextStartMs)
                nativeClipDurationMs.clear(); nativeClipDurationMs.putAll(nextDurationMs)
                nativeClipSourceInMs.clear(); nativeClipSourceInMs.putAll(nextSourceInMs)
                nativeClipSourceOutMs.clear(); nativeClipSourceOutMs.putAll(nextSourceOutMs)
                nativeClipSourcePath.clear(); nativeClipSourcePath.putAll(nextSourcePath)
                nativeClipPlaybackSpeed.clear(); nativeClipPlaybackSpeed.putAll(nextPlaybackSpeed)
                nativeClipReversePlayback.clear(); nativeClipReversePlayback.putAll(nextReversePlayback)
                nativeClipFreezeFrameEnabled.clear(); nativeClipFreezeFrameEnabled.putAll(nextFreezeFrameEnabled)
                nativeClipFreezeFrameTimeMs.clear(); nativeClipFreezeFrameTimeMs.putAll(nextFreezeFrameTimeMs)
                nativeClipFreezeFrameDurationMs.clear(); nativeClipFreezeFrameDurationMs.putAll(nextFreezeFrameDurationMs)
                nativeClipCurveSpeedProfile.clear(); nativeClipCurveSpeedProfile.putAll(nextCurveSpeedProfile)
                nativeClipCurveSpeedStrength.clear(); nativeClipCurveSpeedStrength.putAll(nextCurveSpeedStrength)
                nativeClipAudioGainKeyframes.clear(); nativeClipAudioGainKeyframes.putAll(nextAudioGainKeyframes)
                videoClipGainOverrides.keys
                    .filter { clipId ->
                        nextTrackType[clipId] != TrackType.VIDEO &&
                            nextTrackType[clipId] != TrackType.OVERLAY &&
                            nextTrackType[clipId] != TrackType.LAYER
                    }
                    .toList()
                    .forEach { videoClipGainOverrides.remove(it) }
                audioClipGainOverrides.keys
                    .filter { clipId -> nextTrackType[clipId] != TrackType.AUDIO }
                    .toList()
                    .forEach { audioClipGainOverrides.remove(it) }
                nextVolumeGain.forEach { (clipId, gain) ->
                    when (nextTrackType[clipId]) {
                        TrackType.AUDIO -> audioClipGainOverrides[clipId] = gain
                        TrackType.VIDEO, TrackType.OVERLAY, TrackType.LAYER -> videoClipGainOverrides[clipId] = gain
                        else -> Unit
                    }
                }
                duckingEnabledForKey.keys
                    .filter { key ->
                        val managedClipId = parseTimelineManagedClipId(key)
                        managedClipId != null && !validClipIds.contains(managedClipId)
                    }
                    .toList()
                    .forEach { duckingEnabledForKey.remove(it) }
                nextDuckingEnabled.forEach { (clipId, enabled) ->
                    val key = when (nextTrackType[clipId]) {
                        TrackType.AUDIO -> "audio-$clipId"
                        TrackType.VIDEO, TrackType.OVERLAY, TrackType.LAYER -> clipId.toString()
                        else -> return@forEach
                    }
                    duckingEnabledForKey[key] = enabled
                }
                syncAudioClipStoreFromNativeLayout(
                    trackTypes = nextTrackType,
                    startTimesMs = nextStartMs,
                    durationsMs = nextDurationMs,
                    sourcePaths = nextSourcePath,
                    laneIndices = nextLane,
                    volumeGains = nextVolumeGain,
                    fadeInValues = nextFadeInMs,
                    fadeOutValues = nextFadeOutMs,
                    audioGainKeyframes = nextAudioGainKeyframes,
                )
                refreshMainTimelineTracks()
            }
        }.start()
    }

    private fun mergeAudioStateIntoNativeCache(
        nextTrackType: MutableMap<Int, TrackType>,
        nextLane: MutableMap<Int, Int>,
        nextZOrder: MutableMap<Int, Int>,
        nextStartMs: MutableMap<Int, Long>,
        nextDurationMs: MutableMap<Int, Long>,
        nextSourceInMs: MutableMap<Int, Long>,
        nextSourceOutMs: MutableMap<Int, Long>,
        nextSourcePath: MutableMap<Int, String>,
        nextVolumeGain: MutableMap<Int, Float>,
        nextFadeInMs: MutableMap<Int, Int>,
        nextFadeOutMs: MutableMap<Int, Int>,
        nextDuckingEnabled: MutableMap<Int, Boolean>,
        nextPlaybackSpeed: MutableMap<Int, Float>,
        nextReversePlayback: MutableMap<Int, Boolean>,
        nextFreezeFrameEnabled: MutableMap<Int, Boolean>,
        nextFreezeFrameTimeMs: MutableMap<Int, Long>,
        nextFreezeFrameDurationMs: MutableMap<Int, Long>,
        nextCurveSpeedProfile: MutableMap<Int, String>,
        nextCurveSpeedStrength: MutableMap<Int, Float>,
        nextAudioGainKeyframes: MutableMap<Int, List<AudioGainKeyframe>>,
    ) {
        AudioClipStore.all().forEach { clip ->
            val clipId = clip.id
            if (nextTrackType[clipId] == TrackType.AUDIO) return@forEach
            if (nextTrackType.containsKey(clipId) && nextTrackType[clipId] != TrackType.AUDIO) return@forEach
            val durationMs = clip.durationMs.coerceAtLeast(1L)
            nextTrackType[clipId] = TrackType.AUDIO
            nextLane[clipId] = clip.layerIndex.coerceAtLeast(0)
            nextZOrder[clipId] = clip.layerIndex.coerceAtLeast(0)
            nextStartMs[clipId] = clip.startTimeMs.coerceAtLeast(0L)
            nextDurationMs[clipId] = durationMs
            nextSourceInMs[clipId] = 0L
            nextSourceOutMs[clipId] = durationMs
            nextSourcePath[clipId] = clip.sourcePath
            nextVolumeGain[clipId] = audioClipGainOverrides[clipId] ?: clip.gain.coerceAtLeast(0f)
            nextFadeInMs[clipId] = clip.fadeInMs.coerceAtLeast(0)
            nextFadeOutMs[clipId] = clip.fadeOutMs.coerceAtLeast(0)
            nextDuckingEnabled[clipId] = duckingEnabledForKey["audio-$clipId"] ?: false
            nextPlaybackSpeed[clipId] = 1f
            nextReversePlayback[clipId] = false
            nextFreezeFrameEnabled[clipId] = false
            nextFreezeFrameTimeMs[clipId] = 0L
            nextFreezeFrameDurationMs[clipId] = 0L
            nextCurveSpeedProfile[clipId] = "linear"
            nextCurveSpeedStrength[clipId] = 1f
            nextAudioGainKeyframes[clipId] = normalizeAudioGainKeyframes(clip.gainKeyframes, durationMs)
        }
    }

    private fun syncAudioClipStoreFromNativeLayout(
        trackTypes: Map<Int, TrackType>,
        startTimesMs: Map<Int, Long>,
        durationsMs: Map<Int, Long>,
        sourcePaths: Map<Int, String>,
        laneIndices: Map<Int, Int>,
        volumeGains: Map<Int, Float>,
        fadeInValues: Map<Int, Int>,
        fadeOutValues: Map<Int, Int>,
        audioGainKeyframes: Map<Int, List<AudioGainKeyframe>>,
    ) {
        val existingById = AudioClipStore.all().associateBy { it.id }
        val existingByPath = AudioClipStore.all().groupBy { it.sourcePath }
        val audioIds = trackTypes
            .filterValues { it == TrackType.AUDIO }
            .keys
            .sorted()

        existingById.keys
            .filter { it !in audioIds }
            .forEach { AudioClipStore.remove(it) }

        audioIds.forEach { clipId ->
            val sourcePath = sourcePaths[clipId].orEmpty()
            val existing = existingById[clipId]
            val inherited = existing ?: existingByPath[sourcePath]?.firstOrNull()
            val displayName = inherited?.displayName
                ?: File(sourcePath).nameWithoutExtension.takeIf { it.isNotBlank() }
                ?: "Audio $clipId"
            val gain = volumeGains[clipId] ?: (if (inherited?.muted == true) 0f else 1f)
            val fadeInMs = fadeInValues[clipId] ?: inherited?.fadeInMs ?: 0
            val fadeOutMs = fadeOutValues[clipId] ?: inherited?.fadeOutMs ?: 0
            val gainKeyframes = audioGainKeyframes[clipId] ?: inherited?.gainKeyframes.orEmpty()
            AudioClipStore.add(
                com.video.engine.audio.AudioClip(
                    id = clipId,
                    sourcePath = sourcePath,
                    displayName = displayName,
                    startTimeMs = startTimesMs[clipId] ?: inherited?.startTimeMs ?: 0L,
                    durationMs = durationsMs[clipId] ?: inherited?.durationMs ?: 1L,
                    gain = gain,
                    fadeInMs = fadeInMs.coerceAtLeast(0),
                    fadeOutMs = fadeOutMs.coerceAtLeast(0),
                    layerIndex = laneIndices[clipId] ?: inherited?.layerIndex ?: 0,
                    visible = inherited?.visible ?: true,
                    muted = gain <= 0.001f,
                    peakMapPath = inherited?.peakMapPath,
                    peakBucketMs = inherited?.peakBucketMs ?: 20,
                    peakLevels = inherited?.peakLevels.orEmpty(),
                    gainKeyframes = gainKeyframes,
                ),
            )
            audioClipGainOverrides[clipId] = gain
        }

        if (audioIds.isNotEmpty()) {
            nextAudioClipId = maxOf(nextAudioClipId, (audioIds.maxOrNull() ?: 0) + 1)
        }
    }

    private fun syncTimelineShellFromNative(selectedClipId: Int? = null) {
        val pv = previewView ?: return
        Thread {
            val nativeClipIds = NativeBridge.getClipIds(pv).toList()
            val nativeClipIdSet = nativeClipIds.toSet()
            val retainedClipIds = nativeClipIdSet + AudioClipStore.all().map { it.id }
            mainHandler.post {
            refreshNativeClipLayoutCache(retainedClipIds)
        val clips = nativeClipIds.mapIndexed { index, clipId ->
            val trackType = nativeClipTrackType[clipId] ?: TrackType.VIDEO
            val title = when (trackType) {
                TrackType.AUDIO -> "Audio ${index + 1}"
                TrackType.OVERLAY -> "Overlay ${index + 1}"
                TrackType.LAYER -> "Layer ${index + 1}"
                else -> "Clip ${index + 1}"
            }
            com.video.engine.timeline.TimelineClip(
                id = clipId,
                durationMs = nativeClipDurationMs[clipId] ?: NativeBridge.getClipDuration(pv, clipId),
                title = title,
            )
        }
        timeline.clear()
        clips.forEach { clip ->
            timeline.addClip(clip.id, clip.durationMs, clip.title)
        }
        videoClipReverseOverrides.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipReverseOverrides.remove(it) }
        videoClipFreezeOverrides.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipFreezeOverrides.remove(it) }
        videoClipCurveProfiles.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipCurveProfiles.remove(it) }
        videoClipKeyframes.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipKeyframes.remove(it) }
        videoClipGainOverrides.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipGainOverrides.remove(it) }
        duckingEnabledForKey.keys
            .filter { key -> parseTimelineManagedClipId(key)?.let { !retainedClipIds.contains(it) } == true }
            .toList()
            .forEach { duckingEnabledForKey.remove(it) }
        nativeClipTrackType.keys.retainAll(retainedClipIds)
        nativeClipLane.keys.retainAll(retainedClipIds)
        nativeClipZOrder.keys.retainAll(retainedClipIds)
        nativeClipStartMs.keys.retainAll(retainedClipIds)
        nativeClipDurationMs.keys.retainAll(retainedClipIds)
        nativeClipSourceInMs.keys.retainAll(retainedClipIds)
        nativeClipSourceOutMs.keys.retainAll(retainedClipIds)
        nativeClipSourcePath.keys.retainAll(retainedClipIds)
        nativeClipPlaybackSpeed.keys.retainAll(retainedClipIds)
        nativeClipReversePlayback.keys.retainAll(retainedClipIds)
        nativeClipFreezeFrameEnabled.keys.retainAll(retainedClipIds)
        nativeClipFreezeFrameTimeMs.keys.retainAll(retainedClipIds)
        nativeClipFreezeFrameDurationMs.keys.retainAll(retainedClipIds)
        nativeClipCurveSpeedProfile.keys.retainAll(retainedClipIds)
        nativeClipCurveSpeedStrength.keys.retainAll(retainedClipIds)
        videoClipTimingOverrides.clear()
        val manager = timelineManager
        val existingVisibilityByClipId =
            manager?.getClips()?.associate { it.id to manager.getClipVisibility(it.id) }.orEmpty()
        manager?.syncClips(clips, recordHistory = false, clearHistory = false)
        clips.forEach { clip ->
            val existingLayer = manager?.getClipLayerIndex(clip.id) ?: 0
            manager?.setClipLayerIndex(clip.id, nativeClipZOrder[clip.id] ?: existingLayer)
            val defaultVisible = trackVisibilityOverrides[nativeClipTrackType[clip.id] ?: TrackType.VIDEO] ?: true
            manager?.setClipVisibility(clip.id, existingVisibilityByClipId[clip.id] ?: defaultVisible)
        }
        if (selectedClipId != null) {
            if (nativeClipIdSet.contains(selectedClipId)) {
                manager?.selectClip(selectedClipId)
            }
            selectedTimelineClipKey = selectionKeyForNativeClipId(selectedClipId)
        }
        refreshMainTimelineTracks()
            } // end mainHandler.post
        }.start() // end Thread
    }

    private fun updateUndoRedoButtons() {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            findViewById<ImageView?>(R.id.undoButton)?.alpha = if (undoDomains.isNotEmpty()) 1.0f else 0.35f
            findViewById<ImageView?>(R.id.undoButton)?.isEnabled = undoDomains.isNotEmpty()
            findViewById<ImageView?>(R.id.redoButton)?.alpha = if (redoDomains.isNotEmpty()) 1.0f else 0.35f
            findViewById<ImageView?>(R.id.redoButton)?.isEnabled = redoDomains.isNotEmpty()
        }
    }

    private fun recordUndoDomain(domain: UndoDomain) {
        undoDomains.addLast(domain)
        redoDomains.clear()
        updateUndoRedoButtons()
    }

    private fun setupMainMultiTrackTimeline() {
        multiTrackTimelineView?.setListener(object : MultiTrackTimelineView.Listener {
            override fun onSeek(timeMs: Long) {
                lastTimelineScrollMs = SystemClock.elapsedRealtime()
                currentTimeMs = timeMs  // keep in sync so split uses correct position
                playbackController?.scrubTo(timeMs, syncTimelineUi = false)
                val now = SystemClock.elapsedRealtime()
                if (now - lastTimelineSeekTelemetryElapsedMs >= 120L) {
                    lastTimelineSeekTelemetryElapsedMs = now
                    recordTelemetryEvent(
                        "timeline",
                        "seek",
                        JSONObject()
                            .put("timeMs", timeMs)
                            .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
                    )
                }
            }

            override fun onClipUpdatePreview(update: MultiTrackTimelineView.ClipUpdate) {
                if (applyLocalClipTimingUpdate(update)) {
                    return
                }
                val videoClipId = parseNativeClipId(update.clipId) ?: return
                videoClipTimingOverrides[videoClipId] = ClipTimingSnapshot(
                    startTimeMs = update.startTimeMs,
                    durationMs = update.durationMs,
                    sourceInMs = update.sourceInMs,
                    sourceOutMs = update.sourceOutMs,
                )
            }

            override fun onClipUpdateCommitted(update: MultiTrackTimelineView.ClipUpdate) {
                if (update.clipId.startsWith("audio-")) {
                    val audioClipId = update.clipId.removePrefix("audio-").toIntOrNull() ?: return
                    val result = runCatching {
                        NativeBridge.executeCommand(
                            action = "UPDATE_CLIP_TIMING",
                            params = mapOf(
                                "clipId" to audioClipId,
                                "newStartTimeMs" to update.startTimeMs,
                                "newDurationMs" to update.durationMs,
                                "newSourceInMs" to update.sourceInMs,
                                "newSourceOutMs" to update.sourceOutMs,
                                "originalStartTimeMs" to update.originalStartTimeMs,
                                "originalDurationMs" to update.originalDurationMs,
                                "originalSourceInMs" to update.originalSourceInMs,
                                "originalSourceOutMs" to update.originalSourceOutMs,
                                "previewOnly" to false,
                                "applyMagnetic" to false,
                            ),
                        )
                    }.getOrNull()
                    if (result?.success == true) {
                        AudioClipStore.get(audioClipId)?.let { audioClip ->
                            audioClip.startTimeMs = update.startTimeMs
                            audioClip.durationMs = update.durationMs
                        }
                        lastLayoutFetchMs = 0L
                        previewView?.let { pv ->
                            refreshNativeClipLayoutCache(NativeBridge.getClipIds(pv).toSet())
                        } ?: refreshMainTimelineTracks()
                        recordUndoDomain(UndoDomain.TIMELINE)
                        recordTelemetryEvent(
                            "timeline",
                            "clip_update_committed_native",
                            buildClipUpdateTelemetry(update)
                                .put("clipIdInt", audioClipId)
                                .put("success", true),
                        )
                    } else {
                        Log.w(TAG, "Audio clip commit failed: id=$audioClipId message=${result?.message}")
                        recordTelemetryEvent(
                            "timeline",
                            "clip_update_failed",
                            buildClipUpdateTelemetry(update)
                                .put("clipIdInt", audioClipId)
                                .put("success", false)
                                .put("message", result?.message ?: "unknown"),
                        )
                    }
                    return
                }
                if (applyLocalClipTimingUpdate(update)) {
                    recordUndoDomain(UndoDomain.EDITOR)
                    recordTelemetryEvent(
                        "timeline",
                        "clip_update_committed_local",
                        buildClipUpdateTelemetry(update)
                            .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
                    )
                    refreshMainTimelineTracks()
                    return
                }
                val videoClipId = parseNativeClipId(update.clipId) ?: return
                val result = runCatching {
                    NativeBridge.executeCommand(
                        action = "UPDATE_CLIP_TIMING",
                        params = mapOf(
                            "clipId" to videoClipId,
                            "newStartTimeMs" to update.startTimeMs,
                            "newDurationMs" to update.durationMs,
                            "newSourceInMs" to update.sourceInMs,
                            "newSourceOutMs" to update.sourceOutMs,
                            "originalStartTimeMs" to update.originalStartTimeMs,
                            "originalDurationMs" to update.originalDurationMs,
                            "originalSourceInMs" to update.originalSourceInMs,
                            "originalSourceOutMs" to update.originalSourceOutMs,
                            "previewOnly" to false,
                            "applyMagnetic" to (update.trackType == TrackType.VIDEO),
                        ),
                    )
                }.getOrNull()
                if (result?.success == true) {
                    if (update.gestureKind != MultiTrackTimelineView.ClipGestureKind.MOVE) {
                        updateTimelineManagerClipDuration(videoClipId, update.durationMs)
                    }
                    recordUndoDomain(UndoDomain.TIMELINE)
                    recordTelemetryEvent(
                        "timeline",
                        "clip_update_committed_native",
                        buildClipUpdateTelemetry(update)
                            .put("clipIdInt", videoClipId)
                            .put("success", true),
                    )
                    refreshMainTimelineTracks()
                } else {
                    Log.w(TAG, "Clip commit failed: id=$videoClipId message=${result?.message}")
                    recordTelemetryEvent(
                        "timeline",
                        "clip_update_failed",
                        buildClipUpdateTelemetry(update)
                            .put("clipIdInt", videoClipId)
                            .put("success", false)
                            .put("message", result?.message ?: "unknown"),
                    )
                    captureDebugSnapshot("clip_update_failed")
                    videoClipTimingOverrides.remove(videoClipId)
                    refreshMainTimelineTracks()
                }
            }

            override fun onZoomChanged(pxPerSecond: Float) {
                runCatching {
                    NativeBridge.setTimelineZoomPxPerSecond(pxPerSecond)
                }
                recordTelemetryEvent(
                    "timeline",
                    "zoom_changed",
                    JSONObject().put("pxPerSecond", pxPerSecond.toDouble()),
                )
            }

            override fun onClipSelected(clipId: String?) {
                if (clipId == null && selectedTimelineClipKey != null) {
                    // Don't deselect if this null came from scroll - only deselect on explicit tap
                    val lastScrollMs = lastTimelineScrollMs
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastScrollMs < 300L) return
                }
                selectedTimelineClipKey = normalizeTimelineSelectionKey(clipId)
                updateBottomToolbarMode()
                parseTimelineManagedClipId(clipId)?.let { timelineManager?.selectClip(it) }
                if (clipId == null) {
                    timelineManager?.selectClip(null)
                }
                recordTelemetryEvent(
                    "timeline",
                    "clip_selected",
                    JSONObject()
                        .put("clipId", clipId ?: JSONObject.NULL)
                        .put("clipKind", selectedClipKind().name),
                )
            }

            override fun onTrackImportRequested(trackType: TrackType) {
                recordTelemetryEvent(
                    "timeline",
                    "track_import_requested",
                    JSONObject().put("trackType", trackType.name),
                )
                when (trackType) {
                    TrackType.VIDEO -> openVideoTrackImport()
                    TrackType.OVERLAY -> openOverlayTrackImport()
                    TrackType.LAYER -> openLayerTrackImport()
                    TrackType.TEXT -> showAddTextDialog()
                    TrackType.AUDIO -> openAudioTrackImport()
                }
            }

            override fun onTrackVisibilityChanged(trackType: TrackType, isVisible: Boolean) {
                applyTrackVisibilityChange(trackType, isVisible)
                recordTelemetryEvent(
                    "timeline",
                    "track_visibility_changed",
                    JSONObject()
                        .put("trackType", trackType.name)
                        .put("isVisible", isVisible),
                )
            }

            override fun onTrackLockedChanged(trackType: TrackType, isLocked: Boolean) {
                applyTrackLockChange(trackType, isLocked)
                recordTelemetryEvent(
                    "timeline",
                    "track_locked_changed",
                    JSONObject()
                        .put("trackType", trackType.name)
                        .put("isLocked", isLocked),
                )
            }
        })
    }

    private fun openVideoTrackImport() {
        if (!ensureTrackEditable(TrackType.VIDEO, "import")) return
        importController?.setNextImportTrackType(TrackType.VIDEO)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_VIDEO_REQUEST)
    }

    private fun openOverlayTrackImport() {
        if (!ensureTrackEditable(TrackType.OVERLAY, "import")) return
        importController?.setNextImportTrackType(TrackType.OVERLAY)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_VIDEO_REQUEST)
    }

    private fun openLayerTrackImport() {
        if (!ensureTrackEditable(TrackType.LAYER, "import")) return
        importController?.setNextImportTrackType(TrackType.LAYER)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_VIDEO_REQUEST)
    }

    private fun openAudioTrackImport() {
        if (!ensureTrackEditable(TrackType.AUDIO, "import")) return
        audioImportController?.openPicker(PICK_AUDIO_REQUEST)
    }

    private fun applyLocalClipTimingUpdate(update: MultiTrackTimelineView.ClipUpdate): Boolean {
        return when {
            update.clipId.startsWith("text-") -> {
                val overlayId = update.clipId.removePrefix("text-").toIntOrNull() ?: return false
                val overlay = OverlayStore.get(overlayId) ?: return false
                overlay.startTimeMs = update.startTimeMs.toInt()
                overlay.endTimeMs = (update.startTimeMs + update.durationMs).toInt()
                previewView?.updateTextOverlay(
                    overlay.id,
                    overlay.x,
                    overlay.y,
                    overlay.scale,
                    overlay.rotation,
                    overlay.color,
                    overlay.fontSize,
                    overlay.startTimeMs,
                    overlay.endTimeMs,
                )
                true
            }
            update.clipId.startsWith("sticker-") -> {
                val stickerId = update.clipId.removePrefix("sticker-").toIntOrNull() ?: return false
                val sticker = StickerClipStore.all().find { it.id == stickerId } ?: return false
                sticker.startTimeMs = update.startTimeMs.toInt()
                sticker.durationMs = update.durationMs.toInt().coerceAtLeast(1)
                applyStickerOverlayPose(sticker)
                true
            }
            update.clipId.startsWith("audio-") -> {
                val audioId = update.clipId.removePrefix("audio-").toIntOrNull() ?: return false
                val audio = AudioClipStore.get(audioId) ?: return false
                audio.startTimeMs = update.startTimeMs
                audio.durationMs = update.durationMs
                true
            }
            else -> false
        }
    }

    private fun updateTimelineManagerClipDuration(clipId: Int, durationMs: Long) {
        val manager = timelineManager ?: return
        val current = manager.getClips()
        if (current.none { it.id == clipId }) return
        val updated = current.map { clip ->
            if (clip.id == clipId) clip.copy(durationMs = durationMs) else clip
        }
        manager.syncClips(updated, recordHistory = true, clearHistory = false)
        manager.selectClip(clipId)
    }

    private val refreshTimelineRunnable = Runnable {
        if (!isFinishing && !isDestroyed) refreshMainTimelineTracksInternal()
    }

    private fun refreshMainTimelineTracks() {
        mainHandler.removeCallbacks(refreshTimelineRunnable)
        mainHandler.postDelayed(refreshTimelineRunnable, 16L)
    }

    private fun resolvedTrackVisibility(trackType: TrackType, computedDefault: Boolean): Boolean {
        return trackVisibilityOverrides[trackType] ?: computedDefault
    }

    private fun resolvedTrackLocked(trackType: TrackType): Boolean {
        return trackLockedOverrides[trackType] ?: false
    }

    private fun refreshMainTimelineTracksInternal() {
        var managerClips = timelineManager?.getClips().orEmpty()
        if (managerClips.isEmpty()) {
            val fallbackClips = buildTimelineShellClipsFromNativeCache()
            if (fallbackClips.isNotEmpty()) {
                timeline.clear()
                fallbackClips.forEach { clip ->
                    timeline.addClip(clip.id, clip.durationMs, clip.title)
                }
                timelineManager?.syncClips(fallbackClips, recordHistory = false, clearHistory = false)
                fallbackClips.forEach { clip ->
                    val existingLayer = timelineManager?.getClipLayerIndex(clip.id) ?: 0
                    timelineManager?.setClipLayerIndex(clip.id, nativeClipZOrder[clip.id] ?: existingLayer)
                    timelineManager?.setClipVisibility(
                        clip.id,
                        timelineManager?.getClipVisibility(clip.id) ?: true,
                    )
                }
                managerClips = fallbackClips
            }
        }
        val videoManagerClips = managerClips.filter { clip ->
            (nativeClipTrackType[clip.id] ?: TrackType.VIDEO) == TrackType.VIDEO
        }
        val overlayManagerClips = managerClips.filter { clip ->
            nativeClipTrackType[clip.id] == TrackType.OVERLAY
        }
        val layerManagerClips = managerClips.filter { clip ->
            nativeClipTrackType[clip.id] == TrackType.LAYER
        }
        val audioManagerClips = managerClips.filter { clip ->
            nativeClipTrackType[clip.id] == TrackType.AUDIO
        }
        var rollingStartMs = 0L
        val videoTrack = TrackState(
            id = "main-video",
            type = TrackType.VIDEO,
            clips = videoManagerClips.map { clip ->
                val timing = videoClipTimingOverrides[clip.id]
                val nativeStartTimeMs = nativeClipStartMs[clip.id] ?: rollingStartMs
                val nativeDuration = nativeClipDurationMs[clip.id] ?: clip.durationMs
                val durationMs = timing?.durationMs ?: nativeDuration
                val startTimeMs = timing?.startTimeMs ?: nativeStartTimeMs
                val sourceInMs = timing?.sourceInMs ?: (nativeClipSourceInMs[clip.id] ?: 0L)
                val sourceOutMs = timing?.sourceOutMs ?: (nativeClipSourceOutMs[clip.id] ?: (sourceInMs + durationMs))
                val reverseEnabled = videoClipReverseOverrides[clip.id] == true
                val freeze = videoClipFreezeOverrides[clip.id]
                val curveProfile = videoClipCurveProfiles[clip.id] ?: "linear"
                val keyframes = videoClipKeyframes[clip.id].orEmpty()
                rollingStartMs = maxOf(rollingStartMs, startTimeMs + durationMs)
                ClipSegment(
                    id = clip.id.toString(),
                    sourcePath = nativeClipSourcePath[clip.id].orEmpty().ifBlank { clip.title },
                    trackType = TrackType.VIDEO,
                    startTimeMs = startTimeMs,
                    durationMs = durationMs,
                    sourceInMs = sourceInMs,
                    sourceOutMs = sourceOutMs,
                    zOrder = nativeClipZOrder[clip.id] ?: (timelineManager?.getClipLayerIndex(clip.id) ?: 0),
                    isHidden = !(timelineManager?.getClipVisibility(clip.id) ?: true),
                    metadata = mapOf(
                        "reverse" to reverseEnabled.toString(),
                        "freezeAtMs" to (freeze?.first?.toString() ?: ""),
                        "freezeDurationMs" to (freeze?.second?.toString() ?: ""),
                        "curveSpeedProfile" to curveProfile,
                        "keyframesMs" to keyframes.joinToString(","),
                        "subTrack" to ((nativeClipLane[clip.id] ?: 0) + 1).toString(),
                    ),
                )
            },
            isLocked = resolvedTrackLocked(TrackType.VIDEO),
            isVisible = resolvedTrackVisibility(
                TrackType.VIDEO,
                videoManagerClips.any { timelineManager?.getClipVisibility(it.id) ?: true } || videoManagerClips.isEmpty(),
            ),
        )
        val overlayTrack = TrackState(
            id = "main-overlay",
            type = TrackType.OVERLAY,
            clips = withAutoSubTracks(
                overlayManagerClips.map { clip ->
                    val timing = videoClipTimingOverrides[clip.id]
                    val nativeStartTimeMs = nativeClipStartMs[clip.id] ?: currentTimeMs
                    val nativeDuration = nativeClipDurationMs[clip.id] ?: clip.durationMs
                    val durationMs = timing?.durationMs ?: nativeDuration
                    val startTimeMs = timing?.startTimeMs ?: nativeStartTimeMs
                    val sourceInMs = timing?.sourceInMs ?: (nativeClipSourceInMs[clip.id] ?: 0L)
                    val sourceOutMs = timing?.sourceOutMs ?: (nativeClipSourceOutMs[clip.id] ?: (sourceInMs + durationMs))
                    ClipSegment(
                        id = "overlay-${clip.id}",
                        sourcePath = nativeClipSourcePath[clip.id].orEmpty().ifBlank { clip.title },
                        trackType = TrackType.OVERLAY,
                        startTimeMs = startTimeMs,
                        durationMs = durationMs,
                        sourceInMs = sourceInMs,
                        sourceOutMs = sourceOutMs,
                        zOrder = nativeClipZOrder[clip.id] ?: (timelineManager?.getClipLayerIndex(clip.id) ?: 200),
                        isHidden = !(timelineManager?.getClipVisibility(clip.id) ?: true),
                        metadata = mapOf(
                            "subTrack" to ((nativeClipLane[clip.id] ?: 0) + 1).toString(),
                        ),
                    )
                },
            ),
            isLocked = resolvedTrackLocked(TrackType.OVERLAY),
            isVisible = resolvedTrackVisibility(
                TrackType.OVERLAY,
                overlayManagerClips.any { timelineManager?.getClipVisibility(it.id) ?: true } || overlayManagerClips.isEmpty(),
            ),
        )
        val layerTrack = TrackState(
            id = "main-layer",
            type = TrackType.LAYER,
            clips = withAutoSubTracks(
                layerManagerClips.map { clip ->
                    val timing = videoClipTimingOverrides[clip.id]
                    val nativeStartTimeMs = nativeClipStartMs[clip.id] ?: currentTimeMs
                    val nativeDuration = nativeClipDurationMs[clip.id] ?: clip.durationMs
                    val durationMs = timing?.durationMs ?: nativeDuration
                    val startTimeMs = timing?.startTimeMs ?: nativeStartTimeMs
                    val sourceInMs = timing?.sourceInMs ?: (nativeClipSourceInMs[clip.id] ?: 0L)
                    val sourceOutMs = timing?.sourceOutMs ?: (nativeClipSourceOutMs[clip.id] ?: (sourceInMs + durationMs))
                    ClipSegment(
                        id = "layer-${clip.id}",
                        sourcePath = nativeClipSourcePath[clip.id].orEmpty().ifBlank { clip.title },
                        trackType = TrackType.LAYER,
                        startTimeMs = startTimeMs,
                        durationMs = durationMs,
                        sourceInMs = sourceInMs,
                        sourceOutMs = sourceOutMs,
                        zOrder = nativeClipZOrder[clip.id] ?: (timelineManager?.getClipLayerIndex(clip.id) ?: 120),
                        isHidden = !(timelineManager?.getClipVisibility(clip.id) ?: true),
                        metadata = mapOf(
                            "subTrack" to ((nativeClipLane[clip.id] ?: 0) + 1).toString(),
                        ),
                    )
                },
            ),
            isLocked = resolvedTrackLocked(TrackType.LAYER),
            isVisible = resolvedTrackVisibility(
                TrackType.LAYER,
                layerManagerClips.any { timelineManager?.getClipVisibility(it.id) ?: true } || layerManagerClips.isEmpty(),
            ),
        )
        val topLayerTrack = TrackState(
            id = "top-text-sticker",
            type = TrackType.TEXT,
            clips = withAutoSubTracks(
                allTextOverlays().map { overlay ->
                    val duration = (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(1)
                    ClipSegment(
                        id = "text-${overlay.id}",
                        sourcePath = overlay.text,
                        trackType = TrackType.TEXT,
                        startTimeMs = overlay.startTimeMs.toLong(),
                        durationMs = duration.toLong(),
                        sourceInMs = 0L,
                        sourceOutMs = duration.toLong(),
                        zOrder = 400 + overlay.layerIndex,
                        isHidden = !overlay.visible,
                        metadata = mapOf("contentType" to "text"),
                    )
                } + StickerClipStore.all().map { clip ->
                    val stickerKeyframes = stickerClipKeyframes[clip.id].orEmpty()
                    ClipSegment(
                        id = "sticker-${clip.id}",
                        sourcePath = clip.imagePath ?: clip.type,
                        trackType = TrackType.TEXT,
                        startTimeMs = clip.startTimeMs.toLong(),
                        durationMs = clip.durationMs.toLong(),
                        sourceInMs = 0L,
                        sourceOutMs = clip.durationMs.toLong(),
                        zOrder = 400 + clip.layerIndex,
                        isHidden = !clip.visible,
                        metadata = mapOf(
                            "contentType" to "sticker",
                            "opacity" to String.format(Locale.US, "%.2f", clip.opacity),
                            "mirrorX" to clip.mirrorX.toString(),
                            "keyframesMs" to stickerKeyframes.joinToString(","),
                        ),
                    )
                }
            ),
            isLocked = resolvedTrackLocked(TrackType.TEXT),
            isVisible = resolvedTrackVisibility(
                TrackType.TEXT,
                allTextOverlays().any { it.visible } || StickerClipStore.all().any { it.visible } ||
                    (allTextOverlays().isEmpty() && StickerClipStore.all().isEmpty()),
            ),
        )
        val audioTrack = TrackState(
            id = "main-audio",
            type = TrackType.AUDIO,
            clips = AudioClipStore.all()
                .sortedWith(
                    compareBy<AudioClip> { it.startTimeMs }
                        .thenBy { it.layerIndex }
                        .thenBy { it.id },
                )
                .map { clip ->
                val gain = audioClipGainOverrides[clip.id] ?: if (clip.muted) 0f else clip.gain.coerceIn(0f, 2f)
                val startTimeMs = nativeClipStartMs[clip.id] ?: clip.startTimeMs
                val durationMs = nativeClipDurationMs[clip.id] ?: clip.durationMs.coerceAtLeast(1L)
                val sourceInMs = nativeClipSourceInMs[clip.id] ?: 0L
                val sourceOutMs = nativeClipSourceOutMs[clip.id] ?: (sourceInMs + durationMs)
                ClipSegment(
                    id = "audio-${clip.id}",
                    sourcePath = nativeClipSourcePath[clip.id].orEmpty().ifBlank { clip.sourcePath },
                    trackType = TrackType.AUDIO,
                    startTimeMs = startTimeMs,
                    durationMs = durationMs,
                    sourceInMs = sourceInMs,
                    sourceOutMs = sourceOutMs,
                    zOrder = nativeClipZOrder[clip.id] ?: clip.layerIndex,
                    isMuted = gain <= 0.001f,
                    isHidden = !clip.visible,
                    metadata = mapOf(
                        "displayName" to clip.displayName,
                        "isImportedAudio" to "true",
                        "gain" to String.format(Locale.US, "%.2f", gain),
                        "peakMapPath" to (clip.peakMapPath ?: ""),
                        "peakBucketMs" to clip.peakBucketMs.toString(),
                        "peakLevels" to clip.peakLevels.joinToString(","),
                    ),
                )
            },
            isLocked = resolvedTrackLocked(TrackType.AUDIO),
            isVisible = resolvedTrackVisibility(
                TrackType.AUDIO,
                AudioClipStore.all().any { it.visible } || AudioClipStore.all().isEmpty(),
            ),
        )
        val trackStates = listOf(topLayerTrack, overlayTrack, layerTrack, videoTrack, audioTrack)
        val selectedClipKey =
            selectedTimelineClipKey ?: timelineManager?.getSelectedClipId()?.let(::selectionKeyForNativeClipId)

        activeMultiTrackTimelineView()?.let { timelineView ->
            timelineView.submitTracks(trackStates)
            timelineView.setSelectedClipId(selectedClipKey)
            timelineView.setCurrentTimeMs(currentTimeMs)
        }

        activeCanvasTimelineView()?.let { canvasView ->
            canvasView.setTracks(trackStates)
            canvasView.setSelectedClipId(selectedClipKey)
            canvasView.setPlayheadMs(currentTimeMs)
        }

        syncPreviewAudioClipsToNative()
        NativeBridge.invalidatePreviewAudioResolutionCache()
        updateBottomToolbarMode()
    }

    private fun syncPreviewAudioClipsToNative() {
        val clipStates = NativeBridge.buildPreviewAudioClipStates(AudioClipStore.all()) { clip ->
            audioClipGainOverrides[clip.id] ?: if (clip.muted) 0f else 1f
        }
        val signature = clipStates.joinToString(separator = "|") { clip ->
            listOf(
                clip.path,
                clip.startTimeMs.toString(),
                clip.durationMs.toString(),
                clip.volume.toString(),
                clip.fadeInMs.toString(),
                clip.fadeOutMs.toString(),
                clip.keyframesCsv,
                clip.layerIndex.toString(),
                clip.visible.toString(),
            ).joinToString(separator = "\u0001")
        }
        if (signature == lastPreviewAudioSyncSignature) {
            return
        }
        lastPreviewAudioSyncSignature = signature
        NativeBridge.setPreviewAudioClips(clipStates)
    }

    private fun cacheAudioClipLayout(clip: AudioClip) {
        val durationMs = clip.durationMs.coerceAtLeast(1L)
        nativeClipTrackType[clip.id] = TrackType.AUDIO
        nativeClipLane[clip.id] = clip.layerIndex.coerceAtLeast(0)
        nativeClipZOrder[clip.id] = clip.layerIndex.coerceAtLeast(0)
        nativeClipStartMs[clip.id] = clip.startTimeMs.coerceAtLeast(0L)
        nativeClipDurationMs[clip.id] = durationMs
        nativeClipSourceInMs[clip.id] = 0L
        nativeClipSourceOutMs[clip.id] = durationMs
        nativeClipSourcePath[clip.id] = clip.sourcePath
        nativeClipAudioGainKeyframes[clip.id] = normalizeAudioGainKeyframes(clip.gainKeyframes, durationMs)
    }

    private fun splitAudioAtPlayhead(): Boolean {
        val requestedTimeMs = multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs
        val targetClip = resolveSplitTargetAudioClip(requestedTimeMs)
        if (targetClip == null) {
            recordTelemetryEvent(
                "edit",
                "split_rejected",
                JSONObject()
                    .put("trackType", TrackType.AUDIO.name)
                    .put("playheadMs", requestedTimeMs)
                    .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
            )
            return false
        }
        val clipStartMs = targetClip.startTimeMs
        val clipEndMs = targetClip.startTimeMs + targetClip.durationMs
        val targetTimeMs = requestedTimeMs.coerceIn(clipStartMs + 1L, clipEndMs - 1L)
        val nextId = maxOf(
            nextAudioClipId,
            (AudioClipStore.all().maxOfOrNull { it.id } ?: 0) + 1,
        )
        val splitPair = AudioClipStore.splitAt(
            clipId = targetClip.id,
            splitTimeMs = targetTimeMs,
            nextId = nextId,
        )
        if (splitPair == null) {
            Log.w(TAG, "Audio split rejected in store: clip=${targetClip.id} time=$targetTimeMs")
            return false
        }
        val (leftClip, rightClip) = splitPair
        val leftClipId = leftClip.id
        val rightClipId = rightClip.id
        nextAudioClipId = maxOf(nextAudioClipId, rightClipId + 1)
        val sourceGain = audioClipGainOverrides[targetClip.id] ?: if (targetClip.muted) 0f else 1f
        audioClipGainOverrides.remove(targetClip.id)
        audioClipGainOverrides[leftClipId] = sourceGain
        audioClipGainOverrides[rightClipId] = sourceGain
        leftClip.gain = sourceGain
        rightClip.gain = sourceGain
        leftClip.muted = sourceGain <= 0.001f
        rightClip.muted = sourceGain <= 0.001f
        val sourceDucking = duckingEnabledForKey["audio-${targetClip.id}"] ?: false
        duckingEnabledForKey.remove("audio-${targetClip.id}")
        duckingEnabledForKey["audio-$leftClipId"] = sourceDucking
        duckingEnabledForKey["audio-$rightClipId"] = sourceDucking
        cacheAudioClipLayout(leftClip)
        cacheAudioClipLayout(rightClip)
        selectedTimelineClipKey = "audio-$rightClipId"
        currentTimeMs = targetTimeMs
        lastLayoutFetchMs = 0L
        lastPreviewAudioSyncSignature = ""
        syncPreviewAudioClipsToNative()
        refreshMainTimelineTracks()
        playbackController?.scrubTo(targetTimeMs, syncTimelineUi = false)
        stabilizeAfterSplit(targetTimeMs, "audio-$rightClipId")
        Toast.makeText(
            this,
            "Audio split at ${targetTimeMs}ms",
            Toast.LENGTH_SHORT,
        ).show()
        recordTelemetryEvent(
            "edit",
            "split_success",
            JSONObject()
                .put("trackType", TrackType.AUDIO.name)
                .put("playheadMs", targetTimeMs)
                .put("targetClipId", targetClip.id)
                .put("leftClipId", leftClipId)
                .put("rightClipId", rightClipId),
        )
        captureDebugSnapshot("split_audio_success")
        Log.d(TAG, "Audio split complete: left=$leftClipId right=$rightClipId at=${targetTimeMs}ms")
        return true
    }

    private fun splitSelectedVideoAtPlayhead(): Boolean {
        // Get clip start to ensure we don't split at exact boundary (native rejects it)
        val rawMs = currentPlayheadMs()
        val selectedId = selectedVideoClipId()
        val clipStartMs = selectedId?.let { nativeClipStartMs[it] } ?: 0L
        val clipDurMs = selectedId?.let { nativeClipDurationMs[it] } ?: Long.MAX_VALUE
        val targetTimeMs = when {
            rawMs <= clipStartMs -> clipStartMs + 100L  // too early, push inside
            rawMs >= clipStartMs + clipDurMs -> clipStartMs + clipDurMs - 100L  // too late
            else -> rawMs
        }
        currentTimeMs = targetTimeMs
        // Synchronously refresh layout before split so all clips are current
        previewView?.let { pv ->
            val result = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
            if (result?.success == true) {
                val clipsJson = result.data.optJSONArray("clips") ?: return@let
                for (i in 0 until clipsJson.length()) {
                    val c = clipsJson.optJSONObject(i) ?: continue
                    val id = c.optInt("clipId", -1)
                    if (id <= 0) continue
                    nativeClipTrackType[id] = mapNativeTrackType(c.optString("trackType", "VIDEO"), c.optInt("zOrder", 0))
                    nativeClipStartMs[id] = c.optLong("startTimeMs", 0L)
                    nativeClipDurationMs[id] = c.optLong("durationMs", 1L).coerceAtLeast(1L)
                    nativeClipSourceInMs[id] = c.optLong("sourceInMs", 0L)
                    nativeClipSourceOutMs[id] = c.optLong("sourceOutMs", 0L)
                    nativeClipSourcePath[id] = c.optString("originalSourcePath").ifBlank { c.optString("sourcePath", "") }
                    nativeClipPlaybackSpeed[id] = c.optDouble("playbackSpeed", 1.0).toFloat().coerceAtLeast(0.1f)
                    nativeClipReversePlayback[id] = c.optBoolean("reversePlayback", false)
                    nativeClipFreezeFrameEnabled[id] = c.optBoolean("freezeFrameEnabled", false)
                    nativeClipFreezeFrameTimeMs[id] = c.optLong("freezeFrameTimeMs", 0L).coerceAtLeast(0L)
                    nativeClipFreezeFrameDurationMs[id] = c.optLong("freezeFrameDurationMs", 0L).coerceAtLeast(0L)
                    nativeClipCurveSpeedProfile[id] = c.optString("curveSpeedProfile", "linear")
                    nativeClipCurveSpeedStrength[id] = c.optDouble("curveSpeedStrength", 1.0).toFloat().coerceAtLeast(0.1f)
                }
            }
        }
        val clipId = resolveSplitTargetVideoClipId(targetTimeMs)
        if (clipId == null) {
            recordTelemetryEvent(
                "edit",
                "split_rejected",
                JSONObject()
                    .put("trackType", selectedTrackType()?.name ?: TrackType.VIDEO.name)
                    .put("playheadMs", targetTimeMs)
                    .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
            )
            return false
        }
        val clipTrackType = nativeClipTrackType[clipId] ?: TrackType.VIDEO
        val clipLabel = when (clipTrackType) {
            TrackType.LAYER -> "Layer"
            TrackType.OVERLAY -> "Overlay"
            else -> "Video"
        }
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "SPLIT",
                params = mapOf(
                    "clipId" to clipId,
                    "timeMs" to targetTimeMs,
                ),
            )
        }.getOrNull()
        if (result == null) {
            recordTelemetryEvent(
                "edit",
                "split_failed",
                JSONObject()
                    .put("trackType", clipTrackType.name)
                    .put("playheadMs", targetTimeMs)
                    .put("targetClipId", clipId)
                    .put("message", "Native command returned null"),
            )
            captureDebugSnapshot("split_native_null")
            return false
        }
        if (!result.success) {
            Log.d(TAG, "$clipLabel split rejected: ${result.message}")
            recordTelemetryEvent(
                "edit",
                "split_failed",
                JSONObject()
                    .put("trackType", clipTrackType.name)
                    .put("playheadMs", targetTimeMs)
                    .put("targetClipId", clipId)
                    .put("message", result.message),
            )
            captureDebugSnapshot("split_native_failed")
            return false
        }
        val rightClipId = result.data.optInt("rightClipId", -1).takeIf { it > 0 }
        if (rightClipId != null) {
            val keyframes = videoClipKeyframes[clipId].orEmpty()
            val leftKeyframes = keyframes.filter { it <= targetTimeMs }.toMutableList()
            val rightKeyframes = keyframes.filter { it > targetTimeMs }.toMutableList()
            if (leftKeyframes.isNotEmpty()) {
                videoClipKeyframes[clipId] = leftKeyframes
            } else {
                videoClipKeyframes.remove(clipId)
            }
            if (rightKeyframes.isNotEmpty()) {
                videoClipKeyframes[rightClipId] = rightKeyframes
            } else {
                videoClipKeyframes.remove(rightClipId)
            }
            videoClipReverseOverrides[clipId]?.let { videoClipReverseOverrides[rightClipId] = it }
            videoClipCurveProfiles[clipId]?.let { videoClipCurveProfiles[rightClipId] = it }
            videoClipFreezeOverrides[clipId]?.let { videoClipFreezeOverrides[rightClipId] = it }
            videoClipGainOverrides[clipId]?.let { videoClipGainOverrides[rightClipId] = it }
            clipPreviewTransforms[clipId]?.let { clipPreviewTransforms[rightClipId] = it.copy() }
            duckingEnabledForKey[clipId.toString()]?.let {
                duckingEnabledForKey[rightClipId.toString()] = it
            }
        }
        currentTimeMs = targetTimeMs  // set before sync so refreshMainTimelineTracks uses correct time
        lastLayoutFetchMs = 0L  // force fresh layout fetch after split

        // Immediately update cache from split result so next split uses correct positions
        val origStart = nativeClipStartMs[clipId] ?: 0L
        val origDur = nativeClipDurationMs[clipId] ?: 0L
        val leftDur = targetTimeMs - origStart
        val rightDur = origDur - leftDur
        nativeClipStartMs[clipId] = origStart
        nativeClipDurationMs[clipId] = leftDur
        if (rightClipId != null) {
            nativeClipStartMs[rightClipId] = targetTimeMs
            nativeClipDurationMs[rightClipId] = rightDur
            nativeClipTrackType[rightClipId] = nativeClipTrackType[clipId] ?: TrackType.VIDEO
        }

        syncTimelineShellFromNative(selectedClipId = rightClipId ?: clipId)
        recordUndoDomain(UndoDomain.TIMELINE)
        runOnUiThread {
            playbackController?.scrubTo(targetTimeMs, syncTimelineUi = false)
            stabilizeAfterSplit(targetTimeMs, selectionKeyForNativeClipId(rightClipId ?: clipId))
            safeToast("$clipLabel split at ${targetTimeMs}ms", Toast.LENGTH_SHORT)
        }
        recordTelemetryEvent(
            "edit",
            "split_success",
            JSONObject()
                .put("trackType", clipTrackType.name)
                .put("playheadMs", targetTimeMs)
                .put("targetClipId", clipId)
                .put("leftClipId", result.data.optInt("leftClipId", -1))
                .put("rightClipId", result.data.optInt("rightClipId", -1)),
        )
        captureDebugSnapshot("split_native_success")
        Log.d(
            TAG,
            "$clipLabel split complete: original=$clipId left=${result.data.optInt("leftClipId", -1)} right=${result.data.optInt("rightClipId", -1)} at=${targetTimeMs}ms",
        )
        return true
    }

    private fun performAudioSplitAction() {
        Log.d(TAG, "Audio split requested at ${multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs}ms selected=$selectedTimelineClipKey")
        if (!splitAudioAtPlayhead()) {
            mainHandler.post {
                safeToast("No audio clip at playhead to split", Toast.LENGTH_SHORT)
            }
            Log.d(TAG, "Audio split rejected at ${multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs}ms selected=$selectedTimelineClipKey")
        }
    }

    private fun splitSelectedTextAtPlayhead(): Boolean {
        val targetTimeMs = (multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs).toInt()
        val overlay = resolveSplitTargetTextOverlay(targetTimeMs)
        if (overlay == null) {
            recordTelemetryEvent(
                "edit",
                "split_rejected",
                JSONObject()
                    .put("trackType", TrackType.TEXT.name)
                    .put("playheadMs", targetTimeMs)
                    .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
            )
            return false
        }

        val rightOverlay = overlay.copy(
            id = nextTextOverlayId++,
            startTimeMs = targetTimeMs,
            endTimeMs = overlay.endTimeMs,
            layerIndex = overlay.layerIndex + 1,
        )
        overlay.endTimeMs = targetTimeMs
        OverlayStore.put(overlay)
        OverlayStore.put(rightOverlay)
        previewView?.updateTextOverlay(
            overlay.id,
            overlay.x,
            overlay.y,
            overlay.scale,
            overlay.rotation,
            overlay.color,
            overlay.fontSize,
            overlay.startTimeMs,
            overlay.endTimeMs,
        )
        applyTextOverlayPose(overlay)
        addTextOverlayToPreview(rightOverlay)
        selectedTimelineClipKey = "text-${rightOverlay.id}"
        currentTimeMs = targetTimeMs.toLong()
        refreshMainTimelineTracks()
        stabilizeAfterSplit(targetTimeMs.toLong(), selectedTimelineClipKey)
        mainHandler.post {
            safeToast("Text split", Toast.LENGTH_SHORT)
        }
        recordTelemetryEvent(
            "edit",
            "split_success",
            JSONObject()
                .put("trackType", TrackType.TEXT.name)
                .put("playheadMs", targetTimeMs)
                .put("targetClipId", overlay.id)
                .put("leftClipId", overlay.id)
                .put("rightClipId", rightOverlay.id),
        )
        captureDebugSnapshot("split_text_success")
        return true
    }

    private fun splitSelectedStickerAtPlayhead(): Boolean {
        val targetTimeMs = (multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs).toInt()
        val clip = resolveSplitTargetStickerClip(targetTimeMs)
        if (clip == null) {
            recordTelemetryEvent(
                "edit",
                "split_rejected",
                JSONObject()
                    .put("trackType", TrackType.OVERLAY.name)
                    .put("playheadMs", targetTimeMs)
                    .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
            )
            return false
        }
        val clipStart = clip.startTimeMs
        val clipEnd = clip.startTimeMs + clip.durationMs
        val leftDuration = targetTimeMs - clipStart
        val rightDuration = clipEnd - targetTimeMs
        clip.durationMs = leftDuration
        val rightClip = clip.copy(
            id = nextStickerId++,
            startTimeMs = targetTimeMs,
            durationMs = rightDuration,
            layerIndex = clip.layerIndex + 1,
        )
        StickerClipStore.add(clip)
        StickerClipStore.add(rightClip)
        applyStickerOverlayPose(clip)
        addStickerOverlayView(rightClip)
        applyStickerOverlayPose(rightClip)
        selectedTimelineClipKey = "sticker-${rightClip.id}"
        currentTimeMs = targetTimeMs.toLong()
        refreshMainTimelineTracks()
        stabilizeAfterSplit(targetTimeMs.toLong(), selectedTimelineClipKey)
        mainHandler.post {
            safeToast("Overlay split", Toast.LENGTH_SHORT)
        }
        recordTelemetryEvent(
            "edit",
            "split_success",
            JSONObject()
                .put("trackType", TrackType.OVERLAY.name)
                .put("playheadMs", targetTimeMs)
                .put("targetClipId", clip.id)
                .put("leftClipId", clip.id)
                .put("rightClipId", rightClip.id),
        )
        captureDebugSnapshot("split_overlay_success")
        return true
    }

    private fun resolveSplitTargetAudioClip(playheadMs: Long): com.video.engine.audio.AudioClip? {
        val selectedAudioId = selectedTimelineClipKey
            ?.takeIf { it.startsWith("audio-") }
            ?.removePrefix("audio-")
            ?.toIntOrNull()
        val selectedAudioClip = selectedAudioId?.let { AudioClipStore.get(it) }
        if (selectedAudioClip != null &&
            playheadMs >= selectedAudioClip.startTimeMs &&
            playheadMs < (selectedAudioClip.startTimeMs + selectedAudioClip.durationMs)
        ) {
            return selectedAudioClip
        }
        return AudioClipStore.all()
            .asSequence()
            .filter { clip ->
                playheadMs >= clip.startTimeMs && playheadMs < (clip.startTimeMs + clip.durationMs)
            }
            .maxWithOrNull(
                compareBy<com.video.engine.audio.AudioClip> { it.layerIndex }
                    .thenBy { it.startTimeMs },
            )
            ?.also { selectedTimelineClipKey = "audio-${it.id}" }
    }

    private fun resolveSplitTargetTextOverlay(playheadMs: Int): TextOverlay? {
        val selectedOverlay = selectedTextOverlayId()?.let { OverlayStore.get(it) }
        if (selectedOverlay != null && playheadMs > selectedOverlay.startTimeMs && playheadMs < selectedOverlay.endTimeMs) {
            return selectedOverlay
        }
        return OverlayStore.all()
            .asSequence()
            .filter { overlay ->
                overlay.visible && playheadMs > overlay.startTimeMs && playheadMs < overlay.endTimeMs
            }
            .maxWithOrNull(
                compareBy<TextOverlay> { it.layerIndex }
                    .thenBy { it.startTimeMs },
            )
            ?.also { selectedTimelineClipKey = "text-${it.id}" }
    }

    private fun resolveSplitTargetStickerClip(playheadMs: Int): StickerClip? {
        val selectedSticker = selectedStickerClipId()?.let { stickerId ->
            StickerClipStore.all().firstOrNull { it.id == stickerId }
        }
        if (selectedSticker != null) {
            val selectedEnd = selectedSticker.startTimeMs + selectedSticker.durationMs
            if (playheadMs > selectedSticker.startTimeMs && playheadMs < selectedEnd) {
                return selectedSticker
            }
        }
        return StickerClipStore.all()
            .asSequence()
            .filter { clip ->
                clip.visible && playheadMs >= clip.startTimeMs && playheadMs < (clip.startTimeMs + clip.durationMs)
            }
            .maxWithOrNull(
                compareBy<StickerClip> { it.layerIndex }
                    .thenBy { it.startTimeMs },
            )
            ?.also { selectedTimelineClipKey = "sticker-${it.id}" }
    }

    private fun performSelectedClipSplitAction() {
        var playheadMs = currentPlayheadMs()

        // Sync selected clip from canvas view if not set
        if (selectedTimelineClipKey == null) {
            val canvasSelected = findViewById<com.video.engine.pro.timeline.TimelineCanvasView?>(R.id.timelineCanvasView)?.getSelectedClipId()
            if (canvasSelected != null) selectedTimelineClipKey = canvasSelected
        }

        recordTelemetryEvent("edit", "split_requested", JSONObject()
            .put("playheadMs", playheadMs)
            .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL)
            .put("selectedClipKind", selectedClipKind().name))

        // Split only the track type that is currently selected/active
        // If nothing selected, try video first then others
        val handled = when (selectedClipKind()) {
            ClipKind.VIDEO -> splitSelectedVideoAtPlayhead()
            ClipKind.OVERLAY -> splitSelectedVideoAtPlayhead()
            ClipKind.AUDIO -> splitAudioAtPlayhead()
            ClipKind.TEXT -> splitSelectedTextAtPlayhead()
            ClipKind.STICKER -> splitSelectedStickerAtPlayhead()
            ClipKind.NONE -> when {
                resolveSplitTargetVideoClipId(playheadMs) != null -> splitSelectedVideoAtPlayhead()
                resolveSplitTargetAudioClip(playheadMs) != null -> splitAudioAtPlayhead()
                resolveSplitTargetTextOverlay(playheadMs.toInt()) != null -> splitSelectedTextAtPlayhead()
                resolveSplitTargetStickerClip(playheadMs.toInt()) != null -> splitSelectedStickerAtPlayhead()
                else -> false
            }
        }

        if (!handled) {
            recordTelemetryEvent("edit", "split_no_target", JSONObject()
                .put("playheadMs", playheadMs)
                .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL))
            mainHandler.post {
                safeToast("No clip at playhead to split", Toast.LENGTH_SHORT)
            }
        }
    }

    private fun stabilizeAfterSplit(timeMs: Long, selectedClipKey: String?) {
        currentTimeMs = timeMs.coerceAtLeast(0L)
        timelineManager?.updateDisplayedTime(currentTimeMs)
        timelineManager?.selectClip(parseTimelineManagedClipId(selectedClipKey))
        selectedTimelineClipKey = selectedClipKey
        multiTrackTimelineView?.setCurrentTimeMs(currentTimeMs)
        multiTrackTimelineView?.setSelectedClipId(selectedClipKey)
        activeCanvasTimelineView()?.setPlayheadMs(currentTimeMs)
        activeCanvasTimelineView()?.setSelectedClipId(selectedClipKey)
        updateBottomToolbarMode()
    }

    private fun stabilizeAfterDelete(timeMs: Long) {
        currentTimeMs = timeMs.coerceAtLeast(0L)
        timelineManager?.updateDisplayedTime(currentTimeMs)
        timelineManager?.selectClip(null)
        selectedTimelineClipKey = null
        multiTrackTimelineView?.setCurrentTimeMs(currentTimeMs)
        multiTrackTimelineView?.setSelectedClipId(null)
        activeCanvasTimelineView()?.setPlayheadMs(currentTimeMs)
        activeCanvasTimelineView()?.setSelectedClipId(null)
        updateBottomToolbarMode()
    }

    private fun resolvePostDeleteRevealTime(anchorTimeMs: Long): Long {
        val durationMs = previewView?.getDuration()?.coerceAtLeast(0L) ?: 0L
        return when {
            durationMs <= 0L -> 0L
            durationMs == 1L -> 0L
            else -> anchorTimeMs.coerceIn(0L, durationMs - 1L)
        }
    }

    private fun trimSelectedVideoAtPlayhead(edge: String): Boolean {
        val clipId = selectedTimelineClipKey
            ?.takeIf { key ->
                !key.startsWith("audio-") &&
                    !key.startsWith("text-") &&
                    !key.startsWith("sticker-")
            }
            ?.let(::parseNativeClipId)
            ?: timelineManager?.getSelectedClipId()
            ?: return false
        val targetTimeMs = multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "TRIM_CLIP",
                params = mapOf(
                    "clipId" to clipId,
                    "timeMs" to targetTimeMs,
                    "edge" to edge,
                ),
            )
        }.getOrNull() ?: return false
        if (!result.success) {
            Log.d(TAG, "Video trim rejected: ${result.message}")
            return false
        }
        syncTimelineShellFromNative(selectedClipId = clipId)
        recordUndoDomain(UndoDomain.TIMELINE)
        playbackController?.scrubTo(targetTimeMs)
        Toast.makeText(
            this,
            if (edge == "start") "Trim in at ${targetTimeMs}ms" else "Trim out at ${targetTimeMs}ms",
            Toast.LENGTH_SHORT,
        ).show()
        Log.d(TAG, "Video trim complete: clip=$clipId edge=$edge at=${targetTimeMs}ms")
        return true
    }

    private fun performSelectedClipTrimAction(edge: String) {
        val key = selectedTimelineClipKey
        when {
            key?.startsWith("audio-") == true -> {
                if (!trimSelectedAudioAtPlayhead(edge)) {
                    safeToast("No selected clip at playhead to trim", Toast.LENGTH_SHORT)
                }
            }
            trimSelectedVideoAtPlayhead(edge) -> Unit
            else -> safeToast("No selected clip at playhead to trim", Toast.LENGTH_SHORT)
        }
    }

    private fun trimSelectedAudioAtPlayhead(edge: String): Boolean {
        val audioId = selectedAudioClipId() ?: return false
        val targetTimeMs = multiTrackTimelineView?.currentTimeMs() ?: currentTimeMs
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "TRIM_CLIP",
                params = mapOf(
                    "clipId" to audioId,
                    "timeMs" to targetTimeMs,
                    "edge" to edge,
                ),
            )
        }.getOrNull() ?: return false
        if (!result.success) {
            Log.d(TAG, "Audio trim rejected: ${result.message}")
            return false
        }
        lastLayoutFetchMs = 0L
        syncTimelineShellFromNative(selectedClipId = audioId)
        recordUndoDomain(UndoDomain.TIMELINE)
        playbackController?.scrubTo(targetTimeMs)
        Toast.makeText(
            this,
            if (edge == "start") "Audio trim in at ${targetTimeMs}ms" else "Audio trim out at ${targetTimeMs}ms",
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun showSelectedAudioTrimSheet() {
        selectedAudioClipId() ?: run {
            safeToast("Select audio clip first", Toast.LENGTH_SHORT)
            return
        }
        ModernSheet.show(this, "Audio Trim") {
            chips("At Playhead", listOf("Trim In Here", "Trim Out Here"), -1) { index, _ ->
                val edge = if (index == 0) "start" else "end"
                if (!trimSelectedAudioAtPlayhead(edge)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Move playhead inside selected audio clip",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun showSelectedClipTrimSheet() {
        when (selectedClipKind()) {
            ClipKind.AUDIO -> showSelectedAudioTrimSheet()
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: run {
                    Toast.makeText(this@MainActivity, "Select video clip first", Toast.LENGTH_SHORT).show()
                    return
                }
                val sourcePath = nativeClipSourcePath[clipId] ?: run {
                    Toast.makeText(this@MainActivity, "Source clip unavailable", Toast.LENGTH_SHORT).show()
                    return
                }
                val timing = selectedVideoTiming(clipId)
                val inMs = nativeClipSourceInMs[clipId] ?: 0L
                val outMs = nativeClipSourceOutMs[clipId] ?: (timing?.second ?: 0L)
                val totalMs = outMs.coerceAtLeast(inMs + 100L)
                TrimPanel(
                    activity = this,
                    clipId = clipId,
                    sourcePath = sourcePath,
                    sourceInMs = inMs,
                    sourceOutMs = outMs,
                    sourceDurationMs = totalMs,
                    onApply = { newIn, newOut ->
                        nativeClipSourceInMs[clipId] = newIn
                        nativeClipSourceOutMs[clipId] = newOut
                        runCatching {
                            NativeBridge.executeCommand(
                                "TRIM_CLIP",
                                mapOf(
                                    "clipId" to clipId,
                                    "sourceInMs" to newIn,
                                    "sourceOutMs" to newOut,
                                ),
                            )
                        }
                        lastLayoutFetchMs = 0L
                        refreshMainTimelineTracks()
                        safeToast("Trimmed", Toast.LENGTH_SHORT)
                    },
                ).show()
            }
            else -> Toast.makeText(this@MainActivity, "Trim for selected clip not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedAudioClip(): Boolean {
        val anchorTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        val audioId = selectedTimelineClipKey
            ?.takeIf { it.startsWith("audio-") }
            ?.removePrefix("audio-")
            ?.toIntOrNull()
            ?: return false
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "DELETE_CLIP",
                params = mapOf("clipId" to audioId),
            )
        }.getOrNull() ?: return false
        if (!result.success) {
            return false
        }
        AudioClipStore.remove(audioId)
        audioClipGainOverrides.remove(audioId)
        duckingEnabledForKey.remove("audio-$audioId")
        selectedTimelineClipKey = null
        lastLayoutFetchMs = 0L
        syncTimelineShellFromNative()
        val revealTimeMs = resolvePostDeleteRevealTime(anchorTimeMs)
        playbackController?.scrubTo(revealTimeMs, syncTimelineUi = false)
        stabilizeAfterDelete(revealTimeMs)
        recordUndoDomain(UndoDomain.TIMELINE)
        safeToast("Audio deleted", Toast.LENGTH_SHORT)
        Log.d(TAG, "Audio deleted: id=$audioId")
        return true
    }

    private fun deleteSelectedVideoClip(): Boolean {
        val anchorTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        val clipId = selectedTimelineClipKey
            ?.takeIf { key ->
                !key.startsWith("audio-") &&
                    !key.startsWith("text-") &&
                    !key.startsWith("sticker-")
            }
            ?.let(::parseNativeClipId)
            ?: timelineManager?.getSelectedClipId()
            ?: return false
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "DELETE_CLIP",
                params = mapOf("clipId" to clipId),
            )
        }.getOrNull() ?: return false
        if (!result.success) {
            return false
        }
        videoClipReverseOverrides.remove(clipId)
        videoClipFreezeOverrides.remove(clipId)
        videoClipCurveProfiles.remove(clipId)
        videoClipKeyframes.remove(clipId)
        duckingEnabledForKey.remove(clipId.toString())
        selectedTimelineClipKey = null
        lastLayoutFetchMs = 0L
        syncTimelineShellFromNative()
        val timelineEmpty = result.data.optBoolean("timelineEmpty", false)
        if (timelineEmpty) {
            // Timeline empty — stop playback and clear preview
            playbackController?.nativePause()
            previewView?.let { pv ->
                Thread { NativeBridge.seekToTime(pv, 0L) }.start()
            }
            stabilizeAfterDelete(0L)
        } else {
            val revealTimeMs = resolvePostDeleteRevealTime(anchorTimeMs)
            playbackController?.scrubTo(revealTimeMs, syncTimelineUi = false)
            stabilizeAfterDelete(revealTimeMs)
        }
        recordUndoDomain(UndoDomain.TIMELINE)
        safeToast("Video deleted", Toast.LENGTH_SHORT)
        Log.d(TAG, "Video deleted: id=$clipId")
        return true
    }

    private fun performSelectedClipDeleteAction() {
        val deleted = when (selectedClipKind()) {
            ClipKind.AUDIO -> deleteSelectedAudioClip()
            ClipKind.TEXT -> deleteSelectedTextClip()
            ClipKind.STICKER -> deleteSelectedStickerClip()
            ClipKind.VIDEO -> deleteSelectedVideoClip()
            ClipKind.OVERLAY -> deleteSelectedVideoClip()
            ClipKind.NONE -> false
        }
        if (!deleted) {
            safeToast("No selected clip to delete", Toast.LENGTH_SHORT)
        }
    }

    private fun deleteSelectedTextClip(): Boolean {
        val anchorTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        val overlayId = selectedTextOverlayId() ?: return false
        deleteTextOverlayById(overlayId, anchorTimeMs = anchorTimeMs)
        return true
    }

    private fun deleteSelectedStickerClip(): Boolean {
        val anchorTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        val stickerId = selectedStickerClipId() ?: return false
        StickerClipStore.remove(stickerId)
        stickerClipKeyframes.remove(stickerId)
        duckingEnabledForKey.remove("sticker-$stickerId")
        removeStickerOverlayView(stickerId)
        selectedTimelineClipKey = null
        refreshMainTimelineTracks()
        stabilizeAfterDelete(anchorTimeMs)
        safeToast("Overlay deleted", Toast.LENGTH_SHORT)
        return true
    }

    private fun clearSelectedTimelineItem() {
        if (selectedTimelineClipKey == null) {
            return
        }
        selectedTimelineClipKey = null
        timelineManager?.selectClip(null)
        multiTrackTimelineView?.setSelectedClipId(null)
        activeCanvasTimelineView()?.setSelectedClipId(null)
        updateBottomToolbarMode()
    }

    private fun bindClipToolbarAction(buttonId: Int, action: () -> Unit) {
        findViewById<View>(buttonId)?.setOnClickListener {
            val trackType = selectedTrackType()
            if (trackType != null && !ensureTrackEditable(trackType)) {
                return@setOnClickListener
            }
            action()
        }
    }

    private fun parseNativeClipId(clipKey: String?): Int? {
        if (clipKey.isNullOrBlank()) return null
        return when {
            clipKey.startsWith("overlay-") -> clipKey.removePrefix("overlay-").toIntOrNull()
            clipKey.startsWith("layer-") -> clipKey.removePrefix("layer-").toIntOrNull()
            else -> clipKey.toIntOrNull()
        }
    }

    private fun parseTimelineManagedClipId(clipKey: String?): Int? {
        if (clipKey.isNullOrBlank()) return null
        return when {
            clipKey.startsWith("audio-") -> clipKey.removePrefix("audio-").toIntOrNull()
            clipKey.startsWith("overlay-") -> clipKey.removePrefix("overlay-").toIntOrNull()
            clipKey.startsWith("layer-") -> clipKey.removePrefix("layer-").toIntOrNull()
            else -> clipKey.toIntOrNull()
        }
    }

    private fun selectionKeyForNativeClipId(clipId: Int): String {
        return when (nativeClipTrackType[clipId]) {
            TrackType.AUDIO -> "audio-$clipId"
            TrackType.OVERLAY -> "overlay-$clipId"
            TrackType.LAYER -> "layer-$clipId"
            else -> clipId.toString()
        }
    }

    private fun normalizeTimelineSelectionKey(clipKey: String?): String? {
        val clipId = parseTimelineManagedClipId(clipKey) ?: return clipKey
        return selectionKeyForNativeClipId(clipId)
    }

    private fun isOverlayNativeClip(clipId: Int): Boolean {
        return nativeClipTrackType[clipId] == TrackType.OVERLAY
    }

    private fun isLayerNativeClip(clipId: Int): Boolean {
        return nativeClipTrackType[clipId] == TrackType.LAYER
    }

    private fun selectedClipKind(): ClipKind {
        val key = selectedTimelineClipKey ?: return ClipKind.NONE
        val nativeClipId = key.toIntOrNull()
        return when {
            key.startsWith("audio-") -> ClipKind.AUDIO
            key.startsWith("text-") -> ClipKind.TEXT
            key.startsWith("sticker-") -> ClipKind.STICKER
            key.startsWith("overlay-") || key.startsWith("layer-") -> ClipKind.OVERLAY
            nativeClipId?.let { nativeClipTrackType[it] == TrackType.AUDIO } == true -> ClipKind.AUDIO
            nativeClipId?.let { isOverlayNativeClip(it) || isLayerNativeClip(it) } == true -> ClipKind.OVERLAY
            nativeClipId != null -> ClipKind.VIDEO
            else -> ClipKind.NONE
        }
    }

    private fun selectedTrackType(): TrackType? {
        return when (selectedClipKind()) {
            ClipKind.VIDEO -> TrackType.VIDEO
            ClipKind.OVERLAY -> parseNativeClipId(selectedTimelineClipKey)
                ?.let { nativeClipTrackType[it] }
                ?: TrackType.OVERLAY
            ClipKind.AUDIO -> TrackType.AUDIO
            ClipKind.TEXT, ClipKind.STICKER -> TrackType.TEXT
            ClipKind.NONE -> null
        }
    }

    private fun selectedVideoClipId(): Int? {
        return parseNativeClipId(selectedTimelineClipKey)
    }

    private fun selectedAudioClipId(): Int? {
        return selectedTimelineClipKey
            ?.takeIf { it.startsWith("audio-") }
            ?.removePrefix("audio-")
            ?.toIntOrNull()
    }

    private fun selectedTextOverlayId(): Int? {
        return selectedTimelineClipKey
            ?.takeIf { it.startsWith("text-") }
            ?.removePrefix("text-")
            ?.toIntOrNull()
    }

    private fun selectedStickerClipId(): Int? {
        return selectedTimelineClipKey
            ?.takeIf { it.startsWith("sticker-") }
            ?.removePrefix("sticker-")
            ?.toIntOrNull()
    }

    private fun selectedVideoTiming(clipId: Int): Pair<Long, Long>? {
        val override = videoClipTimingOverrides[clipId]
        if (override != null) {
            return override.startTimeMs to override.durationMs.coerceAtLeast(1L)
        }

        val nativeStart = nativeClipStartMs[clipId]
        val nativeDuration = nativeClipDurationMs[clipId]
        if (nativeStart != null && nativeDuration != null) {
            return nativeStart to nativeDuration.coerceAtLeast(1L)
        }

        val managerClip = timelineManager?.getClips().orEmpty().firstOrNull { it.id == clipId } ?: return null
        return (nativeStart ?: 0L) to (nativeDuration ?: managerClip.durationMs).coerceAtLeast(1L)
    }

    private fun selectedNativeClipLabel(): String {
        return when (selectedTrackType()) {
            TrackType.LAYER -> "Layer"
            TrackType.OVERLAY -> "Overlay"
            else -> "Video"
        }
    }

    private fun deleteTextOverlayById(
        overlayId: Int,
        removeFromPreview: Boolean = true,
        anchorTimeMs: Long = currentPlayheadMs().coerceAtLeast(0L),
    ) {
        editorState?.recordLayerSnapshot()
        recordUndoDomain(UndoDomain.EDITOR)
        if (removeFromPreview) {
            previewView?.removeTextOverlay(overlayId)
        }
        removeOverlayView(overlayId)
        OverlayStore.remove(overlayId)
        if (selectedTimelineClipKey == "text-$overlayId") {
            selectedTimelineClipKey = null
        }
        refreshMainTimelineTracks()
        stabilizeAfterDelete(anchorTimeMs)
        safeToast("Text deleted", Toast.LENGTH_SHORT)
    }

    private fun showSelectedTextStudio() {
        val overlayId = selectedTextOverlayId() ?: run {
            safeToast("Select text clip first", Toast.LENGTH_SHORT)
            return
        }
        val overlay = OverlayStore.get(overlayId) ?: return
        val preview = previewView ?: run {
            safeToast("Preview unavailable", Toast.LENGTH_SHORT)
            return
        }
        if (isPlaying) {
            isPlaying = false
            playbackController?.pauseRendering()
        }
        editorState?.recordLayerSnapshot()
        recordUndoDomain(UndoDomain.EDITOR)
        TextEditorPanel(
            activity = this,
            previewView = preview,
            overlay = overlay,
            onDone = { updated ->
                overlayViews[updated.id]?.setText(updated.text)
                NativeBridge.setTextOverlayBitmap(preview, updated)
                applyTextOverlayState(updated)
                applyTextOverlayPose(updated)
                selectedTimelineClipKey = "text-${updated.id}"
                refreshMainTimelineTracks()
                safeToast("Text updated", Toast.LENGTH_SHORT)
            },
            onDuplicate = { source ->
                val duration = (source.endTimeMs - source.startTimeMs).coerceAtLeast(1)
                val duplicate = source.copy(
                    id = nextTextOverlayId++,
                    startTimeMs = source.endTimeMs + 60,
                    endTimeMs = source.endTimeMs + 60 + duration,
                    layerIndex = source.layerIndex + 1,
                )
                addTextOverlay(duplicate, "Text duplicated")
            },
            onDelete = { source ->
                deleteTextOverlayById(
                    overlayId = source.id,
                    removeFromPreview = false,
                )
            },
        ).show()
    }

    private fun isStillImageClip(clipId: Int): Boolean {
        val extension = nativeClipSourcePath[clipId]
            ?.substringBefore('?')
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            .orEmpty()
        return extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff")
    }

    private fun updateNativeClipDuration(clipId: Int, newDurationMs: Long, clipLabel: String): Boolean {
        val currentTiming = selectedVideoTiming(clipId) ?: return false
        val sourceInMs = nativeClipSourceInMs[clipId] ?: 0L
        val sourceOutMs = (nativeClipSourceOutMs[clipId] ?: (sourceInMs + 1L)).coerceAtLeast(sourceInMs + 1L)
        val safeDurationMs = newDurationMs.coerceAtLeast(150L)
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "UPDATE_CLIP_TIMING",
                params = mapOf(
                    "clipId" to clipId,
                    "newStartTimeMs" to currentTiming.first,
                    "newDurationMs" to safeDurationMs,
                    "newSourceInMs" to sourceInMs,
                    "newSourceOutMs" to sourceOutMs,
                    "originalStartTimeMs" to currentTiming.first,
                    "originalDurationMs" to currentTiming.second,
                    "originalSourceInMs" to sourceInMs,
                    "originalSourceOutMs" to sourceOutMs,
                    "previewOnly" to false,
                    "applyMagnetic" to (nativeClipTrackType[clipId] == TrackType.VIDEO),
                ),
            )
        }.getOrNull() ?: return false
        if (!result.success) {
            Log.w(TAG, "$clipLabel duration update failed: clip=$clipId message=${result.message}")
            return false
        }
        lastLayoutFetchMs = 0L
        nativeClipDurationMs[clipId] = safeDurationMs
        syncTimelineShellFromNative(selectedClipId = clipId)
        refreshPreviewAtPlayhead()
        recordUndoDomain(UndoDomain.TIMELINE)
        safeToast("$clipLabel duration ${safeDurationMs}ms", Toast.LENGTH_SHORT)
        return true
    }

    private fun showStillImageDurationSheet(clipId: Int, clipLabel: String) {
        ModernSheet.show(this, "$clipLabel Duration") {
            chips("Presets", listOf("2s", "3s", "5s", "8s", "10s")) { _, opt ->
                val durationMs = ((opt.removeSuffix("s").toFloatOrNull() ?: 5f) * 1000f).roundToLong()
                if (!updateNativeClipDuration(clipId, durationMs, clipLabel)) {
                    Toast.makeText(this@MainActivity, "$clipLabel duration update failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showNativeClipLayerSheet(clipId: Int, clipLabel: String) {
        val trackType = nativeClipTrackType[clipId] ?: TrackType.VIDEO
        val currentLane = nativeClipLane[clipId] ?: 0
        val currentZ = nativeClipZOrder[clipId] ?: 0
        val peerIds = nativeClipTrackType.filterValues { it == trackType }.keys
        val minZ = peerIds.mapNotNull { nativeClipZOrder[it] }.minOrNull() ?: currentZ
        val maxZ = peerIds.mapNotNull { nativeClipZOrder[it] }.maxOrNull() ?: currentZ
        val options = arrayOf("Bring Forward", "Send Backward", "Bring To Front", "Send To Back")
        AlertDialog.Builder(this)
            .setTitle("$clipLabel Layer")
            .setItems(options) { _, which ->
                val requestedZ = when (which) {
                    0 -> currentZ + 1
                    1 -> currentZ - 1
                    2 -> maxZ + 1
                    3 -> minZ - 1
                    else -> currentZ
                }
                val nextZ = normalizeTrackZOrder(trackType, requestedZ)
                val result = runCatching {
                    NativeBridge.executeCommand(
                        action = "SET_CLIP_TRACK",
                        params = mapOf(
                            "clipId" to clipId,
                            "trackType" to trackType.nativeRoleName(),
                            "trackLane" to currentLane,
                            "zOrder" to nextZ,
                        ),
                    )
                }.getOrNull()
                if (result?.success == true) {
                    nativeClipZOrder[clipId] = nextZ
                    lastLayoutFetchMs = 0L
                    syncTimelineShellFromNative(selectedClipId = clipId)
                    refreshPreviewAtPlayhead()
                    recordUndoDomain(UndoDomain.TIMELINE)
                    safeToast("$clipLabel layer updated", Toast.LENGTH_SHORT)
                } else {
                    safeToast("$clipLabel layer update failed", Toast.LENGTH_SHORT)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveSplitTargetVideoClipId(playheadMs: Long): Int? {
        // Build timing map from canvas view tracks as fallback (always fresh)
        val canvasTimings = mutableMapOf<Int, Pair<Long, Long>>()
        findViewById<com.video.engine.pro.timeline.TimelineCanvasView?>(R.id.timelineCanvasView)
            ?.getTracks()
            ?.forEach { track ->
                if (track.type == TrackType.VIDEO) {
                    track.clips.forEach { clip ->
                        clip.id.toIntOrNull()?.let { id ->
                            canvasTimings[id] = clip.startTimeMs to clip.durationMs
                        }
                    }
                }
            }

        fun clipContainsPlayhead(clipId: Int): Boolean {
            val timing = selectedVideoTiming(clipId) ?: canvasTimings[clipId] ?: return false
            val startMs = timing.first
            val endMs = startMs + timing.second
            return playheadMs > (startMs + 33L) && playheadMs < (endMs - 33L)
        }

        // First try selected clip only if it strictly contains the playhead
        val selectedId = selectedVideoClipId()
        if (selectedId != null && clipContainsPlayhead(selectedId)) return selectedId

        // Then try any VIDEO clip under playhead (ignore selected)
        val candidateClipIds = linkedSetOf<Int>().apply {
            addAll(nativeClipStartMs.keys)
            addAll(nativeClipDurationMs.keys)
            addAll(timelineManager?.getClips().orEmpty().map { it.id })
            timelineManager?.getSelectedClipId()?.let { add(it) }
        }.filter {
            nativeClipTrackType[it] != TrackType.OVERLAY && nativeClipTrackType[it] != TrackType.LAYER
        }

        val matchingIds = candidateClipIds
            .filter(::clipContainsPlayhead)
        if (matchingIds.isEmpty()) {
            return null
        }
        val resolved = matchingIds.maxWithOrNull(
            compareBy<Int> { nativeClipZOrder[it] ?: 0 }
                .thenBy<Int> { nativeClipStartMs[it] ?: 0L }
                .thenBy { it },
        )
        if (resolved != null) {
            selectedTimelineClipKey = selectionKeyForNativeClipId(resolved)
        }
        return resolved
    }

    private fun applySelectedClipPreviewTransform() {
        val preview = previewView ?: return
        val clipId = selectedVideoClipId()
        val transform = clipId?.let { clipPreviewTransforms[it] } ?: ClipPreviewTransform()
        val safeZoom = transform.zoom.coerceIn(0.75f, 4.0f)
        val maxPanX = ((if (preview.width > 0) preview.width else preview.resources.displayMetrics.widthPixels) * 0.48f)
            .coerceAtLeast(32f)
        val maxPanY = ((if (preview.height > 0) preview.height else preview.resources.displayMetrics.heightPixels) * 0.48f)
            .coerceAtLeast(32f)
        val targetScaleX = (if (transform.mirrorX) -1f else 1f) * safeZoom
        val targetScaleY = safeZoom
        val targetTranslationX = transform.panXPx.coerceIn(-maxPanX, maxPanX)
        val targetTranslationY = transform.panYPx.coerceIn(-maxPanY, maxPanY)
        val targetRotation = transform.rotationDeg.coerceIn(-180f, 180f)
        preview.pivotX = preview.width * 0.5f
        preview.pivotY = preview.height * 0.5f
        if (preview.scaleX != targetScaleX) preview.scaleX = targetScaleX
        if (preview.scaleY != targetScaleY) preview.scaleY = targetScaleY
        if (preview.translationX != targetTranslationX) preview.translationX = targetTranslationX
        if (preview.translationY != targetTranslationY) preview.translationY = targetTranslationY
        if (preview.rotation != targetRotation) preview.rotation = targetRotation
    }

    private fun updateSelectedVideoPreviewTransform(
        mutator: (ClipPreviewTransform) -> ClipPreviewTransform,
    ): Boolean {
        val clipId = selectedVideoClipId() ?: return false
        val updated = mutator(clipPreviewTransforms[clipId] ?: ClipPreviewTransform())
        clipPreviewTransforms[clipId] = updated
        applySelectedClipPreviewTransform()
        return true
    }

    private fun setupPreviewTransformGestures() {
        previewTransformTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        previewTransformScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    val clipId = selectedVideoClipId() ?: return false
                    previewTransformGestureClipId = clipId
                    previewTransformBase = clipPreviewTransforms[clipId] ?: ClipPreviewTransform()
                    previewTransformScaleAccumulator = 1.0f
                    previewTransformPinching = true
                    previewView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val clipId = previewTransformGestureClipId ?: selectedVideoClipId() ?: return false
                    val preview = previewView ?: return false
                    val base = previewTransformBase
                    previewTransformScaleAccumulator =
                        (previewTransformScaleAccumulator * detector.scaleFactor).coerceIn(0.1f, 8.0f)
                    val nextZoom = (base.zoom * previewTransformScaleAccumulator).coerceIn(0.75f, 4.0f)
                    val centerX = preview.width * 0.5f
                    val centerY = preview.height * 0.5f
                    val focusShift =
                        if (base.zoom > 0.001f) 1f - (nextZoom / base.zoom) else 0f
                    clipPreviewTransforms[clipId] = base.copy(
                        zoom = nextZoom,
                        panXPx = base.panXPx + ((detector.focusX - centerX) * focusShift),
                        panYPx = base.panYPx + ((detector.focusY - centerY) * focusShift),
                    )
                    applySelectedClipPreviewTransform()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    previewTransformPinching = false
                    previewTransformScaleAccumulator = 1.0f
                    previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            },
        )
        overlayContainer?.setOnTouchListener { _, event ->
            handlePreviewTransformTouch(event)
        }
    }

    private fun handlePreviewTransformTouch(event: MotionEvent): Boolean {
        if (selectedClipKind() != ClipKind.VIDEO && selectedClipKind() != ClipKind.OVERLAY) {
            previewTransformDragging = false
            previewTransformPinching = false
            previewTransformGestureClipId = null
            previewTransformScaleAccumulator = 1.0f
            previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
            return false
        }

        previewTransformScaleDetector?.onTouchEvent(event)

        if (event.pointerCount > 1 || previewTransformPinching) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP
            ) {
                previewTransformPinching = false
                previewTransformScaleAccumulator = 1.0f
                previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewTransformGestureClipId = selectedVideoClipId()
                previewTransformLastX = event.x
                previewTransformLastY = event.y
                previewTransformDragging = false
                previewView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - previewTransformLastX
                val dy = event.y - previewTransformLastY
                if (!previewTransformDragging &&
                    hypot(dx.toDouble(), dy.toDouble()) < previewTransformTouchSlop.toDouble()
                ) {
                    return true
                }
                previewTransformDragging = true
                previewTransformLastX = event.x
                previewTransformLastY = event.y
                updateSelectedVideoPreviewTransform { current ->
                    current.copy(
                        panXPx = current.panXPx + dx,
                        panYPx = current.panYPx + dy,
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                previewTransformDragging = false
                previewTransformPinching = false
                previewTransformGestureClipId = null
                previewTransformScaleAccumulator = 1.0f
                previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                return true
            }
        }

        return false
    }

    private fun selectedClipTypeLabel(key: String): String {
        return when {
            key.startsWith("audio-") -> "Audio Clip"
            key.startsWith("text-") -> "Text Clip"
            key.startsWith("sticker-") -> "Overlay Clip"
            key.startsWith("overlay-") -> "Overlay Clip"
            key.startsWith("layer-") -> "Layer Clip"
            else -> "Video Clip"
        }
    }

    private fun selectedClipContextName(kind: ClipKind): String? {
        return when (kind) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return null
                nativeClipSourcePath[clipId]
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: "${selectedNativeClipLabel()} $clipId"
            }
            ClipKind.AUDIO -> {
                val clipId = selectedAudioClipId() ?: return null
                AudioClipStore.get(clipId)?.displayName?.takeIf { it.isNotBlank() } ?: "Audio $clipId"
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return null
                OverlayStore.get(overlayId)?.text?.trim()?.takeIf { it.isNotEmpty() } ?: "Text $overlayId"
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return null
                StickerClipStore.all().find { it.id == stickerId }?.let { clip ->
                    if (clip.type == "image") "Image Overlay" else "Sticker ${clip.stickerId}"
                } ?: "Overlay $stickerId"
            }
            ClipKind.NONE -> null
        }
    }

    private fun updateClipToolbarHeader(kind: ClipKind) {
        val header = clipToolbarContextLabel ?: findViewById<TextView?>(R.id.audioEditLabel) ?: return
        clipToolbarContextLabel = header
        if (kind == ClipKind.NONE) {
            header.text = ""
            header.visibility = View.GONE
            return
        }
        val selectedTrackType = selectedTrackType()
        val label = when (kind) {
            ClipKind.VIDEO -> "VIDEO CLIP"
            ClipKind.OVERLAY -> if (selectedTrackType == TrackType.LAYER) "MEDIA LAYER" else "OVERLAY LAYER"
            ClipKind.AUDIO -> "AUDIO CLIP"
            ClipKind.TEXT -> "TEXT LAYER"
            ClipKind.STICKER -> "GRAPHIC LAYER"
            ClipKind.NONE -> ""
        }
        val isLocked = selectedTrackType?.let(::isTrackLocked) == true
        header.text = if (isLocked) "$label • LOCKED" else label
        header.setTextColor(if (isLocked) resources.getColor(R.color.accent_cyan) else resources.getColor(R.color.accent_blue))
        header.alpha = if (isLocked) 0.88f else 1f
        header.visibility = View.VISIBLE
    }

    private fun showClipToolPending(toolName: String) {
        safeToast("$toolName next", Toast.LENGTH_SHORT)
    }

    private fun formatAutomationTime(timeMs: Long): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun setAutomationPlayhead(timeMs: Long) {
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        currentTimeMs = targetTimeMs
        playbackController?.scrubTo(targetTimeMs)
        timelineManager?.updateDisplayedTime(targetTimeMs)
        multiTrackTimelineView?.setCurrentTimeMs(targetTimeMs)
        activeCanvasTimelineView()?.setPlayheadMs(targetTimeMs)
        timelineCurrentTimeText?.text = formatAutomationTime(targetTimeMs)
        previewAudioPlayer?.seekTo(targetTimeMs, continuePlaying = false)
        updateBottomToolbarMode()
        Log.i(TAG, "[Automation] set_playhead_ms=$targetTimeMs")
    }

    private fun currentPlayheadMs(): Long {
        val canvasMs = activeCanvasTimelineView()?.getPlayheadMs()
        if (canvasMs != null && canvasMs >= 0L) return canvasMs
        return activeMultiTrackTimelineView()?.currentTimeMs() ?: currentTimeMs
    }

    private fun applyTrackVisibilityChange(trackType: TrackType, isVisible: Boolean) {
        trackVisibilityOverrides[trackType] = isVisible
        when (trackType) {
            TrackType.VIDEO, TrackType.OVERLAY, TrackType.LAYER -> {
                nativeClipTrackType
                    .filterValues { it == trackType }
                    .keys
                    .forEach { clipId ->
                        timelineManager?.setClipVisibility(clipId, isVisible)
                        previewView?.toggleLayerVisibility(clipId, isVisible)
                    }
            }
            TrackType.TEXT -> {
                allTextOverlays().forEach { overlay ->
                    overlay.visible = isVisible
                    applyTextOverlayState(overlay)
                }
                StickerClipStore.all().forEach { sticker ->
                    sticker.visible = isVisible
                    applyStickerLayerState(sticker)
                }
            }
            TrackType.AUDIO -> {
                AudioClipStore.all().forEach { clip ->
                    clip.visible = isVisible
                }
                NativeBridge.syncAudioClips()
            }
        }
        refreshMainTimelineTracks()
        previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = isPlaying)
    }

    private fun applyTrackLockChange(trackType: TrackType, isLocked: Boolean) {
        trackLockedOverrides[trackType] = isLocked
        refreshMainTimelineTracks()
        updateBottomToolbarMode()
        if (isLocked) {
            safeToast("${trackDisplayName(trackType)} track locked", Toast.LENGTH_SHORT)
        }
    }

    private fun applyClipToolbarProfile(kind: ClipKind) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            val visibleButtons = when (kind) {
                ClipKind.VIDEO -> clipToolbarItems.map { it.buttonId }.toSet()
                ClipKind.OVERLAY -> setOf(
                    R.id.clipDeleteButton,
                    R.id.clipSplitButton,
                    R.id.clipVolumeButton,
                    R.id.clipSpeedButton,
                    R.id.clipPanZoomButton,
                    R.id.clipFilterButton,
                    R.id.clipTrimButton,
                    R.id.clipReplaceButton,
                    R.id.clipDuplicateButton,
                    R.id.clipAddLayerButton,
                    R.id.clipBrightnessButton,
                    R.id.clipGraphicsButton,
                    R.id.clipChromaKeyButton,
                    R.id.clipRotateMirrorButton,
                    R.id.clipKeyframeButton,
                    R.id.clipReverseButton,
                    R.id.clipFreezeFrameButton,
                    R.id.clipCurveSpeedButton,
                    R.id.clipCutoutButton,
                )
                ClipKind.AUDIO -> setOf(
                    R.id.clipDeleteButton,
                    R.id.clipSplitButton,
                    R.id.clipVolumeButton,
                    R.id.clipBrightnessButton,
                    R.id.clipSpeedButton,
                    R.id.clipTrimButton,
                    R.id.clipReplaceButton,
                    R.id.clipDuplicateButton,
                    R.id.clipDuckingButton,
                )
                ClipKind.TEXT -> setOf(
                    R.id.clipDeleteButton,
                    R.id.clipSplitButton,
                    R.id.clipVolumeButton,
                    R.id.clipSpeedButton,
                    R.id.clipPanZoomButton,
                    R.id.clipGraphicsButton,
                    R.id.clipBrightnessButton,
                    R.id.clipReplaceButton,
                    R.id.clipDuplicateButton,
                    R.id.clipAddLayerButton,
                    R.id.clipRotateMirrorButton,
                    R.id.clipKeyframeButton,
                )
                ClipKind.STICKER -> setOf(
                    R.id.clipDeleteButton,
                    R.id.clipSplitButton,
                    R.id.clipVolumeButton,
                    R.id.clipSpeedButton,
                    R.id.clipPanZoomButton,
                    R.id.clipBrightnessButton,
                    R.id.clipGraphicsButton,
                    R.id.clipReplaceButton,
                    R.id.clipDuplicateButton,
                    R.id.clipAddLayerButton,
                    R.id.clipRotateMirrorButton,
                    R.id.clipKeyframeButton,
                )
                ClipKind.NONE -> emptySet()
            }

            val labelOverrides = mutableMapOf<Int, String>()
            when (kind) {
                ClipKind.AUDIO -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Gain"
                    labelOverrides[R.id.clipBrightnessLabel] = "Fade"
                    labelOverrides[R.id.clipSpeedLabel] = "Stretch"
                    labelOverrides[R.id.clipDuckingLabel] = "Ducking"
                }
                ClipKind.TEXT -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Opacity"
                    labelOverrides[R.id.clipSpeedLabel] = "Duration"
                    labelOverrides[R.id.clipPanZoomLabel] = "Position"
                    labelOverrides[R.id.clipFilterLabel] = "Palette"
                    labelOverrides[R.id.clipGraphicsLabel] = "Studio"
                    labelOverrides[R.id.clipBrightnessLabel] = "Fade"
                    labelOverrides[R.id.clipReplaceLabel] = "EditText"
                    labelOverrides[R.id.clipRotateMirrorLabel] = "Rotate"
                    labelOverrides[R.id.clipKeyframeLabel] = "Keyframe"
                }
                ClipKind.STICKER -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Opacity"
                    labelOverrides[R.id.clipSpeedLabel] = "Duration"
                    labelOverrides[R.id.clipPanZoomLabel] = "Position"
                    labelOverrides[R.id.clipGraphicsLabel] = "Change"
                    labelOverrides[R.id.clipBrightnessLabel] = "Fade"
                    labelOverrides[R.id.clipReplaceLabel] = "Replace"
                    labelOverrides[R.id.clipKeyframeLabel] = "Keyframe"
                }
                ClipKind.OVERLAY -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Opacity"
                    labelOverrides[R.id.clipBrightnessLabel] = "Adjust"
                    labelOverrides[R.id.clipChromaKeyLabel] = "ChromaKey"
                }
                else -> Unit
            }

            val featuredButtons = when (kind) {
                ClipKind.VIDEO -> setOf(
                    R.id.clipTrimButton,
                    R.id.clipTransitionButton,
                    R.id.clipPanZoomButton,
                    R.id.clipFilterButton,
                    R.id.clipBrightnessButton,
                    R.id.clipVolumeButton,
                )
                ClipKind.OVERLAY -> setOf(
                    R.id.clipTrimButton,
                    R.id.clipPanZoomButton,
                    R.id.clipGraphicsButton,
                    R.id.clipChromaKeyButton,
                )
                ClipKind.AUDIO -> setOf(
                    R.id.clipVolumeButton,
                    R.id.clipBrightnessButton,
                    R.id.clipSpeedButton,
                    R.id.clipDuckingButton,
                )
                ClipKind.TEXT -> setOf(
                    R.id.clipGraphicsButton,
                    R.id.clipPanZoomButton,
                    R.id.clipReplaceButton,
                    R.id.clipKeyframeButton,
                )
                ClipKind.STICKER -> setOf(
                    R.id.clipGraphicsButton,
                    R.id.clipPanZoomButton,
                    R.id.clipKeyframeButton,
                )
                ClipKind.NONE -> emptySet()
            }

            clipToolbarItems.forEach { item ->
                val button = findViewById<LinearLayout?>(item.buttonId)
                button?.visibility = if (visibleButtons.contains(item.buttonId)) View.VISIBLE else View.GONE
                button?.isEnabled = visibleButtons.contains(item.buttonId)
                val label = findViewById<TextView>(item.labelId)
                label?.text = labelOverrides[item.labelId] ?: item.defaultLabel
                val icon = button?.getChildAt(0) as? ImageView
                val isDestructive = item.buttonId == R.id.clipDeleteButton
                val isFeatured = featuredButtons.contains(item.buttonId)
                if (button != null) {
                    when {
                        isDestructive -> {
                            button.setBackgroundResource(R.drawable.toolbar_item_danger_background)
                            icon?.setColorFilter(resources.getColor(R.color.accent_cyan))
                            label?.setTextColor(resources.getColor(R.color.accent_cyan))
                            button.alpha = 1f
                        }
                        isFeatured -> {
                            button.setBackgroundResource(R.drawable.toolbar_item_active_background)
                            icon?.setColorFilter(resources.getColor(R.color.accent_blue))
                            label?.setTextColor(resources.getColor(R.color.accent_blue))
                            button.alpha = 1f
                        }
                        else -> {
                            button.setBackgroundResource(R.drawable.toolbar_item_background)
                            icon?.setColorFilter(resources.getColor(R.color.text_primary))
                            label?.setTextColor(resources.getColor(R.color.text_primary))
                            button.alpha = 0.94f
                        }
                    }
                }
            }

            findViewById<HorizontalScrollView>(R.id.audioEditToolbarScroll)?.post {
                findViewById<HorizontalScrollView>(R.id.audioEditToolbarScroll)?.scrollTo(0, 0)
            }
            if (kind == ClipKind.VIDEO || kind == ClipKind.OVERLAY) {
                syncEffectSlidersForClip(selectedVideoClipId())
            }
        }
    }

    private fun performSelectedClipVolumeAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    safeToast("$clipLabel image has no audio", Toast.LENGTH_SHORT)
                    return
                }
                val cur = videoClipGainOverrides[clipId] ?: 1f
                ModernSheet.show(this, "$clipLabel Volume") {
                    slider("Volume", 0f, 2f, cur, { "%.0f%%".format(it * 100) }) { v ->
                        videoClipGainOverrides[clipId] = v
                        runCatching { NativeBridge.executeCommand("SET_CLIP_VOLUME", mapOf("clipId" to clipId, "volume" to v.toDouble())) }
                        previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = isPlaying)
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val audioClip = AudioClipStore.get(audioId) ?: return
                val cur = audioClipGainOverrides[audioId] ?: 1f
                ModernSheet.show(this, "Audio Gain") {
                    slider("Gain", 0f, 2f, cur, { "%.0f%%".format(it * 100) }) { v ->
                        audioClipGainOverrides[audioId] = v
                        audioClip.gain = v
                        audioClip.muted = v <= 0.001f
                        runCatching {
                            NativeBridge.executeCommand(
                                "SET_CLIP_VOLUME",
                                mapOf("clipId" to audioId, "volume" to v.toDouble()),
                            )
                        }
                        refreshMainTimelineTracks()
                        previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = isPlaying)
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this, "Text Opacity") {
                    slider("Opacity", 0f, 1f, overlay.opacity, { "%.0f%%".format(it * 100) }) { v ->
                        overlay.opacity = v
                        previewView?.updateTextOverlayOpacity(overlay.id, v, 0, 0)
                        applyTextOverlayState(overlay); applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                ModernSheet.show(this, "Overlay Opacity") {
                    slider("Opacity", 0f, 1f, clip.opacity, { "%.0f%%".format(it * 100) }) { v ->
                        clip.opacity = v
                        applyStickerLayerState(clip); applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                    }
                }
            }
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipSpeedAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: run {
                    safeToast("Select clip first", Toast.LENGTH_SHORT)
                    return
                }
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    showStillImageDurationSheet(clipId, clipLabel)
                    return
                }
                val currentSpeed = nativeClipPlaybackSpeed[clipId] ?: 1f
                ModernSheet.show(this, "$clipLabel Speed") {
                    chips("Presets", listOf("0.25x", "0.5x", "0.75x", "1x", "1.5x", "2x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toDoubleOrNull() ?: 1.0
                        runCatching {
                            NativeBridge.executeCommand("SPEED", mapOf("clipId" to clipId, "speed" to speed))
                        }
                        nativeClipPlaybackSpeed[clipId] = speed.toFloat()
                        lastLayoutFetchMs = 0L; refreshMainTimelineTracks(); refreshPreviewAtPlayhead()
                    }
                    divider()
                    slider("Custom", 0.1f, 4f, currentSpeed, { "%.2fx".format(it) }) { speed ->
                        runCatching {
                            NativeBridge.executeCommand("SPEED", mapOf("clipId" to clipId, "speed" to speed.toDouble()))
                        }
                        nativeClipPlaybackSpeed[clipId] = speed
                        lastLayoutFetchMs = 0L; refreshMainTimelineTracks(); refreshPreviewAtPlayhead()
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                ModernSheet.show(this, "Audio Speed") {
                    chips("Presets", listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toDoubleOrNull() ?: 1.0
                        runCatching {
                            NativeBridge.executeCommand("SPEED", mapOf("clipId" to audioId, "speed" to speed))
                        }
                        lastLayoutFetchMs = 0L
                        syncTimelineShellFromNative(selectedClipId = audioId)
                        refreshPreviewAtPlayhead()
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this, "Text Speed") {
                    chips("Presets", listOf("0.5x", "1x", "2x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        val currentDurationMs = (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(150)
                        val newDurationMs = (currentDurationMs / speed).roundToInt().coerceAtLeast(150)
                        overlay.endTimeMs = overlay.startTimeMs + newDurationMs
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                        refreshMainTimelineTracks()
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                ModernSheet.show(this, "Overlay Speed") {
                    chips("Presets", listOf("0.5x", "1x", "2x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        val currentDurationMs = clip.durationMs.coerceAtLeast(150)
                        clip.durationMs = (currentDurationMs / speed).roundToInt().coerceAtLeast(150)
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                    }
                }
            }
            else -> safeToast("Select a clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipPanZoomAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val selected = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                val cur = clipPreviewTransforms[selected] ?: ClipPreviewTransform()
                val panStep = ((previewView?.width ?: 320) * 0.08f).coerceAtLeast(18f)
                ModernSheet.show(this, "$clipLabel Transform") {
                    slider("Zoom", 1f, 3f, cur.zoom, { "%.1fx".format(it) }) { v ->
                        updateSelectedVideoPreviewTransform { it.copy(zoom = v) }
                    }
                    slider("Pan X", -300f, 300f, cur.panXPx, { "${it.toInt()}px" }) { v ->
                        updateSelectedVideoPreviewTransform { it.copy(panXPx = v) }
                    }
                    slider("Pan Y", -300f, 300f, cur.panYPx, { "${it.toInt()}px" }) { v ->
                        updateSelectedVideoPreviewTransform { it.copy(panYPx = v) }
                    }
                    chips("Quick", listOf("Reset", "+10%", "+25%", "+50%")) { _, opt ->
                        updateSelectedVideoPreviewTransform { c ->
                            when (opt) {
                                "Reset" -> ClipPreviewTransform()
                                "+10%" -> c.copy(zoom = (c.zoom * 1.10f).coerceAtMost(3f))
                                "+25%" -> c.copy(zoom = (c.zoom * 1.25f).coerceAtMost(3f))
                                "+50%" -> c.copy(zoom = (c.zoom * 1.50f).coerceAtMost(3f))
                                else -> c
                            }
                        }
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this, "Text Position") {
                    slider("X", 0f, 1f, overlay.x, { "%.0f%%".format(it * 100) }) { v ->
                        overlay.x = v; applyTextOverlayPose(overlay)
                    }
                    slider("Y", 0f, 1f, overlay.y, { "%.0f%%".format(it * 100) }) { v ->
                        overlay.y = v; applyTextOverlayPose(overlay)
                    }
                    slider("Scale", 0.2f, 4f, overlay.scale, { "%.1fx".format(it) }) { v ->
                        overlay.scale = v; applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                ModernSheet.show(this, "Overlay Position") {
                    slider("X", 0f, 1f, clip.x, { "%.0f%%".format(it * 100) }) { v ->
                        clip.x = v; applyStickerOverlayPose(clip)
                    }
                    slider("Y", 0f, 1f, clip.y, { "%.0f%%".format(it * 100) }) { v ->
                        clip.y = v; applyStickerOverlayPose(clip)
                    }
                    slider("Scale", 0.2f, 4f, clip.scale, { "%.1fx".format(it) }) { v ->
                        clip.scale = v; applyStickerOverlayPose(clip)
                    }
                }
            }
            ClipKind.AUDIO -> safeToast("Position tool is not for audio clips", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipExtractAudioAction() {
        val clipId = selectedVideoClipId() ?: run {
            safeToast("Select video clip first", Toast.LENGTH_SHORT)
            return
        }
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "EXTRACT_AUDIO",
                params = mapOf("clipId" to clipId),
            )
        }.getOrNull()
        if (result?.success != true) {
            safeToast("Extract audio failed", Toast.LENGTH_SHORT)
            return
        }
        val sourcePath = result.data.optString("sourcePath")
        if (sourcePath.isBlank()) {
            safeToast("Audio source unavailable", Toast.LENGTH_SHORT)
            return
        }
        val timing = selectedVideoTiming(clipId)
        val durationMs = timing?.second ?: result.data.optLong("durationMs", 0L)
        val imported = audioImportController?.importFromPath(
            path = sourcePath,
            startTimeMs = timing?.first ?: 0L,
            durationOverrideMs = durationMs.takeIf { it > 0L },
            displayNameOverride = "Clip $clipId Audio",
        )
        if (imported != null) {
            safeToast("Audio extracted", Toast.LENGTH_SHORT)
            refreshMainTimelineTracks()
        } else {
            safeToast("Audio import failed", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipReplaceAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO -> {
                val clipId = selectedVideoClipId() ?: run {
                    safeToast("Select video clip first", Toast.LENGTH_SHORT)
                    return
                }
                pendingVideoReplaceClipId = clipId
                openVideoTrackImport()
            }
            ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: run {
                    safeToast("Select ${selectedNativeClipLabel().lowercase(Locale.US)} clip first", Toast.LENGTH_SHORT)
                    return
                }
                pendingVideoReplaceClipId = clipId
                if (selectedTrackType() == TrackType.LAYER) {
                    openLayerTrackImport()
                } else {
                    openOverlayTrackImport()
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                pendingAudioReplaceClipId = audioId
                openAudioTrackImport()
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this, "Edit Text") {
                    textInput("Text", overlay.text) { }
                    chips("", listOf("Apply"), -1) { _, _ ->
                        val nextText = getTextInput().trim().ifEmpty { overlay.text }
                        overlay.text = nextText
                        overlayViews[overlay.id]?.setText(nextText)
                        NativeBridge.setTextOverlayBitmap(previewView ?: return@chips, overlay)
                        previewView?.updateTextOverlay(
                            overlay.id, overlay.x, overlay.y, overlay.scale,
                            overlay.rotation, overlay.color, overlay.fontSize,
                            overlay.startTimeMs, overlay.endTimeMs,
                        )
                        refreshMainTimelineTracks()
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                val stickers = com.video.engine.stickers.StickerPacks.getAllPacks()["default"].orEmpty()
                val labels = stickers.map { it.emojiOrSymbol } + listOf("📷")
                ModernSheet.show(this, "Replace Overlay") {
                    chips("Select", labels, -1) { i, _ ->
                        if (i < stickers.size) { clip.type = "sticker"; clip.stickerId = stickers[i].id; clip.imagePath = null }
                        else { clip.type = "image"; clip.imagePath = "placeholder://image" }
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                        Toast.makeText(this@MainActivity, "Overlay replaced", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ClipKind.NONE -> {
                safeToast("No selected clip to replace", Toast.LENGTH_SHORT)
            }
        }
    }

    private fun performSelectedClipDuplicateAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: run {
                    Toast.makeText(this@MainActivity, "Select clip first", Toast.LENGTH_SHORT).show()
                    return
                }
                val clipLabel = selectedNativeClipLabel()
                val result = runCatching {
                    NativeBridge.executeCommand(
                        action = "DUPLICATE_CLIP",
                        params = mapOf("clipId" to clipId),
                    )
                }.getOrNull()
                if (result?.success == true) {
                    val newClipId = result.data.optInt("newClipId", -1).takeIf { it > 0 }
                    if (newClipId != null) {
                        videoClipReverseOverrides[clipId]?.let { videoClipReverseOverrides[newClipId] = it }
                        videoClipFreezeOverrides[clipId]?.let { videoClipFreezeOverrides[newClipId] = it }
                        videoClipCurveProfiles[clipId]?.let { videoClipCurveProfiles[newClipId] = it }
                        videoClipKeyframes[clipId]?.let { keyframes ->
                            videoClipKeyframes[newClipId] = keyframes.toMutableList()
                        }
                        duckingEnabledForKey[clipId.toString()]?.let { duckingEnabledForKey[newClipId.toString()] = it }
                    }
                    syncTimelineShellFromNative(selectedClipId = newClipId ?: clipId)
                    Toast.makeText(this@MainActivity, "$clipLabel duplicated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Duplicate failed", Toast.LENGTH_SHORT).show()
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                val result = runCatching {
                    NativeBridge.executeCommand(
                        action = "DUPLICATE_CLIP",
                        params = mapOf("clipId" to audioId),
                    )
                }.getOrNull()
                val newId = result?.data?.optInt("newClipId", -1)?.takeIf { result.success && it > 0 } ?: run {
                    Toast.makeText(this@MainActivity, "Audio duplicate failed", Toast.LENGTH_SHORT).show()
                    return
                }
                runCatching {
                    NativeBridge.executeCommand(
                        action = "MOVE_CLIP",
                        params = mapOf(
                            "clipId" to newId,
                            "newTimeMs" to (clip.startTimeMs + clip.durationMs + 50L),
                        ),
                    )
                }
                audioClipGainOverrides[newId] = audioClipGainOverrides[audioId] ?: if (clip.muted) 0f else 1f
                duckingEnabledForKey["audio-$newId"] = duckingEnabledForKey["audio-$audioId"] ?: false
                selectedTimelineClipKey = "audio-$newId"
                lastLayoutFetchMs = 0L
                syncTimelineShellFromNative(selectedClipId = newId)
                safeToast("Audio duplicated", Toast.LENGTH_SHORT)
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                val duration = (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(1)
                val duplicate = overlay.copy(
                    id = nextTextOverlayId++,
                    startTimeMs = overlay.endTimeMs + 60,
                    endTimeMs = overlay.endTimeMs + 60 + duration,
                    layerIndex = overlay.layerIndex + 1,
                )
                OverlayStore.put(duplicate)
                addTextOverlayToPreview(duplicate)
                selectedTimelineClipKey = "text-${duplicate.id}"
                refreshMainTimelineTracks()
                safeToast("Text duplicated", Toast.LENGTH_SHORT)
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                val duplicate = clip.copy(
                    id = nextStickerId++,
                    startTimeMs = clip.startTimeMs + clip.durationMs + 60,
                    layerIndex = clip.layerIndex + 1,
                )
                StickerClipStore.add(duplicate)
                addStickerOverlayView(duplicate)
                applyStickerOverlayPose(duplicate)
                selectedTimelineClipKey = "sticker-${duplicate.id}"
                refreshMainTimelineTracks()
                safeToast("Overlay duplicated", Toast.LENGTH_SHORT)
            }
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipRotateMirrorAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val selected = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                val cur = clipPreviewTransforms[selected] ?: ClipPreviewTransform()
                ModernSheet.show(this, "$clipLabel Rotate & Mirror") {
                    slider("Rotation", -180f, 180f, cur.rotationDeg, { "${it.toInt()}°" }) { v ->
                        updateSelectedVideoPreviewTransform { it.copy(rotationDeg = v) }
                    }
                    toggle("Mirror Horizontal", cur.mirrorX) { v ->
                        updateSelectedVideoPreviewTransform { it.copy(mirrorX = v) }
                    }
                    chips("Quick", listOf("+90°", "-90°", "Reset")) { _, opt ->
                        updateSelectedVideoPreviewTransform { c ->
                            when (opt) {
                                "+90°" -> c.copy(rotationDeg = c.rotationDeg + 90f)
                                "-90°" -> c.copy(rotationDeg = c.rotationDeg - 90f)
                                "Reset" -> ClipPreviewTransform()
                                else -> c
                            }
                        }
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this, "Rotate Text") {
                    slider("Rotation", -180f, 180f, overlay.rotation, { "${it.toInt()}°" }) { v ->
                        overlay.rotation = v; applyTextOverlayPose(overlay)
                    }
                    chips("Quick", listOf("+15°", "-15°", "Reset")) { _, opt ->
                        when (opt) {
                            "+15°" -> overlay.rotation += 15f
                            "-15°" -> overlay.rotation -= 15f
                            "Reset" -> overlay.rotation = 0f
                        }
                        applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                ModernSheet.show(this, "Rotate Overlay") {
                    slider("Rotation", -180f, 180f, clip.rotation, { "${it.toInt()}°" }) { v ->
                        clip.rotation = v; applyStickerOverlayPose(clip)
                    }
                    toggle("Mirror", clip.mirrorX) { v ->
                        clip.mirrorX = v; applyStickerLayerState(clip); applyStickerOverlayPose(clip)
                    }
                }
            }
            ClipKind.AUDIO -> safeToast("Rotate is not for audio clips", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipKeyframeAction() {
        val playheadMs = currentPlayheadMs()
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                val keyframes = videoClipKeyframes.getOrPut(clipId) { mutableListOf() }
                if (!keyframes.contains(playheadMs)) {
                    keyframes += playheadMs
                    keyframes.sort()
                }
                runCatching {
                    NativeBridge.executeCommand(
                        action = "KEYFRAME_ADD",
                        params = mapOf(
                            "clipId" to clipId,
                            "timeMs" to playheadMs,
                        ),
                    )
                }
                safeToast("$clipLabel keyframe @ ${playheadMs}ms", Toast.LENGTH_SHORT)
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                previewView?.addTextKeyframe(
                    overlay.id,
                    playheadMs,
                    overlay.x,
                    overlay.y,
                    overlay.scale,
                    overlay.opacity,
                )
                safeToast("Text keyframe @ ${playheadMs}ms", Toast.LENGTH_SHORT)
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val keyframes = stickerClipKeyframes.getOrPut(stickerId) { mutableListOf() }
                if (!keyframes.contains(playheadMs)) {
                    keyframes += playheadMs
                    keyframes.sort()
                }
                safeToast("Overlay keyframe @ ${playheadMs}ms", Toast.LENGTH_SHORT)
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                val clipEndMs = clip.startTimeMs + clip.durationMs
                if (playheadMs < clip.startTimeMs || playheadMs >= clipEndMs) {
                    safeToast("Move playhead inside audio clip", Toast.LENGTH_SHORT)
                    return
                }
                val localTimeMs = (playheadMs - clip.startTimeMs).coerceIn(0L, clip.durationMs.coerceAtLeast(1L) - 1L)
                val currentKeyframes = normalizeAudioGainKeyframes(clip.gainKeyframes, clip.durationMs)
                val existingKeyframe = currentKeyframes.firstOrNull { it.timeMs == localTimeMs }
                val initialGain = existingKeyframe?.gain ?: sampleAudioGainEnvelope(currentKeyframes, localTimeMs)
                if (existingKeyframe == null) {
                    applyAudioGainKeyframes(audioId, clip, upsertAudioGainKeyframe(currentKeyframes, localTimeMs, initialGain, clip.durationMs))
                }
                showAudioKeyframeSheet(audioId, clip, localTimeMs, initialGain)
            }
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipReverseAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    safeToast("$clipLabel image cannot be reversed", Toast.LENGTH_SHORT)
                    return
                }
                val enableReverse = !(videoClipReverseOverrides[clipId] ?: false)
                val result = runCatching {
                    NativeBridge.executeCommand(
                        action = "REVERSE_CLIP",
                        params = mapOf(
                            "clipId" to clipId,
                            "enabled" to enableReverse,
                        ),
                    )
                }.getOrNull()
                if (result?.success != false) {
                    videoClipReverseOverrides[clipId] = enableReverse
                    lastLayoutFetchMs = 0L
                    refreshMainTimelineTracks()
                    refreshPreviewAtPlayhead()
                    Toast.makeText(
                        this,
                        if (enableReverse) "$clipLabel Reverse ON" else "$clipLabel Reverse OFF",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    safeToast("Reverse update failed", Toast.LENGTH_SHORT)
                }
            }
            ClipKind.AUDIO -> safeToast("Reverse for audio next", Toast.LENGTH_SHORT)
            ClipKind.TEXT, ClipKind.STICKER -> safeToast("Reverse only for video", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipFreezeFrameAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    safeToast("$clipLabel image is already a still frame", Toast.LENGTH_SHORT)
                    return
                }
                val playheadMs = currentPlayheadMs()
                ModernSheet.show(this, "$clipLabel Freeze Frame") {
                    chips("Duration", listOf("0.5s", "1s", "2s", "3s", "5s")) { _, opt ->
                        val durationMs = (opt.removeSuffix("s").toFloatOrNull() ?: 1f).toLong() * 1000L
                        runCatching {
                            NativeBridge.executeCommand("FREEZE_FRAME", mapOf(
                                "clipId" to clipId, "timeMs" to playheadMs, "durationMs" to durationMs,
                            ))
                        }
                        videoClipFreezeOverrides[clipId] = playheadMs to durationMs
                        lastLayoutFetchMs = 0L; refreshMainTimelineTracks(); refreshPreviewAtPlayhead()
                    }
                }
            }
            ClipKind.AUDIO, ClipKind.TEXT, ClipKind.STICKER -> safeToast("Freeze only for video", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun applyAudioDuckingLocallyForRange(
        startMs: Long,
        endMs: Long,
        enabled: Boolean,
        amount: Float,
        excludeAudioId: Int? = null,
    ) {
        val duckAmount = amount.coerceIn(0.05f, 1.0f)
        AudioClipStore.all().forEach { clip ->
            if (excludeAudioId != null && clip.id == excludeAudioId) {
                audioClipGainOverrides[clip.id] = 1.0f
                clip.gain = 1.0f
                clip.muted = false
                return@forEach
            }
            val clipStart = clip.startTimeMs
            val clipEnd = clip.startTimeMs + clip.durationMs
            val overlaps = clipStart < endMs && clipEnd > startMs
            if (!overlaps) return@forEach
            val targetGain = if (enabled) duckAmount else 1.0f
            audioClipGainOverrides[clip.id] = targetGain
            clip.gain = targetGain
            clip.muted = targetGain <= 0.001f
        }
    }

    private fun performSelectedClipDuckingAction() {
        val duckAmount = 0.35f
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    safeToast("$clipLabel image has no audio", Toast.LENGTH_SHORT)
                    return
                }
                val key = clipId.toString()
                val enabled = !(duckingEnabledForKey[key] ?: false)
                val timing = selectedVideoTiming(clipId)
                val startMs = timing?.first ?: currentPlayheadMs()
                val endMs = startMs + (timing?.second ?: 2000L)
                runCatching {
                    NativeBridge.executeCommand(
                        action = "AUDIO_DUCKING",
                        params = mapOf(
                            "clipId" to clipId,
                            "enabled" to enabled,
                            "amount" to duckAmount,
                        ),
                    )
                }
                applyAudioDuckingLocallyForRange(startMs, endMs, enabled, duckAmount, excludeAudioId = null)
                duckingEnabledForKey[key] = enabled
                refreshMainTimelineTracks()
                safeToast(if (enabled) "$clipLabel Ducking ON" else "$clipLabel Ducking OFF", Toast.LENGTH_SHORT)
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                val key = "audio-$audioId"
                val enabled = !(duckingEnabledForKey[key] ?: false)
                runCatching {
                    NativeBridge.executeCommand(
                        action = "AUDIO_DUCKING",
                        params = mapOf(
                            "clipId" to audioId,
                            "enabled" to enabled,
                            "amount" to duckAmount,
                        ),
                    )
                }
                applyAudioDuckingLocallyForRange(
                    startMs = clip.startTimeMs,
                    endMs = clip.startTimeMs + clip.durationMs,
                    enabled = enabled,
                    amount = duckAmount,
                    excludeAudioId = audioId,
                )
                duckingEnabledForKey[key] = enabled
                refreshMainTimelineTracks()
                safeToast(if (enabled) "Audio focus ON" else "Audio focus OFF", Toast.LENGTH_SHORT)
            }
            ClipKind.TEXT, ClipKind.STICKER -> safeToast("Ducking for video/audio clips", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipCurveSpeedAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    showStillImageDurationSheet(clipId, clipLabel)
                    return
                }
                val labels = listOf("Linear 1x", "Ease In 0.8x", "Ease Out 1.2x", "Speed Ramp 1.4x", "Hyperlapse 2x")
                val profiles = listOf("linear", "ease_in", "ease_out", "speed_ramp", "hyperlapse")
                val strengths = listOf(1.0, 0.8, 1.2, 1.4, 2.0)
                ModernSheet.show(this, "$clipLabel Curve Speed") {
                    chips("Profile", labels, -1) { i, _ ->
                        runCatching { NativeBridge.executeCommand("CURVE_SPEED", mapOf("clipId" to clipId, "profile" to profiles[i], "strength" to strengths[i])) }
                        runCatching { NativeBridge.executeCommand("SPEED", mapOf("clipId" to clipId, "speed" to strengths[i])) }
                        videoClipCurveProfiles[clipId] = profiles[i]
                        lastLayoutFetchMs = 0L
                        refreshMainTimelineTracks()
                        refreshPreviewAtPlayhead()
                        Toast.makeText(this@MainActivity, labels[i], Toast.LENGTH_SHORT).show()
                    }
                }
            }
            else -> safeToast("Curve speed for video clips", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipFilterAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                selectedVideoClipId()?.let { timelineManager?.selectClip(it) }
                uiChromeController?.showEffectsSheet() ?: showClipToolPending("Effects")
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this, "Text Color") {
                    chips("Color", listOf("White", "Yellow", "Cyan", "Green", "Red"), -1) { i, _ ->
                        overlay.color = listOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF76FF03.toInt(), 0xFFFF5252.toInt())[i]
                        NativeBridge.setTextOverlayBitmap(previewView ?: return@chips, overlay)
                        previewView?.updateTextOverlay(overlay.id, overlay.x, overlay.y, overlay.scale, overlay.rotation, overlay.color, overlay.fontSize, overlay.startTimeMs, overlay.endTimeMs)
                    }
                }
            }
            ClipKind.STICKER -> performSelectedClipGraphicsAction()
            ClipKind.AUDIO -> showClipToolPending("Filter")
            ClipKind.NONE -> Toast.makeText(this@MainActivity, "Select clip first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSelectedClipBrightnessAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                selectedVideoClipId()?.let { timelineManager?.selectClip(it) }
                syncEffectSlidersForClip(selectedVideoClipId())
                val sliders = findViewById<View>(R.id.effectSlidersContainer)
                sliders?.visibility = if (sliders?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                ModernSheet.show(this@MainActivity, "Text Fade") {
                    chips("Opacity", listOf("100%", "85%", "70%", "55%"), -1) { i, _ ->
                        overlay.opacity = listOf(1f, 0.85f, 0.70f, 0.55f)[i]
                        previewView?.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
                        applyTextOverlayState(overlay); applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                ModernSheet.show(this@MainActivity, "Overlay Fade") {
                    chips("Opacity", listOf("100%", "85%", "70%", "55%"), -1) { i, _ ->
                        clip.opacity = listOf(1f, 0.85f, 0.70f, 0.55f)[i]
                        applyStickerLayerState(clip); applyStickerOverlayPose(clip)
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                val maxFadeMsInt = clip.durationMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val maxFadeMs = maxFadeMsInt.toFloat()
                ModernSheet.show(this@MainActivity, "Audio Fade") {
                    slider(
                        "Fade In",
                        0f,
                        maxFadeMs,
                        clip.fadeInMs.toFloat().coerceIn(0f, maxFadeMs),
                        { formatFadeLabel(it) },
                    ) { value ->
                        val fadeInMs = value.toInt().coerceIn(0, maxFadeMsInt)
                        clip.fadeInMs = fadeInMs
                        runCatching {
                            NativeBridge.executeCommand(
                                action = "SET_CLIP_AUDIO_FADES",
                                params = mapOf(
                                    "clipId" to audioId,
                                    "fadeInMs" to clip.fadeInMs,
                                    "fadeOutMs" to clip.fadeOutMs,
                                ),
                            )
                        }
                        refreshMainTimelineTracks()
                        previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = isPlaying)
                    }
                    slider(
                        "Fade Out",
                        0f,
                        maxFadeMs,
                        clip.fadeOutMs.toFloat().coerceIn(0f, maxFadeMs),
                        { formatFadeLabel(it) },
                    ) { value ->
                        val fadeOutMs = value.toInt().coerceIn(0, maxFadeMsInt)
                        clip.fadeOutMs = fadeOutMs
                        runCatching {
                            NativeBridge.executeCommand(
                                action = "SET_CLIP_AUDIO_FADES",
                                params = mapOf(
                                    "clipId" to audioId,
                                    "fadeInMs" to clip.fadeInMs,
                                    "fadeOutMs" to clip.fadeOutMs,
                                ),
                            )
                        }
                        refreshMainTimelineTracks()
                        previewAudioPlayer?.seekTo(currentTimeMs, continuePlaying = isPlaying)
                    }
                }
            }
            ClipKind.NONE -> Toast.makeText(this@MainActivity, "Select clip first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSelectedClipTransitionAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO -> {
                selectedVideoClipId()?.let { timelineManager?.selectClip(it) }
                uiChromeController?.showTransitionSheet() ?: showClipToolPending("Transition")
            }
            ClipKind.OVERLAY -> safeToast("Transitions are for main video cuts", Toast.LENGTH_SHORT)
            ClipKind.TEXT, ClipKind.STICKER -> safeToast("Transitions are for video clips", Toast.LENGTH_SHORT)
            ClipKind.AUDIO -> safeToast("Transitions are not for audio clips", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipGraphicsAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO -> {
                uiChromeController?.showGraphicsSheet() ?: showClipToolPending("Graphics")
            }
            ClipKind.TEXT -> showSelectedTextStudio()
            ClipKind.STICKER -> performSelectedClipReplaceAction()
            ClipKind.AUDIO -> showClipToolPending("Graphics")
            ClipKind.OVERLAY -> performSelectedClipReplaceAction()
            ClipKind.NONE -> Toast.makeText(this@MainActivity, "Select clip first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSelectedClipCutoutAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                uiChromeController?.showChromaKeyPanel(clipId)
            }
            ClipKind.TEXT, ClipKind.STICKER -> showClipToolPending("Cutout")
            ClipKind.AUDIO -> Toast.makeText(this@MainActivity, "Cutout is not for audio clips", Toast.LENGTH_SHORT).show()
            ClipKind.NONE -> Toast.makeText(this@MainActivity, "Select clip first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSelectedClipAddLayerAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO -> {
                uiChromeController?.showLayerImportSheet() ?: showClipToolPending("Add Layer")
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                val minLayer = allTextOverlays().minOfOrNull { it.layerIndex } ?: 0
                val maxLayer = allTextOverlays().maxOfOrNull { it.layerIndex } ?: 0
                val options = arrayOf("Bring Forward", "Send Backward", "Bring To Front", "Send To Back")
                AlertDialog.Builder(this)
                    .setTitle("Text Layer")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> overlay.layerIndex += 1
                            1 -> overlay.layerIndex = (overlay.layerIndex - 1).coerceAtLeast(minLayer - 1)
                            2 -> overlay.layerIndex = maxLayer + 1
                            3 -> overlay.layerIndex = minLayer - 1
                        }
                        applyTextOverlayState(overlay)
                        refreshMainTimelineTracks()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                val minLayer = StickerClipStore.all().minOfOrNull { it.layerIndex } ?: 0
                val maxLayer = StickerClipStore.all().maxOfOrNull { it.layerIndex } ?: 0
                val options = arrayOf("Bring Forward", "Send Backward", "Bring To Front", "Send To Back")
                AlertDialog.Builder(this)
                    .setTitle("Overlay Layer")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> clip.layerIndex += 1
                            1 -> clip.layerIndex = (clip.layerIndex - 1).coerceAtLeast(minLayer - 1)
                            2 -> clip.layerIndex = maxLayer + 1
                            3 -> clip.layerIndex = minLayer - 1
                        }
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            ClipKind.AUDIO -> showClipToolPending("Layer")
            ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                showNativeClipLayerSheet(clipId, selectedNativeClipLabel())
            }
            ClipKind.NONE -> Toast.makeText(this@MainActivity, "Select clip first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomToolbarModes() {
        bindClipToolbarAction(R.id.clipDeleteButton) { performSelectedClipDeleteAction() }
        bindClipToolbarAction(R.id.clipSplitButton) { performSelectedClipSplitAction() }
        bindClipToolbarAction(R.id.clipVolumeButton) { performSelectedClipVolumeAction() }
        bindClipToolbarAction(R.id.clipSpeedButton) { performSelectedClipSpeedAction() }
        bindClipToolbarAction(R.id.clipPanZoomButton) { performSelectedClipPanZoomAction() }
        bindClipToolbarAction(R.id.clipFilterButton) { performSelectedClipFilterAction() }
        bindClipToolbarAction(R.id.clipBrightnessButton) { performSelectedClipBrightnessAction() }
        bindClipToolbarAction(R.id.clipTransitionButton) { performSelectedClipTransitionAction() }
        bindClipToolbarAction(R.id.clipGraphicsButton) { performSelectedClipGraphicsAction() }
        bindClipToolbarAction(R.id.clipExtractAudioButton) { performSelectedClipExtractAudioAction() }
        bindClipToolbarAction(R.id.clipReplaceButton) { performSelectedClipReplaceAction() }
        bindClipToolbarAction(R.id.clipDuplicateButton) { performSelectedClipDuplicateAction() }
        bindClipToolbarAction(R.id.clipAddLayerButton) { performSelectedClipAddLayerAction() }
        bindClipToolbarAction(R.id.clipRotateMirrorButton) { performSelectedClipRotateMirrorAction() }
        bindClipToolbarAction(R.id.clipKeyframeButton) { performSelectedClipKeyframeAction() }
        bindClipToolbarAction(R.id.clipReverseButton) { performSelectedClipReverseAction() }
        bindClipToolbarAction(R.id.clipFreezeFrameButton) { performSelectedClipFreezeFrameAction() }
        bindClipToolbarAction(R.id.clipDuckingButton) { performSelectedClipDuckingAction() }
        bindClipToolbarAction(R.id.clipCurveSpeedButton) { performSelectedClipCurveSpeedAction() }
        bindClipToolbarAction(R.id.clipChromaKeyButton) {
            val clipId = selectedVideoClipId() ?: return@bindClipToolbarAction
            uiChromeController?.showChromaKeyPanel(clipId)
        }
        bindClipToolbarAction(R.id.clipCutoutButton) { performSelectedClipCutoutAction() }
        bindClipToolbarAction(R.id.clipTrimButton) { showSelectedClipTrimSheet() }
        updateBottomToolbarMode()
    }

    private fun updateBottomToolbarMode() {
        val key = selectedTimelineClipKey
        val kind = selectedClipKind()
        val selectedTrackLocked = selectedTrackType()?.let(::isTrackLocked) == true
        val showClipEdit = when {
            key == null -> false
            key.startsWith("audio-") -> true
            key.startsWith("text-") -> true
            key.startsWith("sticker-") -> true
            else -> parseNativeClipId(key) != null
        }
        findViewById<View>(R.id.normalToolbarScroll)?.visibility = if (showClipEdit) View.GONE else View.VISIBLE
        findViewById<View>(R.id.audioEditToolbarScroll)?.visibility = if (showClipEdit) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audioEditToolbarScroll)?.alpha = if (showClipEdit && selectedTrackLocked) 0.55f else 1f
        if (!showClipEdit || (kind != ClipKind.VIDEO && kind != ClipKind.OVERLAY)) {
            effectSlidersContainer?.visibility = View.GONE
        }
        if (showClipEdit) {
            applyClipToolbarProfile(kind)
        }
        updateClipToolbarHeader(if (showClipEdit) kind else ClipKind.NONE)
        applySelectedClipPreviewTransform()
    }

}
