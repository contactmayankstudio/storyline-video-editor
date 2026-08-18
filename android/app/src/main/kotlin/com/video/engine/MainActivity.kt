package com.video.engine

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.video.engine.UiToast as Toast
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
import com.video.engine.photo.PhotoEditorActivity
import com.video.engine.photo.PhotoProjectStore
import com.video.engine.pro.model.ClipSegment
import com.video.engine.pro.model.TrackState
import com.video.engine.pro.model.TrackType
import com.video.engine.pro.timeline.AudioWaveformCache
import com.video.engine.pro.timeline.MultiTrackTimelineView
import com.video.engine.pro.timeline.TimelineThumbnailCache
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.stickers.StickerOverlayView
import com.video.engine.timeline.MultiClipTimeline
import com.video.engine.timeline.TimelineManager
import com.video.engine.transition.TransitionStore
import com.video.engine.transition.TransitionType
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
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
class VideoEditorActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private var playbackIdleUiRestoreRunnable: Runnable? = null
    private val bannerRefreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            refreshTopBannerPlacement()
        }
    }
    private val rewardedPreloadRunnable = Runnable {
        maybePreloadRewardedUnlock()
    }
    private val postExportInterstitialRunnable = Runnable {
        maybeShowPostExportInterstitial()
    }
    private val appHealthHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            syncAppHealth(force = true)
            val nextIntervalMs =
                if (::opsReporter.isInitialized) {
                    opsReporter.heartbeatIntervalMs()
                } else {
                    45_000L
                }
            mainHandler.postDelayed(this, nextIntervalMs)
        }
    }

    private fun safeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, duration).show()
            }
        }
    }

    private fun timelineCanvasView() =
        findViewById<com.video.engine.pro.timeline.TimelineCanvasView?>(R.id.timelineCanvasView)

    private fun activeCanvasTimelineView() =
        timelineCanvasView()?.takeIf { it.visibility == View.VISIBLE }

    private fun activeMultiTrackTimelineView() =
        multiTrackTimelineView?.takeIf { it.visibility == View.VISIBLE }

    private fun uiDp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun ensureImportProgressPanel(): LinearLayout? {
        importProgressPanel?.let { return it }
        val host = previewContainerView ?: return null
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val detail = TextView(this).apply {
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(16), uiDp(16), uiDp(16), uiDp(16))
            visibility = View.GONE
            alpha = 0.98f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(232, 18, 23, 32))
                setStroke(uiDp(1), Color.argb(90, 255, 255, 255))
                cornerRadius = uiDp(12).toFloat()
            }
            addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(detail, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = uiDp(5)
            })
            addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                uiDp(5),
            ).apply {
                topMargin = uiDp(10)
            })
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            leftMargin = uiDp(40)
            rightMargin = uiDp(40)
        }
        host.addView(panel, params)
        importProgressPanel = panel
        importProgressTitleText = title
        importProgressDetailText = detail
        importProgressBar = progress
        return panel
    }

    private fun showImportProgress(update: ImportProgressUpdate) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            hideImportProgressRunnable?.let(mainHandler::removeCallbacks)
            val panel = ensureImportProgressPanel() ?: return@post
            importProgressTitleText?.text =
                when (update.phase) {
                    ImportProgressPhase.READY -> "Import ready"
                    ImportProgressPhase.FAILED -> "Import failed"
                    else -> "Importing ${trackDisplayName(update.trackType).lowercase(Locale.US)}"
                }
            importProgressDetailText?.text = importProgressDetail(update)
            importProgressBar?.apply {
                val percent = update.percent
                isIndeterminate = percent == null && update.phase !in setOf(ImportProgressPhase.READY, ImportProgressPhase.FAILED)
                progress = when {
                    update.phase == ImportProgressPhase.READY -> 100
                    update.phase == ImportProgressPhase.FAILED -> 0
                    percent != null -> percent.coerceIn(0, 100)
                    else -> 0
                }
            }
            panel.visibility = View.VISIBLE
            panel.bringToFront()
            if (update.phase == ImportProgressPhase.READY || update.phase == ImportProgressPhase.FAILED) {
                scheduleHideImportProgress(if (update.phase == ImportProgressPhase.READY) 1_600L else 3_400L)
            }
        }
    }

    private fun scheduleHideImportProgress(delayMs: Long) {
        val runnable = Runnable {
            importProgressPanel?.visibility = View.GONE
        }
        hideImportProgressRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun importProgressDetail(update: ImportProgressUpdate): String {
        return when (update.phase) {
            ImportProgressPhase.OPENING -> update.message ?: "Opening selected media"
            ImportProgressPhase.COPYING -> {
                val percent = update.percent?.let { "$it%" } ?: "Copying"
                val copied = formatImportBytes(update.bytesCopied)
                val total =
                    if (update.totalBytes > 0L) {
                        " / ${formatImportBytes(update.totalBytes)}"
                    } else {
                        ""
                    }
                val eta =
                    update.etaMs
                        ?.takeIf { it > 0L }
                        ?.let { " - ${formatImportEta(it)} left" }
                        ?: if (update.elapsedMs > 1_500L && update.totalBytes > 0L) " - estimating" else ""
                "$percent - $copied$total$eta"
            }
            ImportProgressPhase.PREPARING -> update.message ?: "Preparing media"
            ImportProgressPhase.TIMELINE -> update.message ?: "Adding clip to timeline"
            ImportProgressPhase.READY -> update.message ?: "Ready in editor"
            ImportProgressPhase.FAILED -> update.message ?: "Unable to import media"
        }
    }

    private fun formatImportBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val mb = safeBytes / (1024.0 * 1024.0)
        return if (mb >= 100.0) {
            "${mb.roundToInt()} MB"
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun formatImportEta(ms: Long): String {
        val totalSeconds = ((ms + 999L) / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        } else {
            "${seconds}s"
        }
    }

    private fun trackDisplayName(trackType: TrackType): String = trackType.displayName()

    private fun isTrackLocked(trackType: TrackType): Boolean =
        trackLockedOverrides[trackType] == true

    private fun trackVisibilityStateSnapshot(): Map<TrackType, Boolean> =
        TrackType.displayOrder().associateWith { trackType -> trackVisibilityOverrides[trackType] ?: true }

    private fun trackLockStateSnapshot(): Map<TrackType, Boolean> =
        TrackType.displayOrder().associateWith { trackType -> trackLockedOverrides[trackType] ?: false }

    private fun hasProjectContent(): Boolean {
        val hasNativeClips =
            previewView?.let { preview ->
                runCatching { NativeBridge.getClipIds(preview).isNotEmpty() }.getOrDefault(false)
            } == true
        return hasNativeClips ||
            timelineManager?.getClips()?.isNotEmpty() == true ||
            allTextOverlays().isNotEmpty() ||
            StickerClipStore.all().isNotEmpty() ||
            AudioClipStore.all().isNotEmpty()
    }

    private fun hasPreviewVisualContent(): Boolean {
        fun isVisualTrack(trackType: TrackType): Boolean =
            trackType == TrackType.VIDEO ||
                trackType == TrackType.OVERLAY ||
                trackType == TrackType.LAYER

        val hasNativeVisualClips =
            previewView?.let { preview ->
                runCatching {
                    NativeBridge.getClipIds(preview).any { clipId ->
                        isVisualTrack(resolveShellTrackType(clipId))
                    }
                }.getOrDefault(false)
            } == true
        val hasTimelineVisualClips =
            timelineManager?.getClips()?.any { clip -> isVisualTrack(resolveShellTrackType(clip.id)) } == true
        return hasNativeVisualClips ||
            hasTimelineVisualClips ||
            allTextOverlays().any { it.visible } ||
            StickerClipStore.all().any { it.visible }
    }

    private fun currentProjectDurationMs(): Long {
        var maxEndMs = 0L
        var usedPositionedNativeClip = false
        val managerClipsById = timelineManager?.getClips().orEmpty().associateBy { it.id }
        val nativeIds = (nativeClipStartMs.keys + nativeClipDurationMs.keys + managerClipsById.keys).toSet()

        nativeIds.forEach { clipId ->
            val durationMs =
                (nativeClipDurationMs[clipId] ?: managerClipsById[clipId]?.durationMs)
                    ?.coerceAtLeast(1L)
                    ?: return@forEach
            val startTimeMs = nativeClipStartMs[clipId]
            if (startTimeMs != null) {
                usedPositionedNativeClip = true
                maxEndMs = maxOf(maxEndMs, startTimeMs.coerceAtLeast(0L) + durationMs)
            }
        }

        allTextOverlays().forEach { overlay ->
            if (overlay.visible) {
                maxEndMs = maxOf(
                    maxEndMs,
                    overlay.endTimeMs.toLong().coerceAtLeast(overlay.startTimeMs.toLong()),
                )
            }
        }
        StickerClipStore.all().forEach { clip ->
            if (clip.visible) {
                maxEndMs = maxOf(
                    maxEndMs,
                    clip.startTimeMs.toLong().coerceAtLeast(0L) + clip.durationMs.toLong().coerceAtLeast(1L),
                )
            }
        }
        AudioClipStore.all().forEach { clip ->
            if (clip.visible) {
                val startTimeMs = (nativeClipStartMs[clip.id] ?: clip.startTimeMs).coerceAtLeast(0L)
                val durationMs = (nativeClipDurationMs[clip.id] ?: clip.durationMs).coerceAtLeast(1L)
                maxEndMs = maxOf(maxEndMs, startTimeMs + durationMs)
            }
        }

        val legacySequentialDurationMs = timelineManager?.getTotalDurationMs()?.coerceAtLeast(0L) ?: 0L
        return when {
            usedPositionedNativeClip -> maxEndMs
            maxEndMs > 0L -> maxOf(maxEndMs, videoDurationMs.coerceAtLeast(0L))
            else -> maxOf(videoDurationMs.coerceAtLeast(0L), legacySequentialDurationMs)
        }
    }

    private fun ensureTrackEditable(trackType: TrackType, actionName: String? = null): Boolean {
        if (!isTrackLocked(trackType)) return true
        val suffix = actionName?.let { " for $it" }.orEmpty()
        safeToast("${trackDisplayName(trackType)} track locked$suffix", Toast.LENGTH_SHORT)
        return false
    }

    private fun normalizeTrackZOrder(trackType: TrackType, requestedZ: Int): Int {
        return when (trackType) {
            TrackType.VIDEO -> requestedZ.coerceIn(0, 99)
            TrackType.LAYER -> requestedZ.coerceIn(100, 199)
            TrackType.OVERLAY -> requestedZ.coerceIn(200, 399)
            TrackType.TEXT -> requestedZ.coerceAtLeast(400)
            TrackType.AUDIO -> 0
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
    private var lastSubmittedTimelineTracks: List<TrackState> = emptyList()
    private var lastSubmittedTimelineSelectionKey: String? = null
    private data class ClipTimingSnapshot(
        val startTimeMs: Long,
        val durationMs: Long,
        val sourceInMs: Long,
        val sourceOutMs: Long,
    )

    private data class ClipPreviewTransform(
        val zoom: Float = 1.0f,
        val scaleX: Float = 1.0f,
        val scaleY: Float = 1.0f,
        val panXPx: Float = 0f,
        val panYPx: Float = 0f,
        val rotationDeg: Float = 0f,
        val mirrorX: Boolean = false,
    )

    private data class ClipPreviewKeyframe(
        val timeMs: Long,
        val transform: ClipPreviewTransform,
    )

    private data class NativeClipPreviewTransformState(
        val zoom: Float,
        val scaleX: Float,
        val scaleY: Float,
        val panXPx: Float,
        val panYPx: Float,
        val rotationDeg: Float,
        val mirrorX: Boolean,
        val cleared: Boolean,
    )

    private fun NativeClipPreviewTransformState.toClipPreviewTransform(): ClipPreviewTransform? {
        if (cleared) return null
        return ClipPreviewTransform(
            zoom = zoom,
            scaleX = scaleX,
            scaleY = scaleY,
            panXPx = panXPx,
            panYPx = panYPx,
            rotationDeg = rotationDeg,
            mirrorX = mirrorX,
        )
    }

    private data class PreviewTrimSession(
        val clipId: Int,
        val edge: String,
        val originalStartTimeMs: Long,
        val originalDurationMs: Long,
        val originalSourceInMs: Long,
        val originalSourceOutMs: Long,
        val sourceLimitMs: Long,
    )

    private data class PreviewResizeSession(
        val clipId: Int,
        val cornerSignX: Float,
        val cornerSignY: Float,
    )

    private enum class PreviewTransformGestureMode(val nativeValue: Int) {
        DRAG(1),
        PINCH_ROTATE(2),
        EDGE_RESIZE(3),
    }

    // Mirrors smooth_engine/HapticEngine::Effect for editor-side tactile feedback.
    private enum class EditorHapticEffect(val feedbackConstant: Int) {
        LightClick(HapticFeedbackConstants.VIRTUAL_KEY),
        HeavyClick(HapticFeedbackConstants.LONG_PRESS),
        Tick(HapticFeedbackConstants.CLOCK_TICK),
        Success(HapticFeedbackConstants.KEYBOARD_TAP),
        Warning(HapticFeedbackConstants.LONG_PRESS),
    }

    private data class AspectRatioOption(
        val label: String,
        val width: Int,
        val height: Int,
        val example: String,
        val toolbarLabel: String,
    )

    private data class HostedReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val builtAtRaw: String,
        val builtAtLabel: String,
        val commitSubject: String,
        val downloadUrl: String,
        val portalUrl: String,
        val adminPanelUrl: String,
        val updateAvailable: Boolean,
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
        private const val AUTO_STARTUP_AUTOSAVE_RESTORE_ENABLED = true
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PICK_VIDEO_REQUEST = 200
        private const val PICK_OVERLAY_REQUEST = 202
        private const val PICK_LAYER_REQUEST = 203
        private const val PICK_AUDIO_REQUEST = 201
        private const val STATE_ACTIVE_IMPORT_PICKER_REQUEST_CODE = "state_active_import_picker_request_code"
        private const val STATE_ACTIVE_IMPORT_PICKER_OPENED_AT_MS = "state_active_import_picker_opened_at_ms"
        private const val STATE_ACTIVE_IMPORT_PICKER_STARTED_FROM_START = "state_active_import_picker_started_from_start"
        private const val STATE_ACTIVE_IMPORT_PICKER_START_TIME_MS = "state_active_import_picker_start_time_ms"
        private const val STATE_SELECTED_IMPORT_TRACK_TYPE = "state_selected_import_track_type"
        private const val STATE_SELECTED_ASPECT_RATIO_INDEX = "state_selected_aspect_ratio_index"
        private const val STATE_ASPECT_RATIO_MANUALLY_SELECTED = "state_aspect_ratio_manually_selected"
        private const val PREFS_IMPORT_PICKER = "storyline_import_picker"
        private const val PREF_IMPORT_PICKER_RESTORE_AUTOSAVE = "import_picker_restore_autosave"
        private const val PREF_ACTIVE_IMPORT_PICKER_REQUEST_CODE = "active_import_picker_request_code"
        private const val PREF_ACTIVE_IMPORT_PICKER_START_TIME_MS = "active_import_picker_start_time_ms"
        private const val PREF_ACTIVE_IMPORT_PICKER_STARTED_FROM_START = "active_import_picker_started_from_start"
        private var processStartupAutoRestoreAttempted = false

        private const val EXPORT_NOTIFICATION_CHANNEL_ID = "video_export"
        private const val EXPORT_NOTIFICATION_ID = 1001
        private const val HARDWARE_TELEMETRY_REFRESH_MS = 750L
        private const val AUTOMATION_SMOKE_DURATION_MS = 15_000L
        private const val AUTOMATION_DUPLICATE_SUPPRESS_MS = 1_500L
        private const val ROBO_SEED_SECOND_CLIP_OFFSET_MS = 3_000L
        private const val ROBO_SEED_SECOND_IMPORT_DELAY_MS = 2_400L
        private const val ROBO_SEED_AUDIO_IMPORT_DELAY_MS = 4_800L
        private const val ROBO_SEED_TEXT_IMPORT_DELAY_MS = 6_400L
        private const val ROBO_SEED_FINALIZE_DELAY_MS = 8_200L
        private const val PLAYBACK_AUDIO_CLOCK_SAMPLE_INTERVAL_MS = 180L
        private const val PLAYBACK_AUDIO_CLOCK_SAMPLE_DISTANCE_MS = 144L
        private const val PREVIEW_DUPLICATE_NATIVE_SEEK_TOLERANCE_MS = 8L
        private const val PREVIEW_NATIVE_SEEK_QUIET_WINDOW_MS = 64L
        private const val PREVIEW_SURFACE_RESTORE_DELAY_MS = 90L
        private const val PREVIEW_LIFECYCLE_RESTORE_WINDOW_MS = 2_400L
        private const val PROJECT_LOAD_SURFACE_HOLD_MS = 8_000L
        private const val PREVIEW_PLAYING_AUDIO_SEEK_MIN_INTERVAL_MS = 220L
        private const val PREVIEW_PLAYING_AUDIO_SEEK_MIN_DISTANCE_MS = 420L
        private const val MIN_PREVIEW_TRIM_DURATION_MS = 150L
        private const val PREVIEW_TRIM_DISPATCH_INTERVAL_MS = 90L
        private const val PREVIEW_TRIM_EDGE_TOUCH_SLOP_DP = 28f
        private const val PREVIEW_RESIZE_EDGE_TOUCH_SLOP_DP = 36f
        private const val PREVIEW_OBJECT_MIN_ZOOM = 0.15f
        private const val PREVIEW_OBJECT_MAX_ZOOM = 8.00f
        private const val DEFAULT_ASPECT_RATIO_INDEX = 3 // 9:16 (Shorts/Reels)
        private const val EDITOR_INTERACTION_BUSY_WINDOW_MS = 1_400L
        private const val TIMELINE_IDLE_AUTOSAVE_DELAY_MS = 3_500L
        private const val TIMELINE_BUSY_AUTOSAVE_DELAY_MS = 9_000L
    }

    // UI References
    private var previewView: VideoPreviewView? = null
    private var previewContainerView: FrameLayout? = null
    private var previewViewportFrame: FrameLayout? = null
    private var projectLoadSurfaceHoldUntilElapsedMs = 0L
    private var previewEmptyStateView: View? = null
    private var previewCropOverlayView: View? = null
    private var previewCropFrameGuideView: View? = null
    private var previewCropStatusText: TextView? = null
    private var previewTrimStartHandleView: View? = null
    private var previewTrimEndHandleView: View? = null
    private var previewResizeTopLeftHandleView: View? = null
    private var previewResizeTopHandleView: View? = null
    private var previewResizeTopRightHandleView: View? = null
    private var previewResizeRightHandleView: View? = null
    private var previewResizeBottomLeftHandleView: View? = null
    private var previewResizeBottomHandleView: View? = null
    private var previewResizeBottomRightHandleView: View? = null
    private var previewResizeLeftHandleView: View? = null
    private var previewProGuidesOverlayView: View? = null
    private var previewThirdsGuideView: View? = null
    private var previewSafeAreaGuideView: View? = null
    private var previewHudView: View? = null
    private var previewHudStatusText: TextView? = null
    private var previewFrameInfoChip: TextView? = null
    private var previewGridToggleButton: TextView? = null
    private var previewSafeToggleButton: TextView? = null
    private var previewFitFillToggleButton: TextView? = null
    private var previewQualityToggleButton: TextView? = null
    private var importProgressPanel: LinearLayout? = null
    private var importProgressTitleText: TextView? = null
    private var importProgressDetailText: TextView? = null
    private var importProgressBar: ProgressBar? = null
    private var hideImportProgressRunnable: Runnable? = null
    private var timelineRecyclerView: RecyclerView? = null
    private var timelineCurrentTimeText: TextView? = null
    private var previewAspectRatioText: TextView? = null
    private var startScreenOverlayView: View? = null
    private var startRecentProjectsList: androidx.recyclerview.widget.RecyclerView? = null
    private var startRecentProjectsEmptyText: TextView? = null
    private var startRecentPhotoText: TextView? = null
    private var startAiSummaryText: TextView? = null
    private var startAiUpdateText: TextView? = null
    private var startAiUpdateButton: TextView? = null
    private var startAiAdminButton: TextView? = null
    private var editorTopBannerContainer: FrameLayout? = null
    private var startTopBannerContainer: FrameLayout? = null
    private var editorAiStatusPill: TextView? = null
    private var clipToolbarContextLabel: TextView? = null
    private var clipToolbarScrollView: HorizontalScrollView? = null
    private var hardwareTelemetrySummaryText: TextView? = null
    private var hardwareTelemetryReasonText: TextView? = null
    private var multiTrackTimelineView: MultiTrackTimelineView? = null
    private var notificationManager: NotificationManager? = null
    private var adsController: AdsController? = null
    private var activeTopBannerHostId: Int? = null
    private var adsDeferredUntilElapsedMs: Long = 0L
    private var postExportInterstitialPending = false
    private var postExportInterstitialRetryCount = 0

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
    private var clipToolbarScrollX: Int = 0
    private var lastClipToolbarSelectionKey: String? = null
    private var lastClipToolbarKind: ClipKind = ClipKind.NONE
    private var lastAutomationToken: String? = null
    private var lastAutomationTokenElapsedMs: Long = 0L
    private var roboWorkspaceSeeded = false
    private var roboWorkspaceSeedInFlight = false
    private var audioImportController: AudioImportController? = null
    private var voiceoverController: VoiceoverController? = null
    private var rewardedUnlockController: RewardedUnlockController? = null
    private var previewAudioPlayer: PreviewAudioPlayer? = null
    private lateinit var debugTelemetryManager: DebugTelemetryManager
    private lateinit var appHealthReporter: AppHealthReporter
    private lateinit var opsReporter: OpsReporter
    private lateinit var problemReportManager: ProblemReportManager
    private lateinit var uiFreezeWatchdog: UiFreezeWatchdog
    private lateinit var playbackExportIssueDetector: PlaybackExportIssueDetector
    private lateinit var uiActionExpectationDetector: UiActionExpectationDetector
    private lateinit var remoteCommandManager: RemoteCommandManager
    private var latestHealthAction: String = "launch"
    private var hostedReleaseInfo: HostedReleaseInfo? = null
    private var hostedReleaseFetchInFlight = false
    private var lastHostedReleaseFetchElapsedMs = 0L
    private var startupHeavyWorkDeferredUntilElapsedMs = 0L
    private var startShellOnly = false
    private var returningToSingleHome = false

    private fun storeAdsEnabled(): Boolean =
        resources.getBoolean(R.bool.storyline_runtime_ads_enabled)

    private fun storeStartupAdsEnabled(): Boolean =
        resources.getBoolean(R.bool.storyline_runtime_startup_ads_enabled)

    private fun storeHostedUpdatesEnabled(): Boolean =
        resources.getBoolean(R.bool.storyline_runtime_hosted_updates_enabled)

    private fun storeOpsEnabled(): Boolean =
        resources.getBoolean(R.bool.storyline_runtime_ops_enabled)

    private fun startupHeavyWorkDelayMs(): Long =
        (startupHeavyWorkDeferredUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun shouldDeferStartupHeavyWork(): Boolean =
        startupHeavyWorkDelayMs() > 0L

    private fun shouldUseStartShell(savedInstanceState: Bundle?, initialIntent: Intent?): Boolean {
        if (savedInstanceState != null) return false
        if (shouldHonorForceEditorBoot(initialIntent)) return false
        if (automationActionFrom(initialIntent).isNotBlank()) return false
        return when (initialIntent?.action) {
            Intent.ACTION_VIEW,
            Intent.ACTION_SEND,
            Intent.ACTION_EDIT,
            -> false
            else -> true
        }
    }

    private fun returnToSingleHome(reason: String) {
        if (returningToSingleHome) return
        returningToSingleHome = true
        Log.d(TAG, "Returning to single launcher home reason=$reason")
        if (!startShellOnly && hasProjectContent()) {
            saveCurrentSessionNow("return_home_$reason")
        }
        val nextIntent = Intent(this, LaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(nextIntent)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun launchFullEditorFromStartShell(action: String) {
        val nextIntent = EditorLaunchIntents.markForEditorBoot(this, Intent(this, VideoEditorActivity::class.java))
            .putExtra(EditorLaunchIntents.EXTRA_START_SHELL_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (startShellOnly) {
            setIntent(nextIntent)
            recreate()
            overridePendingTransition(0, 0)
            return
        }
        startActivity(nextIntent)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun handleStartShellAction(intent: Intent?) {
        val action = intent?.getStringExtra(EditorLaunchIntents.EXTRA_START_SHELL_ACTION)
        val startProjectPath =
            intent?.getStringExtra(EditorLaunchIntents.EXTRA_START_PROJECT_PATH)
                ?.takeIf { it.isNotBlank() }
        if (action.isNullOrBlank() && startProjectPath == null) return
        intent?.let {
            it.removeExtra(EditorLaunchIntents.EXTRA_START_SHELL_ACTION)
            it.removeExtra(EditorLaunchIntents.EXTRA_START_PROJECT_PATH)
            setIntent(it)
        }
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (startProjectPath != null && loadProjectFromStartAction(startProjectPath)) {
                return@postDelayed
            }
            when (action) {
                EditorLaunchIntents.START_ACTION_NEW -> {
                    processStartupAutoRestoreAttempted = true
                    autoSaveRestorePromptShown = true
                    startBlankProject(showToast = true)
                }
                EditorLaunchIntents.START_ACTION_OPEN -> projectController?.showLoadProjectDialog()
                EditorLaunchIntents.START_ACTION_IMPORT -> openStartScreenVideoImport()
            }
        }, 350L)
    }

    private fun loadProjectFromStartAction(filePath: String): Boolean {
        val projectFile = File(filePath)
        if (!projectFile.isFile) return false
        if (!RecentProjectFiles.isProjectUsable(this, projectFile)) {
            Toast.makeText(this, "Recent media missing. Import again.", Toast.LENGTH_SHORT).show()
            refreshRecentProjects()
            return false
        }
        processStartupAutoRestoreAttempted = true
        autoSaveRestorePromptShown = true
        clearImportSessionState("start_recent_project")
        projectController?.loadProject(projectFile.absolutePath) ?: return false
        setStartScreenVisible(false)
        return true
    }

    private fun resumeExistingSessionOrStartBlank(showToast: Boolean) {
        if (hasProjectContent()) {
            setStartScreenVisible(false)
            return
        }
        val restored = restoreAutoSaveFromStartAction("start_resume_autosave")
        if (restored) {
            setStartScreenVisible(false)
            return
        }
        startBlankProject(showToast = showToast)
    }

    private fun openStartScreenVideoImport() {
        if (hasProjectContent()) {
            setStartScreenVisible(false)
            openVideoTrackImport()
            return
        }
        // Keep home visible while the external picker is open. If the user backs out,
        // Quick Import should be a no-op and the recent/resume screen must stay intact.
        openVideoTrackImport()
    }

    private fun restoreAutoSaveFromStartAction(reason: String): Boolean {
        val autoSaveFile = RecentProjectFiles.latestAutoSaveCandidate(this)
        if (autoSaveFile == null) {
            return false
        }
        processStartupAutoRestoreAttempted = true
        autoSaveRestorePromptShown = true
        clearImportSessionState(reason)
        val restored = projectController?.restoreAutoSave() == true
        if (!restored) {
            processStartupAutoRestoreAttempted = false
            autoSaveRestorePromptShown = false
        }
        return restored
    }

    private fun scheduleStartupIdleWork(initialIntent: Intent?) {
        val hasAutomationAction = automationActionFrom(initialIntent).isNotBlank()
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                setEditorChromeVisible(true)
                applyPreviewAspectRatio()
                updatePreviewEmptyState()
            }
        }, if (hasAutomationAction) 350L else 650L)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                maybeHandleAutomationIntent(initialIntent)
            }
        }, if (hasAutomationAction) 450L else 900L)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                playbackController?.scheduleInitialDurationRefresh()
            }
        }, 1_250L)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                timelineCanvasView()?.visibility = View.VISIBLE
                refreshMainTimelineTracks()
            }
        }, 1_550L)
        if (
            !isAutomationPerfMode() &&
            !hasAutomationAction &&
            activeImportPickerRequestCode == null
        ) {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    maybePromptAutoSaveRestore()
                }
            }, 2_600L)
        }
    }

    private fun setEditorChromeVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View?>(R.id.previewContainer)?.visibility = visibility
        findViewById<View?>(R.id.bottomContainer)?.visibility = visibility
        if (visible) {
            timelineCanvasView()?.visibility = View.VISIBLE
            findViewById<View?>(R.id.previewPlayPauseButton)?.bringToFront()
            findViewById<View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        }
    }

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
        set(value) {
            field = value
            refreshOverlayVisibilityAndPose()
        }
    private var videoDurationMs = 0L
    private var selectedTimelineClipKey: String? = null
    private var previewTransformGestureDetector: GestureDetector? = null
    private var previewTransformTouchSlop = 0
    private var previewTransformStartX = 0f
    private var previewTransformStartY = 0f
    private var previewTransformDragging = false
    private var previewTransformPinching = false
    private var previewTransformTouchOwner: View? = null
    private var previewTransformGestureClipId: Int? = null
    private var previewTransformBase = ClipPreviewTransform()
    private var previewTransformApplyScheduled = false
    private var previewTransformTouchArmed = false
    private var previewTransformNativeImmediatePending = false
    private var previewTransformMovedSinceDown = false
    private var previewTransformSuppressTapCycle = false
    private var previewCropModeActive = false
    private var previewCropModeClipKey: String? = null
    private var previewPanZoomControlsVisible = false
    private var previewTrimSession: PreviewTrimSession? = null
    private var previewResizeSession: PreviewResizeSession? = null
    private var previewTrimStartRawX = 0f
    private var previewTrimLastDispatchElapsedMs = 0L
    private var lastPreviewEditableSelectionKey: String? = null
    private var previewSelectionStickyUntilElapsedMs = 0L
    private var previewSelectionLockClipId: Int? = null
    private var previewSelectionLockUntilElapsedMs = 0L
    private var previewInteractionBusyUntilElapsedMs = 0L
    private var pendingImportedClipSelectionId: Int? = null
    private var pendingImportedClipRevealTimeMs = 0L
    private var previewGestureFrameScheduled = false
    private var previewPendingGestureGeometry: PreviewPointerGeometry? = null
    private var previewLastAppliedGestureGeometry: PreviewPointerGeometry? = null
    private var previewGestureStartGeometry: PreviewPointerGeometry? = null
    private var previewGestureCenterXPx = Float.NaN
    private var previewGestureCenterYPx = Float.NaN
    private var previewGestureViewportWidthPx = 1f
    private var previewGestureViewportHeightPx = 1f
    private var previewGestureMinZoom = PREVIEW_OBJECT_MIN_ZOOM
    private var previewGestureMaxZoom = PREVIEW_OBJECT_MAX_ZOOM
    private var previewGestureAllowRotation = false
    private var lastPreviewGestureFrameApplyElapsedMs = 0L
    private var lastPreviewCropStatusRefreshElapsedMs = 0L
    private var lastPreviewCropStatusLabel: String? = null
    private var previewPlaybackSurfaceTransformActive = false
    private var previewGridVisible = false
    private var previewSafeAreaVisible = false
    private var previewQualityBoosted = false
    private val previewAudioGestureResumeRunnable =
        Runnable {
            if (shouldSuspendPreviewAudioForDirectGesture()) return@Runnable
            if (isPlaying || shouldSuppressPausedPreviewAudioSync()) return@Runnable
            previewAudioPlayer?.seekTo(currentTimeMs.coerceAtLeast(0L), continuePlaying = false)
        }

    private val undoDomains = ArrayDeque<UndoDomain>()
    private val redoDomains = ArrayDeque<UndoDomain>()
    private val videoClipTimingOverrides = mutableMapOf<Int, ClipTimingSnapshot>()
    private val clipPreviewTransforms = mutableMapOf<Int, ClipPreviewTransform>()
    private val nativeAppliedClipPreviewTransforms = mutableMapOf<Int, NativeClipPreviewTransformState>()
    private val previewTransformApplyRunnable =
        Runnable {
            previewTransformApplyScheduled = false
            applySelectedClipPreviewTransform()
        }
    private val previewGestureFrameRunnable =
        Runnable {
            previewGestureFrameScheduled = false
            val geometry = previewPendingGestureGeometry ?: return@Runnable
            previewPendingGestureGeometry = null
            previewLastAppliedGestureGeometry = geometry
            lastPreviewGestureFrameApplyElapsedMs = SystemClock.elapsedRealtime()
            updateNativePreviewTransformGesture(geometry)?.let {
                maybeRefreshPreviewCropStatusDuringGesture()
            }
        }
    private val audioClipGainOverrides = mutableMapOf<Int, Float>()
    private val audioDuckingRestoreGainOverrides = mutableMapOf<Int, Float>()
    private val videoClipGainOverrides = mutableMapOf<Int, Float>()
    private val videoClipReverseOverrides = mutableMapOf<Int, Boolean>()
    private val videoClipFreezeOverrides = mutableMapOf<Int, Pair<Long, Long>>()
    private val videoClipCurveProfiles = mutableMapOf<Int, String>()
    private val videoClipKeyframes = mutableMapOf<Int, MutableList<Long>>()
    private val videoClipPreviewKeyframes = mutableMapOf<Int, MutableList<ClipPreviewKeyframe>>()
    private val textOverlayKeyframes = mutableMapOf<Int, MutableList<Long>>()
    private val stickerClipKeyframes = mutableMapOf<Int, MutableList<Long>>()
    private val nativeClipAudioGainKeyframes = mutableMapOf<Int, List<AudioGainKeyframe>>()
    private val sourceDurationCacheMs = mutableMapOf<String, Long>()
    private val duckingEnabledForKey = mutableMapOf<String, Boolean>()
    private val trackVisibilityOverrides = mutableMapOf<TrackType, Boolean>()
    private val trackLockedOverrides = mutableMapOf<TrackType, Boolean>()
    private var timelineRefreshScheduled = false
    private var timelineRefreshRequestedDuringRun = false
    private var lastTimelineRefreshUptimeMs = 0L
    private var timelineShellSyncGeneration = 0
    private val postImportClipHydrationRunnable =
        Runnable {
            val clipId = pendingImportedClipSelectionId ?: return@Runnable
            val revealTimeMs = pendingImportedClipRevealTimeMs.coerceAtLeast(0L)
            pendingImportedClipSelectionId = null
            val retainedClipIds =
                (
                    timelineManager?.getClips()?.map { it.id }
                        ?: timeline.getClips().map { it.id }
                ).toSet() + AudioClipStore.all().map { it.id } + clipId
            lastLayoutFetchMs = 0L
            refreshNativeClipLayoutCache(retainedClipIds, refreshCachedUi = false)
            val clipKey = selectionKeyForNativeClipId(clipId)
            selectedTimelineClipKey = clipKey
            rememberPreviewEditableSelection(clipKey)
            timelineManager?.selectClip(clipId)
            multiTrackTimelineView?.setSelectedClipId(clipKey)
            multiTrackTimelineView?.revealClip(clipKey)
            multiTrackTimelineView?.setCurrentTimeMs(revealTimeMs)
            syncTimelineShellFromNative(selectedClipId = clipId)
        }
    private var previewRefreshScheduled = false
    private var previewRefreshRequestedDuringRun = false
    private var previewRefreshNeedsAudioResync = false
    private var lastPreviewRefreshUptimeMs = 0L
    private var lastPreviewNativeSeekTimeMs = Long.MIN_VALUE
    private var lastPreviewNativeSeekUptimeMs = 0L
    private var pendingPreviewNativeSeekTimeMs = Long.MIN_VALUE
    private var previewLifecycleAnchorTimeMs = 0L
    private var previewLifecycleRestoreUntilElapsedMs = 0L
    private var previewSurfaceRestoreToken = 0
    private var pausedPreviewAudioSuppressUntilElapsedMs = 0L
    private var pendingPreviewAudioSeekTimeMs = Long.MIN_VALUE
    private var pendingContinuePreviewAudioSeekTimeMs = Long.MIN_VALUE
    private var lastContinuePreviewAudioSeekTimeMs = Long.MIN_VALUE
    private var lastContinuePreviewAudioSeekElapsedMs = 0L
    private val previewAudioSeekDebounceRunnable =
        Runnable {
            val targetTimeMs = pendingPreviewAudioSeekTimeMs
            pendingPreviewAudioSeekTimeMs = Long.MIN_VALUE
            if (targetTimeMs == Long.MIN_VALUE) return@Runnable
            if (isPlaying) return@Runnable
            if (shouldSuspendPreviewAudioForDirectGesture() || shouldSuppressPausedPreviewAudioSync()) {
                return@Runnable
            }
            previewAudioPlayer?.seekTo(targetTimeMs, continuePlaying = false)
        }
    private val previewNativeSeekDebounceRunnable =
        Runnable {
            val targetTimeMs = pendingPreviewNativeSeekTimeMs
            pendingPreviewNativeSeekTimeMs = Long.MIN_VALUE
            if (targetTimeMs == Long.MIN_VALUE) return@Runnable
            if (isPlaying) return@Runnable
            seekPreviewNativeIfNeeded(
                timeMs = targetTimeMs,
                force = true,
                allowDuringPlayback = false,
            )
        }
    private val continuePreviewAudioSeekRunnable =
        Runnable {
            val targetTimeMs = pendingContinuePreviewAudioSeekTimeMs
            pendingContinuePreviewAudioSeekTimeMs = Long.MIN_VALUE
            if (targetTimeMs == Long.MIN_VALUE) return@Runnable
            dispatchContinuePreviewAudioSeek(targetTimeMs)
        }
    private var lastPlaybackAudioClockSyncRealtimeMs = 0L
    private var lastPlaybackAudioClockSyncTimelineMs = Long.MIN_VALUE
    private val initializedAudioImportClipIds = mutableSetOf<Int>()
    private val hardwareTelemetryHandler = Handler(Looper.getMainLooper())
    private var lastTimelineSeekTelemetryElapsedMs = 0L
    private var autoSaveRestorePromptShown = false
    private var pendingVideoReplaceClipId: Int? = null
    private var pendingAudioReplaceClipId: Int? = null
    private var activeImportPickerRequestCode: Int? = null
    private var activeImportPickerOpenedAtElapsedMs = 0L
    private var activeImportPickerStartedFromStartScreen = false
    private var activeImportPickerStartTimeMs = 0L
    private var importPickerAutosaveRestoreAttempted = false
    private var importPickerAutosaveRestoreInProgress = false
    private var importPickerResultInProgress = false
    private var importPickerNativeClipSnapshot: ImportPickerNativeClipSnapshot? = null
    private var lastImmediateAutoSaveElapsedMs = 0L
    private var selectedTimelineImportTrackType: TrackType? = null
    private data class ImportPickerNativeClipSnapshot(
        val states: List<NativeClipUiState>,
    )
    private data class NativeShellClipLayout(
        val id: Int,
        val trackType: TrackType,
        val lane: Int,
        val zOrder: Int,
        val startTimeMs: Long,
        val durationMs: Long,
        val sourceInMs: Long,
        val sourceOutMs: Long,
        val sourcePath: String,
    )
    private val aspectRatioOptions = listOf(
        AspectRatioOption(label = "16:9", width = 16, height = 9, example = "YouTube / TV / Landscape", toolbarLabel = "16:9"),
        AspectRatioOption(label = "21:9", width = 21, height = 9, example = "Cinema widescreen", toolbarLabel = "21:9"),
        AspectRatioOption(label = "1:1", width = 1, height = 1, example = "Square post", toolbarLabel = "1:1"),
        AspectRatioOption(label = "9:16", width = 9, height = 16, example = "Reels / Shorts / TikTok", toolbarLabel = "9:16"),
        AspectRatioOption(label = "4:5", width = 4, height = 5, example = "Instagram portrait post", toolbarLabel = "4:5"),
        AspectRatioOption(label = "5:4", width = 5, height = 4, example = "Landscape social post", toolbarLabel = "5:4"),
        AspectRatioOption(label = "4:3", width = 4, height = 3, example = "Classic camera", toolbarLabel = "4:3"),
        AspectRatioOption(label = "3:4", width = 3, height = 4, example = "Portrait classic", toolbarLabel = "3:4"),
        AspectRatioOption(label = "3:2", width = 3, height = 2, example = "Photo landscape", toolbarLabel = "3:2"),
        AspectRatioOption(label = "2:3", width = 2, height = 3, example = "Photo portrait", toolbarLabel = "2:3"),
        AspectRatioOption(label = "2:1", width = 2, height = 1, example = "Banner / cover", toolbarLabel = "2:1"),
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
    private var selectedAspectRatioIndex = DEFAULT_ASPECT_RATIO_INDEX
    private var aspectRatioManuallySelected = false
    private var lastAppliedAspectWidth = -1
    private var lastAppliedAspectHeight = -1
    private val nativeClipTrackType = mutableMapOf<Int, TrackType>()
    private val nativeClipLane = mutableMapOf<Int, Int>()
    private val nativeClipZOrder = mutableMapOf<Int, Int>()
    private val restoredNativeClipTrackTypeOverrides = mutableMapOf<Int, TrackType>()
    private val restoredNativeClipLaneOverrides = mutableMapOf<Int, Int>()
    private val restoredNativeClipZOrderOverrides = mutableMapOf<Int, Int>()
    private val nativeClipStartMs = mutableMapOf<Int, Long>()
    private val nativeClipDurationMs = mutableMapOf<Int, Long>()
    private val nativeClipSourceInMs = mutableMapOf<Int, Long>()
    private val nativeClipSourceOutMs = mutableMapOf<Int, Long>()
    private val nativeClipSourcePath = mutableMapOf<Int, String>()
    private val previewSourceAspectCache = mutableMapOf<String, Float>()
    private val nativeClipPlaybackSpeed = mutableMapOf<Int, Float>()
    private val nativeClipReversePlayback = mutableMapOf<Int, Boolean>()
    private val nativeClipFreezeFrameEnabled = mutableMapOf<Int, Boolean>()
    private val nativeClipFreezeFrameTimeMs = mutableMapOf<Int, Long>()
    private val nativeClipFreezeFrameDurationMs = mutableMapOf<Int, Long>()
    private val nativeClipCurveSpeedProfile = mutableMapOf<Int, String>()
    private val nativeClipCurveSpeedStrength = mutableMapOf<Int, Float>()
    private var aiAutoCutInFlight = false
    private val clipToolbarItems = listOf(
        ClipToolbarItem(R.id.clipDeleteButton, R.id.clipDeleteLabel, "Delete"),
        ClipToolbarItem(R.id.clipSplitButton, R.id.clipSplitLabel, "Split"),
        ClipToolbarItem(R.id.clipGraphicsButton, R.id.clipGraphicsLabel, "Graphics"),
        ClipToolbarItem(R.id.clipAddLayerButton, R.id.clipAddLayerLabel, "Layer"),
        ClipToolbarItem(R.id.clipAiCaptionButton, R.id.clipAiCaptionLabel, "Caption"),
        ClipToolbarItem(R.id.clipAiTrackButton, R.id.clipAiTrackLabel, "AI Track"),
        ClipToolbarItem(R.id.clipTrimButton, R.id.clipTrimLabel, "Trim"),
        ClipToolbarItem(R.id.clipVolumeButton, R.id.clipVolumeLabel, "Volume"),
        ClipToolbarItem(R.id.clipSpeedButton, R.id.clipSpeedLabel, "Speed"),
        ClipToolbarItem(R.id.clipPanZoomButton, R.id.clipPanZoomLabel, "Pan/Zoom"),
        ClipToolbarItem(R.id.clipFilterButton, R.id.clipFilterLabel, "Effects"),
        ClipToolbarItem(R.id.clipBrightnessButton, R.id.clipBrightnessLabel, "Color"),
        ClipToolbarItem(R.id.clipTransitionButton, R.id.clipTransitionLabel, "Transition"),
        ClipToolbarItem(R.id.clipExtractAudioButton, R.id.clipExtractAudioLabel, "Isolate"),
        ClipToolbarItem(R.id.clipReplaceButton, R.id.clipReplaceLabel, "Replace"),
        ClipToolbarItem(R.id.clipDuplicateButton, R.id.clipDuplicateLabel, "Duplicate"),
        ClipToolbarItem(R.id.clipRotateMirrorButton, R.id.clipRotateMirrorLabel, "Rotate"),
        ClipToolbarItem(R.id.clipKeyframeButton, R.id.clipKeyframeLabel, "Keyframe"),
        ClipToolbarItem(R.id.clipReverseButton, R.id.clipReverseLabel, "Reverse"),
        ClipToolbarItem(R.id.clipFreezeFrameButton, R.id.clipFreezeFrameLabel, "Freeze"),
        ClipToolbarItem(R.id.clipDuckingButton, R.id.clipDuckingLabel, "Ducking"),
        ClipToolbarItem(R.id.clipCurveSpeedButton, R.id.clipCurveSpeedLabel, "Curve"),
        ClipToolbarItem(R.id.clipChromaKeyButton, R.id.clipChromaKeyLabel, "AI Matte"),
        ClipToolbarItem(R.id.clipCutoutButton, R.id.clipCutoutLabel, "AI Cut"),
    )
    private data class AiAutoCutPreset(
        val label: String,
        val targetSegmentMs: Long,
        val minSegmentMs: Long,
        val edgeGuardMs: Long,
        val maxCuts: Int,
        val cadencePattern: List<Float>,
    )

    private data class AiAutoCutSelection(
        val kind: ClipKind,
        val clipId: Int,
        val startTimeMs: Long,
        val durationMs: Long,
        val label: String,
    )

    private val aiAutoCutPresets = listOf(
        AiAutoCutPreset("Punchy", 1350L, 850L, 260L, 10, listOf(0.92f, 1.08f, 0.84f, 1.12f, 0.96f)),
        AiAutoCutPreset("Balanced", 2200L, 1300L, 320L, 8, listOf(0.96f, 1.04f, 0.88f, 1.10f, 1.0f)),
        AiAutoCutPreset("Scene", 3400L, 2200L, 420L, 6, listOf(1.0f, 1.12f, 0.9f, 1.06f)),
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
            ghostLongEdgePx = maxOf(DeviceDetector.getRecommendedGhostLongEdgePx(), 720),
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
        val photoEditorBtn = findViewById<android.view.View?>(R.id.startPhotoEditorButton)
        val openProjectBtn = findViewById<android.view.View>(R.id.startOpenProjectButton)
        val importMediaBtn = findViewById<android.view.View>(R.id.startImportMediaButton)
        startRecentProjectsList = findViewById(R.id.startRecentProjectsList)
        startRecentProjectsEmptyText = findViewById(R.id.startRecentProjectsEmptyText)
        startRecentPhotoText = findViewById(R.id.startRecentPhotoText)
        startAiSummaryText = findViewById(R.id.startAiSummaryText)
        startAiUpdateText = findViewById(R.id.startAiUpdateText)
        startAiUpdateButton = findViewById(R.id.startAiUpdateButton)
        startAiAdminButton = findViewById(R.id.startAiAdminButton)
        editorAiStatusPill = findViewById(R.id.editorAiStatusPill)
        clipToolbarContextLabel = findViewById(R.id.audioEditLabel)
        clipToolbarScrollView = findViewById(R.id.audioEditToolbarScroll)
        clipToolbarScrollView?.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            clipToolbarScrollX = scrollX.coerceAtLeast(0)
        }

        newProjectBtn.setOnClickListener {
            if (startShellOnly) {
                launchFullEditorFromStartShell(EditorLaunchIntents.START_ACTION_NEW)
                return@setOnClickListener
            }
            noteUiButtonTap("start_new_project", "start_screen")
            if (hasProjectContent()) {
                saveCurrentSessionNow("before_new_video_project")
            }
            startBlankProject(showToast = true)
        }

        photoEditorBtn?.setOnClickListener {
            noteUiButtonTap("start_photo_editor", "start_screen")
            if (hasProjectContent()) {
                saveCurrentSessionNow("before_new_photo_project")
            }
            openPhotoProjectFromStart(projectFile = null, resume = false)
        }

        openProjectBtn.setOnClickListener {
            if (startShellOnly) {
                launchFullEditorFromStartShell(EditorLaunchIntents.START_ACTION_OPEN)
                return@setOnClickListener
            }
            noteUiButtonTap("start_open_project", "start_screen")
            projectController?.showLoadProjectDialog()
        }

        importMediaBtn.setOnClickListener {
            if (startShellOnly) {
                launchFullEditorFromStartShell(EditorLaunchIntents.START_ACTION_IMPORT)
                return@setOnClickListener
            }
            noteUiButtonTap("start_import_media", "start_screen")
            openStartScreenVideoImport()
        }

        startAiUpdateButton?.setOnClickListener {
            noteUiButtonTap("ai_open_update", "ai_status_card")
            openExternalUrl(resolveUpdateTargetUrl())
        }

        startAiAdminButton?.setOnClickListener {
            noteUiButtonTap("ai_open_admin", "ai_status_card")
            openExternalUrl(resolveAdminPanelUrl())
        }

        val hostedUpdatesEnabled = storeHostedUpdatesEnabled()
        startAiUpdateButton?.visibility = if (hostedUpdatesEnabled) View.VISIBLE else View.GONE
        startAiAdminButton?.visibility = if (hostedUpdatesEnabled) View.VISIBLE else View.GONE

        startRecentProjectsList?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        startRecentProjectsList?.adapter =
            ProjectListAdapter(emptyList(), onProjectClick = { })
        refreshRecentPhotoProjects()
        refreshAiCompanionUi()
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            refreshRecentProjects()
            refreshRecentPhotoProjects()
            if (hostedUpdatesEnabled) {
                fetchHostedReleaseInfo(force = true)
            }
        }, 1_600L)
    }

    private fun setStartScreenVisible(visible: Boolean) {
        if (visible && !startShellOnly) {
            returnToSingleHome("embedded_home_request")
            return
        }
        val overlay = startScreenOverlayView ?: findViewById<View?>(R.id.startScreenOverlay) ?: return
        startScreenOverlayView = overlay
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
        setEditorChromeVisible(!visible)
        if (visible) {
            playbackController?.nativePause()
            effectSlidersContainer?.visibility = View.GONE
            clearSelectedTimelineItem()
            refreshRecentProjects()
            refreshRecentPhotoProjects()
            overlay.bringToFront()
            if (storeHostedUpdatesEnabled()) {
                fetchHostedReleaseInfo()
            }
        }
        scheduleTopBannerPlacement()
        updatePreviewEmptyState()
        refreshAiCompanionUi()
        syncAppHealth(force = true, action = if (visible) "home_visible" else "editor_visible")
    }

    private fun buildRecentProjectFiles(): List<File> {
        return RecentProjectFiles.all(this)
    }

    private fun refreshTopBannerPlacement() {
        val ads = adsController ?: return
        fun releaseEditorBanner(reserveSpace: Boolean = false) {
            editorTopBannerContainer?.let { editorHost ->
                ads.releaseBanner(editorHost)
                configureEditorBannerSlot(reserveSpace)
            }
        }
        fun releaseAllBanners(reserveEditorSpace: Boolean = false) {
            startTopBannerContainer?.let(ads::releaseBanner)
            releaseEditorBanner(reserveEditorSpace)
            activeTopBannerHostId = null
        }
        if (!storeStartupAdsEnabled()) {
            releaseAllBanners()
            return
        }
        val homeVisible = startScreenOverlayView?.visibility == View.VISIBLE
        if (!homeVisible) {
            val target = editorTopBannerContainer
            if (!selectedPreviewAspectIs16By9() || target == null) {
                releaseAllBanners()
                return
            }
            startTopBannerContainer?.let(ads::releaseBanner)
            if (shouldDeferAdsForSmoothEditing()) {
                releaseEditorBanner(reserveSpace = true)
                activeTopBannerHostId = null
                scheduleTopBannerPlacement(delayMs = nextAdsRetryDelayMs())
                return
            }
            configureEditorBannerSlot(reserveSpace = true)
            if (activeTopBannerHostId == target.id) {
                target.visibility = View.VISIBLE
                return
            }
            ads.attachTopBanner(target)
            activeTopBannerHostId = target.id
            return
        }
        val target = startTopBannerContainer
        if (target == null) {
            releaseAllBanners()
            return
        }
        if (activeTopBannerHostId == target.id) {
            target.visibility = View.VISIBLE
            releaseEditorBanner()
            return
        }
        releaseEditorBanner()
        ads.attachTopBanner(target)
        activeTopBannerHostId = target.id
    }

    private fun selectedPreviewAspectIs16By9(): Boolean {
        val option = aspectRatioOptions.getOrNull(selectedAspectRatioIndex) ?: return false
        return option.width == 16 && option.height == 9
    }

    private fun configureEditorBannerSlot(reserveSpace: Boolean) {
        val slot = editorTopBannerContainer ?: return
        val targetHeight = if (reserveSpace) {
            previewDp(56f).roundToInt()
        } else {
            FrameLayout.LayoutParams.WRAP_CONTENT
        }
        var layoutChanged = false
        slot.layoutParams?.let { params ->
            if (params.height != targetHeight) {
                params.height = targetHeight
                slot.layoutParams = params
                layoutChanged = true
            }
        }
        val targetVisibility = when {
            reserveSpace && slot.childCount == 0 -> View.INVISIBLE
            reserveSpace -> View.VISIBLE
            else -> View.GONE
        }
        if (slot.visibility != targetVisibility) {
            slot.visibility = targetVisibility
            layoutChanged = true
        }
        if (layoutChanged) {
            lastAppliedAspectWidth = -1
            lastAppliedAspectHeight = -1
            slot.post { applyPreviewAspectRatio() }
        }
    }

    private fun scheduleTopBannerPlacement(delayMs: Long = 900L) {
        if (isAutomationPerfMode()) return
        mainHandler.removeCallbacks(bannerRefreshRunnable)
        mainHandler.postDelayed(bannerRefreshRunnable, delayMs)
    }

    private fun deferAdsForSmoothEditing(windowMs: Long = 45_000L) {
        if (isAutomationPerfMode()) return
        adsDeferredUntilElapsedMs =
            maxOf(adsDeferredUntilElapsedMs, SystemClock.elapsedRealtime() + windowMs.coerceAtLeast(5_000L))
        mainHandler.removeCallbacks(bannerRefreshRunnable)
        mainHandler.removeCallbacks(rewardedPreloadRunnable)
    }

    private fun shouldDeferAdsForSmoothEditing(): Boolean {
        if (isAutomationPerfMode()) return true
        return isPlaying ||
            isPreviewInteractionBusy() ||
            SystemClock.elapsedRealtime() < adsDeferredUntilElapsedMs
    }

    private fun nextAdsRetryDelayMs(): Long {
        val remainingMs = (adsDeferredUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return remainingMs.coerceIn(15_000L, 90_000L)
    }

    private fun scheduleRewardedUnlockPreload(delayMs: Long = 30_000L) {
        if (!storeAdsEnabled() || isAutomationPerfMode()) return
        mainHandler.removeCallbacks(rewardedPreloadRunnable)
        mainHandler.postDelayed(rewardedPreloadRunnable, delayMs)
    }

    private fun maybePreloadRewardedUnlock() {
        if (isFinishing || isDestroyed || !storeAdsEnabled() || isAutomationPerfMode()) return
        if (shouldDeferAdsForSmoothEditing()) {
            scheduleRewardedUnlockPreload(delayMs = nextAdsRetryDelayMs())
            return
        }
        rewardedUnlockController?.preload()
    }

    private fun hasRecentAutomationIntent(windowMs: Long = 180_000L): Boolean {
        return lastAutomationToken != null &&
            SystemClock.elapsedRealtime() - lastAutomationTokenElapsedMs < windowMs
    }

    private fun schedulePostExportInterstitial(delayMs: Long = 4_000L) {
        if (!storeAdsEnabled() || isAutomationPerfMode() || hasRecentAutomationIntent()) {
            postExportInterstitialPending = false
            postExportInterstitialRetryCount = 0
            return
        }
        postExportInterstitialPending = true
        postExportInterstitialRetryCount = 0
        adsController?.preloadPostExportInterstitial()
        postPostExportInterstitialCheck(delayMs)
    }

    private fun postPostExportInterstitialCheck(delayMs: Long) {
        mainHandler.removeCallbacks(postExportInterstitialRunnable)
        mainHandler.postDelayed(postExportInterstitialRunnable, delayMs.coerceAtLeast(2_000L))
    }

    private fun maybeShowPostExportInterstitial() {
        if (!postExportInterstitialPending) return
        if (isFinishing || isDestroyed || !storeAdsEnabled() || isAutomationPerfMode() || hasRecentAutomationIntent()) {
            postExportInterstitialPending = false
            postExportInterstitialRetryCount = 0
            return
        }
        if (isPlaying || isPreviewInteractionBusy()) {
            postPostExportInterstitialCheck(delayMs = 15_000L)
            return
        }
        val ads = adsController ?: run {
            postExportInterstitialPending = false
            postExportInterstitialRetryCount = 0
            return
        }
        if (!ads.isPostExportInterstitialReady()) {
            ads.preloadPostExportInterstitial()
            if (postExportInterstitialRetryCount < 8) {
                postExportInterstitialRetryCount += 1
                Log.d(TAG, "Post-export interstitial not ready, retry=${postExportInterstitialRetryCount}")
                postPostExportInterstitialCheck(delayMs = 5_000L)
                return
            }
            Log.d(TAG, "Post-export interstitial skipped after retries")
            postExportInterstitialPending = false
            postExportInterstitialRetryCount = 0
            return
        }
        val shown = ads.showPostExportInterstitial()
        if (shown) {
            postExportInterstitialPending = false
            postExportInterstitialRetryCount = 0
            return
        }
        if (postExportInterstitialRetryCount < 8) {
            postExportInterstitialRetryCount += 1
            postPostExportInterstitialCheck(delayMs = 5_000L)
        } else {
            postExportInterstitialPending = false
            postExportInterstitialRetryCount = 0
        }
    }

    private fun refreshRecentProjects() {
        val projectsList = startRecentProjectsList ?: return
        Thread {
            val projectFiles = buildRecentProjectFiles()
            projectsList.post {
                if (isFinishing || isDestroyed) return@post
                if (projectFiles.isNotEmpty()) {
                    startRecentProjectsEmptyText?.visibility = View.GONE
                    projectsList.visibility = View.VISIBLE
                    projectsList.adapter = ProjectListAdapter(
                        projects = projectFiles,
                        onProjectClick = { project ->
                            loadProjectFromStartAction(project.absolutePath)
                        },
                        onProjectDelete = { project ->
                            confirmDeleteVideoProject(project)
                        },
                    )
                    return@post
                }
                projectsList.visibility = View.GONE
                projectsList.adapter =
                    ProjectListAdapter(emptyList(), onProjectClick = { })
                startRecentProjectsEmptyText?.apply {
                    visibility = View.VISIBLE
                    text = getString(R.string.recent_project_button_empty)
                    isEnabled = false
                    isClickable = false
                    isFocusable = false
                    alpha = 0.62f
                    setOnClickListener(null)
                }
            }
        }.start()
    }

    private fun refreshRecentPhotoProjects() {
        val recentPhotoText = startRecentPhotoText ?: return
        Thread {
            val photoFiles = PhotoProjectStore.all(this)
            recentPhotoText.post {
                if (isFinishing || isDestroyed) return@post
                if (photoFiles.isEmpty()) {
                    recentPhotoText.text = getString(R.string.recent_photo_button_empty)
                    recentPhotoText.isEnabled = false
                    recentPhotoText.isClickable = false
                    recentPhotoText.isFocusable = false
                    recentPhotoText.alpha = 0.62f
                    recentPhotoText.setOnClickListener(null)
                    return@post
                }
                recentPhotoText.text = getString(R.string.recent_photo_button_resume)
                recentPhotoText.isEnabled = true
                recentPhotoText.isClickable = true
                recentPhotoText.isFocusable = true
                recentPhotoText.alpha = 1f
                recentPhotoText.setOnClickListener {
                    showRecentPhotoProjectsDialog(photoFiles)
                }
            }
        }.start()
    }

    private fun openPhotoProjectFromStart(projectFile: File?, resume: Boolean) {
        if (!resume) {
            PhotoProjectStore.startNewProject(this)
        } else if (projectFile != null) {
            PhotoProjectStore.setActiveProject(this, projectFile)
        }
        val intent = Intent(this, PhotoEditorActivity::class.java)
            .putExtra(PhotoEditorActivity.EXTRA_RESUME_PHOTO, resume)
        projectFile?.let {
            intent.putExtra(PhotoEditorActivity.EXTRA_PHOTO_PROJECT_PATH, it.absolutePath)
        }
        startActivity(intent)
    }

    private fun showRecentPhotoProjectsDialog(projects: List<File>) {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Recent Photo Projects")
            .setItems(projects.map(::formatPhotoProjectRow).toTypedArray()) { _, which ->
                projects.getOrNull(which)?.let { project ->
                    openPhotoProjectFromStart(projectFile = project, resume = true)
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                showDeletePhotoProjectDialog(projects)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeletePhotoProjectDialog(projects: List<File>) {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete Photo Project")
            .setItems(projects.map(::formatPhotoProjectRow).toTypedArray()) { _, which ->
                projects.getOrNull(which)?.let(::confirmDeletePhotoProject)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteVideoProject(project: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete video draft?")
            .setMessage("This removes the saved video draft and its editor state.")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = RecentProjectFiles.delete(this, project)
                Toast.makeText(this, if (deleted) "Video draft deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                refreshRecentProjects()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePhotoProject(project: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete photo project?")
            .setMessage("This removes the saved photo project.")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = PhotoProjectStore.delete(this, project)
                Toast.makeText(this, if (deleted) "Photo project deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                refreshRecentPhotoProjects()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatPhotoProjectRow(file: File): String {
        return "${PhotoProjectStore.displayName(file)}\n${formatUpdatedAt(file)}"
    }

    private fun formatUpdatedAt(file: File): String {
        val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        return "Updated $date"
    }

    private fun fetchHostedReleaseInfo(force: Boolean = false) {
        if (!storeHostedUpdatesEnabled()) {
            hostedReleaseFetchInFlight = false
            hostedReleaseInfo = null
            refreshAiCompanionUi()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && hostedReleaseFetchInFlight) return
        if (!force && now - lastHostedReleaseFetchElapsedMs < 90_000L) {
            refreshAiCompanionUi()
            return
        }
        val portalUrl = getString(R.string.release_portal_url).trim().trimEnd('/')
        if (portalUrl.isBlank()) {
            refreshAiCompanionUi()
            return
        }
        hostedReleaseFetchInFlight = true
        lastHostedReleaseFetchElapsedMs = now
        refreshAiCompanionUi()
        commandExecutor.execute {
            val fetched = runCatching { loadHostedReleaseInfo(portalUrl) }.getOrNull()
            mainHandler.post {
                hostedReleaseFetchInFlight = false
                if (fetched != null) {
                    hostedReleaseInfo = fetched
                }
                refreshAiCompanionUi()
            }
        }
    }

    private fun loadHostedReleaseInfo(portalUrl: String): HostedReleaseInfo? {
        val connection =
            (URL("$portalUrl/build.json").openConnection() as? HttpURLConnection)
                ?: return null
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(payload)
            val versionName = json.optString("versionName", "unknown")
            val versionCode = json.optInt("versionCode", 0)
            val builtAtRaw = json.optString("builtAt")
            val commitSubject = json.optString("commitSubject")
            val downloadPath = json.optString("downloadPath", "/app.apk")
            val adminPanelUrl = json.optString("adminPanelUrl", getString(R.string.admin_panel_url))
            val downloadUrl =
                if (downloadPath.startsWith("http://") || downloadPath.startsWith("https://")) {
                    downloadPath
                } else {
                    portalUrl + if (downloadPath.startsWith("/")) downloadPath else "/$downloadPath"
                }
            HostedReleaseInfo(
                versionName = versionName,
                versionCode = versionCode,
                builtAtRaw = builtAtRaw,
                builtAtLabel = formatHostedBuildTime(builtAtRaw),
                commitSubject = commitSubject,
                downloadUrl = downloadUrl,
                portalUrl = portalUrl,
                adminPanelUrl = adminPanelUrl,
                updateAvailable = versionCode > appVersionCode() || versionName != appVersionName(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun formatHostedBuildTime(raw: String): String {
        if (raw.isBlank()) return "recently"
        val inputPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        )
        inputPatterns.forEach { pattern ->
            runCatching {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = parser.parse(raw) ?: return@runCatching null
                SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(parsed)
            }.getOrNull()?.let { return it }
        }
        return raw
    }

    private fun humanizeHealthAction(action: String): String =
        action.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

    private fun resolveUpdateTargetUrl(): String {
        val release = hostedReleaseInfo
        return if (release?.updateAvailable == true) {
            release.downloadUrl
        } else {
            release?.portalUrl ?: getString(R.string.release_portal_url).trim()
        }
    }

    private fun resolveAdminPanelUrl(): String =
        hostedReleaseInfo?.adminPanelUrl?.takeIf { it.isNotBlank() }
            ?: getString(R.string.admin_panel_url).trim()

    private fun openExternalUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            safeToast("Unable to open link", Toast.LENGTH_SHORT)
        }
    }

    private fun refreshAiCompanionUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshAiCompanionUi() }
            return
        }
        if (!storeOpsEnabled() && !storeHostedUpdatesEnabled()) {
            startAiSummaryText?.text =
                "This store build runs the local editor only. Hosted updates, remote commands, and device telemetry are disabled."
            startAiUpdateText?.text =
                if (storeAdsEnabled()) {
                    "Remote services are unavailable for this distribution build."
                } else {
                    "Ads and remote services are disabled for this distribution build."
                }
            startAiUpdateButton?.visibility = View.GONE
            startAiAdminButton?.visibility = View.GONE
            editorAiStatusPill?.text = "LOCAL BUILD"
            return
        }

        val actionLabel = humanizeHealthAction(latestHealthAction.ifBlank { "launch" })
        val screen = currentHealthScreenName()
        val summary =
            when {
                screen == "crop" -> "AI is tracking crop gestures, freezes, and export path. Last action: $actionLabel."
                isPlaying -> "AI is checking playback for lag, stalls, and sync issues. Last action: $actionLabel."
                hasProjectContent() -> "AI is checking this edit for dead buttons, exports, and new builds. Last action: $actionLabel."
                else -> "AI is tracking playback, export, device health, and hosted updates. Last action: $actionLabel."
            }
        startAiSummaryText?.text = summary

        val hosted = hostedReleaseInfo
        startAiUpdateText?.text =
            when {
                hosted == null && hostedReleaseFetchInFlight -> getString(R.string.ai_status_update_checking)
                hosted == null -> "Hosted update: latest build unavailable right now."
                hosted.updateAvailable -> buildString {
                    append("Update ready: v")
                    append(hosted.versionName)
                    append(" (")
                    append(hosted.versionCode)
                    append(") • ")
                    append(hosted.builtAtLabel)
                    if (hosted.commitSubject.isNotBlank()) {
                        append(" • ")
                        append(hosted.commitSubject)
                    }
                }
                else -> buildString {
                    append("Hosted build live: v")
                    append(hosted.versionName)
                    append(" (")
                    append(hosted.versionCode)
                    append(") • ")
                    append(hosted.builtAtLabel)
                    if (hosted.commitSubject.isNotBlank()) {
                        append(" • ")
                        append(hosted.commitSubject)
                    }
                }
            }

        startAiUpdateButton?.text =
            if (hosted?.updateAvailable == true) {
                getString(R.string.ai_status_download_update_action)
            } else {
                getString(R.string.ai_status_open_update_action)
            }

        editorAiStatusPill?.text =
            when {
                hosted?.updateAvailable == true -> getString(R.string.ai_status_pill_update)
                screen == "crop" -> getString(R.string.ai_status_pill_crop)
                isPlaying -> getString(R.string.ai_status_pill_playback)
                screen == "home" -> getString(R.string.ai_status_pill_home)
                else -> getString(R.string.ai_status_pill_editor)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldUseStartShell(savedInstanceState, intent)) {
            Log.d(TAG, "onCreate - redirecting direct MainActivity home to LaunchActivity")
            returnToSingleHome("direct_main_start")
            return
        }
        startupHeavyWorkDeferredUntilElapsedMs = SystemClock.elapsedRealtime() + 2_200L
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
        editorAiStatusPill = findViewById(R.id.editorAiStatusPill)
        adsController = AdsController(this)
        if (storeAdsEnabled()) {
            adsController?.preloadPostExportInterstitial()
        }
        rewardedUnlockController = RewardedUnlockController(this)
        setupStartScreen()
        scheduleRewardedUnlockPreload(delayMs = 30_000L)
        if (storeStartupAdsEnabled()) {
            scheduleTopBannerPlacement(delayMs = 6_500L)
        }
        window.decorView.postDelayed({ CrashAutoFix.retryPending(this) }, 12000L)
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
        }
        debugTelemetryManager = DebugTelemetryManager(this)
        opsReporter = OpsReporter(
            this,
            {
                if (::debugTelemetryManager.isInitialized) debugTelemetryManager.sessionId else "unknown"
            },
        ) { updatedKeys ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (
                    updatedKeys.isEmpty() ||
                    "ops_health_heartbeat_interval_ms" in updatedKeys
                ) {
                    startAppHealthHeartbeat()
                }
                if (
                    updatedKeys.isEmpty() ||
                    updatedKeys.any {
                        it == "ops_health_heartbeat_interval_ms" ||
                            it == "ops_force_flush_actions" ||
                            it == "ops_perf_tracing_enabled"
                    }
                ) {
                    noteAppHealthAction("remote_config_applied", force = true)
                }
            }
        }
        appHealthReporter = AppHealthReporter(this)
        problemReportManager = ProblemReportManager(this)
        playbackExportIssueDetector = PlaybackExportIssueDetector(
            onIssueDetected = { reportType, source, description ->
                if (!isFinishing && !isDestroyed) {
                    submitAutoDetectorIssue(
                        reportType = reportType,
                        source = source,
                        description = description,
                    )
                }
            },
        )
        uiActionExpectationDetector = UiActionExpectationDetector(
            onIssueDetected = { reportType, source, description ->
                if (!isFinishing && !isDestroyed) {
                    submitAutoDetectorIssue(
                        reportType = reportType,
                        source = source,
                        description = description,
                    )
                }
            },
        )
        remoteCommandManager = RemoteCommandManager(
            context = this,
            installationIdProvider = { appHealthReporter.installationId() },
            sessionIdProvider = { debugTelemetryManager.sessionId },
            onExecute = ::executePhoneCommand,
        )
        uiFreezeWatchdog = UiFreezeWatchdog { stallMs ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                recordTelemetryEvent(
                    category = "ops",
                    name = "ui_freeze_detected",
                    payload = JSONObject()
                        .put("stallMs", stallMs)
                        .put("screen", currentHealthScreenName())
                        .put("lastAction", latestHealthAction),
                )
                if (::opsReporter.isInitialized) {
                    opsReporter.noteAction(
                        action = "ui_freeze_detected",
                        metadata = mapOf(
                            "stall_ms" to stallMs,
                            "screen" to currentHealthScreenName(),
                            "last_action" to latestHealthAction,
                        ),
                    )
                }
                problemReportManager.submitFreezeReport(
                    stallMs = stallMs,
                    sessionId = debugTelemetryManager.sessionId,
                    currentScreen = currentHealthScreenName(),
                    lastAction = latestHealthAction,
                    hasProjectContent = hasProjectContent(),
                    isPlaying = isPlaying,
                    versionName = appVersionName(),
                    versionCode = appVersionCode(),
                )
            }
        }
        appHealthReporter.bindSession(debugTelemetryManager.sessionId)
        if (!isAutomationPerfMode()) {
            remoteCommandManager.start()
        }
        NativeBridge.setTelemetrySink { debugTelemetryManager.recordNativeBridgeTelemetry(it) }
        NativeBridge.clearNativeCommandTelemetry()
        recordTelemetryEvent(
            category = "session",
            name = "main_activity_created",
            payload = JSONObject().put("savedInstanceState", savedInstanceState != null),
        )
        activeImportPickerRequestCode =
            savedInstanceState
                ?.takeIf { it.containsKey(STATE_ACTIVE_IMPORT_PICKER_REQUEST_CODE) }
                ?.getInt(STATE_ACTIVE_IMPORT_PICKER_REQUEST_CODE)
        activeImportPickerOpenedAtElapsedMs =
            savedInstanceState?.getLong(STATE_ACTIVE_IMPORT_PICKER_OPENED_AT_MS, 0L) ?: 0L
        activeImportPickerStartedFromStartScreen =
            savedInstanceState?.getBoolean(STATE_ACTIVE_IMPORT_PICKER_STARTED_FROM_START, false) == true
        activeImportPickerStartTimeMs =
            savedInstanceState?.getLong(STATE_ACTIVE_IMPORT_PICKER_START_TIME_MS, 0L)?.coerceAtLeast(0L) ?: 0L
        savedInstanceState
            ?.getString(STATE_SELECTED_IMPORT_TRACK_TYPE)
            ?.let { rawTrackType ->
                TrackType.values().firstOrNull { it.name == rawTrackType }
            }
            ?.let(::setSelectedImportTrackType)
        savedInstanceState?.let { state ->
            if (state.containsKey(STATE_SELECTED_ASPECT_RATIO_INDEX)) {
                selectedAspectRatioIndex =
                    state.getInt(STATE_SELECTED_ASPECT_RATIO_INDEX, DEFAULT_ASPECT_RATIO_INDEX)
                        .coerceIn(0, aspectRatioOptions.lastIndex)
                aspectRatioManuallySelected = state.getBoolean(STATE_ASPECT_RATIO_MANUALLY_SELECTED, false)
            }
        }
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                syncAppHealth(force = true, action = "main_activity_created")
            }
        }, 1_800L)

        // Get UI references
        val previewContainer = findViewById<FrameLayout>(R.id.previewContainer)
        val previewStageHost = findViewById<FrameLayout>(R.id.previewStageHost)
        previewContainerView = previewStageHost
        previewViewportFrame =
            FrameLayout(this).apply {
                clipChildren = true
                clipToPadding = true
            }
        previewEmptyStateView = findViewById(R.id.previewEmptyState)
        previewCropOverlayView = findViewById(R.id.previewCropOverlay)
        (previewCropOverlayView as? FrameLayout)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        previewCropFrameGuideView = findViewById(R.id.previewCropFrameGuide)
        previewCropStatusText = findViewById(R.id.previewCropStatus)
        previewTrimStartHandleView = findViewById(R.id.previewCropTrimStartHandle)
        previewTrimEndHandleView = findViewById(R.id.previewCropTrimEndHandle)
        previewResizeTopLeftHandleView = findViewById(R.id.previewCropResizeTopLeftHandle)
        previewResizeTopHandleView = findViewById(R.id.previewCropResizeTopHandle)
        previewResizeTopRightHandleView = findViewById(R.id.previewCropResizeTopRightHandle)
        previewResizeRightHandleView = findViewById(R.id.previewCropResizeRightHandle)
        previewResizeBottomLeftHandleView = findViewById(R.id.previewCropResizeBottomLeftHandle)
        previewResizeBottomHandleView = findViewById(R.id.previewCropResizeBottomHandle)
        previewResizeBottomRightHandleView = findViewById(R.id.previewCropResizeBottomRightHandle)
        previewResizeLeftHandleView = findViewById(R.id.previewCropResizeLeftHandle)
        previewProGuidesOverlayView = findViewById(R.id.previewProGuidesOverlay)
        previewThirdsGuideView = findViewById(R.id.previewThirdsGuide)
        previewSafeAreaGuideView = findViewById(R.id.previewSafeAreaGuide)
        previewHudView = findViewById(R.id.previewHud)
        previewHudStatusText = findViewById(R.id.topPreviewStatus)
        previewFrameInfoChip = findViewById(R.id.previewFrameInfoChip)
        previewGridToggleButton = findViewById(R.id.topPreviewGridToggle)
        previewSafeToggleButton = findViewById(R.id.topPreviewSafeToggle)
        previewFitFillToggleButton = findViewById(R.id.topPreviewFitFillToggle)
        previewQualityToggleButton = findViewById(R.id.topPreviewQualityToggle)
        val bottomContainer = findViewById<View>(R.id.bottomContainer)
        val timelineLayout = findViewById<View>(R.id.timelineLayout)
        timelineCurrentTimeText = findViewById(R.id.timelineCurrentTimeText)
        timelineCurrentTimeText?.visibility = View.VISIBLE
        updateTimelineTimeText(0L)
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
                notePreviewInteractionBusy(EDITOR_INTERACTION_BUSY_WINDOW_MS)
                if (update.clipId.startsWith("text-") || update.clipId.startsWith("sticker-")) {
                    if (applyLocalClipTimingUpdate(toMultiTrackClipUpdate(update), refreshVisualState = false)) {
                        return
                    }
                }
                val safeUpdate = clampTimelineCanvasClipUpdateToSource(update, allowSourceProbe = false)
                val videoClipId = parseNativeClipId(update.clipId) ?: return
                if (safeUpdate.gestureKind == com.video.engine.pro.timeline.ClipGestureKind.MOVE) {
                    nativeClipTrackType[videoClipId] = safeUpdate.trackType
                    restoredNativeClipTrackTypeOverrides[videoClipId] = safeUpdate.trackType
                    if (safeUpdate.targetLane >= 0) {
                        nativeClipLane[videoClipId] = safeUpdate.targetLane
                        restoredNativeClipLaneOverrides[videoClipId] = safeUpdate.targetLane
                    }
                    if (safeUpdate.targetZOrder != Int.MIN_VALUE) {
                        val normalizedZ = normalizeTrackZOrder(safeUpdate.trackType, safeUpdate.targetZOrder)
                        nativeClipZOrder[videoClipId] = normalizedZ
                        restoredNativeClipZOrderOverrides[videoClipId] = normalizedZ
                    }
                }
                videoClipTimingOverrides[videoClipId] = ClipTimingSnapshot(
                    startTimeMs = safeUpdate.startTimeMs, durationMs = safeUpdate.durationMs,
                    sourceInMs = safeUpdate.sourceInMs, sourceOutMs = safeUpdate.sourceOutMs,
                )
            }
            override fun onClipUpdateCommitted(update: com.video.engine.pro.timeline.ClipUpdate) {
                val mtUpdate = toMultiTrackClipUpdate(update)
                if (update.clipId.startsWith("text-") || update.clipId.startsWith("sticker-")) {
                    if (applyLocalClipTimingUpdate(mtUpdate)) {
                        recordUndoDomain(UndoDomain.EDITOR)
                        recordTelemetryEvent(
                            "timeline",
                            "canvas_clip_update_committed_local",
                            buildClipUpdateTelemetry(mtUpdate)
                                .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
                        )
                        refreshMainTimelineTracks()
                        scheduleTimelineIdleAutoSave("timeline_canvas_local_commit")
                    }
                    return
                }
                val safeUpdate = clampTimelineCanvasClipUpdateToSource(update, allowSourceProbe = true)
                val videoClipId = parseNativeClipId(safeUpdate.clipId) ?: return
                // Reuse existing commit logic via the MultiTrackTimelineView listener path
                // Use async command to avoid blocking main thread (ANR fix)
                if (safeUpdate.gestureKind == com.video.engine.pro.timeline.ClipGestureKind.MOVE) {
                    val requestedLane = safeUpdate.targetLane.takeIf { it >= 0 } ?: nativeClipLane[videoClipId] ?: 0
                    val requestedZ = normalizeTrackZOrder(
                        safeUpdate.trackType,
                        safeUpdate.targetZOrder.takeIf { it != Int.MIN_VALUE }
                            ?: nativeClipZOrder[videoClipId]
                            ?: safeUpdate.trackType.defaultZOrder(requestedLane),
                    )
                    if (
                        nativeClipTrackType[videoClipId] != safeUpdate.trackType ||
                        nativeClipLane[videoClipId] != requestedLane ||
                        nativeClipZOrder[videoClipId] != requestedZ
                    ) {
                        nativeClipTrackType[videoClipId] = safeUpdate.trackType
                        nativeClipLane[videoClipId] = requestedLane
                        nativeClipZOrder[videoClipId] = requestedZ
                        restoredNativeClipTrackTypeOverrides[videoClipId] = safeUpdate.trackType
                        restoredNativeClipLaneOverrides[videoClipId] = requestedLane
                        restoredNativeClipZOrderOverrides[videoClipId] = requestedZ
                        runCatching {
                            NativeBridge.executeCommandAsync(
                                action = "SET_CLIP_TRACK",
                                params = mapOf(
                                    "clipId" to videoClipId,
                                    "trackType" to safeUpdate.trackType.nativeRoleName(),
                                    "trackLane" to requestedLane,
                                    "zOrder" to requestedZ,
                                ),
                            )
                        }
                    }
                }
                runCatching {
                    NativeBridge.executeCommandAsync(
                        action = "UPDATE_CLIP_TIMING",
                        params = mapOf(
                            "clipId" to videoClipId,
                            "newStartTimeMs" to safeUpdate.startTimeMs,
                            "newDurationMs" to safeUpdate.durationMs,
                            "newSourceInMs" to safeUpdate.sourceInMs,
                            "newSourceOutMs" to safeUpdate.sourceOutMs,
                            "originalStartTimeMs" to safeUpdate.originalStartTimeMs,
                            "originalDurationMs" to safeUpdate.originalDurationMs,
                            "originalSourceInMs" to safeUpdate.originalSourceInMs,
                            "originalSourceOutMs" to safeUpdate.originalSourceOutMs,
                            "previewOnly" to false,
                            "applyMagnetic" to (safeUpdate.trackType == TrackType.VIDEO),
                        ),
                    )
                }
                nativeClipStartMs[videoClipId] = safeUpdate.startTimeMs
                nativeClipDurationMs[videoClipId] = safeUpdate.durationMs
                nativeClipSourceInMs[videoClipId] = safeUpdate.sourceInMs
                nativeClipSourceOutMs[videoClipId] = safeUpdate.sourceOutMs
                videoClipTimingOverrides[videoClipId] = ClipTimingSnapshot(
                    startTimeMs = safeUpdate.startTimeMs,
                    durationMs = safeUpdate.durationMs,
                    sourceInMs = safeUpdate.sourceInMs,
                    sourceOutMs = safeUpdate.sourceOutMs,
                )
                lastLayoutFetchMs = 0L
                refreshMainTimelineTracks()
                scheduleTimelineIdleAutoSave("timeline_canvas_commit")
            }
            override fun onZoomChanged(pxPerSecond: Float) {
                runCatching { NativeBridge.setTimelineZoomPxPerSecond(pxPerSecond) }
            }
            override fun onClipSelected(clipId: String?) {
                selectedTimelineClipKey = normalizeTimelineSelectionKey(clipId)
                syncSelectedImportTrackFromSelection(selectedTimelineClipKey)
                updateBottomToolbarMode()
                revealSelectedClipInPreview()
            }
            override fun onPlayheadScrub(timeMs: Long) {
                currentTimeMs = timeMs
                playbackController?.scrubTo(timeMs)
            }
            override fun onTransitionRequested(outgoingClipId: Int, incomingClipId: Int) {
                noteAppHealthAction("timeline_transition_chip_tapped")
                transitionController?.showTransitionEditor(outgoingClipId, incomingClipId)
            }
            override fun onTrackImportRequested(trackType: TrackType) {
                setSelectedImportTrackType(trackType)
                when (trackType) {
                    TrackType.VIDEO -> openVideoTrackImport()
                    TrackType.OVERLAY -> openOverlayTrackImport()
                    TrackType.LAYER -> openLayerTrackImport()
                    TrackType.TEXT -> showAddTextDialog()
                    TrackType.AUDIO -> openAudioTrackImport()
                }
            }
            override fun onTrackSelected(trackType: TrackType) {
                setSelectedImportTrackType(trackType)
                selectPreviewActiveClipForTrack(trackType)
            }
            override fun onTrackVisibilityChanged(trackType: TrackType, isVisible: Boolean) {
                applyTrackVisibilityChange(trackType, isVisible)
                scheduleSessionAutoSave("canvas_track_visibility", delayMs = 1000L)
            }
            override fun onTrackLockedChanged(trackType: TrackType, isLocked: Boolean) {
                applyTrackLockChange(trackType, isLocked)
                scheduleSessionAutoSave("canvas_track_lock", delayMs = 1000L)
            }
        }
        canvasView?.onSplitAtPlayhead = { timeMs ->
            currentTimeMs = timeMs
            mainHandler.post { performSelectedClipSplitAction() }
        }
        canvasView?.onClipLongPress = null
        previewHudView?.visibility = View.GONE
        findViewById<View?>(R.id.hardwareBufferTelemetryPanel)?.visibility = View.GONE
        applyAdaptivePreviewProfile()
        previewAudioPlayer = PreviewAudioPlayer(
            context = this,
            previewViewProvider = { previewView },
        )
        findViewById<android.view.View?>(R.id.previewPlayPauseButton)?.bringToFront()
        notificationManager = getSystemService(NotificationManager::class.java)
        setupBottomToolbarModes()
        previewContainer.setOnClickListener {
            if (!canDirectPreviewTransformSelectedClip()) {
                clearSelectedTimelineItem()
            }
        }
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
        val viewportFrame = previewViewportFrame ?: FrameLayout(this)
        previewStageHost.addView(
            viewportFrame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        viewportFrame.addView(previewView, params)

        // Check if native library is loaded
        if (!VideoPreviewView.isNativeLibraryLoaded()) {
            Log.w(TAG, "WARNING: Native library not available. Building the C++ video engine is required.")
            safeToast("Storyline native renderer not found. Build the C++ module to enable GPU rendering.", Toast.LENGTH_LONG)
        }

        // Overlay container
        overlayContainer = FrameLayout(this)
        val overlayLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        viewportFrame.addView(overlayContainer, overlayLp)
        previewStageHost.clipToOutline = true
        previewEmptyStateView?.bringToFront()
        previewProGuidesOverlayView?.bringToFront()
        previewCropOverlayView?.bringToFront()
        setupPreviewTransformGestures()
        setupAspectRatioButton()
        setupPreviewCropControls()
        setupPreviewProControls()
        previewContainer.post { applyPreviewAspectRatio() }
        previewHudView?.bringToFront()
        findViewById<android.view.View?>(R.id.hardwareBufferTelemetryPanel)?.bringToFront()
        findViewById<android.view.View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        findViewById<android.view.View?>(R.id.previewPlayPauseButton)?.bringToFront()
        updatePreviewEmptyState()
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
            setCurrentTimeMs = { timeMs ->
                currentTimeMs = timeMs
                refreshOverlayVisibilityAndPose()
            },
            isPlayingProvider = { isPlaying },
            setIsPlaying = { updatePlayingState(it) },
            setVideoDurationMs = { videoDurationMs = it },
            totalDurationMsProvider = { currentProjectDurationMs() },
            onSelectionChanged = selectionChanged@{ selectedClipId ->
                if (shouldIgnorePreviewSelectionChange(selectedClipId)) {
                    Log.d(
                        TAG,
                        "Ignoring preview selection change while transform lock is active: incoming=${selectedClipId ?: "none"} locked=${previewSelectionLockClipId ?: "none"}",
                    )
                    return@selectionChanged
                }
                if (selectedClipId == null) {
                    val existingAudioId = selectedAudioClipId()
                    if (existingAudioId != null && AudioClipStore.get(existingAudioId) != null) {
                        Log.d(TAG, "Ignoring null playback selection while audio clip remains selected: $existingAudioId")
                        updateBottomToolbarMode()
                        updateUndoRedoButtons()
                        return@selectionChanged
                    }
                    val existingVisualSelectionKey = selectedTimelineClipKey
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val shouldKeepVisualSelection =
                        existingVisualSelectionKey != null &&
                            parseNativeClipId(existingVisualSelectionKey) != null &&
                            (isPlaying || nowElapsedMs < previewSelectionStickyUntilElapsedMs || previewCropModeActive)
                    if (shouldKeepVisualSelection) {
                        updateBottomToolbarMode()
                        updateUndoRedoButtons()
                        return@selectionChanged
                    }
                }
                selectedTimelineClipKey = selectedClipId?.let(::selectionKeyForNativeClipId)
                syncSelectedImportTrackFromSelection(selectedTimelineClipKey)
                rememberPreviewEditableSelection(selectedTimelineClipKey)
                updateBottomToolbarMode()
                updateUndoRedoButtons()
                if (selectedClipId != null) {
                    Log.d(TAG, "Selected clip changed: $selectedClipId")
                }
            },
            onTransitionRequested = { outgoingClipId, incomingClipId ->
                transitionController?.showTransitionEditor(outgoingClipId, incomingClipId)
            },
            onPlayRequested = { timeMs ->
                lastPlaybackAudioClockSyncRealtimeMs = 0L
                lastPlaybackAudioClockSyncTimelineMs = Long.MIN_VALUE
                cancelPendingPreviewAudioSeek()
                cancelPreviewAudioGestureResume()
                pausedPreviewAudioSuppressUntilElapsedMs = 0L
                previewAudioPlayer?.playFrom(timeMs)
                if (::playbackExportIssueDetector.isInitialized) {
                    playbackExportIssueDetector.onPlaybackStarted(timeMs)
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.startTrace(
                        slot = "playback_session",
                        traceName = "ops_playback_session",
                        attributes = mapOf("screen" to currentHealthScreenName()),
                    )
                }
                noteAppHealthAction("playback_started")
            },
            onPauseRequested = {
                lastPlaybackAudioClockSyncRealtimeMs = 0L
                lastPlaybackAudioClockSyncTimelineMs = Long.MIN_VALUE
                previewAudioPlayer?.pause()
                if (::playbackExportIssueDetector.isInitialized) {
                    playbackExportIssueDetector.onPlaybackPaused()
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.stopTrace(
                        slot = "playback_session",
                        success = true,
                        attributes = mapOf("screen" to currentHealthScreenName()),
                    )
                }
                noteAppHealthAction("playback_paused")
            },
            onSeekRequested = { timeMs, continuePlaying -> syncPreviewAudioAt(timeMs, continuePlaying) },
        )
        playbackController?.setupTimeline()
        // Wire canvas playhead to playback time
        playbackController?.onPlaybackTimeChanged = { timeMs ->
            TimelineThumbnailCache.suspendRequests(750L)
            activeCanvasTimelineView()
                ?.takeIf { it.isShown }
                ?.setPlayheadMs(timeMs)
        }
        playbackController?.onPlaybackTimeSampled = { timeMs ->
            val now = SystemClock.elapsedRealtime()
            if (
                now - lastPlaybackAudioClockSyncRealtimeMs >= PLAYBACK_AUDIO_CLOCK_SAMPLE_INTERVAL_MS ||
                lastPlaybackAudioClockSyncTimelineMs == Long.MIN_VALUE ||
                abs(timeMs - lastPlaybackAudioClockSyncTimelineMs) >= PLAYBACK_AUDIO_CLOCK_SAMPLE_DISTANCE_MS
            ) {
                lastPlaybackAudioClockSyncRealtimeMs = now
                lastPlaybackAudioClockSyncTimelineMs = timeMs
                previewAudioPlayer?.syncToVideoClock(timeMs, continuePlaying = isPlaying)
            }
            if (::playbackExportIssueDetector.isInitialized) {
                playbackExportIssueDetector.onPlaybackTimeChanged(timeMs)
            }
        }
        // On surface recreate (after picker), seek to restore frame
        previewView?.onSurfaceReady = {
            val hasClips = timelineManager?.getClips()?.isNotEmpty() == true
            if (hasClips) {
                val restoreTimeMs = currentPlayheadMs().coerceAtLeast(0L)
                rememberPreviewLifecycleAnchor(restoreTimeMs)
                val token = ++previewSurfaceRestoreToken
                mainHandler.postDelayed({
                    if (token != previewSurfaceRestoreToken || isFinishing || isDestroyed) return@postDelayed
                    if (shouldSuspendPreviewAudioForDirectGesture()) return@postDelayed
                    seekPreviewNativeIfNeeded(
                        timeMs = currentPlayheadMs().coerceAtLeast(0L),
                        force = true,
                        allowDuringPlayback = false,
                    )
                }, PREVIEW_SURFACE_RESTORE_DELAY_MS)
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
            selectedLayerKeyProvider = { selectedTimelineClipKey },
            onToggleClipVisibility = { clipId, visible -> previewView?.toggleLayerVisibility(clipId, visible) },
            onRemoveClip = { clipId ->
                execCmd(
                    action = "DELETE_CLIP",
                    params = mapOf("clipId" to clipId),
                ) { result ->
                    clipPreviewTransforms.remove(clipId)
                    nativeAppliedClipPreviewTransforms.remove(clipId)
                    previewView?.clearClipPreviewTransform(clipId)
                    if (result.success != true) {
                        previewView?.removeClip(clipId)
                    }
                }
            },
            onRemoveText = { overlayId ->
                previewView?.removeTextOverlay(overlayId)
                removeOverlayView(overlayId)
            },
            onRemoveSticker = { stickerId -> removeStickerOverlayView(stickerId) },
            onRemoveAudio = { audioId ->
                execCmd(
                    action = "DELETE_CLIP",
                    params = mapOf("clipId" to audioId),
                ) { result ->
                    if (result.success != true) {
                        previewView?.removeClip(audioId)
                    }
                }
                nativeClipTrackType.remove(audioId)
                nativeClipStartMs.remove(audioId)
                nativeClipDurationMs.remove(audioId)
                nativeClipSourcePath.remove(audioId)
                nativeClipSourceInMs.remove(audioId)
                nativeClipSourceOutMs.remove(audioId)
                nativeClipLane.remove(audioId)
                nativeClipZOrder.remove(audioId)
                nativeClipAudioGainKeyframes.remove(audioId)
                audioClipGainOverrides.remove(audioId)
                audioDuckingRestoreGainOverrides.remove(audioId)
                duckingEnabledForKey.remove("audio-$audioId")
                duckingEnabledForKey.remove(audioId.toString())
                initializedAudioImportClipIds.remove(audioId)
                lastPreviewAudioSyncSignature = ""
            },
            onApplyTextState = { overlay -> applyTextOverlayState(overlay) },
            onApplyStickerState = { clip -> applyStickerLayerState(clip) },
            onRecordEditorUndo = {
                editorState?.recordLayerSnapshot()
                recordUndoDomain(UndoDomain.EDITOR)
            },
            onRecordTimelineUndo = { recordUndoDomain(UndoDomain.TIMELINE) },
            onApplyTimelineShell = { applyTimelineStateToShell() },
            onTimelineContentChanged = { refreshMainTimelineTracks() },
            onSelectLayer = { key -> selectTimelineClipKey(key, revealPreview = true, forceRevealPreview = true) },
        )
        overlayController = OverlayController(
            activity = this,
            previewViewProvider = { previewView },
            overlayContainerProvider = { overlayContainer },
            isPlayingProvider = { isPlaying },
            onPausePlayback = {
                updatePlayingState(false)
                playbackController?.pauseRendering()
            },
            currentTimeMsProvider = { currentTimeMs },
            nextTextOverlayIdProvider = { nextTextOverlayId },
            setNextTextOverlayId = { nextTextOverlayId = it },
            nextStickerIdProvider = { nextStickerId },
            setNextStickerId = { nextStickerId = it },
            nextCompositeLayerIndexProvider = { editorState?.nextCompositeLayerIndex() ?: 0 },
            trackEndTimeMsProvider = { trackType -> timelineTrackEndTimeMs(trackType) },
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
            playheadTimeMsProvider = { currentPlayheadMs() },
            trackEndTimeMsProvider = { trackType -> timelineTrackEndTimeMs(trackType) },
            isPlayingProvider = { isPlaying },
            shouldDeferBackgroundWorkProvider = { shouldDeferHeavyUiWork() },
            onImportStarted = { path, trackType ->
                importPickerResultInProgress = true
                deferAdsForSmoothEditing(windowMs = 120_000L)
                if (startScreenOverlayView?.visibility == View.VISIBLE) {
                    setStartScreenVisible(false)
                }
                holdPreviewSurfaceForImport()
                lastPreviewNativeSeekTimeMs = Long.MIN_VALUE
                lastPreviewNativeSeekUptimeMs = 0L
                pausedPreviewAudioSuppressUntilElapsedMs =
                    maxOf(pausedPreviewAudioSuppressUntilElapsedMs, SystemClock.elapsedRealtime() + 2600L)
                notePreviewInteractionBusy(2500L)
                if (::uiFreezeWatchdog.isInitialized) {
                    uiFreezeWatchdog.suspendFor(10_000L)
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.startTrace(
                        slot = "visual_import",
                        traceName = "ops_visual_import",
                        attributes = mapOf(
                            "track" to trackType.name.lowercase(Locale.US),
                            "ext" to path.substringAfterLast('.', "media").lowercase(Locale.US),
                        ),
                    )
                }
            },
            onImportProgress = { update ->
                showImportProgress(update)
            },
            onImportFinished = { success, path, trackType, _, importedDurationMs, error ->
                importPickerResultInProgress = false
                if (success) {
                    importPickerAutosaveRestoreAttempted = true
                    importPickerAutosaveRestoreInProgress = false
                }
                if (!success && !hasProjectContent()) {
                    projectLoadSurfaceHoldUntilElapsedMs = 0L
                    updatePreviewEmptyState()
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.stopTrace(
                        slot = "visual_import",
                        success = success,
                        attributes = buildMap {
                            put("track", trackType.name.lowercase(Locale.US))
                            put("ext", path.substringAfterLast('.', "media").lowercase(Locale.US))
                            if (!error.isNullOrBlank()) {
                                put("error", error.take(24))
                            }
                        },
                        metrics = mapOf(
                            "clip_duration_ms" to importedDurationMs.coerceAtLeast(0L),
                        ),
                    )
                }
            },
            onImportedClip = { clipId, importPath, importedDurationMs, trackType, requestedStartTimeMs ->
                deferAdsForSmoothEditing(windowMs = 90_000L)
                notePreviewInteractionBusy(6000L)
                pausedPreviewAudioSuppressUntilElapsedMs =
                    maxOf(pausedPreviewAudioSuppressUntilElapsedMs, SystemClock.elapsedRealtime() + 2200L)
                val fileExtension = importPath.substringAfterLast(".", "mp4").lowercase()
                val safeDurationMs = importedDurationMs.coerceAtLeast(1L)
                val clipStartMs = requestedStartTimeMs.coerceAtLeast(0L)
                videoDurationMs = maxOf(videoDurationMs, clipStartMs + safeDurationMs)
                nativeClipTrackType[clipId] = trackType
                nativeClipStartMs[clipId] = clipStartMs
                nativeClipDurationMs[clipId] = safeDurationMs
                nativeClipSourceInMs[clipId] = nativeClipSourceInMs[clipId] ?: 0L
                nativeClipSourceOutMs[clipId] = nativeClipSourceOutMs[clipId] ?: safeDurationMs
                nativeClipSourcePath[clipId] = importPath
                videoClipTimingOverrides[clipId] = ClipTimingSnapshot(
                    startTimeMs = clipStartMs,
                    durationMs = safeDurationMs,
                    sourceInMs = nativeClipSourceInMs[clipId] ?: 0L,
                    sourceOutMs = nativeClipSourceOutMs[clipId] ?: safeDurationMs,
                )
                rememberMediaSourceDuration(importPath, safeDurationMs)
                when (trackType) {
                    TrackType.TEXT -> {
                        nativeClipLane[clipId] = 0
                        nativeClipZOrder[clipId] = 400
                    }
                    TrackType.OVERLAY -> {
                        nativeClipLane[clipId] = 0
                        nativeClipZOrder[clipId] = 200
                    }
                    TrackType.LAYER -> {
                        nativeClipLane[clipId] = 0
                        nativeClipZOrder[clipId] = 120
                    }
                    else -> {
                        nativeClipLane[clipId] = nativeClipLane[clipId] ?: 0
                        nativeClipZOrder[clipId] = nativeClipZOrder[clipId] ?: 0
                    }
                }
                resetImportedVisualClipPreviewTransform(clipId, trackType)
                setSelectedImportTrackType(trackType)
                importController?.setNextImportTrackType(trackType)
                val revealTimeMs = when (trackType) {
                    TrackType.AUDIO -> currentTimeMs
                    else -> clipStartMs
                }
                currentTimeMs = revealTimeMs
                forceImportedPreviewLayoutAndRender(revealTimeMs)
                scheduleImportedClipSmartCrop(clipId, importPath, trackType, revealTimeMs)
                schedulePostImportClipHydration(clipId, revealTimeMs)
                scheduleSessionAutoSave(
                    "visual_import_${trackType.name.lowercase(Locale.US)}",
                    delayMs = if (shouldDeferHeavyUiWork()) TIMELINE_BUSY_AUTOSAVE_DELAY_MS else 1400L,
                )
                Log.d(TAG, "Clip import complete: id=$clipId track=$trackType duration=${importedDurationMs}ms ext=$fileExtension")
                when (trackType) {
                    TrackType.VIDEO,
                    TrackType.OVERLAY,
                    -> previewAudioPlayer?.prewarmSource(importPath)
                    else -> Unit
                }
                noteAppHealthAction(
                    when (trackType) {
                        TrackType.LAYER -> "layer_clip_imported"
                        TrackType.OVERLAY -> "overlay_clip_imported"
                        else -> "video_clip_imported"
                    },
                )
                revealTimeMs
            },
        )
        audioImportController = AudioImportController(
            activity = this,
            nextAudioClipIdProvider = { nextAudioClipId },
            setNextAudioClipId = { nextAudioClipId = it },
            shouldBuildPeakMapsProvider = { !isAutomationPerfMode() },
            defaultStartTimeMsProvider = { defaultAudioImportStartTimeMs() },
            onImportStarted = { path ->
                deferAdsForSmoothEditing(windowMs = 90_000L)
                if (startScreenOverlayView?.visibility == View.VISIBLE) {
                    setStartScreenVisible(false)
                }
                pausedPreviewAudioSuppressUntilElapsedMs =
                    maxOf(pausedPreviewAudioSuppressUntilElapsedMs, SystemClock.elapsedRealtime() + 2200L)
                if (::uiFreezeWatchdog.isInitialized) {
                    uiFreezeWatchdog.suspendFor(8_000L)
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.startTrace(
                        slot = "audio_import",
                        traceName = "ops_audio_import",
                        attributes = mapOf(
                            "ext" to path.substringAfterLast('.', "audio").lowercase(Locale.US),
                        ),
                    )
                }
            },
            onImportProgress = { update ->
                showImportProgress(update)
            },
            onImportFinished = { success, path, _, error ->
                if (::opsReporter.isInitialized) {
                    opsReporter.stopTrace(
                        slot = "audio_import",
                        success = success,
                        attributes = buildMap {
                            put("ext", path.substringAfterLast('.', "audio").lowercase(Locale.US))
                            if (!error.isNullOrBlank()) {
                                put("error", error.take(24))
                            }
                        },
                    )
                }
            },
            onImportedAudio = { audioClip ->
                val firstUiHydration = initializedAudioImportClipIds.add(audioClip.id)
                val automationMode = isAutomationPerfMode()
                pausedPreviewAudioSuppressUntilElapsedMs =
                    maxOf(pausedPreviewAudioSuppressUntilElapsedMs, SystemClock.elapsedRealtime() + 1600L)
                if (isPlaying) {
                    isPlaying = false
                    playbackController?.pauseRendering()
                }
                nativeClipTrackType[audioClip.id] = TrackType.AUDIO
                val targetGain = audioClipGainOverrides[audioClip.id] ?: audioClip.gain.coerceIn(0f, 2f)
                audioClip.gain = targetGain
                audioClip.muted = targetGain <= 0.001f
                audioClipGainOverrides[audioClip.id] = targetGain
                cacheAudioClipLayout(audioClip)
                lastPreviewAudioSyncSignature = ""
                execCmd(
                    action = "SET_CLIP_VOLUME",
                    params = mapOf("clipId" to audioClip.id, "volume" to targetGain.toDouble()),
                )
                if (firstUiHydration) {
                    val audioStartMs = audioClip.startTimeMs.coerceAtLeast(0L)
                    val selectedAudioKey = "audio-${audioClip.id}"
                    if (!automationMode) {
                        currentTimeMs = audioStartMs
                        selectedTimelineClipKey = selectedAudioKey
                    }
                    if (previewView != null) {
                        lastLayoutFetchMs = 0L
                        syncTimelineShellFromNative(selectedClipId = if (automationMode) null else audioClip.id)
                    } else {
                        refreshMainTimelineTracks()
                    }
                    if (!automationMode) {
                        multiTrackTimelineView?.setCurrentTimeMs(currentTimeMs)
                        multiTrackTimelineView?.setSelectedClipId(selectedAudioKey)
                        multiTrackTimelineView?.revealClip(selectedAudioKey)
                        syncPreviewAudioAt(currentTimeMs, continuePlaying = false)
                    }
                    Log.d(
                        TAG,
                        "Audio import complete: id=${audioClip.id} name=${audioClip.displayName} duration=${audioClip.durationMs}ms path=${audioClip.sourcePath} peaks=${audioClip.peakLevels.size}",
                    )
                    noteAppHealthAction(if (automationMode) "audio_clip_seeded" else "audio_clip_imported")
                } else {
                    refreshMainTimelineTracks()
                    Log.d(
                        TAG,
                        "Audio import peaks updated: id=${audioClip.id} peaks=${audioClip.peakLevels.size} path=${audioClip.sourcePath}",
                    )
                }
                scheduleSessionAutoSave("audio_import", delayMs = 1400L)
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
                updatePlayingState(false)
                playbackController?.pauseRendering()
            },
            notificationManagerProvider = { notificationManager },
            isWatermarkUnlockedProvider = { rewardedUnlockController?.isWatermarkUnlocked() == true },
            onRequestWatermarkUnlock = { callback ->
                rewardedUnlockController?.requestWatermarkUnlock(callback) ?: callback(false)
            },
            onConsumeWatermarkUnlock = {
                rewardedUnlockController?.consumeWatermarkUnlock()
            },
            onExportStarted = { profileLabel, width, height, fps, bitrateMbps, codec ->
                deferAdsForSmoothEditing(windowMs = 120_000L)
                if (::playbackExportIssueDetector.isInitialized) {
                    playbackExportIssueDetector.onExportStarted()
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.startTrace(
                        slot = "export_job",
                        traceName = "ops_export_job",
                        attributes = mapOf(
                            "profile" to profileLabel.lowercase(Locale.US),
                            "codec" to codec.lowercase(Locale.US),
                            "size" to "${width}x$height",
                            "fps" to fps.toString(),
                            "bitrate" to bitrateMbps.toString(),
                        ),
                    )
                }
                noteAppHealthAction("export_started")
            },
            onExportProgress = { progress ->
                if (::playbackExportIssueDetector.isInitialized) {
                    playbackExportIssueDetector.onExportProgress(progress)
                }
            },
            onExportCompleted = { success, outputPath, error ->
                if (::playbackExportIssueDetector.isInitialized) {
                    playbackExportIssueDetector.onExportCompleted(success, error)
                }
                if (::opsReporter.isInitialized) {
                    opsReporter.stopTrace(
                        slot = "export_job",
                        success = success,
                        attributes = buildMap {
                            put("has_output", (outputPath != null).toString())
                            if (!error.isNullOrBlank()) {
                                put("error", error.take(24))
                            }
                        },
                        metrics = mapOf(
                            "output_bytes" to (outputPath?.let { File(it).takeIf(File::exists)?.length() } ?: 0L),
                        ),
                    )
                }
                noteAppHealthAction(if (success) "export_completed" else "export_failed")
                deferAdsForSmoothEditing(windowMs = 20_000L)
            },
            onExportSuccess = {
                schedulePostExportInterstitial()
            },
        )
        projectController = ProjectController(
            activity = this,
            previewViewProvider = { previewView },
            hasProjectContentProvider = { hasProjectContent() },
            isPlayingProvider = { isPlaying },
            shouldDeferHeavyWorkProvider = { shouldDeferHeavyUiWork() },
            onPausePlayback = {
                updatePlayingState(false)
                playbackController?.pauseRendering()
            },
            onSaveUiState = { projectFile, projectName -> saveUiState(projectFile, projectName) },
            onPrepareProjectLoadSurface = {
                projectLoadSurfaceHoldUntilElapsedMs = SystemClock.elapsedRealtime() + PROJECT_LOAD_SURFACE_HOLD_MS
                setStartScreenVisible(false)
                previewContainerView?.visibility = View.VISIBLE
                previewViewportFrame?.visibility = View.VISIBLE
                previewView?.visibility = View.VISIBLE
                overlayContainer?.visibility = View.VISIBLE
                previewViewportFrame?.requestLayout()
                previewView?.requestLayout()
                previewView?.syncNativeSurfaceSizeToView()
                previewView?.forceNativeSurfaceRebind()
                previewView?.post {
                    previewView?.syncNativeSurfaceSizeToView()
                    previewView?.forceNativeSurfaceRebind()
                }
            },
            onPrepareLoadedProject = { clearEditorShellState() },
            onProjectLoaded = { filePath, pv ->
                setStartScreenVisible(false)
                TransitionStore.replaceAll(pv.getTransitions().map { it.copy() })
                timeline.syncFromEngine(pv)
                timelineManager?.syncClips(timeline.getClips(), recordHistory = false, clearHistory = true)
                videoDurationMs = maxOf(
                    pv.getDuration(),
                    currentProjectDurationMs(),
                )
                loadUiState(File(filePath))
                syncTimelineShellFromNative()
                mainHandler.postDelayed({
                    syncTimelineShellFromNative()
                    refreshMainTimelineTracks()
                }, 450L)
                refreshMainTimelineTracks()
            },
            onProjectLoadFinished = {
                importPickerAutosaveRestoreInProgress = false
                projectLoadSurfaceHoldUntilElapsedMs = 0L
                setStartScreenVisible(false)
                timelineManager?.notifyDatasetChanged()
                refreshMainTimelineTracks()
                updateUndoRedoButtons()
                updatePreviewEmptyState()
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
            nativeClipStateProvider = { buildNativeClipUiStateSnapshot() },
            onRestoreNativeClipState = { states -> restoreNativeClipUiState(states) },
        )
        transitionController = TransitionController(
            activity = this,
            previewViewProvider = { previewView },
            timelineManagerProvider = { timelineManager },
            currentTimeMsProvider = { currentTimeMs },
            onRecordTimelineUndo = { recordUndoDomain(UndoDomain.TIMELINE) },
            setCurrentTransitionId = { currentTransitionId = it },
            resolveClipTiming = { clipId -> selectedVideoTiming(clipId) },
            onFocusPreviewTime = { previewTimeMs -> applyEditorPlayhead(previewTimeMs, continueAudio = false) },
            onTransitionChanged = {
                refreshMainTimelineTracks()
                scheduleTimelineIdleAutoSave("transition_update")
            },
            onPreviewTransitionPlayback = { transition ->
                notePreviewInteractionBusy(2600L)
                cancelPendingPreviewAudioSeek()
                cancelPreviewAudioGestureResume()
                pausedPreviewAudioSuppressUntilElapsedMs =
                    maxOf(pausedPreviewAudioSuppressUntilElapsedMs, SystemClock.elapsedRealtime() + 1200L)
                val previewLeadMs = maxOf(360L, transition.durationMs.toLong())
                val playStartMs = (transition.startTimeMs - previewLeadMs).coerceAtLeast(0L)
                applyEditorPlayhead(playStartMs, continueAudio = false)
                previewView?.ensureNativeSurfaceBinding()
                previewView?.syncNativeSurfaceSizeToView()
                seekPreviewNativeIfNeeded(
                    timeMs = playStartMs,
                    force = true,
                    allowDuringPlayback = false,
                )
                previewView?.postDelayed({
                    previewView?.ensureNativeSurfaceBinding()
                    previewView?.syncNativeSurfaceSizeToView()
                    seekPreviewNativeIfNeeded(
                        timeMs = playStartMs,
                        force = true,
                        allowDuringPlayback = false,
                    )
                    playbackController?.nativePlay()
                }, resolveTransitionPreviewPlayDelayMs())
            },
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
            setIsPlaying = { updatePlayingState(it) },
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
                                previewView?.let { pv ->
                                    TransitionStore.replaceAll(pv.getTransitions().map { it.copy() })
                                }
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
                                previewView?.let { pv ->
                                    TransitionStore.replaceAll(pv.getTransitions().map { it.copy() })
                                }
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
            onOpenVideoImportPicker = { openPreferredVisualImport() },
            onOpenOverlayImportPicker = { openOverlayTrackImport() },
            onOpenLayerImportPicker = { openLayerTrackImport() },
            onShowAudioPicker = { openAudioTrackImport() },
            onAddTextPreset = { label -> addTextPresetFromToolbar(label) },
            onOpenSelectedTextStudio = { showSelectedTextStudio() },
            onSplitAudioAtPlayhead = { splitAudioAtPlayhead() },
            onHealthAction = { action -> noteAppHealthAction(action) },
            onUiButtonTap = { control, surface, mode -> noteUiButtonTap(control, surface, mode) },
            onShowProblemReportDialog = { source -> showProblemReportDialog(source) },
            clipEffects = clipEffects,
            onRevealSelectedClipPreview = { revealSelectedClipInPreview(force = true) },
            onResolveActiveVisualClipId = { resolveSelectedVisualClipId(syncSelectionIfNeeded = true) },
            onResolveVisualClipPreviewTimeMs = { clipId -> resolveVisualClipPreviewTimeMs(clipId) },
            onResolveTransitionTargetPair = { resolveToolbarTransitionPair() },
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
        if (!isAutomationPerfMode()) {
            refreshRecentProjects()
        }
        appShellController = AppShellController(
            activity = this,
            permissionRequestCode = PERMISSION_REQUEST_CODE,
            onCreateNotificationChannel = { createNotificationChannel() },
        )
        appShellController?.setup()

        if (
            savedInstanceState != null &&
            activeImportPickerRequestCode != null &&
            !activeImportPickerStartedFromStartScreen
        ) {
            val recreateImportRequestCode = activeImportPickerRequestCode
            mainHandler.post {
                if (activeImportPickerRequestCode != recreateImportRequestCode) {
                    Log.i(TAG, "Skipping autosave restore while import picker request is no longer active reason=on_create_import_picker")
                    return@post
                }
                if (importPickerResultInProgress) {
                    Log.i(TAG, "Skipping autosave restore while import picker result is in progress reason=on_create_import_picker")
                    return@post
                }
                restoreAutoSaveAfterImportPickerRecreate(
                    reason = "on_create_import_picker",
                    allowExistingContent = true,
                )
            }
        }
        scheduleStartupIdleWork(intent)
        if (savedInstanceState == null) {
            handleStartShellAction(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        activeImportPickerRequestCode?.let {
            outState.putInt(STATE_ACTIVE_IMPORT_PICKER_REQUEST_CODE, it)
        }
        outState.putLong(STATE_ACTIVE_IMPORT_PICKER_OPENED_AT_MS, activeImportPickerOpenedAtElapsedMs)
        outState.putBoolean(STATE_ACTIVE_IMPORT_PICKER_STARTED_FROM_START, activeImportPickerStartedFromStartScreen)
        outState.putLong(STATE_ACTIVE_IMPORT_PICKER_START_TIME_MS, activeImportPickerStartTimeMs)
        selectedTimelineImportTrackType?.let { trackType ->
            outState.putString(STATE_SELECTED_IMPORT_TRACK_TYPE, trackType.name)
        }
        outState.putInt(STATE_SELECTED_ASPECT_RATIO_INDEX, selectedAspectRatioIndex)
        outState.putBoolean(STATE_ASPECT_RATIO_MANUALLY_SELECTED, aspectRatioManuallySelected)
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
        activeImportPickerRequestCode?.let { requestCode ->
            Log.d(TAG, "Ignoring editor Back while import picker requestCode=$requestCode is active")
            return true
        }
        if (startScreenOverlayView?.visibility == View.VISIBLE) {
            returnToSingleHome("back_from_embedded_home")
            return true
        }
        returnToSingleHome("editor_back")
        return true
    }

    private fun setupAspectRatioButton() {
        val aspectButton = findViewById<View?>(R.id.aspectRatioButton) ?: return
        aspectButton.setOnClickListener {
            noteUiButtonTap("aspect_ratio", "top_bar")
            showAspectRatioPickerDialog()
        }
        aspectButton.setOnLongClickListener {
            noteUiButtonTap("aspect_ratio_report", "top_bar", mode = "long_press")
            showProblemReportDialog("aspect_ratio_button")
            true
        }
        findViewById<View?>(R.id.topPanZoomButton)?.setOnClickListener {
            noteUiButtonTap("pan_zoom", "top_bar")
            performSelectedClipPanZoomAction()
        }
        findViewById<View?>(R.id.topPanZoomButton)?.setOnLongClickListener {
            noteUiButtonTap("pan_zoom_push", "top_bar", mode = "long_press")
            applySelectedClipPanZoomPreset("push_in")
            true
        }
        findViewById<View?>(R.id.topProStudioButton)?.setOnClickListener {
            noteUiButtonTap("pro_studio", "top_bar")
            showProStudioSheet()
        }
        findViewById<View?>(R.id.topProStudioButton)?.setOnLongClickListener {
            noteUiButtonTap("pro_studio_auto", "top_bar", mode = "long_press")
            applyPremiumAutoPack()
            true
        }
        findViewById<View?>(R.id.proStudioButton)?.setOnClickListener {
            noteUiButtonTap("pro_studio", "main_toolbar")
            showProStudioSheet()
        }
        findViewById<View?>(R.id.proStudioButton)?.setOnLongClickListener {
            noteUiButtonTap("pro_studio_auto", "main_toolbar", mode = "long_press")
            applyPremiumAutoPack()
            true
        }
    }

    private fun showAspectRatioPickerDialog() {
        noteAppHealthAction("aspect_ratio_picker_opened")
        val initialIndex = selectedAspectRatioIndex
        val initialManual = aspectRatioManuallySelected
        val socialAspects = listOf(
            Triple("TikTok", "9:16", 9f / 16f),
            Triple("YouTube", "16:9", 16f / 9f),
            Triple("IG Post", "1:1", 1f),
            Triple("IG Reels", "9:16", 9f / 16f),
            Triple("Shorts", "9:16", 9f / 16f),
            Triple("IG Port.", "4:5", 4f / 5f),
            Triple("Snapchat", "9:16", 9f / 16f),
            Triple("FB Story", "9:16", 9f / 16f),
            Triple("Cinema", "21:9", 21f / 9f),
            Triple("Classic", "4:3", 4f / 3f),
        )
        ModernSheet.showModal(
            context = this,
            title = "Canvas Ratio",
            showClose = true,
            showApply = true,
            onApply = {
                safeToast("Canvas ratio applied", Toast.LENGTH_SHORT)
            },
            onCancel = {
                selectedAspectRatioIndex = initialIndex
                aspectRatioManuallySelected = initialManual
                applyPreviewAspectRatio()
                syncPreviewProOverlayUi()
            },
        ) {
            section("Social & Platform Presets")
            aspectCards(socialAspects, selectedIndex = 0) { idx ->
                val chosenAspect = socialAspects[idx].third
                val matchedIndex = aspectRatioOptions.indices.minByOrNull { i ->
                    kotlin.math.abs((aspectRatioOptions[i].width.toFloat() / aspectRatioOptions[i].height.toFloat()) - chosenAspect)
                } ?: 0
                selectedAspectRatioIndex = matchedIndex
                aspectRatioManuallySelected = true
                applyPreviewAspectRatio()
                syncPreviewProOverlayUi()
            }
            divider()
            section("All Aspect Ratios")
            chips("Presets", aspectRatioOptions.map { it.toolbarLabel }, selected = selectedAspectRatioIndex) { i, _ ->
                selectedAspectRatioIndex = i.coerceIn(0, aspectRatioOptions.lastIndex)
                aspectRatioManuallySelected = true
                applyPreviewAspectRatio()
                syncPreviewProOverlayUi()
            }
        }
    }

    private fun showProStudioSheet() {
        noteAppHealthAction("pro_studio_opened")
        val selectedLabel =
            when (selectedClipKind()) {
                ClipKind.VIDEO, ClipKind.OVERLAY -> selectedNativeClipLabel()
                ClipKind.AUDIO -> "Audio"
                ClipKind.TEXT -> "Text"
                ClipKind.STICKER -> "Overlay"
                ClipKind.NONE -> "No clip"
            }
        ModernSheet.show(this, "Pro Studio") {
            chips(
                "Engine",
                listOf("Smooth Engine", "HQ Monitor", "Proxy Build", "Use Proxy", "Audio Sync"),
                -1,
                dismissOnSelect = false,
            ) { _, option ->
                when (option) {
                    "Smooth Engine" -> applyProSmoothEngineMode()
                    "HQ Monitor" -> applyProQualityMonitorMode()
                    "Proxy Build" -> buildSelectedClipProxy()
                    "Use Proxy" -> activateSelectedClipProxy()
                    "Audio Sync" -> applyProAudioSyncMode()
                }
            }
            chips(
                "Selected: $selectedLabel",
                listOf("Auto Premium", "Motion Push", "Motion Pull", "Keyframe", "Curve", "Cine Color"),
                -1,
                dismissOnSelect = false,
            ) { _, option ->
                when (option) {
                    "Auto Premium" -> applyPremiumAutoPack()
                    "Motion Push" -> applyProMotionPreset("push_in")
                    "Motion Pull" -> applyProMotionPreset("pull_out")
                    "Keyframe" -> performSelectedClipKeyframeAction()
                    "Curve" -> performSelectedClipCurveSpeedAction()
                    "Cine Color" -> applyProCinematicColor()
                }
            }
            chips(
                "AI / Audio / Output",
                listOf("AI Matte", "AI Cut", "Ducking", "Canvas", "Export", "Save"),
                -1,
                dismissOnSelect = false,
            ) { _, option ->
                when (option) {
                    "AI Matte" -> performSelectedClipAiMatteAction()
                    "AI Cut" -> performSelectedClipCutoutAction()
                    "Ducking" -> performSelectedClipDuckingAction()
                    "Canvas" -> showAspectRatioPickerDialog()
                    "Export" -> showExportDialog()
                    "Save" -> showSaveProjectDialog()
                }
            }
        }
    }

    private fun applyProSmoothEngineMode() {
        previewQualityBoosted = false
        val profile = DeviceDetector.getQualityProfile()
        NativeBridge.setPreviewPolicy(
            ghostPreviewEnabled = true,
            ghostLongEdgePx = 720,
            adaptiveFrameDropEnabled = true,
            targetPreviewFps = maxOf(profile.previewFps, 60),
            minPreviewFps = maxOf(profile.minPreviewFps, 30),
        )
        NativeBridge.setPerformancePolicy(
            dirtyRegionEnabled = true,
            predictiveCachingEnabled = true,
            predictiveLookAroundMs = 4_000,
            predictiveSampleStepMs = 80,
            predictiveCacheMaxFrames = 120,
        )
        NativeBridge.setAudioMasterClockEnabled(true)
        syncPreviewProOverlayUi()
        triggerEditorHaptic(EditorHapticEffect.Success)
        safeToast("Smooth Engine ON: cache, frame budget, audio-sync", Toast.LENGTH_SHORT)
    }

    private fun applyProQualityMonitorMode() {
        previewQualityBoosted = true
        applyPreviewQualityMode()
        NativeBridge.setPerformancePolicy(
            dirtyRegionEnabled = true,
            predictiveCachingEnabled = true,
            predictiveLookAroundMs = 2_500,
            predictiveSampleStepMs = 100,
            predictiveCacheMaxFrames = 72,
        )
        syncPreviewProOverlayUi()
        triggerEditorHaptic(EditorHapticEffect.LightClick)
        safeToast("HQ Monitor ON", Toast.LENGTH_SHORT)
    }

    private fun applyProAudioSyncMode() {
        val enabled = NativeBridge.setAudioMasterClockEnabled(true)
        syncPreviewAudioAt(currentPlayheadMs().coerceAtLeast(0L), continuePlaying = isPlaying)
        triggerEditorHaptic(if (enabled) EditorHapticEffect.Success else EditorHapticEffect.Warning)
        safeToast(if (enabled) "Audio master sync ON" else "Audio sync command failed", Toast.LENGTH_SHORT)
    }

    private fun selectedVisualClipForPro(selectIfNeeded: Boolean = true): Int? {
        val clipId =
            resolveSelectedVisualClipId(
                syncSelectionIfNeeded = selectIfNeeded,
                preferredTrackType = selectedTrackType(),
            )
        if (clipId == null) {
            safeToast("Select video/layer clip first", Toast.LENGTH_SHORT)
            return null
        }
        if (selectIfNeeded) {
            timelineManager?.selectClip(clipId)
            selectTimelineClipKey(selectionKeyForNativeClipId(clipId), revealPreview = true, forceRevealPreview = true)
        }
        return clipId
    }

    private fun buildSelectedClipProxy() {
        val clipId = selectedVisualClipForPro() ?: return
        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
        if (sourcePath.isBlank()) {
            safeToast("Clip source path missing", Toast.LENGTH_SHORT)
            return
        }
        val proxyDir = File(filesDir, "proxies").apply { mkdirs() }
        val safeHash = sourcePath.hashCode().toString().replace("-", "n")
        val proxyPath = File(proxyDir, "clip_${clipId}_${safeHash}_640p_proxy.mp4").absolutePath
        val started = NativeBridge.buildClipProxy(
            clipId = clipId,
            sourcePath = sourcePath,
            outputPath = proxyPath,
            maxLongEdgePx = 640,
            targetFps = 30,
        )
        triggerEditorHaptic(if (started) EditorHapticEffect.Tick else EditorHapticEffect.Warning)
        safeToast(if (started) "Proxy build started" else "Proxy build failed", Toast.LENGTH_SHORT)
        if (started) {
            mainHandler.postDelayed({
                val status = NativeBridge.getClipProxyStatus(clipId)
                safeToast(status.message.ifBlank { "Proxy ${status.progress}%" }, Toast.LENGTH_SHORT)
            }, 1_800L)
        }
    }

    private fun activateSelectedClipProxy() {
        val clipId = selectedVisualClipForPro() ?: return
        val status = NativeBridge.getClipProxyStatus(clipId)
        if (!status.ready) {
            buildSelectedClipProxy()
            return
        }
        val activated = NativeBridge.activateClipProxy(clipId)
        refreshPreviewAtPlayhead(resyncAudio = false)
        triggerEditorHaptic(if (activated) EditorHapticEffect.Success else EditorHapticEffect.Warning)
        safeToast(if (activated) "Proxy preview active" else "Proxy not ready", Toast.LENGTH_SHORT)
    }

    private fun applyProMotionPreset(preset: String) {
        val clipId = selectedVisualClipForPro() ?: return
        if (!isStillImageClip(clipId)) {
            applyNativeClipSpeedChange(
                clipId = clipId,
                speed = if (preset == "pull_out") 0.92f else 1.08f,
                curveProfile = if (preset == "pull_out") "ease_in" else "ease_out",
            )
        }
        applySelectedClipPanZoomPreset(preset)
    }

    private fun applyProCinematicColor() {
        val clipId = selectedVisualClipForPro() ?: return
        applySelectedClipEffects(
            clipId = clipId,
            params = EffectParams(brightness = -0.05f, contrast = 1.28f, saturation = 0.88f),
        )
        triggerEditorHaptic(EditorHapticEffect.Success)
        safeToast("Cinematic color applied", Toast.LENGTH_SHORT)
    }

    private fun applyPremiumAutoPack() {
        applyProSmoothEngineMode()
        selectedVisualClipForPro(selectIfNeeded = false)?.let { clipId ->
            if (!isStillImageClip(clipId)) {
                applyNativeClipSpeedChange(clipId, speed = 1.06f, curveProfile = "ease_out")
            }
            applyProCinematicColor()
            applySelectedClipPanZoomPreset("push_in")
        }
        applyProAudioSyncMode()
    }

    private fun setupPreviewProControls() {
        previewGridToggleButton?.setOnClickListener {
            previewGridVisible = !previewGridVisible
            noteUiButtonTap("preview_grid_toggle", "preview")
            syncPreviewProOverlayUi()
        }
        previewSafeToggleButton?.setOnClickListener {
            previewSafeAreaVisible = !previewSafeAreaVisible
            noteUiButtonTap("preview_safe_toggle", "preview")
            syncPreviewProOverlayUi()
        }
        previewFitFillToggleButton?.setOnClickListener {
            applyPreviewFitFillToggle()
        }
        previewQualityToggleButton?.setOnClickListener {
            previewQualityBoosted = !previewQualityBoosted
            applyPreviewQualityMode()
            noteUiButtonTap("preview_quality_toggle", "preview")
            syncPreviewProOverlayUi()
        }
        syncPreviewProOverlayUi()
    }

    private fun applyPreviewQualityMode() {
        if (!previewQualityBoosted) {
            applyAdaptivePreviewProfile()
            return
        }
        val profile = DeviceDetector.getQualityProfile()
        NativeBridge.setPreviewPolicy(
            ghostPreviewEnabled = true,
            ghostLongEdgePx = 720,
            adaptiveFrameDropEnabled = true,
            targetPreviewFps = maxOf(profile.previewFps, 45),
            minPreviewFps = maxOf(profile.minPreviewFps, 24),
        )
    }

    private fun resolveTransitionPreviewPlayDelayMs(): Long =
        when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> 190L
            DeviceDetector.DeviceTier.MID -> 150L
            DeviceDetector.DeviceTier.HIGH -> 110L
        }

    private fun applyPreviewFitFillToggle() {
        if (!canDirectPreviewTransformSelectedClip()) {
            restorePreviewEditableSelection()
            if (!canDirectPreviewTransformSelectedClip()) {
                cyclePreviewEditableSelection()
            }
        }
        val clipId = selectedVideoClipId()
        if (clipId == null || !canDirectPreviewTransformSelectedClip()) {
            safeToast("Select a preview clip first", Toast.LENGTH_SHORT)
            return
        }
        val current = currentClipPreviewTransform(clipId)
        val fitTransform = fitPreviewTransformForClip(clipId)
        val shouldFill = current.zoom <= fitTransform.zoom + 0.04f &&
            abs(current.panXPx) <= 2f &&
            abs(current.panYPx) <= 2f
        updateSelectedVideoPreviewTransform(immediate = true) {
            if (shouldFill) {
                it.copy(zoom = maxOf(1.0f, resolvePreviewMinZoom(clipId)), panXPx = 0f, panYPx = 0f)
            } else {
                fitTransform
            }
        }
        refreshPreviewCropStatus()
        noteUiButtonTap(if (shouldFill) "preview_fill" else "preview_fit", "preview")
        syncPreviewProOverlayUi()
    }

    private fun syncPreviewProOverlayUi() {
        val startVisible = startScreenOverlayView?.visibility == View.VISIBLE
        val hasContent = hasPreviewVisualContent() && !startVisible
        val selectedKind = selectedClipKind()
        val textLikeSelected = selectedKind == ClipKind.TEXT || selectedKind == ClipKind.STICKER
        if (textLikeSelected) {
            previewCropModeActive = false
            previewCropModeClipKey = null
            previewPanZoomControlsVisible = false
            previewSafeAreaVisible = false
            previewGridVisible = false
        }
        val showGuides =
            hasContent && !textLikeSelected && previewPanZoomControlsVisible && (previewGridVisible || previewSafeAreaVisible)
        previewHudView?.visibility = View.GONE
        previewProGuidesOverlayView?.visibility = if (showGuides) View.VISIBLE else View.GONE
        previewThirdsGuideView?.visibility = if (showGuides && previewGridVisible) View.VISIBLE else View.GONE
        previewSafeAreaGuideView?.visibility = if (showGuides && previewSafeAreaVisible) View.VISIBLE else View.GONE
        previewFrameInfoChip?.visibility = View.GONE
        if (textLikeSelected) {
            previewCropOverlayView?.visibility = View.GONE
        }

        val option = aspectRatioOptions[selectedAspectRatioIndex]
        val modeLabel = if (previewPanZoomControlsVisible && canUsePreviewCropMode()) "PAN/ZOOM" else "LIVE"
        previewHudStatusText?.text = "$modeLabel ${option.label}"
        previewFrameInfoChip?.text =
            buildString {
                append("PROGRAM • ")
                append(option.label)
                append(" • ")
                append(if (previewGridVisible) "GRID" else "CLEAN")
                if (previewSafeAreaVisible) append(" • SAFE")
            }

        previewGridToggleButton?.alpha = if (previewGridVisible) 1f else 0.62f
        previewSafeToggleButton?.alpha = if (previewSafeAreaVisible) 1f else 0.62f
        previewQualityToggleButton?.text =
            getString(if (previewQualityBoosted) R.string.preview_smooth_toggle else R.string.preview_quality_toggle)
        selectedVideoClipId()?.let { clipId ->
            val transform = currentClipPreviewTransform(clipId)
            val shouldOfferFit = transform.zoom > 1.05f || abs(transform.panXPx) > 2f || abs(transform.panYPx) > 2f
            previewFitFillToggleButton?.text =
                getString(if (shouldOfferFit) R.string.preview_fit_toggle else R.string.preview_fill_toggle)
        } ?: run {
            previewFitFillToggleButton?.text = getString(R.string.preview_fill_toggle)
        }

        previewProGuidesOverlayView?.bringToFront()
        if (!textLikeSelected) {
            previewCropOverlayView?.bringToFront()
        }
        overlayContainer?.bringToFront()
        findViewById<View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        findViewById<View?>(R.id.previewPlayPauseButton)?.bringToFront()
    }

    private fun applyPreviewAspectRatio() {
        val container = previewContainerView ?: return
        val viewportFrame = previewViewportFrame ?: return
        val preview = previewView ?: return
        val overlay = overlayContainer ?: return
        if (container.width <= 0 || container.height <= 0) {
            container.post { applyPreviewAspectRatio() }
            return
        }

        val option = aspectRatioOptions[selectedAspectRatioIndex]
        findViewById<TextView?>(R.id.aspectRatioButton)?.apply {
            text = option.toolbarLabel
            contentDescription = "Canvas ratio ${option.label}: ${option.example}"
        }
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

        val actualSizeMismatch =
            viewportFrame.width > 0 &&
                viewportFrame.height > 0 &&
                (viewportFrame.width != targetWidth || viewportFrame.height != targetHeight)
        val needsResize =
            targetWidth != lastAppliedAspectWidth ||
                targetHeight != lastAppliedAspectHeight ||
                actualSizeMismatch
        if (needsResize) {
            viewportFrame.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
            preview.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            overlay.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            previewEmptyStateView?.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
            previewCropOverlayView?.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
            previewProGuidesOverlayView?.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
            lastAppliedAspectWidth = targetWidth
            lastAppliedAspectHeight = targetHeight
            viewportFrame.requestLayout()
            preview.requestLayout()
            overlay.requestLayout()
            schedulePreviewSurfaceSyncAfterAspectLayout()
            if (previewCropOverlayView?.visibility == View.VISIBLE && canDirectPreviewTransformSelectedClip()) {
                previewCropOverlayView?.post { layoutSelectedPreviewObjectFrame() }
            }
        }
        applyPreviewSurfaceVisibility(hasPreviewVisualContent())
        previewEmptyStateView?.bringToFront()
        val textLikeSelected =
            selectedClipKind().let { kind -> kind == ClipKind.TEXT || kind == ClipKind.STICKER }
        previewProGuidesOverlayView?.bringToFront()
        if (!textLikeSelected) {
            previewCropOverlayView?.bringToFront()
        } else {
            previewCropOverlayView?.visibility = View.GONE
            previewProGuidesOverlayView?.visibility = View.GONE
        }
        overlay.bringToFront()
        findViewById<View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        findViewById<View?>(R.id.previewPlayPauseButton)?.bringToFront()
        syncPreviewProOverlayUi()
        scheduleTopBannerPlacement(delayMs = 600L)
    }

    private fun syncPreviewSurfaceAndRenderAt(targetTimeMs: Long? = null) {
        val view = previewView ?: return
        view.ensureNativeSurfaceBinding()
        view.syncNativeSurfaceSizeToView()
        targetTimeMs?.let { timeMs ->
            seekPreviewNativeIfNeeded(
                timeMs = timeMs.coerceAtLeast(0L),
                force = true,
                allowDuringPlayback = false,
            )
        }
    }

    private fun schedulePreviewSurfaceSyncAfterAspectLayout(targetTimeMs: Long? = null) {
        val view = previewView ?: return
        val frame = previewViewportFrame
        var detached = false

        fun detachListeners(
            viewListener: View.OnLayoutChangeListener?,
            frameListener: View.OnLayoutChangeListener?,
        ) {
            if (detached) return
            detached = true
            viewListener?.let { view.removeOnLayoutChangeListener(it) }
            frameListener?.let { frame?.removeOnLayoutChangeListener(it) }
        }

        lateinit var viewListener: View.OnLayoutChangeListener
        lateinit var frameListener: View.OnLayoutChangeListener
        fun syncAfterLayout() {
            detachListeners(viewListener, frameListener)
            view.post { syncPreviewSurfaceAndRenderAt(targetTimeMs) }
        }

        viewListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncAfterLayout() }
        frameListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncAfterLayout() }

        view.addOnLayoutChangeListener(viewListener)
        frame?.addOnLayoutChangeListener(frameListener)
        view.post { syncPreviewSurfaceAndRenderAt(targetTimeMs) }
        view.postDelayed({ syncPreviewSurfaceAndRenderAt(targetTimeMs) }, 90L)
        view.postDelayed({
            detachListeners(viewListener, frameListener)
            syncPreviewSurfaceAndRenderAt(targetTimeMs)
        }, 280L)
    }

    private fun forceImportedPreviewLayoutAndRender(revealTimeMs: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { forceImportedPreviewLayoutAndRender(revealTimeMs) }
            return
        }
        val targetTimeMs = revealTimeMs.coerceAtLeast(0L)
        applyPreviewAspectRatio()
        applyPreviewSurfaceVisibility(hasPreviewVisualContent())
        previewViewportFrame?.requestLayout()
        previewView?.requestLayout()
        overlayContainer?.requestLayout()

        schedulePreviewSurfaceSyncAfterAspectLayout(targetTimeMs)
        previewView?.postDelayed({ syncPreviewSurfaceAndRenderAt(targetTimeMs) }, 520L)
    }

    private fun updatePreviewEmptyState() {
        val placeholder = previewEmptyStateView ?: return
        applyPreviewSurfaceVisibility(hasPreviewVisualContent())
        placeholder.visibility = View.GONE
        syncPreviewProOverlayUi()
    }

    private fun applyPreviewSurfaceVisibility(hasVisualContent: Boolean) {
        val holdSurfaceForProjectLoad = SystemClock.elapsedRealtime() < projectLoadSurfaceHoldUntilElapsedMs
        val surfaceVisibility = if (hasVisualContent || holdSurfaceForProjectLoad) View.VISIBLE else View.INVISIBLE
        previewViewportFrame?.visibility = surfaceVisibility
        previewView?.visibility = surfaceVisibility
        overlayContainer?.visibility = surfaceVisibility
        val backgroundColor = if (hasVisualContent || holdSurfaceForProjectLoad) Color.BLACK else Color.TRANSPARENT
        findViewById<View?>(R.id.previewStageHost)?.setBackgroundColor(backgroundColor)
        findViewById<View?>(R.id.previewContainer)?.setBackgroundColor(backgroundColor)
        previewContainerView?.setBackgroundColor(backgroundColor)
        if (!hasVisualContent && !holdSurfaceForProjectLoad) {
            previewCropOverlayView?.visibility = View.GONE
            previewProGuidesOverlayView?.visibility = View.GONE
        }
    }

    private fun holdPreviewSurfaceForImport(windowMs: Long = 60_000L) {
        projectLoadSurfaceHoldUntilElapsedMs =
            maxOf(projectLoadSurfaceHoldUntilElapsedMs, SystemClock.elapsedRealtime() + windowMs)
        setEditorChromeVisible(true)
        previewViewportFrame?.visibility = View.VISIBLE
        previewView?.visibility = View.VISIBLE
        overlayContainer?.visibility = View.VISIBLE
        findViewById<View?>(R.id.previewStageHost)?.setBackgroundColor(Color.BLACK)
        findViewById<View?>(R.id.previewContainer)?.setBackgroundColor(Color.BLACK)
        previewContainerView?.setBackgroundColor(Color.BLACK)
        previewViewportFrame?.requestLayout()
        previewView?.requestLayout()
        previewView?.post {
            previewView?.ensureNativeSurfaceBinding()
            previewView?.syncNativeSurfaceSizeToView()
        }
        previewView?.postDelayed({
            previewView?.ensureNativeSurfaceBinding()
            previewView?.syncNativeSurfaceSizeToView()
        }, 220L)
    }

    private fun updatePlayingState(playing: Boolean) {
        playbackIdleUiRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        playbackIdleUiRestoreRunnable = null
        isPlaying = playing
        TimelineThumbnailCache.suspendRequests(if (playing) 3_000L else 1_400L)
        AudioWaveformCache.suspendRequests(if (playing) 3_000L else 1_400L)
        timelineCanvasView()?.suspendAssetRequests(if (playing) 3_000L else 1_400L)
        findViewById<ImageView?>(R.id.previewPlayPauseButton)?.setImageResource(
            if (playing) R.drawable.ic_pause_toolbar else R.drawable.ic_play_toolbar,
        )
        timelineCanvasView()?.setPlaybackActive(playing)
        allTextOverlays().forEach { overlay ->
            applyTextOverlayState(overlay)
            applyTextOverlayPose(overlay)
        }
        if (playing) {
            previewTransformDragging = false
            previewTransformPinching = false
            previewTransformTouchOwner = null
            previewTransformGestureClipId = null
            previewTrimSession = null
            previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            cancelContinuePreviewAudioSeek()
            val restoreRunnable = Runnable {
                if (isPlaying) return@Runnable
                if (selectedClipKind() == ClipKind.NONE) {
                    restorePreviewEditableSelection()
                }
                syncPreviewCropModeUi()
                applySelectedClipPreviewTransform()
                syncPreviewProOverlayUi()
            }
            playbackIdleUiRestoreRunnable = restoreRunnable
            mainHandler.postDelayed(restoreRunnable, 90L)
            return
        }
        syncPreviewCropModeUi()
        applySelectedClipPreviewTransform()
        syncPreviewProOverlayUi()
    }

    private fun setupPreviewCropControls() {
        findViewById<View?>(R.id.previewCropResetButton)?.setOnClickListener {
            if (!shouldShowDirectPreviewEdit()) return@setOnClickListener
            noteUiButtonTap("crop_reset", "preview_crop")
            applySelectedClipPanZoomPreset("reset")
        }
        findViewById<View?>(R.id.previewCropFillButton)?.setOnClickListener {
            if (!shouldShowDirectPreviewEdit()) return@setOnClickListener
            noteUiButtonTap("crop_fill", "preview_crop")
            applySelectedClipPanZoomPreset("fill")
        }
        findViewById<View?>(R.id.previewCropPushButton)?.setOnClickListener {
            if (!shouldShowDirectPreviewEdit()) return@setOnClickListener
            noteUiButtonTap("crop_push", "preview_crop")
            applySelectedClipPanZoomPreset("push_in")
        }
        findViewById<View?>(R.id.previewCropPullButton)?.setOnClickListener {
            if (!shouldShowDirectPreviewEdit()) return@setOnClickListener
            noteUiButtonTap("crop_pull", "preview_crop")
            applySelectedClipPanZoomPreset("pull_out")
        }
        findViewById<View?>(R.id.previewCropDoneButton)?.setOnClickListener {
            noteUiButtonTap("crop_done", "preview_crop")
            setPreviewCropMode(false)
        }
        previewTrimStartHandleView?.setOnTouchListener(null)
        previewTrimEndHandleView?.setOnTouchListener(null)
        syncPreviewCropModeUi()
    }

    private fun canUsePreviewCropMode(): Boolean {
        return (selectedClipKind() == ClipKind.VIDEO || selectedClipKind() == ClipKind.OVERLAY) &&
            selectedVideoClipId() != null
    }

    private fun rememberPreviewEditableSelection(selectionKey: String?) {
        val normalizedKey = normalizeTimelineSelectionKey(selectionKey)
        val clipId = parseNativeClipId(normalizedKey) ?: return
        when (nativeClipTrackType[clipId]) {
            TrackType.VIDEO,
            TrackType.OVERLAY,
            TrackType.LAYER,
            -> {
                lastPreviewEditableSelectionKey = normalizedKey
                previewSelectionStickyUntilElapsedMs = SystemClock.elapsedRealtime() + 30_000L
            }
            else -> Unit
        }
    }

    private fun resolvePreviewEditableClipAtPlayhead(playheadMs: Long): Int? {
        return resolvePreviewEditableClipCandidatesAtPlayhead(playheadMs).firstOrNull()
    }

    private fun resolvePreviewEditableClipCandidatesAtPlayhead(playheadMs: Long): List<Int> {
        val candidateClipIds = linkedSetOf<Int>().apply {
            addAll(nativeClipStartMs.keys)
            addAll(nativeClipDurationMs.keys)
            addAll(timelineManager?.getClips().orEmpty().map { it.id })
            timelineManager?.getSelectedClipId()?.let { add(it) }
        }.filter { clipId ->
            when (nativeClipTrackType[clipId]) {
                TrackType.VIDEO,
                TrackType.OVERLAY,
                TrackType.LAYER,
                -> true
                else -> false
            }
        }
        return candidateClipIds
            .filter { clipId ->
                val timing = selectedVideoTiming(clipId) ?: return@filter false
                val startMs = timing.first
                val endMs = startMs + timing.second.coerceAtLeast(1L)
                playheadMs in startMs until endMs
            }
            .sortedWith(
                compareByDescending<Int> { nativeClipZOrder[it] ?: 0 }
                    .thenByDescending { nativeClipStartMs[it] ?: 0L }
                    .thenByDescending { it },
            )
    }

    private fun cyclePreviewEditableSelection(): Boolean {
        if (isPlaying || startScreenOverlayView?.visibility == View.VISIBLE) return false
        val candidates = resolvePreviewEditableClipCandidatesAtPlayhead(currentPlayheadMs().coerceAtLeast(0L))
        if (candidates.isEmpty()) return false
        val currentId = selectedVideoClipId()
        val nextId =
            if (currentId == null) {
                candidates.first()
            } else {
                val index = candidates.indexOf(currentId)
                if (index == -1 || index >= candidates.lastIndex) candidates.first() else candidates[index + 1]
            }
        return selectTimelineClipKey(selectionKeyForNativeClipId(nextId), revealPreview = false)
    }

    private fun restorePreviewEditableSelection() {
        if ((selectedClipKind() == ClipKind.VIDEO || selectedClipKind() == ClipKind.OVERLAY) || isPlaying) return
        val currentPlayhead = currentPlayheadMs().coerceAtLeast(0L)
        val stickyKey =
            lastPreviewEditableSelectionKey?.takeIf { key ->
                val clipId = parseNativeClipId(key) ?: return@takeIf false
                val timing = selectedVideoTiming(clipId) ?: return@takeIf false
                currentPlayhead in timing.first until (timing.first + timing.second.coerceAtLeast(1L))
            }
        val resolvedKey = stickyKey ?: resolvePreviewEditableClipAtPlayhead(currentPlayhead)?.let(::selectionKeyForNativeClipId)
        resolvedKey?.let {
            selectTimelineClipKey(it, revealPreview = false)
        }
    }

    private fun shouldShowDirectPreviewEdit(): Boolean {
        return canUsePreviewCropMode() &&
            startScreenOverlayView?.visibility != View.VISIBLE
    }

    private fun previewResizeHandleViews(): List<View?> =
        listOf(
            previewResizeTopLeftHandleView,
            previewResizeTopHandleView,
            previewResizeTopRightHandleView,
            previewResizeRightHandleView,
            previewResizeBottomHandleView,
            previewResizeBottomLeftHandleView,
            previewResizeBottomRightHandleView,
            previewResizeLeftHandleView,
        )

    private fun previewDp(value: Float): Float = value * resources.displayMetrics.density

    private fun localMediaPath(sourcePath: String): String =
        if (sourcePath.startsWith("file://", ignoreCase = true)) {
            Uri.parse(sourcePath).path.orEmpty()
        } else {
            sourcePath
        }

    private fun decodeImageSourceAspect(sourcePath: String): Float? =
        runCatching {
            if (!isImageLikeSourcePath(sourcePath)) return@runCatching null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (sourcePath.startsWith("content://", ignoreCase = true)) {
                contentResolver.openInputStream(Uri.parse(sourcePath))?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return@runCatching null
            } else {
                BitmapFactory.decodeFile(localMediaPath(sourcePath), options)
            }
            val width = options.outWidth
            val height = options.outHeight
            if (width > 0 && height > 0) {
                (width.toFloat() / height.toFloat()).coerceIn(0.05f, 20f)
            } else {
                null
            }
        }.getOrNull()

    private fun decodeVideoSourceAspect(sourcePath: String): Float? =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (sourcePath.startsWith("content://", ignoreCase = true)) {
                    retriever.setDataSource(this, Uri.parse(sourcePath))
                } else {
                    val path = localMediaPath(sourcePath)
                    if (!File(path).exists()) return@runCatching null
                    retriever.setDataSource(path)
                }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                if (width <= 0f || height <= 0f) return@runCatching null
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val displayWidth = if (rotation == 90 || rotation == 270) height else width
                val displayHeight = if (rotation == 90 || rotation == 270) width else height
                (displayWidth / displayHeight.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
            ?: decodeVideoSourceAspectWithExtractor(sourcePath)

    private fun decodeVideoSourceAspectWithExtractor(sourcePath: String): Float? =
        runCatching {
            val extractor = MediaExtractor()
            try {
                if (sourcePath.startsWith("content://", ignoreCase = true)) {
                    extractor.setDataSource(this, Uri.parse(sourcePath), null)
                } else {
                    val path = localMediaPath(sourcePath)
                    if (!File(path).exists()) return@runCatching null
                    extractor.setDataSource(path)
                }
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith("video/")) continue
                    val width =
                        if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val height =
                        if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (width <= 0 || height <= 0) return@runCatching null
                    val rotation =
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            format.getInteger(MediaFormat.KEY_ROTATION)
                        } else {
                            0
                        }
                    val displayWidth = if (rotation == 90 || rotation == 270) height else width
                    val displayHeight = if (rotation == 90 || rotation == 270) width else height
                    return@runCatching (displayWidth.toFloat() / displayHeight.toFloat().coerceAtLeast(1f))
                        .coerceIn(0.05f, 20f)
                }
                null
            } finally {
                extractor.release()
            }
        }.getOrNull()

    private fun selectedCanvasAspect(): Float {
        val option = aspectRatioOptions.getOrNull(selectedAspectRatioIndex) ?: return fallbackPreviewObjectSourceAspect()
        return (option.width.toFloat() / option.height.toFloat().coerceAtLeast(1f)).coerceIn(0.05f, 20f)
    }

    private fun isVisualTimelineTrack(trackType: TrackType): Boolean =
        trackType == TrackType.VIDEO || trackType == TrackType.OVERLAY || trackType == TrackType.LAYER

    private fun importedNativeVisualClipCount(): Int =
        nativeClipTrackType.values.count { isVisualTimelineTrack(it) }

    private fun closestAspectRatioIndexForSource(sourceAspect: Float): Int {
        val safeAspect = sourceAspect.coerceIn(0.05f, 20f)
        return aspectRatioOptions.indices.minByOrNull { index ->
            val option = aspectRatioOptions[index]
            val optionAspect = (option.width.toFloat() / option.height.toFloat().coerceAtLeast(1f)).coerceIn(0.05f, 20f)
            abs(optionAspect - safeAspect)
        } ?: selectedAspectRatioIndex
    }

    private fun maybeAutoAdaptCanvasForImportedClip(sourceAspect: Float): Boolean {
        if (aspectRatioManuallySelected) return false
        if (importedNativeVisualClipCount() > 1) return false
        if (allTextOverlays().isNotEmpty() || StickerClipStore.all().isNotEmpty()) return false
        val targetIndex = closestAspectRatioIndexForSource(sourceAspect).coerceIn(0, aspectRatioOptions.lastIndex)
        val changed = targetIndex != selectedAspectRatioIndex
        selectedAspectRatioIndex = targetIndex
        applyPreviewAspectRatio()
        syncPreviewProOverlayUi()
        Log.d(
            TAG,
            "Auto canvas ratio ${aspectRatioOptions[targetIndex].label} changed=$changed sourceAspect=${"%.3f".format(Locale.US, sourceAspect)}",
        )
        return true
    }

    private fun previewObjectBaseSizePxForAspect(sourceAspect: Float): Pair<Float, Float> {
        val (viewportWidth, viewportHeight) = previewViewportSizePx()
        val safeSourceAspect = sourceAspect.coerceIn(0.05f, 20f)
        val viewportAspect = (viewportWidth / viewportHeight.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
        return if (safeSourceAspect > viewportAspect) {
            (viewportHeight * safeSourceAspect) to viewportHeight
        } else {
            viewportWidth to (viewportWidth / safeSourceAspect.coerceAtLeast(0.05f))
        }
    }

    private fun previewFitZoomForSourceAspect(sourceAspect: Float): Float {
        val (viewportWidth, viewportHeight) = previewViewportSizePx()
        val (baseWidth, baseHeight) = previewObjectBaseSizePxForAspect(sourceAspect)
        return minOf(
            viewportWidth / baseWidth.coerceAtLeast(1f),
            viewportHeight / baseHeight.coerceAtLeast(1f),
        ).coerceIn(PREVIEW_OBJECT_MIN_ZOOM, 1.0f)
    }

    private fun fitPreviewTransformForClip(clipId: Int, sourceAspect: Float? = null): ClipPreviewTransform {
        val resolvedAspect = sourceAspect ?: resolvePreviewObjectSourceAspect(clipId, allowMediaProbe = !isPreviewInteractionBusy())
        val fitZoom = previewFitZoomForSourceAspect(resolvedAspect)
        return normalizeClipPreviewTransformLocal(
            clipId,
            ClipPreviewTransform(zoom = fitZoom, panXPx = 0f, panYPx = 0f),
            allowNativeMetrics = false,
        )
    }

    private fun shouldSmartFitImportedClip(sourceAspect: Float): Boolean {
        val canvasAspect = selectedCanvasAspect()
        val mismatchRatio = (sourceAspect / canvasAspect.coerceAtLeast(0.05f)).coerceIn(0.05f, 20f)
        return mismatchRatio < 0.82f || mismatchRatio > 1.22f
    }

    private fun scheduleImportedClipSmartCrop(clipId: Int, sourcePath: String, trackType: TrackType, revealTimeMs: Long) {
        if (trackType != TrackType.VIDEO && trackType != TrackType.OVERLAY && trackType != TrackType.LAYER) return
        if (sourcePath.isBlank()) return
        Thread {
            val sourceAspect =
                decodeImageSourceAspect(sourcePath)
                    ?: decodeVideoSourceAspect(sourcePath)
            if (sourceAspect == null) {
                Log.w(TAG, "Imported clip aspect probe failed clip=$clipId path=$sourcePath")
                mainHandler.post { forceImportedPreviewLayoutAndRender(revealTimeMs) }
                return@Thread
            }
            mainHandler.post {
                if (nativeClipSourcePath[clipId] != sourcePath) return@post
                previewSourceAspectCache[sourcePath] = sourceAspect
                maybeAutoAdaptCanvasForImportedClip(sourceAspect)
                if (!clipPreviewTransforms.containsKey(clipId) && !nativeAppliedClipPreviewTransforms.containsKey(clipId)) {
                    applyImportedClipSmartCrop(clipId, sourceAspect)
                }
                forceImportedPreviewLayoutAndRender(revealTimeMs)
            }
        }
            .apply {
                name = "imported-clip-aspect-probe"
                start()
            }
    }

    private fun resetImportedVisualClipPreviewTransform(clipId: Int, trackType: TrackType) {
        if (!isVisualTimelineTrack(trackType)) return
        clipPreviewTransforms.remove(clipId)
        nativeAppliedClipPreviewTransforms.remove(clipId)
        previewView?.clearClipPreviewTransform(clipId, immediate = true)
    }

    private fun applyImportedClipSmartCrop(clipId: Int, sourceAspect: Float) {
        if (!shouldSmartFitImportedClip(sourceAspect)) return
        val fitTransform = fitPreviewTransformForClip(clipId, sourceAspect)
        if (fitTransform.zoom >= 0.98f) return
        clipPreviewTransforms[clipId] = fitTransform
        applyNormalizedClipPreviewTransformToNative(clipId, fitTransform, immediate = true)
        if (selectedVideoClipId() == clipId) {
            refreshPreviewCropStatus()
            syncPreviewProOverlayUi()
        }
        Log.d(
            TAG,
            "Smart crop fit applied clip=$clipId sourceAspect=${"%.3f".format(Locale.US, sourceAspect)} zoom=${"%.3f".format(Locale.US, fitTransform.zoom)}",
        )
    }

    private fun fallbackPreviewObjectSourceAspect(): Float {
        val (viewportWidth, viewportHeight) = previewViewportSizePx()
        return (viewportWidth / viewportHeight.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
    }

    private fun resolvePreviewObjectSourceAspect(
        clipId: Int,
        allowMediaProbe: Boolean = true,
    ): Float {
        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
        if (sourcePath.isNotBlank()) {
            previewSourceAspectCache[sourcePath]?.let { return it }
            if (!allowMediaProbe || isPreviewInteractionBusy()) {
                return fallbackPreviewObjectSourceAspect()
            }
            val resolvedAspect =
                decodeImageSourceAspect(sourcePath)
                    ?: decodeVideoSourceAspect(sourcePath)
            if (resolvedAspect != null && resolvedAspect.isFinite() && resolvedAspect > 0f) {
                previewSourceAspectCache[sourcePath] = resolvedAspect
                return resolvedAspect
            }
        }
        return fallbackPreviewObjectSourceAspect()
    }

    private fun previewObjectBaseSizePx(
        clipId: Int,
        allowMediaProbe: Boolean = true,
    ): Pair<Float, Float> {
        val sourceAspect = resolvePreviewObjectSourceAspect(clipId, allowMediaProbe)
        return previewObjectBaseSizePxForAspect(sourceAspect)
    }

    private fun selectedPreviewObjectFrameRect(): RectF? {
        val clipId = selectedVideoClipId() ?: return null
        if (!canDirectPreviewTransformSelectedClip()) return null
        val overlay = previewCropOverlayView ?: return null
        val overlayWidth = overlay.width.takeIf { it > 0 } ?: previewView?.width ?: return null
        val overlayHeight = overlay.height.takeIf { it > 0 } ?: previewView?.height ?: return null
        val transform = currentClipPreviewTransform(clipId, allowNativeFetch = !isPreviewInteractionBusy())
        val (baseWidth, baseHeight) =
            previewObjectBaseSizePx(clipId, allowMediaProbe = !isPreviewInteractionBusy())
        val renderedWidth = (baseWidth * transform.zoom * transform.scaleX).coerceAtLeast(previewDp(24f))
        val renderedHeight = (baseHeight * transform.zoom * transform.scaleY).coerceAtLeast(previewDp(24f))
        val radians = Math.toRadians(transform.rotationDeg.toDouble())
        val cosValue = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
        val sinValue = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
        val boundsWidth = (renderedWidth * cosValue) + (renderedHeight * sinValue)
        val boundsHeight = (renderedWidth * sinValue) + (renderedHeight * cosValue)
        val centerX = (overlayWidth * 0.5f) + transform.panXPx
        val centerY = (overlayHeight * 0.5f) + transform.panYPx
        return RectF(
            centerX - boundsWidth * 0.5f,
            centerY - boundsHeight * 0.5f,
            centerX + boundsWidth * 0.5f,
            centerY + boundsHeight * 0.5f,
        )
    }

    private fun placePreviewOverlayChild(view: View?, left: Float, top: Float, width: Float, height: Float) {
        view ?: return
        val nextParams =
            FrameLayout.LayoutParams(
                width.roundToInt().coerceAtLeast(1),
                height.roundToInt().coerceAtLeast(1),
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left.roundToInt()
                topMargin = top.roundToInt()
            }
        val current = view.layoutParams as? FrameLayout.LayoutParams
        if (
            current != null &&
            current.width == nextParams.width &&
            current.height == nextParams.height &&
            current.leftMargin == nextParams.leftMargin &&
            current.topMargin == nextParams.topMargin &&
            current.gravity == nextParams.gravity
        ) {
            return
        }
        view.layoutParams = nextParams
    }

    private fun layoutSelectedPreviewObjectFrame() {
        val overlay = previewCropOverlayView as? FrameLayout ?: return
        if (overlay.width <= 0 || overlay.height <= 0) {
            overlay.post { layoutSelectedPreviewObjectFrame() }
            return
        }
        val frameRect = selectedPreviewObjectFrameRect() ?: return
        val cornerSize = previewDp(26f)
        val sideLong = previewDp(42f)
        val sideShort = previewDp(18f)
        val minFrameSide = previewDp(36f)
        val frameWidth = frameRect.width().coerceAtLeast(minFrameSide)
        val frameHeight = frameRect.height().coerceAtLeast(minFrameSide)
        val left = frameRect.centerX() - frameWidth * 0.5f
        val top = frameRect.centerY() - frameHeight * 0.5f
        val right = left + frameWidth
        val bottom = top + frameHeight
        val centerX = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f

        placePreviewOverlayChild(previewCropFrameGuideView, left, top, frameWidth, frameHeight)
        placePreviewOverlayChild(previewResizeTopLeftHandleView, left - cornerSize * 0.5f, top - cornerSize * 0.5f, cornerSize, cornerSize)
        placePreviewOverlayChild(previewResizeTopHandleView, centerX - sideLong * 0.5f, top - sideShort * 0.5f, sideLong, sideShort)
        placePreviewOverlayChild(previewResizeTopRightHandleView, right - cornerSize * 0.5f, top - cornerSize * 0.5f, cornerSize, cornerSize)
        placePreviewOverlayChild(previewResizeRightHandleView, right - sideShort * 0.5f, centerY - sideLong * 0.5f, sideShort, sideLong)
        placePreviewOverlayChild(previewResizeBottomRightHandleView, right - cornerSize * 0.5f, bottom - cornerSize * 0.5f, cornerSize, cornerSize)
        placePreviewOverlayChild(previewResizeBottomHandleView, centerX - sideLong * 0.5f, bottom - sideShort * 0.5f, sideLong, sideShort)
        placePreviewOverlayChild(previewResizeBottomLeftHandleView, left - cornerSize * 0.5f, bottom - cornerSize * 0.5f, cornerSize, cornerSize)
        placePreviewOverlayChild(previewResizeLeftHandleView, left - sideShort * 0.5f, centerY - sideLong * 0.5f, sideShort, sideLong)
    }

    private fun selectedPreviewObjectHitRectOnScreen(extraPaddingPx: Float? = null): RectF? {
        val frame = previewCropFrameGuideView ?: return null
        if (frame.visibility != View.VISIBLE || frame.width <= 0 || frame.height <= 0) return null
        val padding = extraPaddingPx ?: previewDp(24f)
        val location = IntArray(2)
        frame.getLocationOnScreen(location)
        return RectF(
            location[0] - padding,
            location[1] - padding,
            location[0] + frame.width + padding,
            location[1] + frame.height + padding,
        )
    }

    private fun setPreviewCropMode(active: Boolean) {
        val wasActive = previewCropModeActive
        val nextActive = active && canUsePreviewCropMode() && startScreenOverlayView?.visibility != View.VISIBLE
        previewCropModeActive = nextActive
        previewCropModeClipKey = if (nextActive) selectedTimelineClipKey else null
        previewPanZoomControlsVisible = nextActive
        if (!nextActive) {
            previewTransformDragging = false
            previewTransformPinching = false
            previewTransformGestureClipId = null
            previewTrimSession = null
            previewSafeAreaVisible = false
            previewGridVisible = false
            previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        syncPreviewCropModeUi()
        updateBottomToolbarMode()
        if (wasActive != previewCropModeActive) {
            noteAppHealthAction(if (previewCropModeActive) "crop_opened" else "crop_closed")
        }
    }

    private fun refreshPreviewCropStatus(updateFrame: Boolean = true) {
        val clipId = selectedVideoClipId()
        val transform =
            clipId?.let { currentClipPreviewTransform(it, allowNativeFetch = !isPreviewInteractionBusy()) }
                ?: ClipPreviewTransform()
        val label = selectedNativeClipLabel()
        val minZoom = clipId?.let { resolvePreviewMinZoom(it, allowNativeFetch = !isPreviewInteractionBusy()) } ?: 1.0f
        val maxZoom = clipId?.let(::resolvePreviewMaxZoom) ?: 1.0f
        val nextLabel =
            "$label Edit • ${"%.2fx".format(Locale.US, transform.zoom.coerceIn(minZoom, maxZoom))} • Drag / Pinch / Rotate / Edge Resize / Tap"
        if (nextLabel != lastPreviewCropStatusLabel) {
            previewCropStatusText?.text = nextLabel
            lastPreviewCropStatusLabel = nextLabel
        }
        if (updateFrame && shouldShowDirectPreviewEdit() && canDirectPreviewTransformSelectedClip()) {
            layoutSelectedPreviewObjectFrame()
        }
        lastPreviewCropStatusRefreshElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun maybeRefreshPreviewCropStatusDuringGesture() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewCropStatusRefreshElapsedMs < 280L) {
            return
        }
        refreshPreviewCropStatus(updateFrame = false)
    }

    private fun currentClipPreviewTransform(
        clipId: Int,
        allowNativeFetch: Boolean = true,
    ): ClipPreviewTransform {
        localClipPreviewTransform(clipId)?.let { return it }
        if (!allowNativeFetch || isPreviewInteractionBusy()) {
            return ClipPreviewTransform()
        }
        val nativeValues = previewView?.getClipPreviewTransform(clipId)
        if (nativeValues != null && nativeValues.size >= 5) {
            return ClipPreviewTransform(
                zoom = nativeValues[0],
                panXPx = nativeValues[1],
                panYPx = nativeValues[2],
                rotationDeg = nativeValues[3],
                mirrorX = nativeValues[4] >= 0.5f,
                scaleX = nativeValues.getOrNull(5) ?: 1.0f,
                scaleY = nativeValues.getOrNull(6) ?: 1.0f,
            ).also { fetched ->
                if (
                    fetched.zoom > 1.001f ||
                    abs(fetched.scaleX - 1.0f) > 0.001f ||
                    abs(fetched.scaleY - 1.0f) > 0.001f ||
                    abs(fetched.panXPx) > 0.5f ||
                    abs(fetched.panYPx) > 0.5f ||
                    abs(fetched.rotationDeg) > 0.001f ||
                    fetched.mirrorX
                ) {
                    clipPreviewTransforms[clipId] = fetched
                }
            }
        }
        return clipPreviewTransforms[clipId] ?: ClipPreviewTransform()
    }

    private fun localClipPreviewTransform(clipId: Int): ClipPreviewTransform? {
        clipPreviewTransforms[clipId]?.let { return it }
        return nativeAppliedClipPreviewTransforms[clipId]?.toClipPreviewTransform()
    }

    private fun normalizePreviewRotationDeg(rotationDeg: Float): Float {
        var normalized = rotationDeg % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return if (abs(normalized) < 0.75f) 0f else normalized
    }

    private fun normalizeClipPreviewTransformLocal(
        clipId: Int,
        transform: ClipPreviewTransform,
        allowNativeMetrics: Boolean = true,
    ): ClipPreviewTransform {
        val minZoom = resolvePreviewMinZoom(clipId, allowNativeFetch = allowNativeMetrics)
        val maxZoom = resolvePreviewMaxZoom(clipId)
        val clampedZoom = transform.zoom.coerceIn(minZoom, maxZoom)
        val clampedScaleX = transform.scaleX.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
        val clampedScaleY = transform.scaleY.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
        val viewportWidthPx =
            (previewView?.width ?: previewContainerView?.width ?: previewViewportFrame?.width ?: 1)
                .coerceAtLeast(1)
                .toFloat()
        val viewportHeightPx =
            (previewView?.height ?: previewContainerView?.height ?: previewViewportFrame?.height ?: 1)
                .coerceAtLeast(1)
                .toFloat()
        val panLimitX = viewportWidthPx * maxOf(0.55f, clampedZoom * clampedScaleX * 1.1f)
        val panLimitY = viewportHeightPx * maxOf(0.55f, clampedZoom * clampedScaleY * 1.1f)
        return transform.copy(
            zoom = clampedZoom,
            scaleX = clampedScaleX,
            scaleY = clampedScaleY,
            panXPx = transform.panXPx.coerceIn(-panLimitX, panLimitX),
            panYPx = transform.panYPx.coerceIn(-panLimitY, panLimitY),
            rotationDeg = normalizePreviewRotationDeg(transform.rotationDeg),
        )
    }

    private fun resolvePreviewMinZoom(clipId: Int, allowNativeFetch: Boolean = true): Float {
        if (allowNativeFetch && !isPreviewInteractionBusy()) {
            previewView?.let { return it.getClipPreviewMinZoom(clipId).coerceIn(PREVIEW_OBJECT_MIN_ZOOM, 1.0f) }
        }
        return when (nativeClipTrackType[clipId]) {
            TrackType.VIDEO,
            TrackType.OVERLAY,
            TrackType.LAYER,
            -> PREVIEW_OBJECT_MIN_ZOOM
            else -> 1.0f
        }
    }

    private fun resolvePreviewMaxZoom(clipId: Int): Float {
        return when (nativeClipTrackType[clipId]) {
            TrackType.VIDEO,
            TrackType.OVERLAY,
            TrackType.LAYER,
            -> PREVIEW_OBJECT_MAX_ZOOM
            else -> 4.0f
        }
    }

    private fun canPreviewTrimClip(clipId: Int): Boolean {
        return !isStillImageClip(clipId)
    }

    private fun formatPreviewTrimTime(timeMs: Long): String {
        val clamped = timeMs.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        return String.format(Locale.US, "%02d:%02d.%02d", totalSeconds / 60L, totalSeconds % 60L, (clamped % 1000L) / 10L)
    }

    private fun resolveClipSourceDurationMs(clipId: Int): Long {
        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
        if (sourcePath.isBlank()) {
            val sourceInMs = nativeClipSourceInMs[clipId] ?: 0L
            return (nativeClipSourceOutMs[clipId] ?: (sourceInMs + 1L)).coerceAtLeast(sourceInMs + 1L)
        }
        sourceDurationCacheMs[sourcePath]?.let { return it }
        val fallback = (nativeClipSourceOutMs[clipId] ?: 0L).coerceAtLeast(1L)
        val durationMs =
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (sourcePath.startsWith("content://")) {
                        retriever.setDataSource(this, Uri.parse(sourcePath))
                    } else if (File(sourcePath).exists()) {
                        retriever.setDataSource(sourcePath)
                    } else {
                        return@runCatching fallback
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1L)
                        ?: fallback
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrDefault(fallback)
        sourceDurationCacheMs[sourcePath] = durationMs
        return durationMs
    }

    private fun resolveMediaSourceDurationMs(sourcePath: String, fallbackMs: Long): Long {
        val safeFallbackMs = fallbackMs.coerceAtLeast(1L)
        if (sourcePath.isBlank()) return safeFallbackMs
        sourceDurationCacheMs[sourcePath]?.let { return it.coerceAtLeast(1L) }
        val durationMs =
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (sourcePath.startsWith("content://")) {
                        retriever.setDataSource(this, Uri.parse(sourcePath))
                    } else if (File(sourcePath).exists()) {
                        retriever.setDataSource(sourcePath)
                    } else {
                        return@runCatching safeFallbackMs
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1L)
                        ?: safeFallbackMs
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrDefault(safeFallbackMs)
        sourceDurationCacheMs[sourcePath] = durationMs
        return durationMs
    }

    private fun rememberMediaSourceDuration(sourcePath: String, durationMs: Long) {
        if (sourcePath.isBlank() || durationMs <= 0L || isImageLikeSourcePath(sourcePath)) return
        sourceDurationCacheMs[sourcePath] = maxOf(sourceDurationCacheMs[sourcePath] ?: 0L, durationMs)
    }

    private fun knownNativeClipSourceDurationMs(clipId: Int): Long {
        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
        if (sourcePath.isBlank() || isImageLikeSourcePath(sourcePath)) return 0L
        return sourceDurationCacheMs[sourcePath]?.coerceAtLeast(1L) ?: 0L
    }

    private fun nativeClipSourceDurationMetadata(clipId: Int): Map<String, String> {
        val sourceDurationMs = knownNativeClipSourceDurationMs(clipId)
        return if (sourceDurationMs > 0L) {
            mapOf("sourceDurationMs" to sourceDurationMs.toString())
        } else {
            emptyMap()
        }
    }

    private fun clampAudioClipDurationToSource(
        sourcePath: String,
        requestedDurationMs: Long,
        fallbackMs: Long = requestedDurationMs,
    ): Long {
        val safeRequestedMs = requestedDurationMs.coerceAtLeast(1L)
        val sourceDurationMs = resolveMediaSourceDurationMs(sourcePath, fallbackMs.coerceAtLeast(safeRequestedMs))
        return minOf(safeRequestedMs, sourceDurationMs.coerceAtLeast(1L)).coerceAtLeast(1L)
    }

    private fun clampNativeAudioTimingToSource(
        clipId: Int,
        sourcePath: String,
        startTimeMs: Long,
        durationMs: Long,
        sourceInMs: Long,
        sourceOutMs: Long,
        updateNative: Boolean,
    ): Long {
        val safeStartMs = startTimeMs.coerceAtLeast(0L)
        val safeSourceInMs = sourceInMs.coerceAtLeast(0L)
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val clampedDurationMs = clampAudioClipDurationToSource(
            sourcePath = sourcePath,
            requestedDurationMs = safeDurationMs,
            fallbackMs = safeDurationMs,
        )
        val clampedSourceOutMs = safeSourceInMs + clampedDurationMs
        val needsNativeUpdate =
            clampedDurationMs != safeDurationMs ||
                sourceOutMs.coerceAtLeast(safeSourceInMs + 1L) != clampedSourceOutMs
        if (updateNative && needsNativeUpdate) {
            execCmd(
                action = "UPDATE_CLIP_TIMING",
                params = mapOf(
                    "clipId" to clipId,
                    "newStartTimeMs" to safeStartMs,
                    "newDurationMs" to clampedDurationMs,
                    "newSourceInMs" to safeSourceInMs,
                    "newSourceOutMs" to clampedSourceOutMs,
                    "originalStartTimeMs" to safeStartMs,
                    "originalDurationMs" to safeDurationMs,
                    "originalSourceInMs" to safeSourceInMs,
                    "originalSourceOutMs" to sourceOutMs.coerceAtLeast(safeSourceInMs + 1L),
                    "previewOnly" to false,
                    "applyMagnetic" to false,
                )
            ) { result ->
                if (result.success) {
                    Log.d(TAG, "Clamped audio clip timing id=$clipId duration=${clampedDurationMs}ms")
                    lastLayoutFetchMs = 0L
                } else {
                    Log.w(TAG, "Audio timing clamp failed id=$clipId message=${result.message}")
                }
            }
        }
        return clampedDurationMs
    }

    private fun syncPreviewCropModeUi() {
        val showCropUi = shouldShowDirectPreviewEdit()
        previewCropModeActive = showCropUi
        if (!showCropUi) {
            previewCropModeClipKey = null
            previewTrimSession = null
            previewPanZoomControlsVisible = false
        } else {
            previewCropModeClipKey = selectedTimelineClipKey
            if (previewTrimSession?.clipId != selectedVideoClipId()) {
                previewTrimSession = null
            }
        }
        val showPanZoomControls = showCropUi && previewPanZoomControlsVisible
        val showDirectResizeHandles = showCropUi && canDirectPreviewTransformSelectedClip()
        previewCropOverlayView?.visibility = if (showCropUi) View.VISIBLE else View.GONE
        previewCropFrameGuideView?.visibility = if (showDirectResizeHandles) View.VISIBLE else View.GONE
        previewCropFrameGuideView?.alpha = 0f
        findViewById<View?>(R.id.previewCropTopRail)?.visibility = if (showPanZoomControls) View.VISIBLE else View.GONE
        findViewById<View?>(R.id.previewCropActionRail)?.visibility = if (showPanZoomControls) View.VISIBLE else View.GONE
        previewTrimSession = null
        previewResizeSession = null
        previewTrimStartHandleView?.visibility = View.GONE
        previewTrimEndHandleView?.visibility = View.GONE
        previewResizeHandleViews().forEach { handle ->
            handle?.visibility = View.GONE
        }
        findViewById<View?>(R.id.playbackUndoRedoRow)?.apply {
            visibility = View.VISIBLE
            bringToFront()
        }
        if (showCropUi) {
            refreshPreviewCropStatus()
            if (showDirectResizeHandles) {
                layoutSelectedPreviewObjectFrame()
                previewCropFrameGuideView?.bringToFront()
            }
            previewCropOverlayView?.bringToFront()
            if (showPanZoomControls) {
                findViewById<View?>(R.id.previewCropTopRail)?.bringToFront()
                findViewById<View?>(R.id.previewCropActionRail)?.bringToFront()
            }
            findViewById<View?>(R.id.playbackUndoRedoRow)?.bringToFront()
        }
        syncPreviewProOverlayUi()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeDismissStaleImportPicker(intent)
        maybeHandleAutomationIntent(intent)
    }



    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        if (startShellOnly) return
        val resumeAnchorMs = rawCurrentPlayheadMs().coerceAtLeast(currentTimeMs.coerceAtLeast(0L))
        rememberPreviewLifecycleAnchor(resumeAnchorMs)
        previewLifecycleRestoreUntilElapsedMs =
            maxOf(previewLifecycleRestoreUntilElapsedMs, SystemClock.elapsedRealtime() + PREVIEW_LIFECYCLE_RESTORE_WINDOW_MS)
        projectController?.cancelPendingAutoSave()
        val startupDelayMs = startupHeavyWorkDelayMs()
        if (startupDelayMs > 0L) {
            previewView?.onResume()
            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                playbackController?.onResume()
                importController?.maybeImportPendingClip()
                maybeSeedFirebaseTestLabWorkspace()
            }, startupDelayMs)
        } else {
            playbackController?.onResume()  // handles previewView.onResume() internally
            importController?.maybeImportPendingClip()
            maybeSeedFirebaseTestLabWorkspace()
        }
        if (!isAutomationPerfMode()) {
            startHardwareTelemetryTicker()
            adsController?.onResume()
            scheduleTopBannerPlacement(delayMs = 6_500L)
            startAppHealthHeartbeat()
        } else {
            stopHardwareTelemetryTicker()
            stopAppHealthHeartbeat()
        }
        if (::uiFreezeWatchdog.isInitialized && !isAutomationPerfMode()) {
            if (startupDelayMs > 0L) {
                mainHandler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        uiFreezeWatchdog.start()
                    }
                }, startupDelayMs)
            } else {
                uiFreezeWatchdog.start()
            }
        }
        if (::appHealthReporter.isInitialized) {
            appHealthReporter.recordForeground(
                screen = currentHealthScreenName(),
                hasContent = hasProjectContent(),
                playing = isPlaying,
            )
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        rememberPreviewLifecycleAnchor(rawCurrentPlayheadMs().coerceAtLeast(currentTimeMs.coerceAtLeast(0L)))
        cancelContinuePreviewAudioSeek()
        stopHardwareTelemetryTicker()
        stopAppHealthHeartbeat()
        if (::uiFreezeWatchdog.isInitialized) {
            uiFreezeWatchdog.stop()
        }
        if (::playbackExportIssueDetector.isInitialized) {
            playbackExportIssueDetector.onPlaybackPaused()
        }
        mainHandler.removeCallbacks(bannerRefreshRunnable)
        if (::opsReporter.isInitialized) {
            opsReporter.stopTrace(
                slot = "playback_session",
                success = true,
                attributes = mapOf("screen" to currentHealthScreenName(), "paused" to "true"),
            )
        }
        val savedNow = saveCurrentSessionNow("activity_pause")
        playbackController?.onPause()  // handles previewView.onPause() internally
        exportController?.dismissProgressDialog()
        mainHandler.removeCallbacks(postExportInterstitialRunnable)
        postExportInterstitialPending = false
        postExportInterstitialRetryCount = 0
        adsController?.onPause()
        if (!savedNow) {
            scheduleSessionAutoSave(
                reason = "activity_pause_backup",
                delayMs = if (shouldDeferHeavyUiWork()) 6000L else 2500L,
            )
        }
        if (::appHealthReporter.isInitialized) {
            appHealthReporter.recordBackground(
                screen = currentHealthScreenName(),
                hasContent = hasProjectContent(),
                playing = isPlaying,
            )
        }
        super.onPause()
    }

    override fun onStop() {
        val savedNow = saveCurrentSessionNowIfStale("activity_stop")
        if (!savedNow) {
            scheduleSessionAutoSave(
                reason = "activity_stop_backup",
                delayMs = if (shouldDeferHeavyUiWork()) 6000L else 2500L,
            )
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopHardwareTelemetryTicker()
        stopAppHealthHeartbeat()
        mainHandler.removeCallbacks(refreshTimelineRunnable)
        cancelContinuePreviewAudioSeek()
        cancelPendingPreviewAudioSeek()
        cancelPreviewAudioGestureResume()
        saveCurrentSessionNowIfStale("activity_destroy")
        previewAudioPlayer?.release()
        voiceoverController?.release()
        rewardedUnlockController?.onDestroy()
        captureDebugSnapshot("activity_destroy")
        if (!startShellOnly) {
            NativeBridge.setTelemetrySink(null)
        }
        if (::opsReporter.isInitialized) {
            opsReporter.close()
        }
        if (::uiFreezeWatchdog.isInitialized) {
            uiFreezeWatchdog.close()
        }
        if (::playbackExportIssueDetector.isInitialized) {
            playbackExportIssueDetector.close()
        }
        if (::uiActionExpectationDetector.isInitialized) {
            uiActionExpectationDetector.close()
        }
        if (::remoteCommandManager.isInitialized) {
            remoteCommandManager.close()
        }
        if (::problemReportManager.isInitialized) {
            problemReportManager.close()
        }
        if (::appHealthReporter.isInitialized) {
            appHealthReporter.close(
                screen = currentHealthScreenName(),
                hasContent = hasProjectContent(),
                playing = isPlaying,
            )
        }
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
        if (::appHealthReporter.isInitialized && category in setOf("session", "project", "import", "export", "automation")) {
            appHealthReporter.noteAction(
                action = "${category}_${name}".take(120),
                screen = currentHealthScreenName(),
                hasContent = hasProjectContent(),
                playing = isPlaying,
                force = category == "export" || category == "automation",
            )
        }
    }

    private fun captureDebugSnapshot(label: String) {
        if (!::debugTelemetryManager.isInitialized) return
        debugTelemetryManager.captureSnapshot(label, buildDebugTelemetrySnapshot(label))
    }

    private fun currentHealthScreenName(): String = when {
        startScreenOverlayView?.visibility == View.VISIBLE -> "home"
        previewCropOverlayView?.visibility == View.VISIBLE -> "crop"
        else -> "editor"
    }

    private fun syncAppHealth(force: Boolean = false, action: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncAppHealth(force = force, action = action) }
            return
        }
        if (!::appHealthReporter.isInitialized) return
        if (!action.isNullOrBlank()) {
            latestHealthAction = action.take(120)
            refreshAiCompanionUi()
            appHealthReporter.noteAction(
                action = action,
                screen = currentHealthScreenName(),
                hasContent = hasProjectContent(),
                playing = isPlaying,
                force = force,
            )
            return
        }
        appHealthReporter.updateSurface(
            screen = currentHealthScreenName(),
            hasContent = hasProjectContent(),
            playing = isPlaying,
            force = force,
        )
        refreshAiCompanionUi()
    }

    private fun noteAppHealthAction(action: String, force: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { noteAppHealthAction(action, force) }
            return
        }
        val actionName = action.take(120)
        latestHealthAction = actionName
        refreshAiCompanionUi()
        if (::uiActionExpectationDetector.isInitialized) {
            uiActionExpectationDetector.noteHealthAction(actionName)
        }
        if (::opsReporter.isInitialized) {
            opsReporter.noteAction(
                action = actionName,
                metadata = mapOf(
                    "screen" to currentHealthScreenName(),
                    "playing" to isPlaying,
                    "project_loaded" to hasProjectContent(),
                ),
            )
        }
        val effectiveForce = force || (::opsReporter.isInitialized && opsReporter.shouldForceFlush(actionName))
        syncAppHealth(force = effectiveForce, action = actionName)
    }

    private fun noteUiButtonTap(control: String, surface: String, mode: String = "tap") {
        if (::uiActionExpectationDetector.isInitialized) {
            uiActionExpectationDetector.noteUiButtonTap(
                control = control,
                surface = surface,
                mode = mode,
                screen = currentHealthScreenName(),
            )
        }
        if (::problemReportManager.isInitialized) {
            problemReportManager.noteUiTap(control = control, surface = surface, mode = mode)
        }
        if (::opsReporter.isInitialized) {
            opsReporter.noteAction(
                action = "ui_button_tap",
                metadata = mapOf(
                    "control" to control,
                    "surface" to surface,
                    "mode" to mode,
                    "screen" to currentHealthScreenName(),
                ),
            )
        }
        if (::debugTelemetryManager.isInitialized) {
            recordTelemetryEvent(
                category = "ui",
                name = "button_tap",
                payload = JSONObject()
                    .put("control", control)
                    .put("surface", surface)
                    .put("mode", mode)
                    .put("screen", currentHealthScreenName()),
            )
        }
        scheduleUiButtonAutoSave(control, surface)
    }

    private fun scheduleUiButtonAutoSave(control: String, surface: String) {
        if (startShellOnly || !hasProjectContent()) return
        if (surface == "start_screen") return
        val delayMs =
            when {
                control == "play_pause" -> TIMELINE_BUSY_AUTOSAVE_DELAY_MS
                shouldDeferHeavyUiWork() -> 6_000L
                else -> 2_000L
            }
        scheduleSessionAutoSave("ui_button_${control.take(32)}", delayMs = delayMs)
    }

    private fun showProblemReportDialog(source: String) {
        if (!::problemReportManager.isInitialized || !::debugTelemetryManager.isInitialized) return
        val input = EditText(this).apply {
            hint = "What went wrong? lag, import, export, playback, UI..."
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Report Problem")
            .setMessage("This sends current screen, last action, recent button taps, and device info.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                val description = input.text?.toString().orEmpty().trim()
                if (description.isBlank()) {
                    safeToast("Write a short problem note", Toast.LENGTH_SHORT)
                    return@setPositiveButton
                }
                noteUiButtonTap("report_problem_send", source, mode = "dialog")
                problemReportManager.submitManualReport(
                    description = description,
                    source = source,
                    sessionId = debugTelemetryManager.sessionId,
                    currentScreen = currentHealthScreenName(),
                    lastAction = latestHealthAction,
                    hasProjectContent = hasProjectContent(),
                    isPlaying = isPlaying,
                    versionName = appVersionName(),
                    versionCode = appVersionCode(),
                ) { success ->
                    mainHandler.post {
                        if (success) {
                            noteAppHealthAction("problem_report_submitted")
                            safeToast("Problem report sent", Toast.LENGTH_SHORT)
                        } else {
                            safeToast("Problem report failed", Toast.LENGTH_SHORT)
                        }
                    }
                }
            }
            .show()
    }

    private fun submitAutoDetectorIssue(
        reportType: String,
        source: String,
        description: String,
    ) {
        if (!::problemReportManager.isInitialized || !::debugTelemetryManager.isInitialized) return
        recordTelemetryEvent(
            category = "ops",
            name = "auto_issue_detected",
            payload = JSONObject()
                .put("reportType", reportType)
                .put("source", source)
                .put("screen", currentHealthScreenName())
                .put("lastAction", latestHealthAction)
                .put("description", description.take(160)),
        )
        if (::opsReporter.isInitialized) {
            opsReporter.noteAction(
                action = "auto_issue_detected",
                metadata = mapOf(
                    "report_type" to reportType,
                    "source" to source,
                    "screen" to currentHealthScreenName(),
                    "last_action" to latestHealthAction,
                ),
            )
        }
        problemReportManager.submitAutoDetectedReport(
            reportType = reportType,
            description = description,
            source = source,
            cooldownKey = "${reportType}:${source}",
            sessionId = debugTelemetryManager.sessionId,
            currentScreen = currentHealthScreenName(),
            lastAction = latestHealthAction,
            hasProjectContent = hasProjectContent(),
            isPlaying = isPlaying,
            versionName = appVersionName(),
            versionCode = appVersionCode(),
        ) { success ->
            if (success) {
                mainHandler.post {
                    noteAppHealthAction("${reportType}_issue_reported")
                }
            }
        }
    }

    private fun startAppHealthHeartbeat() {
        if (isAutomationPerfMode()) return
        mainHandler.removeCallbacks(appHealthHeartbeatRunnable)
        val delayMs =
            if (::opsReporter.isInitialized) {
                opsReporter.heartbeatIntervalMs()
            } else {
                45_000L
            }
        mainHandler.postDelayed(appHealthHeartbeatRunnable, delayMs)
    }

    private fun stopAppHealthHeartbeat() {
        mainHandler.removeCallbacks(appHealthHeartbeatRunnable)
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

    private fun buildNativeClipUiStateSnapshot(): List<NativeClipUiState> {
        val timelineClipsById = timelineManager?.getClips().orEmpty().associateBy { it.id }
        val clipIds = linkedSetOf<Int>().apply {
            addAll(nativeClipTrackType.keys)
            addAll(nativeClipLane.keys)
            addAll(nativeClipZOrder.keys)
            addAll(nativeClipStartMs.keys)
            addAll(nativeClipDurationMs.keys)
            addAll(nativeClipSourcePath.keys)
            addAll(timelineClipsById.keys)
            addAll(AudioClipStore.all().map { it.id })
        }
        return clipIds
            .filter { it > 0 }
            .sorted()
            .map { clipId ->
                val fallbackZ = timelineManager?.getClipLayerIndex(clipId) ?: 0
                val zOrder = restoredNativeClipZOrderOverrides[clipId] ?: nativeClipZOrder[clipId] ?: fallbackZ
                val trackType =
                    restoredNativeClipTrackTypeOverrides[clipId]
                        ?: nativeClipTrackType[clipId]
                        ?: TrackType.fromNativeRole(null, zOrder)
                val storedAudioClip = AudioClipStore.get(clipId)
                val durationMs =
                    if (trackType == TrackType.AUDIO && storedAudioClip != null) {
                        storedAudioClip.durationMs
                    } else {
                        nativeClipDurationMs[clipId]
                            ?: storedAudioClip?.durationMs
                            ?: timelineClipsById[clipId]?.durationMs
                            ?: 1L
                    }
                val sourceInMs = if (trackType == TrackType.AUDIO) 0L else nativeClipSourceInMs[clipId] ?: 0L
                NativeClipUiState(
                    id = clipId,
                    trackType = trackType,
                    trackLane =
                        restoredNativeClipLaneOverrides[clipId]
                            ?: nativeClipLane[clipId]
                            ?: AudioClipStore.get(clipId)?.layerIndex
                            ?: 0,
                    zOrder = normalizeTrackZOrder(trackType, zOrder),
                    startTimeMs = nativeClipStartMs[clipId] ?: AudioClipStore.get(clipId)?.startTimeMs ?: 0L,
                    durationMs = durationMs.coerceAtLeast(1L),
                    sourceInMs = sourceInMs,
                    sourceOutMs =
                        if (trackType == TrackType.AUDIO) {
                            sourceInMs + durationMs.coerceAtLeast(1L)
                        } else {
                            nativeClipSourceOutMs[clipId] ?: (sourceInMs + durationMs.coerceAtLeast(1L))
                        },
                    sourcePath = nativeClipSourcePath[clipId].orEmpty()
                        .ifBlank { AudioClipStore.get(clipId)?.sourcePath.orEmpty() },
                    sourceDurationMs = knownNativeClipSourceDurationMs(clipId),
                    effectParams = clipEffects[clipId] ?: EffectParams(),
                )
            }
    }

    private fun restoreNativeClipUiState(states: List<NativeClipUiState>) {
        if (states.isEmpty()) return
        val remappedStates = remapRestoredNativeClipUiStates(states)
        val restoredVisualTimings = mutableListOf<NativeClipUiState>()
        var audioTimingClamped = false
        AudioClipStore.batchUpdate {
            remappedStates.forEach { state ->
                val normalizedZ = normalizeTrackZOrder(state.trackType, state.zOrder)
                val restoredDurationMs =
                    if (state.trackType == TrackType.AUDIO) {
                        clampNativeAudioTimingToSource(
                            clipId = state.id,
                            sourcePath = state.sourcePath,
                            startTimeMs = state.startTimeMs,
                            durationMs = state.durationMs,
                            sourceInMs = state.sourceInMs,
                            sourceOutMs = state.sourceOutMs,
                            updateNative = true,
                        )
                    } else {
                        state.durationMs.coerceAtLeast(1L)
                    }
                if (state.trackType == TrackType.AUDIO &&
                    (restoredDurationMs != state.durationMs ||
                        state.sourceOutMs.coerceAtLeast(state.sourceInMs + 1L) != state.sourceInMs + restoredDurationMs)
                ) {
                    audioTimingClamped = true
                }
                restoredNativeClipTrackTypeOverrides[state.id] = state.trackType
                restoredNativeClipLaneOverrides[state.id] = state.trackLane
                restoredNativeClipZOrderOverrides[state.id] = normalizedZ
                nativeClipTrackType[state.id] = state.trackType
                nativeClipLane[state.id] = state.trackLane
                nativeClipZOrder[state.id] = normalizedZ
                nativeClipStartMs[state.id] = state.startTimeMs
                nativeClipDurationMs[state.id] = restoredDurationMs
                nativeClipSourceInMs[state.id] = state.sourceInMs
                nativeClipSourceOutMs[state.id] =
                    if (state.trackType == TrackType.AUDIO) {
                        state.sourceInMs + restoredDurationMs
                    } else {
                        state.sourceOutMs.coerceAtLeast(state.sourceInMs + 1L)
                    }
                if (state.effectParams == EffectParams()) {
                    clipEffects.remove(state.id)
                } else {
                    clipEffects[state.id] = state.effectParams
                    previewView?.let { pv ->
                        NativeBridge.setClipEffects(
                            pv,
                            state.id,
                            state.effectParams.brightness,
                            state.effectParams.contrast,
                            state.effectParams.saturation,
                        )
                    } ?: execCmd(
                        action = "SET_CLIP_EFFECTS",
                        params = mapOf(
                            "clipId" to state.id,
                            "brightness" to state.effectParams.brightness,
                            "contrast" to state.effectParams.contrast,
                            "saturation" to state.effectParams.saturation,
                        )
                    )
                }
                rememberMediaSourceDuration(state.sourcePath, state.sourceDurationMs)
                if (state.sourcePath.isNotBlank()) {
                    nativeClipSourcePath[state.id] = state.sourcePath
                }
                if (state.trackType == TrackType.VIDEO || state.trackType == TrackType.OVERLAY || state.trackType == TrackType.LAYER) {
                    restoredVisualTimings += state.copy(
                        zOrder = normalizedZ,
                        durationMs = restoredDurationMs,
                        sourceOutMs = nativeClipSourceOutMs[state.id] ?: state.sourceOutMs,
                    )
                }
                if (state.trackType == TrackType.AUDIO) {
                    val sourcePath = state.sourcePath
                    val displayName =
                        File(sourcePath).nameWithoutExtension.takeIf { it.isNotBlank() }
                            ?: "Audio ${state.id}"
                    AudioClipStore.add(
                        AudioClip(
                            id = state.id,
                            sourcePath = sourcePath,
                            displayName = displayName,
                            startTimeMs = state.startTimeMs,
                            durationMs = restoredDurationMs,
                            gain = audioClipGainOverrides[state.id] ?: 1f,
                            layerIndex = state.trackLane,
                            visible = true,
                            muted = (audioClipGainOverrides[state.id] ?: 1f) <= 0.001f,
                        ),
                    )
                    nextAudioClipId = maxOf(nextAudioClipId, state.id + 1)
                }
                execCmd(
                    action = "SET_CLIP_TRACK",
                    params = mapOf(
                        "clipId" to state.id,
                        "trackType" to state.trackType.nativeRoleName(),
                        "trackLane" to state.trackLane,
                        "zOrder" to normalizedZ,
                    ),
                )
            }
        }
        restoreNativeVisualClipTimings(restoredVisualTimings)
        lastLayoutFetchMs = 0L
        lastPreviewAudioSyncSignature = ""
        if (audioTimingClamped) {
            scheduleSessionAutoSave("restore_audio_duration_clamp", delayMs = 1200L)
        }
    }

    private fun restoreNativeVisualClipTimings(states: List<NativeClipUiState>) {
        states
            .sortedWith(compareBy<NativeClipUiState> { it.startTimeMs }.thenBy { it.id })
            .forEach { state ->
                val safeDurationMs = state.durationMs.coerceAtLeast(1L)
                val safeSourceInMs = state.sourceInMs.coerceAtLeast(0L)
                val safeSourceOutMs = state.sourceOutMs.coerceAtLeast(safeSourceInMs + safeDurationMs)
                execCmd(
                    action = "UPDATE_CLIP_TIMING",
                    params = mapOf(
                        "clipId" to state.id,
                        "newStartTimeMs" to state.startTimeMs.coerceAtLeast(0L),
                        "newDurationMs" to safeDurationMs,
                        "newSourceInMs" to safeSourceInMs,
                        "newSourceOutMs" to safeSourceOutMs,
                        "originalStartTimeMs" to state.startTimeMs.coerceAtLeast(0L),
                        "originalDurationMs" to safeDurationMs,
                        "originalSourceInMs" to safeSourceInMs,
                        "originalSourceOutMs" to safeSourceOutMs,
                        "previewOnly" to false,
                        "applyMagnetic" to false,
                    ),
                ) { result ->
                    if (!result.success) {
                        Log.w(TAG, "Failed to restore native clip timing clip=${state.id}: ${result.message}")
                    }
                }
            }
    }

    private fun remapRestoredNativeClipUiStates(states: List<NativeClipUiState>): List<NativeClipUiState> {
        val layoutResult = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
        val clipsJson = layoutResult?.takeIf { it.success }?.data?.optJSONArray("clips") ?: return states
        val loaded = buildList {
            for (index in 0 until clipsJson.length()) {
                val clipJson = clipsJson.optJSONObject(index) ?: continue
                val clipId = clipJson.optInt("clipId", -1)
                if (clipId <= 0) continue
                val zOrder = clipJson.optInt("zOrder", 0)
                val sourcePath =
                    clipJson.optString("originalSourcePath")
                        .ifBlank { clipJson.optString("sourcePath", "") }
                add(
                    NativeClipUiState(
                        id = clipId,
                        trackType = mapNativeTrackType(
                            trackTypeRaw = clipJson.optString("trackType", "VIDEO"),
                            zOrder = zOrder,
                            mediaTypeRaw = clipJson.optString("mediaType", ""),
                            sourcePath = sourcePath,
                        ),
                        trackLane = clipJson.optInt("trackLane", 0).coerceAtLeast(0),
                        zOrder = zOrder,
                        startTimeMs = clipJson.optLong("startTimeMs", 0L).coerceAtLeast(0L),
                        durationMs = clipJson.optLong("durationMs", 1L).coerceAtLeast(1L),
                        sourceInMs = clipJson.optLong("sourceInMs", 0L).coerceAtLeast(0L),
                        sourceOutMs = clipJson.optLong("sourceOutMs", 1L).coerceAtLeast(1L),
                        sourcePath = sourcePath,
                    ),
                )
            }
        }
        if (loaded.isEmpty()) return states
        val usedIds = mutableSetOf<Int>()
        return states.map { saved ->
            val match = loaded
                .filter { it.id !in usedIds && sameRestoredSource(it.sourcePath, saved.sourcePath) }
                .minWithOrNull(
                    compareBy<NativeClipUiState>(
                        { if (it.id == saved.id) 0 else 1 },
                        { if (it.trackType == saved.trackType) 0 else 1 },
                        { abs(normalizeTrackZOrder(saved.trackType, it.zOrder) - normalizeTrackZOrder(saved.trackType, saved.zOrder)) },
                    ),
                )
            if (match != null) {
                usedIds += match.id
                saved.copy(
                    id = match.id,
                    sourcePath = saved.sourcePath.ifBlank { match.sourcePath },
                )
            } else {
                saved
            }
        }
    }

    private fun sameRestoredSource(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        return File(left).name == File(right).name
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
        execCmd(
            action = "SET_CLIP_AUDIO_KEYFRAMES",
            params = mapOf(
                "clipId" to audioId,
                "keyframesCsv" to NativeBridge.serializeAudioGainKeyframesCsv(normalized),
            )
        ) {
            refreshMainTimelineTracks()
            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
        }
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
        val normalized = normalizeAudioGainKeyframes(clip.gainKeyframes, clip.durationMs)
        val previousKeyframe = normalized.filter { it.timeMs < localTimeMs }.maxByOrNull { it.timeMs }
        val nextKeyframe = normalized.filter { it.timeMs > localTimeMs }.minByOrNull { it.timeMs }
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
            chips(
                "Navigate",
                listOf(
                    previousKeyframe?.let { "Prev ${formatAutomationTime(clip.startTimeMs + it.timeMs)}" } ?: "No Prev",
                    "Now ${formatAutomationTime(clip.startTimeMs + localTimeMs)}",
                    nextKeyframe?.let { "Next ${formatAutomationTime(clip.startTimeMs + it.timeMs)}" } ?: "No Next",
                ),
            ) { index, _ ->
                when (index) {
                    0 -> previousKeyframe?.let { applyEditorPlayhead(clip.startTimeMs + it.timeMs) } ?: safeToast("No previous keyframe", Toast.LENGTH_SHORT)
                    1 -> applyEditorPlayhead(clip.startTimeMs + localTimeMs)
                    2 -> nextKeyframe?.let { applyEditorPlayhead(clip.startTimeMs + it.timeMs) } ?: safeToast("No next keyframe", Toast.LENGTH_SHORT)
                }
            }
            chipGrid("Quick", listOf("0%", "50%", "100%", "150%", "200%", "Reset Curve"), columns = 3) { _, option ->
                when (option) {
                    "0%" -> updateAudioKeyframePoint(audioId, clip, localTimeMs, 0f)
                    "50%" -> updateAudioKeyframePoint(audioId, clip, localTimeMs, 0.5f)
                    "100%" -> updateAudioKeyframePoint(audioId, clip, localTimeMs, 1f)
                    "150%" -> updateAudioKeyframePoint(audioId, clip, localTimeMs, 1.5f)
                    "200%" -> updateAudioKeyframePoint(audioId, clip, localTimeMs, 2f)
                    "Reset Curve" -> applyAudioGainKeyframes(audioId, clip, emptyList())
                }
            }
        }
    }

    private fun normalizeTimelineKeyframes(times: List<Long>): MutableList<Long> {
        return times
            .map { it.coerceAtLeast(0L) }
            .distinct()
            .sorted()
            .toMutableList()
    }

    private fun upsertTimelineKeyframe(store: MutableMap<Int, MutableList<Long>>, itemId: Int, timeMs: Long): Boolean {
        val normalizedTime = timeMs.coerceAtLeast(0L)
        val existing = store[itemId].orEmpty()
        val updated = normalizeTimelineKeyframes(existing + normalizedTime)
        if (updated.isEmpty()) {
            store.remove(itemId)
        } else {
            store[itemId] = updated
        }
        return !existing.contains(normalizedTime)
    }

    private fun deleteTimelineKeyframe(store: MutableMap<Int, MutableList<Long>>, itemId: Int, timeMs: Long): Boolean {
        val normalizedTime = timeMs.coerceAtLeast(0L)
        val existing = store[itemId].orEmpty()
        if (!existing.contains(normalizedTime)) return false
        val updated = normalizeTimelineKeyframes(existing.filterNot { it == normalizedTime })
        if (updated.isEmpty()) {
            store.remove(itemId)
        } else {
            store[itemId] = updated
        }
        return true
    }

    private fun clearTimelineKeyframes(store: MutableMap<Int, MutableList<Long>>, itemId: Int): Boolean {
        return store.remove(itemId) != null
    }

    private fun previousTimelineKeyframe(keyframes: List<Long>, timeMs: Long): Long? =
        keyframes.filter { it < timeMs }.maxOrNull()

    private fun nextTimelineKeyframe(keyframes: List<Long>, timeMs: Long): Long? =
        keyframes.filter { it > timeMs }.minOrNull()

    private fun normalizeClipPreviewKeyframes(keyframes: List<ClipPreviewKeyframe>): MutableList<ClipPreviewKeyframe> {
        val normalized =
            keyframes
                .map { keyframe ->
                    ClipPreviewKeyframe(
                        timeMs = keyframe.timeMs.coerceAtLeast(0L),
                        transform = normalizeClipPreviewTransformForKeyframeStore(keyframe.transform),
                    )
                }
                .sortedBy { it.timeMs }
        if (normalized.isEmpty()) return mutableListOf()
        val deduped = mutableListOf<ClipPreviewKeyframe>()
        normalized.forEach { keyframe ->
            val lastIndex = deduped.lastIndex
            if (lastIndex >= 0 && deduped[lastIndex].timeMs == keyframe.timeMs) {
                deduped[lastIndex] = keyframe
            } else {
                deduped += keyframe
            }
        }
        return deduped
    }

    private fun normalizeClipPreviewTransformForKeyframeStore(transform: ClipPreviewTransform): ClipPreviewTransform {
        return transform.copy(
            zoom = transform.zoom.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM),
            scaleX = transform.scaleX.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM),
            scaleY = transform.scaleY.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM),
            rotationDeg = transform.rotationDeg.coerceIn(-180f, 180f).let { if (abs(it) < 0.75f) 0f else it },
        )
    }

    private fun easePreviewKeyframeFraction(rawFraction: Float): Float {
        val clamped = rawFraction.coerceIn(0f, 1f)
        return clamped * clamped * clamped * (clamped * ((clamped * 6f) - 15f) + 10f)
    }

    private fun upsertClipPreviewKeyframe(
        clipId: Int,
        timeMs: Long,
        transform: ClipPreviewTransform,
    ): Boolean {
        val normalizedTime = timeMs.coerceAtLeast(0L)
        val existing = videoClipPreviewKeyframes[clipId].orEmpty()
        val updated =
            normalizeClipPreviewKeyframes(
                existing.filterNot { it.timeMs == normalizedTime } +
                    ClipPreviewKeyframe(normalizedTime, normalizeClipPreviewTransformForKeyframeStore(transform)),
            )
        if (updated.isEmpty()) {
            videoClipPreviewKeyframes.remove(clipId)
        } else {
            videoClipPreviewKeyframes[clipId] = updated
        }
        upsertTimelineKeyframe(videoClipKeyframes, clipId, normalizedTime)
        return existing.none { it.timeMs == normalizedTime }
    }

    private fun deleteClipPreviewKeyframe(clipId: Int, timeMs: Long): Boolean {
        val normalizedTime = timeMs.coerceAtLeast(0L)
        val existing = videoClipPreviewKeyframes[clipId].orEmpty()
        if (existing.none { it.timeMs == normalizedTime }) {
            return deleteTimelineKeyframe(videoClipKeyframes, clipId, normalizedTime)
        }
        val updated = normalizeClipPreviewKeyframes(existing.filterNot { it.timeMs == normalizedTime })
        if (updated.isEmpty()) {
            videoClipPreviewKeyframes.remove(clipId)
        } else {
            videoClipPreviewKeyframes[clipId] = updated
        }
        deleteTimelineKeyframe(videoClipKeyframes, clipId, normalizedTime)
        return true
    }

    private fun clearClipPreviewKeyframes(clipId: Int): Boolean {
        val removed = videoClipPreviewKeyframes.remove(clipId) != null
        val removedTimeline = clearTimelineKeyframes(videoClipKeyframes, clipId)
        return removed || removedTimeline
    }

    private fun clipPreviewKeyframeTimes(clipId: Int): MutableList<Long> {
        val previewTimes = videoClipPreviewKeyframes[clipId].orEmpty().map { it.timeMs }
        val timelineTimes = videoClipKeyframes[clipId].orEmpty()
        return normalizeTimelineKeyframes(previewTimes + timelineTimes)
    }

    private fun sampleClipPreviewKeyframeTransform(clipId: Int, timeMs: Long): ClipPreviewTransform? {
        val keyframes = videoClipPreviewKeyframes[clipId].orEmpty()
        if (keyframes.isEmpty()) return null
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        if (targetTimeMs <= keyframes.first().timeMs) {
            return keyframes.first().transform
        }
        if (targetTimeMs >= keyframes.last().timeMs) {
            return keyframes.last().transform
        }
        val nextIndex = keyframes.indexOfFirst { it.timeMs >= targetTimeMs }
        if (nextIndex < 0) return keyframes.last().transform
        val nextKeyframe = keyframes[nextIndex]
        if (nextKeyframe.timeMs == targetTimeMs || nextIndex == 0) {
            return nextKeyframe.transform
        }
        val previousKeyframe = keyframes[nextIndex - 1]
        val spanMs = (nextKeyframe.timeMs - previousKeyframe.timeMs).coerceAtLeast(1L).toFloat()
        val fraction = ((targetTimeMs - previousKeyframe.timeMs).toFloat() / spanMs).coerceIn(0f, 1f)
        val easedFraction = easePreviewKeyframeFraction(fraction)
        val rotationDelta =
            (((nextKeyframe.transform.rotationDeg - previousKeyframe.transform.rotationDeg) + 540f) % 360f) - 180f
        return normalizeClipPreviewTransformForKeyframeStore(
            ClipPreviewTransform(
                zoom = previousKeyframe.transform.zoom + ((nextKeyframe.transform.zoom - previousKeyframe.transform.zoom) * easedFraction),
                scaleX = previousKeyframe.transform.scaleX + ((nextKeyframe.transform.scaleX - previousKeyframe.transform.scaleX) * easedFraction),
                scaleY = previousKeyframe.transform.scaleY + ((nextKeyframe.transform.scaleY - previousKeyframe.transform.scaleY) * easedFraction),
                panXPx = previousKeyframe.transform.panXPx + ((nextKeyframe.transform.panXPx - previousKeyframe.transform.panXPx) * easedFraction),
                panYPx = previousKeyframe.transform.panYPx + ((nextKeyframe.transform.panYPx - previousKeyframe.transform.panYPx) * easedFraction),
                rotationDeg = previousKeyframe.transform.rotationDeg + (rotationDelta * easedFraction),
                mirrorX = if (fraction < 0.5f) previousKeyframe.transform.mirrorX else nextKeyframe.transform.mirrorX,
            ),
        )
    }

    private fun syncClipPreviewKeyframePoseIfNeeded(
        clipId: Int,
        transform: ClipPreviewTransform,
        persistToNative: Boolean,
        autoCreate: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        val hasTimelinePoint = clipPreviewKeyframeTimes(clipId).contains(playheadMs)
        val hasAnyKeyframes = videoClipPreviewKeyframes[clipId].orEmpty().isNotEmpty() || videoClipKeyframes[clipId].orEmpty().isNotEmpty()
        if (!hasTimelinePoint && (!autoCreate || !hasAnyKeyframes)) {
            onResult?.invoke(false)
            return
        }
        upsertClipPreviewKeyframe(clipId, playheadMs, transform)
        if (!persistToNative) {
            onResult?.invoke(true)
            return
        }
        addNativeClipKeyframe(clipId, playheadMs, transform) { success ->
            onResult?.invoke(success)
        }
    }

    private fun applySelectedClipKeyframedPreviewTransform(playheadMs: Long, immediate: Boolean): Boolean {
        val clipId = selectedVideoClipId() ?: return false
        val preview = previewView ?: return false
        val sampledTransform = sampleClipPreviewKeyframeTransform(clipId, playheadMs) ?: return false
        syncClipPreviewTransformToNative(
            clipId = clipId,
            transform = sampledTransform,
            preview = preview,
            immediate = immediate,
        )
        return true
    }

    private fun addNativeClipKeyframe(clipId: Int, timeMs: Long, transform: ClipPreviewTransform, onResult: ((Boolean) -> Unit)? = null) {
        val normalizedTransform = normalizeClipPreviewTransformForKeyframeStore(transform)
        execCmd(
            action = "KEYFRAME_ADD",
            params = mapOf(
                "clipId" to clipId,
                "timeMs" to timeMs.coerceAtLeast(0L),
                "zoom" to normalizedTransform.zoom,
                "scaleX" to normalizedTransform.scaleX,
                "scaleY" to normalizedTransform.scaleY,
                "panXPx" to normalizedTransform.panXPx,
                "panYPx" to normalizedTransform.panYPx,
                "rotationDeg" to normalizedTransform.rotationDeg,
                "mirrorX" to normalizedTransform.mirrorX,
            ),
        ) { result ->
            onResult?.invoke(result.success)
        }
    }

    private fun addNativeClipKeyframeAsync(clipId: Int, timeMs: Long, transform: ClipPreviewTransform) {
        val normalizedTransform = normalizeClipPreviewTransformForKeyframeStore(transform)
        NativeBridge.executeCommandAsync(
            action = "KEYFRAME_ADD",
            params = mapOf(
                "clipId" to clipId,
                "timeMs" to timeMs.coerceAtLeast(0L),
                "zoom" to normalizedTransform.zoom,
                "scaleX" to normalizedTransform.scaleX,
                "scaleY" to normalizedTransform.scaleY,
                "panXPx" to normalizedTransform.panXPx,
                "panYPx" to normalizedTransform.panYPx,
                "rotationDeg" to normalizedTransform.rotationDeg,
                "mirrorX" to normalizedTransform.mirrorX,
            ),
        )
    }

    private fun deleteNativeClipKeyframe(clipId: Int, timeMs: Long, onResult: ((Boolean) -> Unit)? = null) {
        execCmd(
            action = "KEYFRAME_DELETE",
            params = mapOf(
                "clipId" to clipId,
                "timeMs" to timeMs.coerceAtLeast(0L),
            ),
        ) { result ->
            onResult?.invoke(result.success && result.data.optBoolean("deleted", false))
        }
    }

    private fun clearNativeClipKeyframes(clipId: Int, onResult: ((Boolean) -> Unit)? = null) {
        execCmd(
            action = "KEYFRAME_CLEAR",
            params = mapOf("clipId" to clipId),
        ) { result ->
            onResult?.invoke(result.success && result.data.optBoolean("cleared", false))
        }
    }

    private fun showTimelineKeyframeStudio(
        title: String,
        clipStartMs: Long,
        clipEndMs: Long,
        keyframes: List<Long>,
        onAddOrUpdateCurrent: () -> Unit,
        onDeleteCurrent: () -> Unit,
        onClearAll: () -> Unit,
    ) {
        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        val previousKeyframe = previousTimelineKeyframe(keyframes, playheadMs)
        val nextKeyframe = nextTimelineKeyframe(keyframes, playheadMs)
        val clampedClipEndMs = clipEndMs.coerceAtLeast(clipStartMs)
        ModernSheet.show(this, title) {
            chips(
                "Point",
                listOf(
                    if (keyframes.contains(playheadMs)) "Update Current" else "Add Current",
                    "Delete Current",
                    "Clear All",
                ),
            ) { index, _ ->
                when (index) {
                    0 -> onAddOrUpdateCurrent()
                    1 -> onDeleteCurrent()
                    2 -> onClearAll()
                }
            }
            chips(
                "Navigate",
                listOf(
                    previousKeyframe?.let { "Prev ${formatAutomationTime(it)}" } ?: "No Prev",
                    "Now ${formatAutomationTime(playheadMs)}",
                    nextKeyframe?.let { "Next ${formatAutomationTime(it)}" } ?: "No Next",
                ),
            ) { index, _ ->
                when (index) {
                    0 -> previousKeyframe?.let { applyEditorPlayhead(it) } ?: safeToast("No previous keyframe", Toast.LENGTH_SHORT)
                    1 -> applyEditorPlayhead(playheadMs)
                    2 -> nextKeyframe?.let { applyEditorPlayhead(it) } ?: safeToast("No next keyframe", Toast.LENGTH_SHORT)
                }
            }
            chipGrid(
                "Quick Jump",
                listOf("-1000ms", "-250ms", "+250ms", "+1000ms", "Clip Start", "Clip End"),
                columns = 3,
            ) { _, option ->
                val targetTimeMs =
                    when (option) {
                        "-1000ms" -> (playheadMs - 1000L).coerceAtLeast(clipStartMs)
                        "-250ms" -> (playheadMs - 250L).coerceAtLeast(clipStartMs)
                        "+250ms" -> (playheadMs + 250L).coerceAtMost(clampedClipEndMs)
                        "+1000ms" -> (playheadMs + 1000L).coerceAtMost(clampedClipEndMs)
                        "Clip Start" -> clipStartMs
                        else -> clampedClipEndMs
                    }
                applyEditorPlayhead(targetTimeMs)
            }
            chips(
                "Summary",
                listOf(
                    "Points ${keyframes.size}",
                    "Clip ${formatAutomationTime(clipStartMs)} - ${formatAutomationTime(clampedClipEndMs)}",
                ),
            ) { _, _ -> }
        }
    }

    private fun showNativeClipKeyframeStudio(clipId: Int, clipLabel: String) {
        val clipRange = selectedVideoTiming(clipId) ?: run {
            safeToast("Clip timing unavailable", Toast.LENGTH_SHORT)
            return
        }
        val clipStartMs = clipRange.first
        val clipEndMs = (clipRange.first + clipRange.second - 1L).coerceAtLeast(clipStartMs)
        val keyframes = clipPreviewKeyframeTimes(clipId)
        showTimelineKeyframeStudio(
            title = "$clipLabel Keyframe",
            clipStartMs = clipStartMs,
            clipEndMs = clipEndMs,
            keyframes = keyframes,
            onAddOrUpdateCurrent = {
                val playheadMs = currentPlayheadMs().coerceIn(clipStartMs, clipEndMs)
                val transform = currentClipPreviewTransform(clipId)
                addNativeClipKeyframe(clipId, playheadMs, transform) { success ->
                    if (success) {
                        upsertClipPreviewKeyframe(clipId, playheadMs, transform)
                        refreshMainTimelineTracks()
                        refreshPreviewAtPlayhead(resyncAudio = false)
                        triggerEditorHaptic(EditorHapticEffect.Tick)
                        safeToast("$clipLabel keyframe @ ${playheadMs}ms", Toast.LENGTH_SHORT)
                    } else {
                        safeToast("Keyframe update failed", Toast.LENGTH_SHORT)
                    }
                }
            },
            onDeleteCurrent = {
                val playheadMs = currentPlayheadMs().coerceIn(clipStartMs, clipEndMs)
                deleteNativeClipKeyframe(clipId, playheadMs) { success ->
                    if (success) {
                        deleteClipPreviewKeyframe(clipId, playheadMs)
                        refreshMainTimelineTracks()
                        refreshPreviewAtPlayhead(resyncAudio = false)
                        triggerEditorHaptic(EditorHapticEffect.Warning)
                        safeToast("$clipLabel keyframe removed", Toast.LENGTH_SHORT)
                    } else {
                        safeToast("No current keyframe", Toast.LENGTH_SHORT)
                    }
                }
            },
            onClearAll = {
                clearNativeClipKeyframes(clipId) { success ->
                    if (success) {
                        clearClipPreviewKeyframes(clipId)
                        refreshMainTimelineTracks()
                        refreshPreviewAtPlayhead(resyncAudio = false)
                        triggerEditorHaptic(EditorHapticEffect.Warning)
                        safeToast("$clipLabel keyframes cleared", Toast.LENGTH_SHORT)
                    } else {
                        safeToast("Keyframes already clear", Toast.LENGTH_SHORT)
                    }
                }
            },
        )
    }

    private fun loadTextKeyframeTimes(overlayId: Int): MutableList<Long> {
        val nativeTimes = previewView?.getTextKeyframeTimes(overlayId)?.toList().orEmpty()
        val merged = normalizeTimelineKeyframes(textOverlayKeyframes[overlayId].orEmpty() + nativeTimes)
        if (merged.isEmpty()) {
            textOverlayKeyframes.remove(overlayId)
        } else {
            textOverlayKeyframes[overlayId] = merged
        }
        return merged
    }

    private fun showTextKeyframeStudio(overlay: TextOverlay) {
        if (previewView == null) {
            safeToast("Preview unavailable", Toast.LENGTH_SHORT)
            return
        }
        val clipStartMs = overlay.startTimeMs.toLong().coerceAtLeast(0L)
        val clipEndMs = (overlay.endTimeMs - 1).toLong().coerceAtLeast(clipStartMs)
        val keyframes = loadTextKeyframeTimes(overlay.id)
        showTimelineKeyframeStudio(
            title = "Text Keyframe",
            clipStartMs = clipStartMs,
            clipEndMs = clipEndMs,
            keyframes = keyframes,
            onAddOrUpdateCurrent = {
                val playheadMs = currentPlayheadMs().coerceIn(clipStartMs, clipEndMs)
                previewView?.addTextKeyframe(
                    overlay.id,
                    playheadMs,
                    overlay.x,
                    overlay.y,
                    overlay.scale,
                    overlay.rotation,
                    overlay.opacity,
                )
                upsertTimelineKeyframe(textOverlayKeyframes, overlay.id, playheadMs)
                refreshPreviewAtPlayhead(resyncAudio = false)
                safeToast("Text keyframe @ ${playheadMs}ms", Toast.LENGTH_SHORT)
            },
            onDeleteCurrent = {
                val playheadMs = currentPlayheadMs().coerceIn(clipStartMs, clipEndMs)
                previewView?.deleteTextKeyframe(overlay.id, playheadMs)
                if (deleteTimelineKeyframe(textOverlayKeyframes, overlay.id, playheadMs)) {
                    refreshPreviewAtPlayhead(resyncAudio = false)
                    safeToast("Text keyframe removed", Toast.LENGTH_SHORT)
                } else {
                    safeToast("No current keyframe", Toast.LENGTH_SHORT)
                }
            },
            onClearAll = {
                previewView?.clearTextKeyframes(overlay.id)
                clearTimelineKeyframes(textOverlayKeyframes, overlay.id)
                refreshPreviewAtPlayhead(resyncAudio = false)
                safeToast("Text keyframes cleared", Toast.LENGTH_SHORT)
            },
        )
    }

    private fun showStickerKeyframeStudio(clip: StickerClip) {
        val clipStartMs = clip.startTimeMs.toLong().coerceAtLeast(0L)
        val clipEndMs = (clip.startTimeMs + clip.durationMs - 1).toLong().coerceAtLeast(clipStartMs)
        val keyframes = normalizeTimelineKeyframes(stickerClipKeyframes[clip.id].orEmpty())
        showTimelineKeyframeStudio(
            title = "Overlay Keyframe",
            clipStartMs = clipStartMs,
            clipEndMs = clipEndMs,
            keyframes = keyframes,
            onAddOrUpdateCurrent = {
                val playheadMs = currentPlayheadMs().coerceIn(clipStartMs, clipEndMs)
                upsertTimelineKeyframe(stickerClipKeyframes, clip.id, playheadMs)
                refreshMainTimelineTracks()
                safeToast("Overlay keyframe @ ${playheadMs}ms", Toast.LENGTH_SHORT)
            },
            onDeleteCurrent = {
                val playheadMs = currentPlayheadMs().coerceIn(clipStartMs, clipEndMs)
                if (deleteTimelineKeyframe(stickerClipKeyframes, clip.id, playheadMs)) {
                    refreshMainTimelineTracks()
                    safeToast("Overlay keyframe removed", Toast.LENGTH_SHORT)
                } else {
                    safeToast("No current keyframe", Toast.LENGTH_SHORT)
                }
            },
            onClearAll = {
                if (clearTimelineKeyframes(stickerClipKeyframes, clip.id)) {
                    refreshMainTimelineTracks()
                    safeToast("Overlay keyframes cleared", Toast.LENGTH_SHORT)
                } else {
                    safeToast("Keyframes already clear", Toast.LENGTH_SHORT)
                }
            },
        )
    }

    private fun formatFadeLabel(value: Float): String {
        val millis = value.toInt().coerceAtLeast(0)
        return if (millis >= 1000) {
            String.format(Locale.US, "%.2fs", millis / 1000f)
        } else {
            "${millis}ms"
        }
    }

    private fun applyAudioClipGainChange(
        audioId: Int,
        clip: AudioClip,
        gain: Float,
    ) {
        val safeGain = gain.coerceIn(0f, 2f)
        audioClipGainOverrides[audioId] = safeGain
        clip.gain = safeGain
        clip.muted = safeGain <= 0.001f
        execCmd(
            "SET_CLIP_VOLUME",
            mapOf("clipId" to audioId, "volume" to safeGain.toDouble()),
        ) {
            refreshMainTimelineTracks()
            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
        }
    }

    private fun applyAudioClipFades(
        audioId: Int,
        clip: AudioClip,
        fadeInMs: Int,
        fadeOutMs: Int,
    ) {
        val maxFadeMs = clip.durationMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        clip.fadeInMs = fadeInMs.coerceIn(0, maxFadeMs)
        clip.fadeOutMs = fadeOutMs.coerceIn(0, maxFadeMs)
        execCmd(
            action = "SET_CLIP_AUDIO_FADES",
            params = mapOf(
                "clipId" to audioId,
                "fadeInMs" to clip.fadeInMs,
                "fadeOutMs" to clip.fadeOutMs,
            ),
        ) {
            refreshMainTimelineTracks()
            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
        }
    }

    private fun applyAudioAutomationPreset(
        audioId: Int,
        clip: AudioClip,
        preset: String,
    ) {
        val clipSpanMs = (clip.durationMs - 1L).coerceAtLeast(1L)
        val q1 = (clipSpanMs * 25L) / 100L
        val q2 = (clipSpanMs * 50L) / 100L
        val q3 = (clipSpanMs * 75L) / 100L
        val edge = (clipSpanMs * 10L) / 100L
        val keyframes =
            when (preset) {
                "Flat" -> emptyList()
                "Rise" -> listOf(
                    AudioGainKeyframe(0L, 0.45f),
                    AudioGainKeyframe(clipSpanMs, 1.15f),
                )
                "Fall" -> listOf(
                    AudioGainKeyframe(0L, 1.15f),
                    AudioGainKeyframe(clipSpanMs, 0.45f),
                )
                "Pulse" -> listOf(
                    AudioGainKeyframe(0L, 0.80f),
                    AudioGainKeyframe(q1, 1.35f),
                    AudioGainKeyframe(q2, 0.65f),
                    AudioGainKeyframe(q3, 1.20f),
                    AudioGainKeyframe(clipSpanMs, 0.90f),
                )
                "Mute Ends" -> listOf(
                    AudioGainKeyframe(0L, 0f),
                    AudioGainKeyframe(edge.coerceAtLeast(1L), 1f),
                    AudioGainKeyframe((clipSpanMs - edge).coerceAtLeast(edge.coerceAtLeast(1L)), 1f),
                    AudioGainKeyframe(clipSpanMs, 0f),
                )
                else -> return
            }
        applyAudioGainKeyframes(audioId, clip, keyframes)
    }

    private fun showAudioClipFilterSheet(
        audioId: Int,
        clip: AudioClip,
    ) {
        val clipLabel = clip.displayName.ifBlank { "Audio $audioId" }
        val maxFadeMs = clip.durationMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ModernSheet.showModal(
            context = this,
            title = "$clipLabel Audio FX",
            showClose = true,
            showApply = true,
            onApply = {
                safeToast("Audio effects applied", Toast.LENGTH_SHORT)
            },
        ) {
            section("Voice FX")
            chips(
                "Voice Character",
                listOf("Original", "Female", "Male", "Child", "Robot", "Echo"),
                selected = 0,
                dismissOnSelect = false,
            ) { _, character ->
                when (character) {
                    "Female" -> {
                        applyAudioClipGainChange(audioId, clip, 1.15f)
                        applyAudioClipSpeedChange(audioId, 1.12f)
                    }
                    "Male" -> {
                        applyAudioClipGainChange(audioId, clip, 1.25f)
                        applyAudioClipSpeedChange(audioId, 0.90f)
                    }
                    "Child" -> {
                        applyAudioClipGainChange(audioId, clip, 1.20f)
                        applyAudioClipSpeedChange(audioId, 1.25f)
                    }
                    "Robot" -> {
                        applyAudioClipGainChange(audioId, clip, 1.30f)
                        applyAudioAutomationPreset(audioId, clip, "Pulse")
                    }
                    "Echo" -> {
                        val shortFade = (maxFadeMs / 6).coerceAtLeast(0)
                        applyAudioClipFades(audioId, clip, shortFade, shortFade)
                        applyAudioAutomationPreset(audioId, clip, "Rise")
                    }
                    else -> {
                        applyAudioClipGainChange(audioId, clip, 1.0f)
                        applyAudioClipSpeedChange(audioId, 1.0f)
                        applyAudioClipFades(audioId, clip, 0, 0)
                    }
                }
                safeToast("Voice FX: $character", Toast.LENGTH_SHORT)
            }
            divider()
            section("Volume & Fades")
            sliderWithBubble(
                label = "Volume Gain",
                min = 0f,
                max = 200f,
                value = ((audioClipGainOverrides[audioId] ?: clip.gain) * 100f).coerceIn(0f, 200f),
                unit = "%",
                format = { "%.0f".format(it) },
            ) { value ->
                applyAudioClipGainChange(audioId, clip, value / 100f)
            }
            chips("Tone Presets", listOf("Mute", "Soft", "Clean", "Boost", "Max")) { _, option ->
                val gain =
                    when (option) {
                        "Mute" -> 0f
                        "Soft" -> 0.62f
                        "Boost" -> 1.18f
                        "Max" -> 1.50f
                        else -> 1.0f
                    }
                applyAudioClipGainChange(audioId, clip, gain)
                safeToast("$clipLabel $option", Toast.LENGTH_SHORT)
            }
            chips("Fade Shape", listOf("None", "In", "Out", "In/Out", "Pad")) { _, option ->
                val shortFade = (maxFadeMs / 8).coerceAtLeast(0)
                val mediumFade = (maxFadeMs / 4).coerceAtLeast(shortFade)
                val fades =
                    when (option) {
                        "In" -> shortFade to 0
                        "Out" -> 0 to shortFade
                        "In/Out" -> shortFade to shortFade
                        "Pad" -> mediumFade to mediumFade
                        else -> 0 to 0
                    }
                applyAudioClipFades(audioId, clip, fades.first, fades.second)
                safeToast("$clipLabel ${option.lowercase(Locale.US)} fade", Toast.LENGTH_SHORT)
            }
        }
    }

    private fun showAudioClipLayerSheet(
        audioId: Int,
        clip: AudioClip,
    ) {
        val currentLayer = nativeClipLane[audioId] ?: clip.layerIndex
        val peerLayers = AudioClipStore.all().map { nativeClipLane[it.id] ?: it.layerIndex }
        val minLayer = peerLayers.minOrNull() ?: currentLayer
        val maxLayer = peerLayers.maxOrNull() ?: currentLayer
        val clipLabel = clip.displayName.ifBlank { "Audio $audioId" }
        val options = arrayOf("Bring Forward", "Send Backward", "Bring To Front", "Send To Back")
        AlertDialog.Builder(this)
            .setTitle("$clipLabel Layer")
            .setItems(options) { _, which ->
                val requestedLayer =
                    when (which) {
                        0 -> currentLayer + 1
                        1 -> currentLayer - 1
                        2 -> maxLayer + 1
                        3 -> minLayer - 1
                        else -> currentLayer
                    }
                execCmd(
                    action = "SET_CLIP_TRACK",
                    params = mapOf(
                        "clipId" to audioId,
                        "trackType" to TrackType.AUDIO.nativeRoleName(),
                        "trackLane" to requestedLayer,
                        "zOrder" to requestedLayer,
                    )
                ) { result ->
                    if (result.success) {
                        clip.layerIndex = requestedLayer
                        nativeClipLane[audioId] = requestedLayer
                        nativeClipZOrder[audioId] = requestedLayer
                        lastLayoutFetchMs = 0L
                        syncTimelineShellFromNative(selectedClipId = audioId)
                        syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        safeToast("$clipLabel layer updated", Toast.LENGTH_SHORT)
                    } else {
                        safeToast("$clipLabel layer update failed", Toast.LENGTH_SHORT)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioClipStudioSheet(
        audioId: Int,
        clip: AudioClip,
    ) {
        val clipLabel = clip.displayName.ifBlank { "Audio $audioId" }
        ModernSheet.show(this, "$clipLabel Audio Studio") {
            chipGrid(
                "Workflows",
                listOf("Voice Over", "Music Bed", "SFX Hit", "Reverse Tail", "Podcast"),
                columns = 3,
            ) { _, option ->
                when (option) {
                    "Voice Over" -> {
                        applyNativeClipReverseChange(audioId, false)
                        applyAudioClipSpeedChange(audioId, 1.0f)
                        applyAudioClipGainChange(audioId, clip, 1.05f)
                        applyAudioClipFades(audioId, clip, 120, 180)
                        applyAudioAutomationPreset(audioId, clip, "Flat")
                    }
                    "Music Bed" -> {
                        applyNativeClipReverseChange(audioId, false)
                        applyAudioClipSpeedChange(audioId, 1.0f)
                        applyAudioClipGainChange(audioId, clip, 0.58f)
                        applyAudioClipFades(audioId, clip, 900, 1200)
                        applyAudioAutomationPreset(audioId, clip, "Rise")
                    }
                    "SFX Hit" -> {
                        applyNativeClipReverseChange(audioId, false)
                        applyAudioClipSpeedChange(audioId, 1.0f)
                        applyAudioClipGainChange(audioId, clip, 1.35f)
                        applyAudioClipFades(audioId, clip, 0, (clip.durationMs / 5L).coerceAtLeast(60L).coerceAtMost(220L).toInt())
                        applyAudioAutomationPreset(audioId, clip, "Pulse")
                    }
                    "Reverse Tail" -> {
                        applyNativeClipReverseChange(audioId, true)
                        applyAudioClipSpeedChange(audioId, 0.75f)
                        applyAudioClipGainChange(audioId, clip, 0.95f)
                        applyAudioClipFades(audioId, clip, (clip.durationMs / 6L).coerceAtLeast(60L).coerceAtMost(180L).toInt(), 0)
                        applyAudioAutomationPreset(audioId, clip, "Fall")
                    }
                    "Podcast" -> {
                        applyNativeClipReverseChange(audioId, false)
                        applyAudioClipSpeedChange(audioId, 1.0f)
                        applyAudioClipGainChange(audioId, clip, 0.92f)
                        applyAudioClipFades(audioId, clip, 80, 120)
                        applyAudioAutomationPreset(audioId, clip, "Flat")
                    }
                }
                safeToast("$clipLabel $option", Toast.LENGTH_SHORT)
            }
            chipGrid(
                "Tools",
                listOf("Gain", "Fade", "FX", "Speed", "Reverse", "Keyframe"),
                columns = 4,
            ) { _, option ->
                when (option) {
                    "Gain" -> performSelectedClipVolumeAction()
                    "Fade" -> performSelectedClipBrightnessAction()
                    "FX" -> showAudioClipFilterSheet(audioId, clip)
                    "Speed" -> performSelectedClipSpeedAction()
                    "Reverse" -> performSelectedClipReverseAction()
                    "Keyframe" -> performSelectedClipKeyframeAction()
                }
            }
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

    private fun toMultiTrackClipUpdate(
        update: com.video.engine.pro.timeline.ClipUpdate,
    ): MultiTrackTimelineView.ClipUpdate {
        return MultiTrackTimelineView.ClipUpdate(
            clipId = update.clipId,
            trackType = update.trackType,
            startTimeMs = update.startTimeMs,
            durationMs = update.durationMs,
            sourceInMs = update.sourceInMs,
            sourceOutMs = update.sourceOutMs,
            originalStartTimeMs = update.originalStartTimeMs,
            originalDurationMs = update.originalDurationMs,
            originalSourceInMs = update.originalSourceInMs,
            originalSourceOutMs = update.originalSourceOutMs,
            gestureKind = when (update.gestureKind) {
                com.video.engine.pro.timeline.ClipGestureKind.TRIM_START -> MultiTrackTimelineView.ClipGestureKind.TRIM_START
                com.video.engine.pro.timeline.ClipGestureKind.TRIM_END -> MultiTrackTimelineView.ClipGestureKind.TRIM_END
                com.video.engine.pro.timeline.ClipGestureKind.MOVE -> MultiTrackTimelineView.ClipGestureKind.MOVE
            },
        )
    }

    private fun clampTimelineCanvasClipUpdateToSource(
        update: com.video.engine.pro.timeline.ClipUpdate,
        allowSourceProbe: Boolean,
    ): com.video.engine.pro.timeline.ClipUpdate {
        val clipId = parseTimelineUpdateNativeClipId(update.clipId) ?: return update
        val timing = clampNativeTimelineTimingToSource(
            clipId = clipId,
            startTimeMs = update.startTimeMs,
            durationMs = update.durationMs,
            sourceInMs = update.sourceInMs,
            sourceOutMs = update.sourceOutMs,
            originalStartTimeMs = update.originalStartTimeMs,
            originalDurationMs = update.originalDurationMs,
            originalSourceInMs = update.originalSourceInMs,
            originalSourceOutMs = update.originalSourceOutMs,
            gestureKindName = update.gestureKind.name,
            allowSourceProbe = allowSourceProbe,
        )
        return update.copy(
            startTimeMs = timing.startTimeMs,
            durationMs = timing.durationMs,
            sourceInMs = timing.sourceInMs,
            sourceOutMs = timing.sourceOutMs,
        )
    }

    private fun clampMultiTrackClipUpdateToSource(
        update: MultiTrackTimelineView.ClipUpdate,
        allowSourceProbe: Boolean,
    ): MultiTrackTimelineView.ClipUpdate {
        val clipId = parseTimelineUpdateNativeClipId(update.clipId) ?: return update
        val timing = clampNativeTimelineTimingToSource(
            clipId = clipId,
            startTimeMs = update.startTimeMs,
            durationMs = update.durationMs,
            sourceInMs = update.sourceInMs,
            sourceOutMs = update.sourceOutMs,
            originalStartTimeMs = update.originalStartTimeMs,
            originalDurationMs = update.originalDurationMs,
            originalSourceInMs = update.originalSourceInMs,
            originalSourceOutMs = update.originalSourceOutMs,
            gestureKindName = update.gestureKind.name,
            allowSourceProbe = allowSourceProbe,
        )
        return update.copy(
            startTimeMs = timing.startTimeMs,
            durationMs = timing.durationMs,
            sourceInMs = timing.sourceInMs,
            sourceOutMs = timing.sourceOutMs,
        )
    }

    private fun parseTimelineUpdateNativeClipId(clipKey: String?): Int? {
        if (clipKey.isNullOrBlank()) return null
        return when {
            clipKey.startsWith("audio-") -> clipKey.removePrefix("audio-").toIntOrNull()
            clipKey.startsWith("overlay-") -> clipKey.removePrefix("overlay-").toIntOrNull()
            clipKey.startsWith("layer-") -> clipKey.removePrefix("layer-").toIntOrNull()
            clipKey.startsWith("text-") || clipKey.startsWith("sticker-") -> null
            else -> clipKey.toIntOrNull()
        }
    }

    private fun resolveTimelineClampSourceDurationMs(clipId: Int, allowSourceProbe: Boolean): Long {
        val sourcePath = nativeClipSourcePath[clipId].orEmpty()
            .ifBlank { AudioClipStore.get(clipId)?.sourcePath.orEmpty() }
        if (sourcePath.isBlank() || isImageLikeSourcePath(sourcePath)) return 0L
        knownNativeClipSourceDurationMs(clipId).takeIf { it > 0L }?.let { return it }
        if (!allowSourceProbe) return 0L
        return resolveMediaSourceDurationMs(
            sourcePath = sourcePath,
            fallbackMs = nativeClipSourceOutMs[clipId]
                ?: nativeClipDurationMs[clipId]
                ?: AudioClipStore.get(clipId)?.durationMs
                ?: 1L,
        )
    }

    private fun clampNativeTimelineTimingToSource(
        clipId: Int,
        startTimeMs: Long,
        durationMs: Long,
        sourceInMs: Long,
        sourceOutMs: Long,
        originalStartTimeMs: Long,
        originalDurationMs: Long,
        originalSourceInMs: Long,
        originalSourceOutMs: Long,
        gestureKindName: String,
        allowSourceProbe: Boolean,
    ): ClipTimingSnapshot {
        val minDurationMs = 1L
        val sourceDurationMs = resolveTimelineClampSourceDurationMs(clipId, allowSourceProbe)
        if (sourceDurationMs <= 0L) {
            val safeStartMs = startTimeMs.coerceAtLeast(0L)
            val safeDurationMs = durationMs.coerceAtLeast(minDurationMs)
            val safeSourceInMs = sourceInMs.coerceAtLeast(0L)
            return ClipTimingSnapshot(
                startTimeMs = safeStartMs,
                durationMs = safeDurationMs,
                sourceInMs = safeSourceInMs,
                sourceOutMs = sourceOutMs.coerceAtLeast(safeSourceInMs + safeDurationMs),
            )
        }

        val originalStartMs = originalStartTimeMs.coerceAtLeast(0L)
        val originalSourceInMsSafe = originalSourceInMs.coerceIn(0L, sourceDurationMs - 1L)
        val originalEndMs = (originalStartMs + originalDurationMs.coerceAtLeast(minDurationMs))
            .coerceAtLeast(originalStartMs + minDurationMs)
        var safeStartMs = startTimeMs.coerceAtLeast(0L)
        var safeSourceInMs = sourceInMs.coerceIn(0L, sourceDurationMs - 1L)
        var safeDurationMs = durationMs.coerceAtLeast(minDurationMs)

        when (gestureKindName) {
            "TRIM_START" -> {
                val earliestStartMs = (originalStartMs - originalSourceInMsSafe).coerceAtLeast(0L)
                val latestStartMs = (originalEndMs - minDurationMs).coerceAtLeast(earliestStartMs)
                safeStartMs = safeStartMs.coerceIn(earliestStartMs, latestStartMs)
                safeSourceInMs = (originalSourceInMsSafe + (safeStartMs - originalStartMs))
                    .coerceIn(0L, sourceDurationMs - 1L)
                val requestedEndMs = (safeStartMs + safeDurationMs).coerceAtLeast(safeStartMs + minDurationMs)
                val safeEndMs = minOf(requestedEndMs, originalEndMs).coerceAtLeast(safeStartMs + minDurationMs)
                safeDurationMs = (safeEndMs - safeStartMs).coerceAtLeast(minDurationMs)
            }
            "TRIM_END" -> {
                safeDurationMs = minOf(safeDurationMs, sourceDurationMs - safeSourceInMs)
                    .coerceAtLeast(minDurationMs)
            }
            else -> {
                val requestedSourceOutMs = sourceOutMs.coerceAtLeast(safeSourceInMs + minDurationMs)
                val availableFromSourceOutMs = minOf(requestedSourceOutMs, sourceDurationMs) - safeSourceInMs
                safeDurationMs = minOf(
                    safeDurationMs,
                    availableFromSourceOutMs.coerceAtLeast(minDurationMs),
                    (sourceDurationMs - safeSourceInMs).coerceAtLeast(minDurationMs),
                ).coerceAtLeast(minDurationMs)
            }
        }

        val safeSourceOutMs = (safeSourceInMs + safeDurationMs)
            .coerceAtMost(sourceDurationMs)
            .coerceAtLeast(safeSourceInMs + minDurationMs)
        return ClipTimingSnapshot(
            startTimeMs = safeStartMs,
            durationMs = (safeSourceOutMs - safeSourceInMs).coerceAtLeast(minDurationMs),
            sourceInMs = safeSourceInMs,
            sourceOutMs = safeSourceOutMs,
        )
    }

    private fun startHardwareTelemetryTicker() {
        if (isAutomationPerfMode()) return
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
        noteAppHealthAction("export_dialog_opened")
        exportController?.showExportDialog()
    }

    private fun saveCurrentSessionNow(reason: String): Boolean {
        if (startShellOnly || !hasProjectContent()) return false
        val saved = projectController?.autoSaveNow(reason) == true
        if (saved) {
            lastImmediateAutoSaveElapsedMs = SystemClock.elapsedRealtime()
        }
        return saved
    }

    private fun saveCurrentSessionNowIfStale(reason: String, minIntervalMs: Long = 1_500L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastImmediateAutoSaveElapsedMs > 0L && now - lastImmediateAutoSaveElapsedMs < minIntervalMs) {
            return hasProjectContent()
        }
        return saveCurrentSessionNow(reason)
    }

    private fun scheduleTimelineIdleAutoSave(reason: String) {
        val delayMs =
            if (shouldDeferHeavyUiWork()) {
                TIMELINE_BUSY_AUTOSAVE_DELAY_MS
            } else {
                TIMELINE_IDLE_AUTOSAVE_DELAY_MS
            }
        scheduleSessionAutoSave(reason, delayMs = delayMs)
    }

    private fun scheduleSessionAutoSave(reason: String, delayMs: Long = 1200L) {
        if (startShellOnly || !hasProjectContent()) return
        Log.d(TAG, "[Project] schedule session autosave reason=$reason delayMs=$delayMs")
        projectController?.scheduleAutoSave(delayMs)
    }

    private fun showEditorHome() {
        setPreviewCropMode(false)
        returnToSingleHome("editor_home")
    }

    private fun restoreAutoSaveAfterImportPickerRecreate(
        reason: String,
        allowExistingContent: Boolean = false,
    ): Boolean {
        if (importPickerResultInProgress) {
            Log.i(TAG, "Import picker autosave restore skipped reason=$reason: import result in progress")
            return false
        }
        if (importPickerAutosaveRestoreAttempted) return importPickerAutosaveRestoreInProgress
        if (!allowExistingContent && hasProjectContent()) return false
        val autoSaveFile = RecentProjectFiles.latestAutoSaveCandidate(this)
        if (autoSaveFile == null) {
            Log.w(TAG, "Import picker autosave restore skipped reason=$reason: no usable autosave")
            return false
        }
        val controller = projectController ?: return false
        importPickerAutosaveRestoreAttempted = true
        processStartupAutoRestoreAttempted = true
        autoSaveRestorePromptShown = true
        Log.i(TAG, "Restoring autosave after import picker recreate reason=$reason path=${autoSaveFile.absolutePath}")
        val restored = controller.restoreAutoSave()
        if (!restored) {
            importPickerAutosaveRestoreAttempted = false
            importPickerAutosaveRestoreInProgress = false
            processStartupAutoRestoreAttempted = false
            autoSaveRestorePromptShown = false
            Log.w(TAG, "Import picker autosave restore failed reason=$reason")
        } else {
            importPickerAutosaveRestoreInProgress = true
            mainHandler.postDelayed({
                if (!hasProjectContent()) {
                    importPickerAutosaveRestoreInProgress = false
                }
            }, 8_000L)
        }
        return restored
    }

    private fun maybePromptAutoSaveRestore() {
        if (!AUTO_STARTUP_AUTOSAVE_RESTORE_ENABLED) return
        if (processStartupAutoRestoreAttempted) return
        if (autoSaveRestorePromptShown) return
        if (hasProjectContent()) return
        if (activeImportPickerRequestCode != null) return
        val controller = projectController ?: return
        val summary = controller.describeAutoSave() ?: return
        processStartupAutoRestoreAttempted = true
        autoSaveRestorePromptShown = true
        Log.i(TAG, "Restoring startup autosave: $summary")
        clearImportSessionState("startup_autosave_restore")
        val restored = controller.restoreAutoSave()
        if (!restored) {
            processStartupAutoRestoreAttempted = false
            autoSaveRestorePromptShown = false
            Log.w(TAG, "Startup autosave restore failed")
        }
    }

    private fun textOverlayPresetByLabel(label: String): TextOverlayPreset? {
        return textOverlayPresets.firstOrNull { it.label == label }
    }

    private fun textOverlayDurationMs(overlay: TextOverlay): Int {
        return (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(1)
    }

    private fun reusableTextDurationMs(): Int? {
        selectedTextOverlayId()
            ?.let(OverlayStore::get)
            ?.let(::textOverlayDurationMs)
            ?.takeIf { it >= 600 }
            ?.let { return it }
        return OverlayStore.all()
            .map(::textOverlayDurationMs)
            .filter { it >= 6000 }
            .maxOrNull()
    }

    private fun preserveTextOverlayTiming(overlay: TextOverlay, startTimeMs: Int, endTimeMs: Int) {
        overlay.startTimeMs = startTimeMs.coerceAtLeast(0)
        overlay.endTimeMs = endTimeMs.coerceAtLeast(overlay.startTimeMs + 1)
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
            layerIndex = 0,
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
                durationOverrideMs = reusableTextDurationMs(),
            ),
            message = "${preset.label} added",
        )
    }

    private fun resolveToolbarTransitionPair(): Pair<Int, Int>? {
        val candidateIds =
            linkedSetOf<Int>().apply {
                addAll(nativeClipStartMs.keys)
                addAll(nativeClipDurationMs.keys)
                addAll(timelineManager?.getClips().orEmpty().map { it.id })
                resolveSelectedVisualClipId(syncSelectionIfNeeded = false, preferredTrackType = TrackType.VIDEO)?.let { add(it) }
            }
                .filter { clipId -> (nativeClipTrackType[clipId] ?: TrackType.VIDEO) == TrackType.VIDEO }
                .sortedWith(
                    compareBy<Int> { selectedVideoTiming(it)?.first ?: Long.MAX_VALUE }
                        .thenBy { it },
                )

        if (candidateIds.size < 2) return null

        val selectedVideoClipId =
            resolveSelectedVisualClipId(
                syncSelectionIfNeeded = true,
                preferredTrackType = TrackType.VIDEO,
            )
        if (selectedVideoClipId != null) {
            val selectedIndex = candidateIds.indexOf(selectedVideoClipId)
            if (selectedIndex >= 0) {
                candidateIds.getOrNull(selectedIndex + 1)?.let { incoming ->
                    return selectedVideoClipId to incoming
                }
                candidateIds.getOrNull(selectedIndex - 1)?.let { outgoing ->
                    return outgoing to selectedVideoClipId
                }
            }
        }

        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        return candidateIds
            .zipWithNext()
            .minByOrNull { (outgoing, _) ->
                kotlin.math.abs(resolveTransitionStartTimeMs(outgoing) - playheadMs)
            }
            ?.let { (outgoing, incoming) -> outgoing to incoming }
    }

    private fun triggerEditorHaptic(effect: EditorHapticEffect) {
        val targetView = currentFocus ?: window.decorView
        targetView.post {
            runCatching {
                targetView.performHapticFeedback(effect.feedbackConstant)
            }
        }
    }

    private fun applyToolbarTransitionPreset(type: TransitionType, durationMs: Int): Boolean {
        val (outgoing, incoming) = resolveToolbarTransitionPair() ?: return false
        transitionController?.applyQuickTransition(outgoing, incoming, type, durationMs)
        triggerEditorHaptic(EditorHapticEffect.Success)
        return true
    }

    private fun applyAutomationColorPreset(presetName: String): Boolean {
        val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return false
        timelineManager?.selectClip(clipId)
        NativeBridge.applyProfessionalEffectPreset(clipId, presetName)?.let { params ->
            clipEffects[clipId] = params
            refreshEffectSliderLabels(params)
            previewView?.let { pv ->
                runCatching { NativeBridge.seekToTime(pv, resolveVisualClipPreviewTimeMs(clipId)) }
            }
            triggerEditorHaptic(EditorHapticEffect.LightClick)
            return true
        }
        val params =
            when (presetName.trim().lowercase(Locale.US)) {
                "matte" -> EffectParams(brightness = 0.12f, contrast = 0.78f, saturation = 0.62f)
                "warm" -> EffectParams(brightness = 0.14f, contrast = 1.12f, saturation = 1.28f)
                "cool" -> EffectParams(brightness = -0.08f, contrast = 1.10f, saturation = 0.74f)
                "vintage" -> EffectParams(brightness = 0.10f, contrast = 0.82f, saturation = 0.46f)
                "bw",
                "b&w" -> EffectParams(brightness = -0.02f, contrast = 1.28f, saturation = 0.00f)
                "cinematic" -> EffectParams(brightness = -0.06f, contrast = 1.34f, saturation = 0.68f)
                "drama" -> EffectParams(brightness = -0.10f, contrast = 1.46f, saturation = 1.06f)
                "punch" -> EffectParams(brightness = 0.06f, contrast = 1.34f, saturation = 1.36f)
                "soft" -> EffectParams(brightness = 0.12f, contrast = 0.84f, saturation = 0.88f)
                "neutral",
                "reset" -> EffectParams()
                else -> EffectParams(brightness = 0.10f, contrast = 1.24f, saturation = 1.55f)
            }
        applySelectedClipEffects(clipId, params)
        refreshPreviewAtPlayhead(resyncAudio = false)
        triggerEditorHaptic(EditorHapticEffect.LightClick)
        return true
    }

    private fun applyAutomationTransitionPreset(typeName: String, durationMs: Int): Boolean {
        val selectedClipId =
            resolveSelectedVisualClipId(
                syncSelectionIfNeeded = true,
                preferredTrackType = TrackType.VIDEO,
            ) ?: return false
        timelineManager?.selectClip(selectedClipId)
        val type =
            runCatching {
                TransitionType.valueOf(typeName.trim().uppercase(Locale.US))
            }.getOrNull() ?: TransitionType.CROSS
        return applyToolbarTransitionPreset(type, durationMs.coerceIn(100, 2000))
    }

    private fun applyAutomationChromaKey(
        enabled: Boolean,
        useBlueKey: Boolean,
        similarity: Float,
        smoothness: Float,
        spill: Float,
    ): Boolean {
        val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return false
        timelineManager?.selectClip(clipId)
        val playheadMs = resolveVisualClipPreviewTimeMs(clipId)
        execCmd(
            action = "SET_CHROMA_KEY",
            params = mapOf(
                "clipId" to clipId,
                "enabled" to enabled,
                "color" to if (useBlueKey) 1 else 0,
                "similarity" to similarity.coerceIn(0f, 1f),
                "smoothness" to smoothness.coerceIn(0f, 1f),
                "spill" to spill.coerceIn(0f, 1f),
            ),
        ) { result ->
            previewView?.let { pv -> NativeBridge.seekToTime(pv, playheadMs) }
            Log.i(TAG, "[Automation] apply_chroma_key_result success=${result.success} clip=$clipId")
        }
        return true
    }

    private fun addAutomationPreviewKeyframe(): Boolean {
        val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return false
        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        val transform = currentClipPreviewTransform(clipId)
        addNativeClipKeyframe(clipId, playheadMs, transform) { success ->
            if (success) {
                upsertClipPreviewKeyframe(clipId, playheadMs, transform)
                refreshMainTimelineTracks()
                refreshPreviewAtPlayhead(resyncAudio = false)
                triggerEditorHaptic(EditorHapticEffect.Tick)
            }
        }
        return true
    }

    private fun removeToolbarTransitionPreset(): Boolean {
        val (outgoing, _) = resolveToolbarTransitionPair() ?: return false
        transitionController?.removeTransitionByOutgoingClip(outgoing)
        return true
    }

    private fun showAddTextDialog() {
        if (!ensureTrackEditable(TrackType.TEXT, "text")) return
        noteAppHealthAction("text_composer_opened")
        var customDurationMs: Int? = reusableTextDurationMs()
        val initialDurationSec = ((customDurationMs ?: 3000).coerceAtLeast(600)) / 1000f
        val durationSliderMaxSec = maxOf(60f, initialDurationSec)
        var customBold = true
        var customColor = 0xFFFFFFFF.toInt()
        var selectedPresetLabel = "Caption"

        val colors = listOf(
            0xFFFFFFFF.toInt(), // White
            0xFFFFEB3B.toInt(), // Yellow
            0xFF00E5FF.toInt(), // Cyan
            0xFF76FF03.toInt(), // Green
            0xFFFF5252.toInt(), // Red
            0xFFFF4081.toInt(), // Pink
            0xFFAB47BC.toInt(), // Purple
            0xFF111111.toInt(), // Black
        )

        ModernSheet.showModal(
            context = this,
            title = "Add Text Overlay",
            showClose = true,
            showApply = true,
            onApply = {
                val preset = textOverlayPresetByLabel(selectedPresetLabel) ?: textOverlayPresets.first()
                val overlay = buildTextOverlayFromPreset(
                    preset = preset,
                    rawText = "",
                    durationOverrideMs = customDurationMs,
                ).apply {
                    bold = customBold
                    color = customColor
                }
                addTextOverlay(overlay, message = "${preset.label} added")
            },
        ) {
            textInput("Content", "Write a title, caption, or label") { }

            fun styledOverlay(label: String): TextOverlay? {
                val preset = textOverlayPresetByLabel(label) ?: return null
                return buildTextOverlayFromPreset(
                    preset = preset,
                    rawText = getTextInput(),
                    durationOverrideMs = customDurationMs,
                ).apply {
                    bold = customBold
                    color = customColor
                }
            }

            section("Text Style Presets")
            chipGrid(
                label = "Styles",
                options = listOf("Title", "Caption", "Subtitle", "Lower 3rd", "Hook", "CTA"),
                selected = 1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, option ->
                selectedPresetLabel = option
            }

            divider()
            section("Text Color")
            colorPalette(colors, customColor) { chosenColor ->
                customColor = chosenColor
            }

            divider()
            section("Formatting")
            toggle("Bold Font", customBold) { enabled ->
                customBold = enabled
            }

            sliderWithBubble(
                label = "Duration",
                min = 0.6f,
                max = durationSliderMaxSec,
                value = initialDurationSec,
                unit = "s",
                format = { "%.1f".format(it) },
            ) { value ->
                customDurationMs = (value * 1000f).roundToInt()
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
                durationOverrideMs = reusableTextDurationMs(),
            ),
            message = "Text added. Drag to move, pinch to scale.",
        )
    }

    private fun isFirebaseTestLabRun(): Boolean {
        return LocalAutomationGate.isFirebaseTestLab(this)
    }

    private fun isDebuggableBuild(): Boolean {
        return LocalAutomationGate.isDebuggable(this)
    }

    private fun automationActionFrom(intent: Intent?): String {
        return LocalAutomationGate.actionFrom(this, intent)
    }

    private fun shouldHonorForceEditorBoot(intent: Intent?): Boolean {
        return EditorLaunchIntents.shouldHonorForceEditorBoot(this, intent)
    }

    private fun isAutomationPerfMode(): Boolean = isFirebaseTestLabRun()

    private fun maybeSeedFirebaseTestLabWorkspace() {
        if (!isDebuggableBuild() || !isFirebaseTestLabRun()) return
        if (roboWorkspaceSeedInFlight) return
        if (hasProjectContent()) {
            roboWorkspaceSeeded = true
            return
        }
        if (roboWorkspaceSeeded) return

        val videoSample = AutomationMediaSampleStore.ensureVideoSample(this) ?: return
        val audioSample = AutomationMediaSampleStore.ensureAudioSample(this)
        roboWorkspaceSeedInFlight = true
        noteAppHealthAction("robo_seed_prepare")
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) {
                roboWorkspaceSeedInFlight = false
                return@postDelayed
            }
            if (hasProjectContent()) {
                roboWorkspaceSeeded = true
                roboWorkspaceSeedInFlight = false
                return@postDelayed
            }

            startBlankProject(showToast = false)
            setSelectedImportTrackType(TrackType.VIDEO)
            importController?.setNextImportTrackType(TrackType.VIDEO)
            importController?.importFromPath(videoSample.absolutePath)

            window.decorView.postDelayed({
                setAutomationPlayhead(ROBO_SEED_SECOND_CLIP_OFFSET_MS)
                setSelectedImportTrackType(TrackType.VIDEO)
                importController?.setNextImportTrackType(TrackType.VIDEO)
                importController?.importFromPath(videoSample.absolutePath)
            }, ROBO_SEED_SECOND_IMPORT_DELAY_MS)

            audioSample?.let { sample ->
                window.decorView.postDelayed({
                    setSelectedImportTrackType(TrackType.AUDIO)
                    audioImportController?.importFromPath(
                        path = sample.absolutePath,
                        startTimeMs = 0L,
                    )
                }, ROBO_SEED_AUDIO_IMPORT_DELAY_MS)
            }

            window.decorView.postDelayed({
                addNewTextOverlay("Robo Sample")
            }, ROBO_SEED_TEXT_IMPORT_DELAY_MS)

            window.decorView.postDelayed({
                setAutomationPlayhead(0L)
                selectLatestClipForTrack(TrackType.VIDEO)
                refreshPreviewAtPlayhead()
                roboWorkspaceSeeded = hasProjectContent()
                roboWorkspaceSeedInFlight = false
                noteAppHealthAction(
                    if (roboWorkspaceSeeded) "robo_seed_ready" else "robo_seed_incomplete",
                )
            }, ROBO_SEED_FINALIZE_DELAY_MS)
        }, 900L)
    }

    private fun maybeHandleAutomationIntent(intent: Intent?) {
        val action = automationActionFrom(intent)
        if (action.isEmpty()) return
        Log.e(TAG, "maybeHandleAutomationIntent: action=$action extras=${intent?.extras?.keySet()?.joinToString(", ")}")
        deferAdsForSmoothEditing(windowMs = 180_000L)
        val token = automationIntentToken(action, intent)
        val nowMs = SystemClock.elapsedRealtime()
        if (lastAutomationToken == token && nowMs - lastAutomationTokenElapsedMs < AUTOMATION_DUPLICATE_SUPPRESS_MS) {
            return
        }
        window.decorView.postDelayed({
            performAutomationAction(action, intent, token, attempt = 0)
        }, 1200L)
    }

    private fun automationIntentToken(action: String, intent: Intent?): String {
        intent?.getStringExtra(LocalAutomationGate.EXTRA_TOKEN)?.takeIf { it.isNotBlank() }?.let { return it }
        val extras = intent?.extras ?: return action
        val signature =
            extras.keySet()
                .filterNot { it == LocalAutomationGate.EXTRA_ACTION }
                .sorted()
                .joinToString(separator = "|") { key -> "$key=${automationExtraString(intent, key).orEmpty()}" }
        return if (signature.isBlank()) action else "$action|$signature"
    }

    private fun automationExtraString(intent: Intent?, key: String): String? {
        val extras = intent?.extras ?: return null
        return runCatching {
            when (val value = extras.get(key)) {
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> value?.toString()
            }
        }.getOrNull()
    }

    private fun automationPath(intent: Intent?): String? {
        return automationExtraString(intent, LocalAutomationGate.EXTRA_PATH)?.takeIf { it.isNotBlank() }
    }

    private fun automationTrackType(intent: Intent?, default: TrackType = TrackType.VIDEO): TrackType {
        return automationTrackTypeOrNull(intent) ?: default
    }

    private fun automationTrackTypeOrNull(intent: Intent?): TrackType? {
        return automationExtraString(intent, LocalAutomationGate.EXTRA_TRACK_TYPE)
            ?.trim()
            ?.uppercase(Locale.US)
            ?.let { raw -> runCatching { TrackType.valueOf(raw) }.getOrNull() }
    }

    private fun runAfterPreviewSurfaceReadyForImport(action: () -> Unit) {
        previewViewportFrame?.visibility = View.VISIBLE
        previewView?.visibility = View.VISIBLE
        overlayContainer?.visibility = View.VISIBLE
        previewView?.syncNativeSurfaceSizeToView()
        previewView?.ensureNativeSurfaceBinding()
        window.decorView.postDelayed({
            previewView?.syncNativeSurfaceSizeToView()
            previewView?.ensureNativeSurfaceBinding()
            action()
        }, 320L)
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
        lastAutomationTokenElapsedMs = SystemClock.elapsedRealtime()
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
                runAfterPreviewSurfaceReadyForImport {
                    setSelectedImportTrackType(TrackType.VIDEO)
                    importController?.setNextImportTrackType(TrackType.VIDEO)
                    importController?.importQuickSample()
                }
            }
            "quick_import_overlay" -> {
                runAfterPreviewSurfaceReadyForImport {
                    setSelectedImportTrackType(TrackType.OVERLAY)
                    importController?.setNextImportTrackType(TrackType.OVERLAY)
                    importController?.importQuickSample()
                }
            }
            "quick_import_layer" -> {
                runAfterPreviewSurfaceReadyForImport {
                    setSelectedImportTrackType(TrackType.LAYER)
                    importController?.setNextImportTrackType(TrackType.LAYER)
                    importController?.importQuickSample()
                }
            }
            "reset_to_blank" -> {
                startBlankProject(showToast = false)
            }
            "import_video_path" -> {
                val path = automationPath(intent)
                if (path != null) {
                    val trackType = automationTrackType(intent)
                    runAfterPreviewSurfaceReadyForImport {
                        setSelectedImportTrackType(trackType)
                        importController?.setNextImportTrackType(trackType)
                        importController?.importFromPath(path)
                    }
                }
            }
            "import_media_path" -> {
                val path = automationPath(intent)
                if (path != null) {
                    val trackType = automationTrackType(intent)
                    runAfterPreviewSurfaceReadyForImport {
                        setSelectedImportTrackType(trackType)
                        importController?.setNextImportTrackType(trackType)
                        importController?.importFromPath(path)
                    }
                }
            }
            "quick_import_audio" -> {
                audioImportController?.importQuickSample()
            }
            "import_audio_path" -> {
                val path = automationPath(intent)
                if (path != null) {
                    audioImportController?.importFromPath(
                        path = path,
                        startTimeMs = currentPlayheadMs().coerceAtLeast(0L),
                    )
                }
            }
            "play" -> {
                playbackController?.nativePlay()
            }
            "pause" -> {
                playbackController?.nativePause()
            }
            "set_playhead_ms" -> {
                val requestedMs = automationExtraString(intent, LocalAutomationGate.EXTRA_TIME_MS)?.toLongOrNull() ?: 0L
                setAutomationPlayhead(requestedMs)
            }
            "select_track_clip" -> {
                val trackType = automationTrackTypeOrNull(intent)
                val selected = trackType?.let(::selectLatestClipForTrack) == true
                Log.i(TAG, "[Automation] select_track_clip_result selected=$selected track=${trackType?.name ?: "unknown"}")
            }
            "apply_color_preset" -> {
                val presetName = intent?.getStringExtra(LocalAutomationGate.EXTRA_PRESET).orEmpty().ifBlank { "Vivid" }
                val applied = applyAutomationColorPreset(presetName)
                Log.i(TAG, "[Automation] apply_color_preset applied=$applied preset=$presetName")
            }
            "apply_chroma_key" -> {
                val applied =
                    applyAutomationChromaKey(
                        enabled = automationExtraString(intent, LocalAutomationGate.EXTRA_ENABLED)?.toBooleanStrictOrNull() ?: true,
                        useBlueKey = automationExtraString(intent, LocalAutomationGate.EXTRA_BLUE)?.toBooleanStrictOrNull() ?: false,
                        similarity = automationExtraString(intent, LocalAutomationGate.EXTRA_SIMILARITY)?.toFloatOrNull() ?: 0.35f,
                        smoothness = automationExtraString(intent, LocalAutomationGate.EXTRA_SMOOTHNESS)?.toFloatOrNull() ?: 0.12f,
                        spill = automationExtraString(intent, LocalAutomationGate.EXTRA_SPILL)?.toFloatOrNull() ?: 0.06f,
                    )
                Log.i(TAG, "[Automation] apply_chroma_key queued=$applied")
            }
            "apply_transition_preset" -> {
                val typeName = intent?.getStringExtra(LocalAutomationGate.EXTRA_TRANSITION).orEmpty().ifBlank { "CROSS" }
                val durationMs = automationExtraString(intent, LocalAutomationGate.EXTRA_DURATION_MS)?.toIntOrNull() ?: 650
                val applied = applyAutomationTransitionPreset(typeName, durationMs)
                Log.i(TAG, "[Automation] apply_transition_preset applied=$applied type=$typeName durationMs=$durationMs")
            }
            "toggle_ducking" -> {
                performSelectedClipDuckingAction()
                Log.i(TAG, "[Automation] toggle_ducking kind=${selectedClipKind().name} key=${selectedTimelineClipKey ?: "none"}")
            }
            "pan_zoom_selected" -> {
                val clipId = enableSelectedClipPanZoomMode(showToast = false)
                Log.i(TAG, "[Automation] pan_zoom_selected clip=${clipId ?: -1} kind=${selectedClipKind().name} key=${selectedTimelineClipKey ?: "none"}")
            }
            "apply_pan_zoom_preset" -> {
                val preset = intent?.getStringExtra(LocalAutomationGate.EXTRA_PRESET).orEmpty().ifBlank { "push_in" }
                val applied = applySelectedClipPanZoomPreset(preset)
                Log.i(TAG, "[Automation] apply_pan_zoom_preset applied=$applied preset=$preset kind=${selectedClipKind().name} key=${selectedTimelineClipKey ?: "none"}")
            }
            "scale_selected_preview" -> {
                val factor = automationExtraString(intent, LocalAutomationGate.EXTRA_FACTOR)?.toFloatOrNull() ?: 1.2f
                val applied = scaleSelectedClipForPreview(factor)
                Log.i(
                    TAG,
                    "[Automation] scale_selected_preview applied=$applied factor=$factor kind=${selectedClipKind().name} key=${selectedTimelineClipKey ?: "none"}",
                )
            }
            "add_preview_keyframe" -> {
                val added = addAutomationPreviewKeyframe()
                Log.i(TAG, "[Automation] add_preview_keyframe added=$added kind=${selectedClipKind().name} key=${selectedTimelineClipKey ?: "none"}")
            }
            "split_selected" -> {
                val split = performSelectedClipSplitAction()
                Log.i(TAG, "[Automation] split_selected split=$split kind=${selectedClipKind().name} key=${selectedTimelineClipKey ?: "none"} playhead=${currentPlayheadMs()}")
            }
            "delete_selected" -> {
                performSelectedClipDeleteAction()
            }
            "import_video_and_play" -> {
                val path = automationPath(intent)
                if (path != null) {
                    val trackType = automationTrackType(intent)
                    setSelectedImportTrackType(trackType)
                    importController?.setNextImportTrackType(trackType)
                    importController?.importFromPath(path)
                    window.decorView.postDelayed({
                        playbackController?.nativePlay()
                    }, 5200L)
                }
            }
            "add_text" -> {
                addNewTextOverlay(resolveAutomationText(intent))
            }
            "smoke_playback_quick" -> {
                startBlankProject(showToast = false)
                setSelectedImportTrackType(TrackType.VIDEO)
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
                projectController?.autoSaveNow("automation")
            }
            "restore_autosave" -> {
                val restored = projectController?.restoreAutoSave() == true
                Log.i(TAG, "[Automation] restore_autosave restored=$restored")
            }
            "discard_autosave" -> {
                projectController?.discardAutoSave()
            }
            "smoke_autosave_prepare" -> {
                val overlayText = intent?.getStringExtra(LocalAutomationGate.EXTRA_TEXT).orEmpty().ifBlank { "AutosaveSmoke" }
                startBlankProject(showToast = false)
                setSelectedImportTrackType(TrackType.VIDEO)
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
                val overlayText = intent?.getStringExtra(LocalAutomationGate.EXTRA_TEXT).orEmpty().ifBlank { "Hello World" }
                startBlankProject(showToast = false)
                setSelectedImportTrackType(TrackType.VIDEO)
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

    private fun resolveAutomationText(intent: Intent?): String {
        intent?.getStringExtra(LocalAutomationGate.EXTRA_TEXT_B64)
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return intent?.getStringExtra(LocalAutomationGate.EXTRA_TEXT).orEmpty().ifBlank { "Hello World" }
    }

    private fun executePhoneCommand(command: String, commandId: String): Pair<Boolean, String> {
        val normalized = command.trim()
        if (normalized.isEmpty()) {
            return false to "Missing command"
        }

        noteAppHealthAction("remote_command_${normalized.take(72)}", force = true)
        return when (normalized) {
            "sync_health" -> {
                syncAppHealth(force = true, action = "remote_sync_health")
                true to "Health sync sent"
            }
            "show_home",
            "open_export",
            "open_media",
            "open_text",
            "play",
            "pause",
            "open_save",
            "open_aspect_ratio",
            "open_audio",
            "open_overlay",
            "open_layer",
            -> {
                val token = "remote:$commandId"
                val intent =
                    Intent().apply {
                        putExtra(LocalAutomationGate.EXTRA_ACTION, normalized)
                        putExtra(LocalAutomationGate.EXTRA_TOKEN, token)
                    }
                performAutomationAction(
                    action = normalized,
                    intent = intent,
                    token = token,
                    attempt = 0,
                )
                true to "Command dispatched: $normalized"
            }
            else -> false to "Unsupported command: $normalized"
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
        val persistedImportPickerStartTimeMs = readPersistedActiveImportPickerStartTimeMs(requestCode)
        val persistedImportPickerStartedFromStart = readPersistedActiveImportPickerStartedFromStart(requestCode)
        val hadMatchingActiveImportRequest =
            isAnyImportPickerRequest(requestCode) &&
                (activeImportPickerRequestCode == requestCode || persistedImportPickerStartTimeMs != null)
        val pickerStartedFromStartScreen =
            hadMatchingActiveImportRequest &&
                if (activeImportPickerRequestCode == requestCode) {
                    activeImportPickerStartedFromStartScreen
                } else {
                    persistedImportPickerStartedFromStart == true
                }
        val pickerRequestedStartTimeMs =
            if (hadMatchingActiveImportRequest) {
                if (activeImportPickerRequestCode == requestCode) {
                    activeImportPickerStartTimeMs.coerceAtLeast(0L)
                } else {
                    persistedImportPickerStartTimeMs?.coerceAtLeast(0L) ?: 0L
                }
            } else {
                currentPlayheadMs().coerceAtLeast(0L)
            }
        if (isAnyImportPickerRequest(requestCode)) {
            clearActiveImportPickerRequest(requestCode)
        }
        if (pendingVideoReplaceClipId != null && isVisualPickerRequest(requestCode)) {
            if (handleVideoReplaceResult(resultCode, data)) return
        }
        if (pendingAudioReplaceClipId != null && requestCode == PICK_AUDIO_REQUEST) {
            if (handleAudioReplaceResult(resultCode, data)) return
        }
        val hasUsablePickerResult =
            resultCode == Activity.RESULT_OK &&
                data?.data != null &&
                isAnyImportPickerRequest(requestCode)
        val shouldRestoreAutosaveAfterPicker =
            if (hasUsablePickerResult && isVisualPickerRequest(requestCode)) {
                consumeImportPickerRestoreAutosaveAllowed()
            } else {
                false
            }
        if (isAnyImportPickerRequest(requestCode) && !hasUsablePickerResult) {
            setImportPickerRestoreAutosaveAllowed(false)
        }
        if (isAnyImportPickerRequest(requestCode) && !hadMatchingActiveImportRequest && !hasUsablePickerResult) {
            setImportPickerRestoreAutosaveAllowed(false)
            ignoreStaleImportPickerResult(requestCode, resultCode, data)
            return
        }
        // Wait for surface to recreate after picker closes, then import
        if (hasUsablePickerResult && isVisualPickerRequest(requestCode)) {
            if (shouldRestoreAutosaveAfterPicker && !pickerStartedFromStartScreen && !hasProjectContent()) {
                restoreAutoSaveAfterImportPickerRecreate(
                    reason = "picker_result_before_import",
                    allowExistingContent = false,
                )
            } else if (!shouldRestoreAutosaveAfterPicker) {
                Log.i(TAG, "Skipping autosave restore after import picker reason=picker_result_before_import restoreArmed=false")
            }
            importPickerResultInProgress = true
            importPickerAutosaveRestoreInProgress = false
            if (pickerStartedFromStartScreen) {
                setStartScreenVisible(false)
            }
            holdPreviewSurfaceForImport()
        }
        val handledVideo =
            importController?.handlePickerResult(
                requestCode = requestCode,
                resultCode = resultCode,
                data = data,
                fallbackTrackType = resolveVisualPickerTrackType(requestCode),
                fallbackStartTimeMs = pickerRequestedStartTimeMs,
            ) == true
        if (handledVideo) {
            if (hasUsablePickerResult) {
                notePreviewInteractionBusy(3500L)
                // Picker return can briefly bounce between raw and resized surfaces.
                // Soft-sync the bound surface size after layout settles instead of hard rebind churn.
                mainHandler.postDelayed({
                    previewView?.ensureNativeSurfaceBinding()
                    previewView?.syncNativeSurfaceSizeToView()
                }, 250L)
                mainHandler.postDelayed({
                    previewView?.syncNativeSurfaceSizeToView()
                }, 900L)
            } else {
                projectLoadSurfaceHoldUntilElapsedMs = 0L
                val restoringLostEditorState =
                    !pickerStartedFromStartScreen &&
                    !hasProjectContent() &&
                        (importPickerAutosaveRestoreInProgress ||
                            restoreAutoSaveAfterImportPickerRecreate("picker_cancel_result"))
                if (pickerStartedFromStartScreen || (!hasProjectContent() && !restoringLostEditorState)) {
                    setStartScreenVisible(true)
                }
                recoverEditorStateAfterImportPickerCancel(requestCode)
                updatePreviewEmptyState()
                Log.d(TAG, "Import picker cancelled requestCode=$requestCode; editor state preserved")
            }
            return
        }
        if (!handledVideo && hasUsablePickerResult && isVisualPickerRequest(requestCode)) {
            importPickerResultInProgress = false
        }
        val handledAudio =
            audioImportController?.handlePickerResult(
                requestCode = requestCode,
                expectedRequestCode = PICK_AUDIO_REQUEST,
                resultCode = resultCode,
                data = data,
                startTimeMsOverride = pickerRequestedStartTimeMs,
            ) == true
        if (!handledAudio && requestCode == PICK_AUDIO_REQUEST && resultCode != Activity.RESULT_OK) {
            recoverEditorStateAfterImportPickerCancel(requestCode)
            Log.d(TAG, "Audio picker cancelled requestCode=$requestCode; editor state preserved")
        }
    }

    private fun recoverEditorStateAfterImportPickerCancel(requestCode: Int) {
        Log.d(TAG, "Recovering editor state after import picker cancel requestCode=$requestCode")
        setStartScreenVisible(false)
        restoreNativeClipStateFromImportPickerSnapshot()
        previewView?.ensureNativeSurfaceBinding()
        previewView?.syncNativeSurfaceSizeToView()
        syncTimelineShellFromNative()
        refreshMainTimelineTracks()
        redrawPreviewAfterImportPickerCancel()
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            previewView?.ensureNativeSurfaceBinding()
            previewView?.syncNativeSurfaceSizeToView()
            syncTimelineShellFromNative()
            refreshMainTimelineTracks()
            redrawPreviewAfterImportPickerCancel()
        }, 450L)
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            syncTimelineShellFromNative()
            refreshMainTimelineTracks()
            redrawPreviewAfterImportPickerCancel()
        }, 1_200L)
    }

    private fun redrawPreviewAfterImportPickerCancel() {
        val targetTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        previewView?.ensureNativeSurfaceBinding()
        previewView?.syncNativeSurfaceSizeToView()
        val redrawn =
            seekPreviewNativeIfNeeded(
                timeMs = targetTimeMs,
                force = true,
                allowDuringPlayback = false,
            )
        Log.d(TAG, "Import picker cancel preview redraw targetMs=$targetTimeMs success=$redrawn")
    }

    private fun restoreNativeClipStateFromImportPickerSnapshot(): Boolean {
        val snapshot = importPickerNativeClipSnapshot ?: return false
        importPickerNativeClipSnapshot = null
        if (snapshot.states.isEmpty()) return false
        restoreNativeClipUiState(snapshot.states)
        Log.d(TAG, "Restored import picker native clip snapshot count=${snapshot.states.size}")
        return true
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

        execCmd(
            action = "REPLACE_CLIP_SOURCE",
            params = mapOf(
                "clipId" to targetClipId,
                "videoPath" to resolvedPath,
            )
        ) { result ->
            if (result.success) {
                val newClipId = result.data.optInt("newClipId", -1).takeIf { it > 0 }
                if (newClipId != null && newClipId != targetClipId) {
                    videoClipReverseOverrides[targetClipId]?.let { videoClipReverseOverrides[newClipId] = it }
                    videoClipFreezeOverrides[targetClipId]?.let { videoClipFreezeOverrides[newClipId] = it }
                    videoClipCurveProfiles[targetClipId]?.let { videoClipCurveProfiles[newClipId] = it }
                    videoClipGainOverrides[targetClipId]?.let { videoClipGainOverrides[newClipId] = it }
                    videoClipKeyframes[targetClipId]?.let { keyframes ->
                        videoClipKeyframes[newClipId] = keyframes.toMutableList()
                    }
                    videoClipPreviewKeyframes[targetClipId]?.let { keyframes ->
                        videoClipPreviewKeyframes[newClipId] = keyframes.toMutableList()
                    }
                    duckingEnabledForKey[targetClipId.toString()]?.let { duckingEnabledForKey[newClipId.toString()] = it }
                    videoClipReverseOverrides.remove(targetClipId)
                    videoClipFreezeOverrides.remove(targetClipId)
                    videoClipCurveProfiles.remove(targetClipId)
                    videoClipGainOverrides.remove(targetClipId)
                    videoClipKeyframes.remove(targetClipId)
                    videoClipPreviewKeyframes.remove(targetClipId)
                    duckingEnabledForKey.remove(targetClipId.toString())
                }
                syncTimelineShellFromNative(selectedClipId = newClipId ?: targetClipId)
                safeToast("Clip replaced", Toast.LENGTH_SHORT)
            } else {
                safeToast("Replace failed", Toast.LENGTH_SHORT)
                Log.w(TAG, "Replace failed clip=$targetClipId message=${result.message}")
            }
        }
        return true
    }

    private fun resolveVisualPickerTrackType(requestCode: Int): TrackType? {
        return when (requestCode) {
            PICK_VIDEO_REQUEST -> TrackType.VIDEO
            PICK_OVERLAY_REQUEST -> TrackType.OVERLAY
            PICK_LAYER_REQUEST -> TrackType.LAYER
            else -> null
        }
    }

    private fun ignoreStaleImportPickerResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!isAnyImportPickerRequest(requestCode)) return
        val uri = data?.data?.toString()
        Log.w(
            TAG,
            "Ignoring stale import picker result requestCode=$requestCode resultCode=$resultCode uri=$uri activeRequest=$activeImportPickerRequestCode",
        )
        clearActiveImportPickerRequest(requestCode)
    }

    private fun isAnyImportPickerRequest(requestCode: Int): Boolean {
        return isVisualPickerRequest(requestCode) || requestCode == PICK_AUDIO_REQUEST
    }

    private fun prepareImportPickerLaunch(requestCode: Int, label: String): Boolean {
        val existingRequestCode = activeImportPickerRequestCode
        if (existingRequestCode != null) {
            val elapsedMs = SystemClock.elapsedRealtime() - activeImportPickerOpenedAtElapsedMs
            if (elapsedMs < 90_000L) {
                Log.d(
                    TAG,
                    "Ignoring duplicate $label picker launch while requestCode=$existingRequestCode is still active (${elapsedMs}ms)",
                )
                safeToast("Finish current picker first", Toast.LENGTH_SHORT)
                return false
            }
            clearActiveImportPickerRequest()
        }
        val contentBeforePicker = hasProjectContent()
        val canRestoreFromPicker =
            if (contentBeforePicker) {
                val saved = saveCurrentSessionNow("before_${label}_import_picker")
                Log.d(TAG, "Pre-picker autosave label=$label saved=$saved")
                importPickerNativeClipSnapshot =
                    ImportPickerNativeClipSnapshot(buildNativeClipUiStateSnapshot())
                saved
            } else {
                importPickerNativeClipSnapshot = null
                false
            }
        setImportPickerRestoreAutosaveAllowed(canRestoreFromPicker)
        if (contentBeforePicker) {
            Log.d(TAG, "Import picker autosave restore armed label=$label allowed=$canRestoreFromPicker")
        }
        importPickerAutosaveRestoreAttempted = false
        importPickerAutosaveRestoreInProgress = false
        activeImportPickerRequestCode = requestCode
        activeImportPickerOpenedAtElapsedMs = SystemClock.elapsedRealtime()
        activeImportPickerStartedFromStartScreen = startScreenOverlayView?.visibility == View.VISIBLE
        activeImportPickerStartTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        persistActiveImportPickerRequest(
            requestCode = requestCode,
            startTimeMs = activeImportPickerStartTimeMs,
            startedFromStartScreen = activeImportPickerStartedFromStartScreen,
        )
        return true
    }

    private fun clearActiveImportPickerRequest(requestCode: Int? = null) {
        if (requestCode == null || activeImportPickerRequestCode == requestCode) {
            activeImportPickerRequestCode = null
            activeImportPickerOpenedAtElapsedMs = 0L
            activeImportPickerStartedFromStartScreen = false
            activeImportPickerStartTimeMs = 0L
        }
        clearPersistedActiveImportPickerRequest(requestCode)
    }

    private fun persistActiveImportPickerRequest(
        requestCode: Int,
        startTimeMs: Long,
        startedFromStartScreen: Boolean,
    ) {
        getSharedPreferences(PREFS_IMPORT_PICKER, MODE_PRIVATE)
            .edit()
            .putInt(PREF_ACTIVE_IMPORT_PICKER_REQUEST_CODE, requestCode)
            .putLong(PREF_ACTIVE_IMPORT_PICKER_START_TIME_MS, startTimeMs.coerceAtLeast(0L))
            .putBoolean(PREF_ACTIVE_IMPORT_PICKER_STARTED_FROM_START, startedFromStartScreen)
            .apply()
    }

    private fun readPersistedActiveImportPickerStartTimeMs(requestCode: Int): Long? {
        if (!isAnyImportPickerRequest(requestCode)) return null
        val prefs = getSharedPreferences(PREFS_IMPORT_PICKER, MODE_PRIVATE)
        return if (prefs.getInt(PREF_ACTIVE_IMPORT_PICKER_REQUEST_CODE, -1) == requestCode) {
            prefs.getLong(PREF_ACTIVE_IMPORT_PICKER_START_TIME_MS, 0L).coerceAtLeast(0L)
        } else {
            null
        }
    }

    private fun readPersistedActiveImportPickerStartedFromStart(requestCode: Int): Boolean? {
        if (!isAnyImportPickerRequest(requestCode)) return null
        val prefs = getSharedPreferences(PREFS_IMPORT_PICKER, MODE_PRIVATE)
        return if (prefs.getInt(PREF_ACTIVE_IMPORT_PICKER_REQUEST_CODE, -1) == requestCode) {
            prefs.getBoolean(PREF_ACTIVE_IMPORT_PICKER_STARTED_FROM_START, false)
        } else {
            null
        }
    }

    private fun clearPersistedActiveImportPickerRequest(requestCode: Int? = null) {
        val prefs = getSharedPreferences(PREFS_IMPORT_PICKER, MODE_PRIVATE)
        val persistedRequestCode = prefs.getInt(PREF_ACTIVE_IMPORT_PICKER_REQUEST_CODE, -1)
        if (requestCode == null || persistedRequestCode == requestCode) {
            prefs.edit()
                .remove(PREF_ACTIVE_IMPORT_PICKER_REQUEST_CODE)
                .remove(PREF_ACTIVE_IMPORT_PICKER_START_TIME_MS)
                .remove(PREF_ACTIVE_IMPORT_PICKER_STARTED_FROM_START)
                .apply()
        }
    }

    private fun setImportPickerRestoreAutosaveAllowed(allowed: Boolean) {
        val prefs = getSharedPreferences(PREFS_IMPORT_PICKER, MODE_PRIVATE)
        prefs.edit().apply {
            if (allowed) {
                putBoolean(PREF_IMPORT_PICKER_RESTORE_AUTOSAVE, true)
            } else {
                remove(PREF_IMPORT_PICKER_RESTORE_AUTOSAVE)
            }
        }.apply()
    }

    private fun consumeImportPickerRestoreAutosaveAllowed(): Boolean {
        val prefs = getSharedPreferences(PREFS_IMPORT_PICKER, MODE_PRIVATE)
        val allowed = prefs.getBoolean(PREF_IMPORT_PICKER_RESTORE_AUTOSAVE, false)
        prefs.edit().remove(PREF_IMPORT_PICKER_RESTORE_AUTOSAVE).apply()
        return allowed
    }

    private fun clearImportSessionState(reason: String) {
        Log.d(TAG, "Clearing import session state: $reason")
        clearActiveImportPickerRequest()
        setImportPickerRestoreAutosaveAllowed(false)
        pendingVideoReplaceClipId = null
        pendingAudioReplaceClipId = null
        importPickerNativeClipSnapshot = null
        importPickerResultInProgress = false
        setSelectedImportTrackType(null)
        importController?.resetPendingState()
    }

    private fun maybeDismissStaleImportPicker(intent: Intent?) {
        val activeRequestCode = activeImportPickerRequestCode ?: return
        if (automationActionFrom(intent).isNotBlank()) return
        Log.d(TAG, "Closing stale picker requestCode=$activeRequestCode on relaunch")
        runCatching { finishActivity(activeRequestCode) }
        clearActiveImportPickerRequest(activeRequestCode)
        importController?.resetPendingState()
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
        val replacementRestoreGain = audioDuckingRestoreGainOverrides[targetAudioId]
        imported.gain = replacementGain
        imported.fadeInMs = sourceClip.fadeInMs.coerceAtLeast(0)
        imported.fadeOutMs = sourceClip.fadeOutMs.coerceAtLeast(0)
        imported.gainKeyframes = normalizeAudioGainKeyframes(sourceClip.gainKeyframes, imported.durationMs)
        imported.muted = replacementGain <= 0.001f
        audioClipGainOverrides[imported.id] = replacementGain
        execCmd(
            action = "SET_CLIP_AUDIO_FADES",
            params = mapOf(
                "clipId" to imported.id,
                "fadeInMs" to imported.fadeInMs,
                "fadeOutMs" to imported.fadeOutMs,
            )
        ) {
            execCmd(
                action = "SET_CLIP_AUDIO_KEYFRAMES",
                params = mapOf(
                    "clipId" to imported.id,
                    "keyframesCsv" to NativeBridge.serializeAudioGainKeyframesCsv(imported.gainKeyframes),
                )
            ) {
                execCmd(
                    action = "DELETE_CLIP",
                    params = mapOf("clipId" to targetAudioId)
                ) {
                    AudioClipStore.remove(targetAudioId)
                    audioClipGainOverrides.remove(targetAudioId)
                    replacementRestoreGain?.let { audioDuckingRestoreGainOverrides[imported.id] = it }
                    audioDuckingRestoreGainOverrides.remove(targetAudioId)
                    duckingEnabledForKey[oldDuckingKey]?.let { duckingEnabledForKey[newDuckingKey] = it }
                    duckingEnabledForKey.remove(oldDuckingKey)
                    selectedTimelineClipKey = "audio-${imported.id}"
                    lastLayoutFetchMs = 0L
                    syncTimelineShellFromNative(selectedClipId = imported.id)
                    safeToast("Audio replaced", Toast.LENGTH_SHORT)
                }
            }
        }
        return true
    }

    private fun resolveVideoImportPath(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        if (uri.scheme != "content") {
            return uri.toString()
        }

        val importsDir = File(filesDir, "imports").apply { mkdirs() }
        val fileName = queryDisplayName(uri)
            ?.takeIf { it.isNotBlank() }
            ?: "import_${System.currentTimeMillis()}.mp4"
        val targetFile = File(importsDir, sanitizeImportFileName(fileName))

        return if (
            ContentUriImportResolver.copyToFile(
                contentResolver = contentResolver,
                uri = uri,
                targetFile = targetFile,
                logTag = TAG,
                label = "selected replacement video",
            )
        ) {
            targetFile.absolutePath
        } else {
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

        val importsDir = File(filesDir, "audio_imports").apply { mkdirs() }
        val fileName = queryDisplayName(uri)
            ?.takeIf { it.isNotBlank() }
            ?: "audio_${System.currentTimeMillis()}.m4a"
        val targetFile = File(importsDir, sanitizeImportFileName(fileName))

        return if (
            ContentUriImportResolver.copyToFile(
                contentResolver = contentResolver,
                uri = uri,
                targetFile = targetFile,
                logTag = TAG,
                label = "selected replacement audio",
            )
        ) {
            targetFile.absolutePath
        } else {
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
        } else {
            runCatching {
                val (pixels, width, height) = TextBitmapHelper.createTextPixels(overlay)
                preview.setTextOverlayBitmap(overlay.id, pixels, width, height)
            }.onFailure { error ->
                Log.w(TAG, "Text bitmap upload failed: ${error.message}")
            }
        }
        if (!overlayViews.containsKey(overlay.id)) {
            addOverlayView(overlay)
        }
        preview.setActiveTextOverlayId(overlay.id)
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

    private fun interpolateAiPoseKeyframe(keyframes: List<AiPoseKeyframe>, timeMs: Long): AiPoseKeyframe? {
        if (keyframes.isEmpty()) return null
        val ordered = keyframes.sortedBy { it.timeMs }
        if (timeMs <= ordered.first().timeMs) return ordered.first()
        if (timeMs >= ordered.last().timeMs) return ordered.last()
        for (index in 1 until ordered.size) {
            val left = ordered[index - 1]
            val right = ordered[index]
            if (timeMs > right.timeMs) continue
            val span = (right.timeMs - left.timeMs).coerceAtLeast(1L)
            val progress = ((timeMs - left.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            return AiPoseKeyframe(
                timeMs = timeMs,
                x = left.x + ((right.x - left.x) * progress),
                y = left.y + ((right.y - left.y) * progress),
                scale = left.scale + ((right.scale - left.scale) * progress),
                rotation = left.rotation + ((right.rotation - left.rotation) * progress),
                opacity = left.opacity + ((right.opacity - left.opacity) * progress),
            )
        }
        return ordered.last()
    }

    private fun applyTextOverlayPose(overlay: TextOverlay) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            val view = overlayViews[overlay.id] ?: return@post
            val container = overlayContainer ?: return@post
            val containerWidth = container.width.takeIf { it > 0 } ?: return@post
            val containerHeight = container.height.takeIf { it > 0 } ?: return@post
            val trackedPose = interpolateAiPoseKeyframe(overlay.aiTrackKeyframes, currentPlayheadMs().coerceAtLeast(0L))
            val renderX = trackedPose?.x ?: overlay.x
            val renderY = trackedPose?.y ?: overlay.y
            val renderScale = trackedPose?.scale ?: overlay.scale
            val renderRotation = trackedPose?.rotation ?: overlay.rotation
            val renderOpacity = trackedPose?.opacity ?: overlay.opacity
            val currentWidth = view.width.takeIf { it > 0 } ?: 200
            val currentHeight = view.height.takeIf { it > 0 } ?: 100
            val renderedWidth = (currentWidth * renderScale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
            val renderedHeight = (currentHeight * renderScale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
            val minCenterX = renderedWidth * 0.5f
            val minCenterY = renderedHeight * 0.5f
            val maxCenterX = (containerWidth.toFloat() - renderedWidth * 0.5f).coerceAtLeast(minCenterX)
            val maxCenterY = (containerHeight.toFloat() - renderedHeight * 0.5f).coerceAtLeast(minCenterY)
            val clampedCenterXPx = (renderX.coerceIn(0f, 1f) * containerWidth).coerceIn(minCenterX, maxCenterX)
            val clampedCenterYPx = (renderY.coerceIn(0f, 1f) * containerHeight).coerceIn(minCenterY, maxCenterY)
            val normalizedX = (clampedCenterXPx / containerWidth.toFloat()).coerceIn(0f, 1f)
            val normalizedY = (clampedCenterYPx / containerHeight.toFloat()).coerceIn(0f, 1f)
            if (trackedPose == null) {
                overlay.x = normalizedX
                overlay.y = normalizedY
            }
            val nextLeft =
                (clampedCenterXPx - (currentWidth * 0.5f)).toInt().coerceIn(0, (containerWidth - currentWidth).coerceAtLeast(0))
            val nextTop =
                (clampedCenterYPx - (currentHeight * 0.5f)).toInt().coerceIn(0, (containerHeight - currentHeight).coerceAtLeast(0))
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
            view.scaleX = renderScale
            view.scaleY = renderScale
            view.rotation = renderRotation
            view.alpha = if (overlay.visible) renderOpacity.coerceIn(0.12f, 1f) else 0.35f
            previewView?.updateTextOverlay(
                overlay.id,
                normalizedX,
                normalizedY,
                renderScale,
                renderRotation,
                overlay.color,
                overlay.fontSize,
                overlay.startTimeMs,
                overlay.endTimeMs,
            )
            previewView?.updateTextOverlayOpacity(overlay.id, if (overlay.visible) renderOpacity.coerceIn(0f, 1f) else 0f, 0, 0)
        }
    }

    private fun applyStickerOverlayPose(clip: StickerClip) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            val view = stickerOverlayViews[clip.id] ?: return@post
            val container = overlayContainer ?: return@post
            val containerWidth = container.width.takeIf { it > 0 } ?: return@post
            val containerHeight = container.height.takeIf { it > 0 } ?: return@post
            val trackedPose = interpolateAiPoseKeyframe(clip.aiTrackKeyframes, currentPlayheadMs().coerceAtLeast(0L))
            val renderX = trackedPose?.x ?: clip.x
            val renderY = trackedPose?.y ?: clip.y
            val renderScale = trackedPose?.scale ?: clip.scale
            val renderRotation = trackedPose?.rotation ?: clip.rotation
            val renderOpacity = trackedPose?.opacity ?: clip.opacity
            val currentWidth = view.width.takeIf { it > 0 } ?: 100
            val currentHeight = view.height.takeIf { it > 0 } ?: 100
            val centerXPx = renderX.coerceIn(0f, 1f) * containerWidth
            val centerYPx = renderY.coerceIn(0f, 1f) * containerHeight
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
            view.scaleX = if (clip.mirrorX) -renderScale else renderScale
            view.scaleY = renderScale
            view.rotation = renderRotation
            view.alpha = if (clip.visible) renderOpacity.coerceIn(0.12f, 1f) else 0.35f
            view.setSticker(stickerLabelForClip(clip), clip.durationMs)
        }
    }

    private fun applyTextOverlayState(overlay: TextOverlay) {
        overlayController?.applyTextOverlayState(overlay)
    }

    private fun applyStickerLayerState(clip: StickerClip) {
        overlayController?.applyStickerLayerState(clip)
    }

    private val refreshPreviewRunnable: Runnable by lazy {
        Runnable {
            if (isFinishing || isDestroyed) {
                previewRefreshScheduled = false
                previewRefreshRequestedDuringRun = false
                previewRefreshNeedsAudioResync = false
                return@Runnable
            }
            lastPreviewRefreshUptimeMs = SystemClock.uptimeMillis()
            val resyncAudio = previewRefreshNeedsAudioResync
            previewRefreshNeedsAudioResync = false
            previewRefreshRequestedDuringRun = false
            refreshPreviewAtPlayheadInternal(resyncAudio)
            if (previewRefreshRequestedDuringRun && !isFinishing && !isDestroyed) {
                previewRefreshRequestedDuringRun = false
                val minIntervalMs = if (shouldDeferHeavyUiWork()) 24L else 8L
                mainHandler.postDelayed(refreshPreviewRunnable, minIntervalMs)
            } else {
                previewRefreshScheduled = false
            }
        }
    }

    private fun rememberPreviewLifecycleAnchor(timeMs: Long) {
        val safeTimeMs = timeMs.coerceAtLeast(0L)
        if (safeTimeMs > PREVIEW_DUPLICATE_NATIVE_SEEK_TOLERANCE_MS) {
            previewLifecycleAnchorTimeMs = safeTimeMs
        }
    }

    private fun resolveLifecycleStablePlayhead(rawTimeMs: Long): Long {
        val safeTimeMs = rawTimeMs.coerceAtLeast(0L)
        if (safeTimeMs > PREVIEW_DUPLICATE_NATIVE_SEEK_TOLERANCE_MS) {
            return safeTimeMs
        }
        val restoreActive = SystemClock.elapsedRealtime() < previewLifecycleRestoreUntilElapsedMs
        val anchorTimeMs = previewLifecycleAnchorTimeMs
        return if (
            restoreActive &&
            anchorTimeMs > PREVIEW_DUPLICATE_NATIVE_SEEK_TOLERANCE_MS &&
            hasProjectContent()
        ) {
            anchorTimeMs
        } else {
            safeTimeMs
        }
    }

    private fun seekPreviewNativeIfNeeded(
        timeMs: Long,
        force: Boolean = false,
        allowDuringPlayback: Boolean = false,
    ): Boolean {
        val preview = previewView ?: return false
        if (!allowDuringPlayback && isPlaying && NativeBridge.isPlaybackActive(preview)) {
            return false
        }
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        val now = SystemClock.uptimeMillis()
        val duplicateSeek =
            lastPreviewNativeSeekTimeMs != Long.MIN_VALUE &&
                abs(targetTimeMs - lastPreviewNativeSeekTimeMs) <= PREVIEW_DUPLICATE_NATIVE_SEEK_TOLERANCE_MS &&
                now - lastPreviewNativeSeekUptimeMs <= PREVIEW_NATIVE_SEEK_QUIET_WINDOW_MS
        if (!force && duplicateSeek) {
            return false
        }
        if (!force && !allowDuringPlayback && !isPlaying && isPreviewInteractionBusy()) {
            val seekDelayMs = resolvePreviewNativeSeekDebounceMs()
            val elapsedSinceLastSeekMs = now - lastPreviewNativeSeekUptimeMs
            if (
                lastPreviewNativeSeekTimeMs != Long.MIN_VALUE &&
                elapsedSinceLastSeekMs < seekDelayMs
            ) {
                pendingPreviewNativeSeekTimeMs = targetTimeMs
                mainHandler.removeCallbacks(previewNativeSeekDebounceRunnable)
                mainHandler.postDelayed(
                    previewNativeSeekDebounceRunnable,
                    (seekDelayMs - elapsedSinceLastSeekMs).coerceAtLeast(32L),
                )
                return false
            }
        }
        pendingPreviewNativeSeekTimeMs = Long.MIN_VALUE
        mainHandler.removeCallbacks(previewNativeSeekDebounceRunnable)
        NativeBridge.seekToTime(preview, targetTimeMs)
        lastPreviewNativeSeekTimeMs = targetTimeMs
        lastPreviewNativeSeekUptimeMs = now
        rememberPreviewLifecycleAnchor(targetTimeMs)
        return true
    }

    private fun refreshOverlayVisibilityAndPose() {
        allTextOverlays().forEach { overlay ->
            applyTextOverlayState(overlay)
            applyTextOverlayPose(overlay)
        }
        StickerClipStore.all().forEach { clip ->
            if (clip.aiTrackKeyframes.isNotEmpty()) {
                applyStickerLayerState(clip)
                applyStickerOverlayPose(clip)
            }
        }
    }

    private fun refreshPreviewAtPlayheadInternal(resyncAudio: Boolean = true) {
        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        currentTimeMs = playheadMs
        rememberPreviewLifecycleAnchor(playheadMs)
        refreshOverlayVisibilityAndPose()
        applySelectedClipKeyframedPreviewTransform(playheadMs, immediate = true)
        seekPreviewNativeIfNeeded(
            timeMs = playheadMs,
            force = lastPreviewNativeSeekTimeMs == Long.MIN_VALUE,
            allowDuringPlayback = false,
        )
        val allowAudioPreviewSync =
            !(isAutomationPerfMode() && roboWorkspaceSeedInFlight && !isPlaying) &&
                !shouldSuspendPreviewAudioForDirectGesture()
        if (resyncAudio && allowAudioPreviewSync) {
            if (isPlaying) {
                previewAudioPlayer?.syncToVideoClock(playheadMs, continuePlaying = true)
            } else {
                syncPreviewAudioAt(playheadMs, continuePlaying = false)
            }
        }
    }

    private fun refreshPreviewAtPlayhead(resyncAudio: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(Runnable { refreshPreviewAtPlayhead(resyncAudio) })
            return
        }
        if (isFinishing || isDestroyed) return
        previewRefreshNeedsAudioResync = previewRefreshNeedsAudioResync || resyncAudio
        if (previewRefreshScheduled) {
            previewRefreshRequestedDuringRun = true
            return
        }
        val elapsedMs = SystemClock.uptimeMillis() - lastPreviewRefreshUptimeMs
        val minIntervalMs = if (shouldDeferHeavyUiWork()) 24L else 8L
        val delayMs = if (elapsedMs >= minIntervalMs) 0L else minIntervalMs - elapsedMs
        previewRefreshScheduled = true
        previewRefreshRequestedDuringRun = false
        if (delayMs <= 0L) {
            mainHandler.post(refreshPreviewRunnable)
        } else {
            mainHandler.postDelayed(refreshPreviewRunnable, delayMs)
        }
    }

    private fun shouldSuppressPausedPreviewAudioSync(): Boolean {
        return (!isPlaying && SystemClock.elapsedRealtime() < pausedPreviewAudioSuppressUntilElapsedMs) ||
            (isAutomationPerfMode() && !isPlaying)
    }

    private fun resolvePreviewNativeSeekDebounceMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.HIGH -> 96L
            DeviceDetector.DeviceTier.MID -> 128L
            DeviceDetector.DeviceTier.LOW -> 180L
        }
    }

    private fun resolvePreviewGestureFrameIntervalMs(): Long {
        if (isPlaying) {
            return when (DeviceDetector.getDeviceTier()) {
                DeviceDetector.DeviceTier.HIGH -> 16L
                DeviceDetector.DeviceTier.MID -> 20L
                DeviceDetector.DeviceTier.LOW -> 28L
            }
        }
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.HIGH -> 24L
            DeviceDetector.DeviceTier.MID -> 34L
            DeviceDetector.DeviceTier.LOW -> 48L
        }
    }

    private fun shouldSuspendPreviewAudioForDirectGesture(): Boolean {
        return isPreviewInteractionBusy() ||
            previewTransformDragging ||
            previewTransformPinching ||
            previewTransformTouchArmed ||
            previewResizeSession != null ||
            previewTrimSession != null
    }

    private fun cancelPreviewAudioGestureResume() {
        mainHandler.removeCallbacks(previewAudioGestureResumeRunnable)
    }

    private fun cancelContinuePreviewAudioSeek() {
        pendingContinuePreviewAudioSeekTimeMs = Long.MIN_VALUE
        mainHandler.removeCallbacks(continuePreviewAudioSeekRunnable)
    }

    private fun cancelPendingPreviewAudioSeek() {
        pendingPreviewAudioSeekTimeMs = Long.MIN_VALUE
        mainHandler.removeCallbacks(previewAudioSeekDebounceRunnable)
    }

    private fun dispatchContinuePreviewAudioSeek(timeMs: Long) {
        if (!isPlaying || shouldSuspendPreviewAudioForDirectGesture()) {
            return
        }
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        lastContinuePreviewAudioSeekTimeMs = targetTimeMs
        lastContinuePreviewAudioSeekElapsedMs = SystemClock.elapsedRealtime()
        previewAudioPlayer?.seekTo(targetTimeMs, continuePlaying = true)
    }

    private fun scheduleContinuePreviewAudioSeek(timeMs: Long) {
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        val lastTimeMs = lastContinuePreviewAudioSeekTimeMs
        val elapsedSinceLast = now - lastContinuePreviewAudioSeekElapsedMs
        val distanceSinceLast =
            if (lastTimeMs == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                abs(targetTimeMs - lastTimeMs)
            }
        pendingContinuePreviewAudioSeekTimeMs = targetTimeMs
        if (
            lastTimeMs == Long.MIN_VALUE ||
            elapsedSinceLast >= PREVIEW_PLAYING_AUDIO_SEEK_MIN_INTERVAL_MS ||
            distanceSinceLast >= PREVIEW_PLAYING_AUDIO_SEEK_MIN_DISTANCE_MS
        ) {
            mainHandler.removeCallbacks(continuePreviewAudioSeekRunnable)
            dispatchContinuePreviewAudioSeek(targetTimeMs)
            pendingContinuePreviewAudioSeekTimeMs = Long.MIN_VALUE
            return
        }
        val delayMs = (PREVIEW_PLAYING_AUDIO_SEEK_MIN_INTERVAL_MS - elapsedSinceLast).coerceAtLeast(48L)
        mainHandler.removeCallbacks(continuePreviewAudioSeekRunnable)
        mainHandler.postDelayed(continuePreviewAudioSeekRunnable, delayMs)
    }

    private fun resolvePausedPreviewAudioSeekDebounceMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.HIGH -> 160L
            DeviceDetector.DeviceTier.MID -> 220L
            DeviceDetector.DeviceTier.LOW -> 300L
        }
    }

    private fun schedulePausedPreviewAudioSeek(timeMs: Long) {
        pendingPreviewAudioSeekTimeMs = timeMs.coerceAtLeast(0L)
        mainHandler.removeCallbacks(previewAudioSeekDebounceRunnable)
        mainHandler.postDelayed(
            previewAudioSeekDebounceRunnable,
            resolvePausedPreviewAudioSeekDebounceMs(),
        )
    }

    private fun pausePreviewAudioForDirectGesture() {
        cancelPreviewAudioGestureResume()
        cancelPendingPreviewAudioSeek()
        cancelContinuePreviewAudioSeek()
        pausedPreviewAudioSuppressUntilElapsedMs =
            maxOf(pausedPreviewAudioSuppressUntilElapsedMs, SystemClock.elapsedRealtime() + 1800L)
        if (isPlaying) return
        previewAudioPlayer?.pause()
    }

    private fun resolvePreviewAudioGestureResumeDelayMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.HIGH -> 900L
            DeviceDetector.DeviceTier.MID -> 1100L
            DeviceDetector.DeviceTier.LOW -> 1400L
        }
    }

    private fun schedulePreviewAudioResumeAfterGesture(delayMs: Long = -1L) {
        cancelPreviewAudioGestureResume()
        val safeDelayMs =
            if (delayMs >= 0L) {
                delayMs
            } else {
                resolvePreviewAudioGestureResumeDelayMs()
            }
        mainHandler.postDelayed(previewAudioGestureResumeRunnable, safeDelayMs.coerceAtLeast(120L))
    }

    private fun syncPreviewAudioAt(timeMs: Long, continuePlaying: Boolean) {
        if (shouldSuspendPreviewAudioForDirectGesture()) {
            pausePreviewAudioForDirectGesture()
            return
        }
        cancelPreviewAudioGestureResume()
        val shouldContinue = continuePlaying && isPlaying
        if (shouldContinue) {
            cancelPendingPreviewAudioSeek()
            scheduleContinuePreviewAudioSeek(timeMs)
        } else if (!shouldSuppressPausedPreviewAudioSync()) {
            cancelContinuePreviewAudioSeek()
            schedulePausedPreviewAudioSeek(timeMs)
        }
    }

    private fun setupEffectSliderControls() {
        brightnessSeekBar?.max = 200
        contrastSeekBar?.max = 200
        brightnessSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return
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
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return
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
        previewView?.let { pv ->
            NativeBridge.setClipEffects(pv, clipId, params.brightness, params.contrast, params.saturation)
        }
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
            runCatching { NativeBridge.seekToTime(pv, resolveVisualClipPreviewTimeMs(clipId)) }
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
        val preview = previewView
        val textOverlayIds = OverlayStore.all().map { it.id }
        textOverlayIds.forEach { overlayId ->
            preview?.removeTextOverlay(overlayId)
            removeOverlayView(overlayId)
        }
        preview?.setActiveTextOverlayId(-1)
        overlayViews.keys.toList()
            .filterNot { it in textOverlayIds }
            .forEach { removeOverlayView(it) }
        stickerOverlayViews.keys.toList().forEach { removeStickerOverlayView(it) }
        OverlayStore.clear()
        StickerClipStore.all().map { it.id }.forEach { StickerClipStore.remove(it) }
        AudioClipStore.clear()
        previewAudioPlayer?.pause()
        NativeBridge.syncAudioClips()
        timelineManager?.syncClips(emptyList(), recordHistory = false, clearHistory = true)
        timelineManager?.selectClip(null)
        videoClipTimingOverrides.clear()
        previewTrimSession = null
        clipPreviewTransforms.clear()
        nativeAppliedClipPreviewTransforms.clear()
        preview?.clearClipPreviewTransforms()
        audioClipGainOverrides.clear()
        audioDuckingRestoreGainOverrides.clear()
        videoClipGainOverrides.clear()
        videoClipReverseOverrides.clear()
        videoClipFreezeOverrides.clear()
        videoClipCurveProfiles.clear()
        videoClipKeyframes.clear()
        videoClipPreviewKeyframes.clear()
        textOverlayKeyframes.clear()
        stickerClipKeyframes.clear()
        duckingEnabledForKey.clear()
        nativeClipTrackType.clear()
        nativeClipLane.clear()
        nativeClipZOrder.clear()
        restoredNativeClipTrackTypeOverrides.clear()
        restoredNativeClipLaneOverrides.clear()
        restoredNativeClipZOrderOverrides.clear()
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
        clipEffects.clear()
        trackVisibilityOverrides.clear()
        trackLockedOverrides.clear()
        importController?.resetPendingState()
        selectedTimelineClipKey = null
        currentTimeMs = 0L
        videoDurationMs = 0L
        importController?.setNextImportTrackType(TrackType.VIDEO)
        undoDomains.clear()
        redoDomains.clear()
        nextTextOverlayId = 1
        nextStickerId = 1
        nextAudioClipId = 1
        sourceDurationCacheMs.clear()
        lastPreviewAudioSyncSignature = ""
        NativeBridge.clearNativeCommandTelemetry()
        updateTimelineTimeText(0L)
        effectSlidersContainer?.visibility = View.GONE
        multiTrackTimelineView?.setSelectedClipId(null)
        activeCanvasTimelineView()?.setSelectedClipId(null)
        applySelectedClipPreviewTransform()
        updateUndoRedoButtons()
        refreshMainTimelineTracks()
    }

    private fun collectNativeTimelineClipIds(preview: VideoPreviewView): List<Int> {
        val clipIds = linkedSetOf<Int>()
        runCatching { NativeBridge.getClipIds(preview).toList() }
            .getOrDefault(emptyList())
            .filter { it > 0 }
            .forEach { clipIds += it }
        if (clipIds.isNotEmpty()) {
            return clipIds.toList()
        }

        val layoutClips =
            runCatching {
                NativeBridge.executeCommand("GET_TIMELINE_LAYOUT").data?.optJSONArray("clips")
            }.getOrNull()
        if (layoutClips != null) {
            for (index in 0 until layoutClips.length()) {
                val clip = layoutClips.optJSONObject(index) ?: continue
                val clipId = clip.optInt("clipId", -1)
                if (clipId > 0) {
                    clipIds += clipId
                }
            }
        }
        return clipIds.toList()
    }

    private fun clearNativeTimelineState(preview: VideoPreviewView): Int {
        val removedClipIds = linkedSetOf<Int>()
        repeat(4) { pass ->
            val clipIds = collectNativeTimelineClipIds(preview)
            if (clipIds.isEmpty()) {
                if (pass > 0) {
                    Log.i(TAG, "[Automation] native timeline cleared after pass=${pass + 1} removed=${removedClipIds.size}")
                }
                return removedClipIds.size
            }
            clipIds.forEach { clipId ->
                removedClipIds += clipId
                NativeBridge.removeClip(preview, clipId)
            }
        }
        val remainingClipIds = collectNativeTimelineClipIds(preview)
        if (remainingClipIds.isNotEmpty()) {
            Log.w(TAG, "[Automation] native timeline still has clips after reset: ${remainingClipIds.joinToString()}")
        }
        return removedClipIds.size
    }

    private fun resetEditorToBlankState(showToast: Boolean) {
        setStartScreenVisible(false)
        setImportPickerRestoreAutosaveAllowed(false)
        val removedNativeClips =
            previewView?.let { preview ->
                clearNativeTimelineState(preview)
            } ?: 0
        clearEditorShellState()
        selectedAspectRatioIndex = DEFAULT_ASPECT_RATIO_INDEX
        aspectRatioManuallySelected = false
        applyPreviewAspectRatio()
        previewView?.let { NativeBridge.seekToTime(it, 0L) }
        refreshMainTimelineTracks()
        if (showToast) {
            safeToast("New Project Created", Toast.LENGTH_SHORT)
        }
        Log.i(TAG, "[Automation] blank project ready removedNativeClips=$removedNativeClips")
    }

    private fun startBlankProject(showToast: Boolean) {
        resetEditorToBlankState(showToast = showToast)
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
        execCmd(
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
            )
        ) { result ->
            if (!result.success) {
                Log.w(TAG, "[Automation] smoke video trim failed: clip=$clipId message=${result.message}")
            } else {
                nativeClipDurationMs[clipId] = clampedDurationMs
                nativeClipSourceOutMs[clipId] = clampedSourceOutMs
                syncTimelineShellFromNative(selectedClipId = clipId)
                refreshPreviewAtPlayhead()
                Log.i(TAG, "[Automation] smoke video clip trimmed clip=$clipId durationMs=$clampedDurationMs")
            }
        }
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
        noteAppHealthAction("project_saved")
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
            aspectRatioManuallySelected = true
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
            allTextOverlays().forEach { overlay ->
                applyTextOverlayState(overlay)
                applyTextOverlayPose(overlay)
            }
        }
        noteAppHealthAction("project_loaded")
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
        val orderedViews = mutableListOf<Pair<Float, android.view.View>>()
        allTextOverlays().forEach { overlay ->
            overlayViews[overlay.id]?.let { orderedViews += it.z to it }
        }
        StickerClipStore.all().forEach { sticker ->
            stickerOverlayViews[sticker.id]?.let { orderedViews += it.z to it }
        }
        orderedViews
            .sortedBy { it.first }
            .forEach { (_, view) -> view.bringToFront() }
        container.invalidate()
    }

    private fun withAutoSubTracks(clips: List<ClipSegment>): List<ClipSegment> {
        return clips
            .sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.zOrder })
            .map { clip ->
                clip.copy(metadata = clip.metadata + ("subTrack" to "1"))
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
        noteAppHealthAction("save_dialog_opened")
        projectController?.showSaveProjectDialog()
    }

    /**
     * Show project load dialog (file picker).
     * Lists all saved projects and allows selection.
     */
    private fun showLoadProjectDialog() {
        noteAppHealthAction("load_dialog_opened")
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

    private fun isImageLikeSourcePath(path: String?): Boolean {
        val normalized = path?.substringBefore('?')?.substringBefore('#')?.trim().orEmpty()
        if (normalized.isBlank()) return false
        val extension = normalized.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in setOf("png", "jpg", "jpeg", "jpe", "jfif", "webp", "bmp", "gif", "tif", "tiff", "heic", "heif", "avif")
    }

    private fun isAudioLikeSourcePath(path: String?): Boolean {
        val normalized = path?.substringBefore('?')?.substringBefore('#')?.trim().orEmpty()
        if (normalized.isBlank()) return false
        val extension = normalized.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in setOf("aac", "amr", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma")
    }

    private fun mapNativeTrackType(
        trackTypeRaw: String?,
        zOrder: Int = 0,
        mediaTypeRaw: String? = null,
        sourcePath: String? = null,
        preferredTrackType: TrackType? = null,
    ): TrackType {
        val normalizedMediaType = mediaTypeRaw?.uppercase(Locale.US)
        if (normalizedMediaType == "AUDIO" || isAudioLikeSourcePath(sourcePath)) {
            return TrackType.AUDIO
        }
        preferredTrackType
            ?.takeIf { it == TrackType.VIDEO || it == TrackType.LAYER || it == TrackType.OVERLAY }
            ?.let { preferredVisualTrack ->
                when (trackTypeRaw?.uppercase(Locale.US)) {
                    "TEXT", "TEXT_STICKER" -> return TrackType.TEXT
                    "AUDIO" -> return TrackType.AUDIO
                    "LAYER" -> return TrackType.LAYER
                    "OVERLAY" -> {
                        return if (preferredVisualTrack == TrackType.LAYER || zOrder in 100 until 200) {
                            TrackType.LAYER
                        } else {
                            TrackType.OVERLAY
                        }
                    }
                    "VIDEO", "MAINVIDEO", "MAIN_VIDEO", "", null -> {
                        return preferredVisualTrack
                    }
                    else -> return preferredVisualTrack
                }
            }
        val inferred = TrackType.fromNativeRole(trackTypeRaw, zOrder)
        if (inferred == TrackType.TEXT || inferred == TrackType.AUDIO || inferred == TrackType.LAYER || inferred == TrackType.OVERLAY) {
            return inferred
        }
        if (trackTypeRaw?.uppercase(Locale.US) in setOf("VIDEO", "MAINVIDEO", "MAIN_VIDEO")) {
            return TrackType.VIDEO
        }
        return if (normalizedMediaType == "IMAGE" || isImageLikeSourcePath(sourcePath)) {
            TrackType.LAYER
        } else {
            inferred
        }
    }

    private fun resolveShellTrackType(clipId: Int): TrackType {
        val cached = restoredNativeClipTrackTypeOverrides[clipId] ?: nativeClipTrackType[clipId] ?: TrackType.VIDEO
        if (cached == TrackType.AUDIO || cached == TrackType.TEXT || cached == TrackType.LAYER || cached == TrackType.OVERLAY || cached == TrackType.VIDEO) {
            return cached
        }
        return cached
    }

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
                val trackType = resolveShellTrackType(clipId)
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

    private fun pruneNativeClipCaches(validClipIds: Set<Int>): Boolean {
        var changed = false
        fun <T> MutableMap<Int, T>.retainValidIds() {
            val beforeSize = size
            keys.retainAll(validClipIds)
            if (size != beforeSize) {
                changed = true
            }
        }

        nativeClipTrackType.retainValidIds()
        nativeClipLane.retainValidIds()
        nativeClipZOrder.retainValidIds()
        restoredNativeClipTrackTypeOverrides.retainValidIds()
        restoredNativeClipLaneOverrides.retainValidIds()
        restoredNativeClipZOrderOverrides.retainValidIds()
        nativeClipStartMs.retainValidIds()
        nativeClipDurationMs.retainValidIds()
        nativeClipSourceInMs.retainValidIds()
        nativeClipSourceOutMs.retainValidIds()
        nativeClipSourcePath.retainValidIds()
        nativeClipPlaybackSpeed.retainValidIds()
        nativeClipReversePlayback.retainValidIds()
        nativeClipFreezeFrameEnabled.retainValidIds()
        nativeClipFreezeFrameTimeMs.retainValidIds()
        nativeClipFreezeFrameDurationMs.retainValidIds()
        nativeClipCurveSpeedProfile.retainValidIds()
        nativeClipCurveSpeedStrength.retainValidIds()
        nativeClipAudioGainKeyframes.retainValidIds()
        videoClipKeyframes.retainValidIds()
        videoClipPreviewKeyframes.retainValidIds()
        return changed
    }

    private fun refreshNativeClipLayoutCache(validClipIds: Set<Int>, refreshCachedUi: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLayoutFetchMs < 300L) {
            // Too soon — just trim stale keys and refresh UI with cached data
            val cacheChanged = pruneNativeClipCaches(validClipIds)
            if (refreshCachedUi || cacheChanged) {
                refreshMainTimelineTracks()
            }
            return
        }
        lastLayoutFetchMs = now
        Thread {
            val result = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
            mainHandler.post {
                if (result?.success != true) {
                    val cacheChanged = pruneNativeClipCaches(validClipIds)
                    if (refreshCachedUi || cacheChanged) {
                        refreshMainTimelineTracks()
                    }
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
                val nextVisualKeyframes = mutableMapOf<Int, MutableList<Long>>()
                val nextVisualKeyframePoses = mutableMapOf<Int, MutableList<ClipPreviewKeyframe>>()
                for (index in 0 until clipsJson.length()) {
                    val clipJson = clipsJson.optJSONObject(index) ?: continue
                    val clipId = clipJson.optInt("clipId", -1)
                    if (clipId <= 0 || !validClipIds.contains(clipId)) continue
                    val clipZOrder = clipJson.optInt("zOrder", 0)
                    val clipSourcePath =
                        clipJson.optString("originalSourcePath")
                            .ifBlank { clipJson.optString("sourcePath", "") }
                    val trackType = mapNativeTrackType(
                        trackTypeRaw = clipJson.optString("trackType", "VIDEO"),
                        zOrder = clipZOrder,
                        mediaTypeRaw = clipJson.optString("mediaType", ""),
                        sourcePath = clipSourcePath,
                        preferredTrackType = restoredNativeClipTrackTypeOverrides[clipId] ?: nativeClipTrackType[clipId],
                    )
                    nextTrackType[clipId] = trackType
                    nextLane[clipId] =
                        restoredNativeClipLaneOverrides[clipId] ?: clipJson.optInt("trackLane", 0).coerceAtLeast(0)
                    nextZOrder[clipId] =
                        normalizeTrackZOrder(trackType, restoredNativeClipZOrderOverrides[clipId] ?: clipZOrder)
                    nextStartMs[clipId] = clipJson.optLong("startTimeMs", 0L).coerceAtLeast(0L)
                    val sourceIn = clipJson.optLong("sourceInMs", 0L).coerceAtLeast(0L)
                    val rawDurationMs = clipJson.optLong("durationMs", 0L).coerceAtLeast(1L)
                    val rawSourceOut = clipJson.optLong("sourceOutMs", sourceIn + rawDurationMs)
                        .coerceAtLeast(sourceIn + 1L)
                    val durationMs =
                        if (trackType == TrackType.AUDIO) {
                            clampNativeAudioTimingToSource(
                                clipId = clipId,
                                sourcePath = clipSourcePath,
                                startTimeMs = nextStartMs[clipId] ?: 0L,
                                durationMs = rawDurationMs,
                                sourceInMs = sourceIn,
                                sourceOutMs = rawSourceOut,
                                updateNative = true,
                            )
                        } else {
                            rawDurationMs
                        }
                    val sourceOut =
                        if (trackType == TrackType.AUDIO) {
                            sourceIn + durationMs
                        } else {
                            rawSourceOut
                        }
                    nextDurationMs[clipId] = durationMs
                    nextSourceInMs[clipId] = sourceIn
                    nextSourceOutMs[clipId] = sourceOut
                    nextSourcePath[clipId] = clipSourcePath
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
                    val parsedVisualKeyframes = mutableListOf<Long>()
                    val parsedVisualKeyframePoses = mutableListOf<ClipPreviewKeyframe>()
                    val visualKeyframePoseJson = clipJson.optJSONArray("visualKeyframes") ?: JSONArray()
                    for (keyframeIndex in 0 until visualKeyframePoseJson.length()) {
                        val keyframeJson = visualKeyframePoseJson.optJSONObject(keyframeIndex) ?: continue
                        parsedVisualKeyframePoses += ClipPreviewKeyframe(
                            timeMs = keyframeJson.optLong("timeMs", 0L),
                            transform = ClipPreviewTransform(
                                zoom = keyframeJson.optDouble("zoom", 1.0).toFloat(),
                                scaleX = keyframeJson.optDouble("scaleX", 1.0).toFloat(),
                                scaleY = keyframeJson.optDouble("scaleY", 1.0).toFloat(),
                                panXPx = keyframeJson.optDouble("panXPx", 0.0).toFloat(),
                                panYPx = keyframeJson.optDouble("panYPx", 0.0).toFloat(),
                                rotationDeg = keyframeJson.optDouble("rotationDeg", 0.0).toFloat(),
                                mirrorX = keyframeJson.optBoolean("mirrorX", false),
                            ),
                        )
                    }
                    val visualKeyframesJson = clipJson.optJSONArray("keyframesMs") ?: JSONArray()
                    for (keyframeIndex in 0 until visualKeyframesJson.length()) {
                        parsedVisualKeyframes += visualKeyframesJson.optLong(keyframeIndex, 0L)
                    }
                    val normalizedVisualKeyframePoses = normalizeClipPreviewKeyframes(parsedVisualKeyframePoses)
                    if (normalizedVisualKeyframePoses.isNotEmpty()) {
                        nextVisualKeyframePoses[clipId] = normalizedVisualKeyframePoses
                    }
                    val normalizedVisualKeyframes =
                        if (normalizedVisualKeyframePoses.isNotEmpty()) {
                            normalizeTimelineKeyframes(normalizedVisualKeyframePoses.map { it.timeMs })
                        } else {
                            normalizeTimelineKeyframes(parsedVisualKeyframes)
                        }
                    if (normalizedVisualKeyframes.isNotEmpty()) {
                        nextVisualKeyframes[clipId] = normalizedVisualKeyframes
                    }
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
                val layoutChanged =
                    nativeClipTrackType != nextTrackType ||
                        nativeClipLane != nextLane ||
                        nativeClipZOrder != nextZOrder ||
                        nativeClipStartMs != nextStartMs ||
                        nativeClipDurationMs != nextDurationMs ||
                        nativeClipSourceInMs != nextSourceInMs ||
                        nativeClipSourceOutMs != nextSourceOutMs ||
                        nativeClipSourcePath != nextSourcePath ||
                        nativeClipPlaybackSpeed != nextPlaybackSpeed ||
                        nativeClipReversePlayback != nextReversePlayback ||
                        nativeClipFreezeFrameEnabled != nextFreezeFrameEnabled ||
                        nativeClipFreezeFrameTimeMs != nextFreezeFrameTimeMs ||
                        nativeClipFreezeFrameDurationMs != nextFreezeFrameDurationMs ||
                        nativeClipCurveSpeedProfile != nextCurveSpeedProfile ||
                        nativeClipCurveSpeedStrength != nextCurveSpeedStrength ||
                        nativeClipAudioGainKeyframes != nextAudioGainKeyframes ||
                        videoClipKeyframes != nextVisualKeyframes ||
                        videoClipPreviewKeyframes != nextVisualKeyframePoses
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
                videoClipKeyframes.clear(); videoClipKeyframes.putAll(nextVisualKeyframes)
                videoClipPreviewKeyframes.clear(); videoClipPreviewKeyframes.putAll(nextVisualKeyframePoses)
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
                audioDuckingRestoreGainOverrides.keys
                    .filter { clipId -> nextTrackType[clipId] != TrackType.AUDIO }
                    .toList()
                    .forEach { audioDuckingRestoreGainOverrides.remove(it) }
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
                val audioStoreChanged = syncAudioClipStoreFromNativeLayout(
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
                if (layoutChanged || audioStoreChanged) {
                    refreshMainTimelineTracks()
                }
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
    ): Boolean {
        val existingById = AudioClipStore.all().associateBy { it.id }
        val existingByPath = AudioClipStore.all().groupBy { it.sourcePath }
        val audioIds = trackTypes
            .filterValues { it == TrackType.AUDIO }
            .keys
            .sorted()

        var changed = false
        AudioClipStore.batchUpdate {
            existingById.keys
                .filter { clipId ->
                    when (trackTypes[clipId]) {
                        TrackType.AUDIO -> false
                        null -> false
                        else -> true
                    }
                }
                .forEach { clipId ->
                    changed = AudioClipStore.remove(clipId) || changed
                }

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
                val startTimeMs = if (existing != null) existing.startTimeMs else (startTimesMs[clipId] ?: inherited?.startTimeMs ?: 0L)
                val durationMs = if (existing != null) existing.durationMs else {
                    val rawDurationMs = durationsMs[clipId] ?: inherited?.durationMs ?: 1L
                    clampAudioClipDurationToSource(
                        sourcePath = sourcePath,
                        requestedDurationMs = rawDurationMs,
                        fallbackMs = inherited?.durationMs ?: rawDurationMs,
                    )
                }
                val sourceInMs = existing?.sourceInMs ?: 0L
                val sourceOutMs = existing?.sourceOutMs ?: durationMs
                changed = AudioClipStore.add(
                    com.video.engine.audio.AudioClip(
                        id = clipId,
                        sourcePath = sourcePath,
                        displayName = displayName,
                        startTimeMs = startTimeMs,
                        durationMs = durationMs,
                        sourceInMs = sourceInMs,
                        sourceOutMs = sourceOutMs,
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
                        peakLevelsCsv = inherited?.peakLevelsCsv?.takeIf { it.isNotBlank() }
                            ?: inherited?.peakLevels.orEmpty().joinToString(","),
                    ),
                ) || changed
                audioClipGainOverrides[clipId] = gain
                nativeClipStartMs[clipId] = startTimeMs
                nativeClipDurationMs[clipId] = durationMs
                nativeClipSourceInMs[clipId] = sourceInMs
                nativeClipSourceOutMs[clipId] = sourceOutMs
            }
        }

        if (audioIds.isNotEmpty()) {
            nextAudioClipId = maxOf(nextAudioClipId, (audioIds.maxOrNull() ?: 0) + 1)
        }
        return changed
    }

    private fun syncTimelineShellFromNative(selectedClipId: Int? = null) {
        val pv = previewView ?: return
        val syncGeneration = ++timelineShellSyncGeneration
        Thread {
            val layoutEntries =
                runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
                    ?.takeIf { it.success }
                    ?.data
                    ?.optJSONArray("clips")
                    ?.let { clipsJson ->
                        buildList {
                            for (index in 0 until clipsJson.length()) {
                                val clipJson = clipsJson.optJSONObject(index) ?: continue
                                val clipId = clipJson.optInt("clipId", -1)
                                if (clipId <= 0) continue
                                val clipZOrder = clipJson.optInt("zOrder", 0)
                                val sourcePath =
                                    clipJson.optString("originalSourcePath")
                                        .ifBlank { clipJson.optString("sourcePath", "") }
                                add(
                                    NativeShellClipLayout(
                                        id = clipId,
                                        trackType = mapNativeTrackType(
                                            trackTypeRaw = clipJson.optString("trackType", "VIDEO"),
                                            zOrder = clipZOrder,
                                            mediaTypeRaw = clipJson.optString("mediaType", ""),
                                            sourcePath = sourcePath,
                                            preferredTrackType =
                                                restoredNativeClipTrackTypeOverrides[clipId]
                                                    ?: nativeClipTrackType[clipId],
                                        ),
                                        lane =
                                            restoredNativeClipLaneOverrides[clipId]
                                                ?: clipJson.optInt("trackLane", 0).coerceAtLeast(0),
                                        zOrder = clipZOrder,
                                        startTimeMs = clipJson.optLong("startTimeMs", 0L).coerceAtLeast(0L),
                                        durationMs = clipJson.optLong("durationMs", 1L).coerceAtLeast(1L),
                                        sourceInMs = clipJson.optLong("sourceInMs", 0L).coerceAtLeast(0L),
                                        sourceOutMs = clipJson.optLong("sourceOutMs", 0L).coerceAtLeast(0L),
                                        sourcePath = sourcePath,
                                    ),
                                )
                            }
                        }
                    }
                    .orEmpty()
            val nativeClipIds =
                if (layoutEntries.isNotEmpty()) {
                    layoutEntries.map { it.id }
                } else {
                    NativeBridge.getClipIds(pv).toList()
                }
            val nativeClipIdSet = nativeClipIds.toSet()
            val retainedClipIds = nativeClipIdSet + AudioClipStore.all().map { it.id }
            mainHandler.post {
                if (syncGeneration != timelineShellSyncGeneration) return@post
                if (layoutEntries.isNotEmpty()) {
                    val audioStoreChanged = syncAudioClipStoreFromNativeLayout(
                        trackTypes = layoutEntries.associate { it.id to it.trackType },
                        startTimesMs = layoutEntries.associate { it.id to it.startTimeMs },
                        durationsMs = layoutEntries.associate { it.id to it.durationMs },
                        sourcePaths = layoutEntries.associate { it.id to it.sourcePath },
                        laneIndices = layoutEntries.associate { it.id to it.lane },
                        volumeGains = emptyMap(),
                        fadeInValues = emptyMap(),
                        fadeOutValues = emptyMap(),
                        audioGainKeyframes = emptyMap(),
                    )
                    if (audioStoreChanged) {
                        lastPreviewAudioSyncSignature = ""
                    }
                    layoutEntries.forEach { layout ->
                        val normalizedZ =
                            normalizeTrackZOrder(
                                layout.trackType,
                                restoredNativeClipZOrderOverrides[layout.id] ?: layout.zOrder,
                            )
                        val durationMs =
                            if (layout.trackType == TrackType.AUDIO) {
                                clampNativeAudioTimingToSource(
                                    clipId = layout.id,
                                    sourcePath = layout.sourcePath,
                                    startTimeMs = layout.startTimeMs,
                                    durationMs = layout.durationMs,
                                    sourceInMs = layout.sourceInMs,
                                    sourceOutMs = layout.sourceOutMs,
                                    updateNative = true,
                                )
                            } else {
                                layout.durationMs
                            }
                        val sourceOutMs =
                            if (layout.trackType == TrackType.AUDIO) {
                                layout.sourceInMs + durationMs
                            } else {
                                layout.sourceOutMs.coerceAtLeast(layout.sourceInMs + 1L)
                            }
                        nativeClipTrackType[layout.id] = layout.trackType
                        nativeClipLane[layout.id] = restoredNativeClipLaneOverrides[layout.id] ?: layout.lane
                        nativeClipZOrder[layout.id] = normalizedZ
                        nativeClipStartMs[layout.id] = layout.startTimeMs
                        nativeClipDurationMs[layout.id] = durationMs
                        nativeClipSourceInMs[layout.id] = layout.sourceInMs
                        nativeClipSourceOutMs[layout.id] = sourceOutMs
                        nativeClipSourcePath[layout.id] = layout.sourcePath
                    }
                    pruneNativeClipCaches(retainedClipIds)
                } else {
                    refreshNativeClipLayoutCache(retainedClipIds, refreshCachedUi = false)
                }
                val clips = nativeClipIds.mapIndexed { index, clipId ->
                    val trackType = resolveShellTrackType(clipId)
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
                videoClipPreviewKeyframes.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipPreviewKeyframes.remove(it) }
                videoClipGainOverrides.keys.toList().forEach { if (!nativeClipIdSet.contains(it)) videoClipGainOverrides.remove(it) }
                duckingEnabledForKey.keys
                    .filter { key -> parseTimelineManagedClipId(key)?.let { !retainedClipIds.contains(it) } == true }
                    .toList()
                    .forEach { duckingEnabledForKey.remove(it) }
                nativeClipTrackType.keys.retainAll(retainedClipIds)
                nativeClipLane.keys.retainAll(retainedClipIds)
                nativeClipZOrder.keys.retainAll(retainedClipIds)
                restoredNativeClipTrackTypeOverrides.keys.retainAll(retainedClipIds)
                restoredNativeClipLaneOverrides.keys.retainAll(retainedClipIds)
                restoredNativeClipZOrderOverrides.keys.retainAll(retainedClipIds)
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
                    val defaultVisible = trackVisibilityOverrides[resolveShellTrackType(clip.id)] ?: true
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
                notePreviewInteractionBusy(EDITOR_INTERACTION_BUSY_WINDOW_MS)
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
                notePreviewInteractionBusy(EDITOR_INTERACTION_BUSY_WINDOW_MS)
                val safeUpdate = clampMultiTrackClipUpdateToSource(update, allowSourceProbe = false)
                if (applyLocalClipTimingUpdate(safeUpdate, refreshVisualState = false)) {
                    return
                }
                val videoClipId = parseNativeClipId(safeUpdate.clipId) ?: return
                videoClipTimingOverrides[videoClipId] = ClipTimingSnapshot(
                    startTimeMs = safeUpdate.startTimeMs,
                    durationMs = safeUpdate.durationMs,
                    sourceInMs = safeUpdate.sourceInMs,
                    sourceOutMs = safeUpdate.sourceOutMs,
                )
            }

            override fun onClipUpdateCommitted(update: MultiTrackTimelineView.ClipUpdate) {
                val safeUpdate = clampMultiTrackClipUpdateToSource(update, allowSourceProbe = true)
                if (safeUpdate.clipId.startsWith("audio-")) {
                    val audioClipId = safeUpdate.clipId.removePrefix("audio-").toIntOrNull() ?: return
                    execCmd(
                        action = "UPDATE_CLIP_TIMING",
                        params = mapOf(
                            "clipId" to audioClipId,
                            "newStartTimeMs" to safeUpdate.startTimeMs,
                            "newDurationMs" to safeUpdate.durationMs,
                            "newSourceInMs" to safeUpdate.sourceInMs,
                            "newSourceOutMs" to safeUpdate.sourceOutMs,
                            "originalStartTimeMs" to safeUpdate.originalStartTimeMs,
                            "originalDurationMs" to safeUpdate.originalDurationMs,
                            "originalSourceInMs" to safeUpdate.originalSourceInMs,
                            "originalSourceOutMs" to safeUpdate.originalSourceOutMs,
                            "previewOnly" to false,
                            "applyMagnetic" to false,
                        )
                    ) { result ->
                        if (result.success) {
                            AudioClipStore.get(audioClipId)?.let { audioClip ->
                                audioClip.startTimeMs = safeUpdate.startTimeMs
                                audioClip.durationMs = safeUpdate.durationMs
                            }
                            nativeClipStartMs[audioClipId] = safeUpdate.startTimeMs
                            nativeClipDurationMs[audioClipId] = safeUpdate.durationMs
                            nativeClipSourceInMs[audioClipId] = safeUpdate.sourceInMs
                            nativeClipSourceOutMs[audioClipId] = safeUpdate.sourceOutMs
                            lastLayoutFetchMs = 0L
                            previewView?.let { pv ->
                                refreshNativeClipLayoutCache(NativeBridge.getClipIds(pv).toSet())
                            } ?: refreshMainTimelineTracks()
                            recordUndoDomain(UndoDomain.TIMELINE)
                            recordTelemetryEvent(
                                "timeline",
                                "clip_update_committed_native",
                                buildClipUpdateTelemetry(safeUpdate)
                                    .put("clipIdInt", audioClipId)
                                    .put("success", true),
                            )
                            scheduleTimelineIdleAutoSave("timeline_audio_commit")
                        } else {
                            Log.w(TAG, "Audio clip commit failed: id=$audioClipId message=${result.message}")
                            recordTelemetryEvent(
                                "timeline",
                                "clip_update_failed",
                                buildClipUpdateTelemetry(safeUpdate)
                                    .put("clipIdInt", audioClipId)
                                    .put("success", false)
                                    .put("message", result.message ?: "unknown"),
                            )
                        }
                    }
                    return
                }
                if (applyLocalClipTimingUpdate(safeUpdate)) {
                    recordUndoDomain(UndoDomain.EDITOR)
                    recordTelemetryEvent(
                        "timeline",
                        "clip_update_committed_local",
                        buildClipUpdateTelemetry(safeUpdate)
                            .put("selectedClipKey", selectedTimelineClipKey ?: JSONObject.NULL),
                    )
                    refreshMainTimelineTracks()
                    scheduleTimelineIdleAutoSave("timeline_local_commit")
                    return
                }
                val videoClipId = parseNativeClipId(safeUpdate.clipId) ?: return
                execCmd(
                    action = "UPDATE_CLIP_TIMING",
                    params = mapOf(
                        "clipId" to videoClipId,
                        "newStartTimeMs" to safeUpdate.startTimeMs,
                        "newDurationMs" to safeUpdate.durationMs,
                        "newSourceInMs" to safeUpdate.sourceInMs,
                        "newSourceOutMs" to safeUpdate.sourceOutMs,
                        "originalStartTimeMs" to safeUpdate.originalStartTimeMs,
                        "originalDurationMs" to safeUpdate.originalDurationMs,
                        "originalSourceInMs" to safeUpdate.originalSourceInMs,
                        "originalSourceOutMs" to safeUpdate.originalSourceOutMs,
                        "previewOnly" to false,
                        "applyMagnetic" to (safeUpdate.trackType == TrackType.VIDEO),
                    )
                ) { result ->
                    if (result.success) {
                        nativeClipStartMs[videoClipId] = safeUpdate.startTimeMs
                        nativeClipDurationMs[videoClipId] = safeUpdate.durationMs
                        nativeClipSourceInMs[videoClipId] = safeUpdate.sourceInMs
                        nativeClipSourceOutMs[videoClipId] = safeUpdate.sourceOutMs
                        if (safeUpdate.gestureKind != MultiTrackTimelineView.ClipGestureKind.MOVE) {
                            updateTimelineManagerClipDuration(videoClipId, safeUpdate.durationMs)
                        }
                        recordUndoDomain(UndoDomain.TIMELINE)
                        recordTelemetryEvent(
                            "timeline",
                            "clip_update_committed_native",
                            buildClipUpdateTelemetry(safeUpdate)
                                .put("clipIdInt", videoClipId)
                                .put("success", true),
                        )
                        refreshMainTimelineTracks()
                        scheduleTimelineIdleAutoSave("timeline_native_commit")
                    } else {
                        Log.w(TAG, "Clip commit failed: id=$videoClipId message=${result.message}")
                        recordTelemetryEvent(
                            "timeline",
                            "clip_update_failed",
                            buildClipUpdateTelemetry(safeUpdate)
                                .put("clipIdInt", videoClipId)
                                .put("success", false)
                                .put("message", result.message ?: "unknown"),
                        )
                        captureDebugSnapshot("clip_update_failed")
                        videoClipTimingOverrides.remove(videoClipId)
                        refreshMainTimelineTracks()
                    }
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
                syncSelectedImportTrackFromSelection(selectedTimelineClipKey)
                updateBottomToolbarMode()
                revealSelectedClipInPreview()
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
                setSelectedImportTrackType(trackType)
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

            override fun onTrackSelected(trackType: TrackType) {
                setSelectedImportTrackType(trackType)
                selectPreviewActiveClipForTrack(trackType)
            }

            override fun onTrackVisibilityChanged(trackType: TrackType, isVisible: Boolean) {
                applyTrackVisibilityChange(trackType, isVisible)
                scheduleSessionAutoSave("track_visibility", delayMs = 1000L)
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
                scheduleSessionAutoSave("track_lock", delayMs = 1000L)
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
        if (!prepareImportPickerLaunch(PICK_VIDEO_REQUEST, "video")) return
        val importStartTimeMs = maxOf(currentPlayheadMs(), currentTimeMs.coerceAtLeast(0L))
        activeImportPickerStartTimeMs = importStartTimeMs
        persistActiveImportPickerRequest(
            requestCode = PICK_VIDEO_REQUEST,
            startTimeMs = importStartTimeMs,
            startedFromStartScreen = activeImportPickerStartedFromStartScreen,
        )
        rememberPreviewLifecycleAnchor(importStartTimeMs)
        setSelectedImportTrackType(TrackType.VIDEO)
        importController?.preparePickerImportTrackType(PICK_VIDEO_REQUEST, TrackType.VIDEO, importStartTimeMs)
        noteAppHealthAction("video_import_picker_opened")
        if (::uiFreezeWatchdog.isInitialized) {
            uiFreezeWatchdog.suspendFor(12_000L)
        }
        val intent = buildVisualImportIntent()
        runCatching {
            startActivityForResult(intent, PICK_VIDEO_REQUEST)
        }.onFailure {
            clearActiveImportPickerRequest(PICK_VIDEO_REQUEST)
            throw it
        }
    }

    private fun openOverlayTrackImport() {
        if (!ensureTrackEditable(TrackType.OVERLAY, "import")) return
        if (!prepareImportPickerLaunch(PICK_OVERLAY_REQUEST, "overlay")) return
        val importStartTimeMs = maxOf(currentPlayheadMs(), currentTimeMs.coerceAtLeast(0L))
        activeImportPickerStartTimeMs = importStartTimeMs
        persistActiveImportPickerRequest(
            requestCode = PICK_OVERLAY_REQUEST,
            startTimeMs = importStartTimeMs,
            startedFromStartScreen = activeImportPickerStartedFromStartScreen,
        )
        rememberPreviewLifecycleAnchor(importStartTimeMs)
        setSelectedImportTrackType(TrackType.OVERLAY)
        importController?.preparePickerImportTrackType(PICK_OVERLAY_REQUEST, TrackType.OVERLAY, importStartTimeMs)
        noteAppHealthAction("overlay_import_picker_opened")
        if (::uiFreezeWatchdog.isInitialized) {
            uiFreezeWatchdog.suspendFor(12_000L)
        }
        val intent = buildVisualImportIntent()
        runCatching {
            startActivityForResult(intent, PICK_OVERLAY_REQUEST)
        }.onFailure {
            clearActiveImportPickerRequest(PICK_OVERLAY_REQUEST)
            throw it
        }
    }

    private fun openLayerTrackImport() {
        if (!ensureTrackEditable(TrackType.LAYER, "import")) return
        if (!prepareImportPickerLaunch(PICK_LAYER_REQUEST, "layer")) return
        val importStartTimeMs = maxOf(currentPlayheadMs(), currentTimeMs.coerceAtLeast(0L))
        activeImportPickerStartTimeMs = importStartTimeMs
        persistActiveImportPickerRequest(
            requestCode = PICK_LAYER_REQUEST,
            startTimeMs = importStartTimeMs,
            startedFromStartScreen = activeImportPickerStartedFromStartScreen,
        )
        rememberPreviewLifecycleAnchor(importStartTimeMs)
        setSelectedImportTrackType(TrackType.LAYER)
        importController?.preparePickerImportTrackType(PICK_LAYER_REQUEST, TrackType.LAYER, importStartTimeMs)
        noteAppHealthAction("layer_import_picker_opened")
        if (::uiFreezeWatchdog.isInitialized) {
            uiFreezeWatchdog.suspendFor(12_000L)
        }
        val intent = buildVisualImportIntent()
        runCatching {
            startActivityForResult(intent, PICK_LAYER_REQUEST)
        }.onFailure {
            clearActiveImportPickerRequest(PICK_LAYER_REQUEST)
            throw it
        }
    }

    private fun buildVisualImportIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
    }

    private fun openAudioTrackImport() {
        if (!ensureTrackEditable(TrackType.AUDIO, "import")) return
        if (!prepareImportPickerLaunch(PICK_AUDIO_REQUEST, "audio")) return
        val importStartTimeMs = maxOf(currentPlayheadMs(), currentTimeMs.coerceAtLeast(0L))
        activeImportPickerStartTimeMs = importStartTimeMs
        persistActiveImportPickerRequest(
            requestCode = PICK_AUDIO_REQUEST,
            startTimeMs = importStartTimeMs,
            startedFromStartScreen = activeImportPickerStartedFromStartScreen,
        )
        rememberPreviewLifecycleAnchor(importStartTimeMs)
        setSelectedImportTrackType(TrackType.AUDIO)
        noteAppHealthAction("audio_import_picker_opened")
        if (::uiFreezeWatchdog.isInitialized) {
            uiFreezeWatchdog.suspendFor(10_000L)
        }
        runCatching {
            audioImportController?.openPicker(PICK_AUDIO_REQUEST)
        }.onFailure {
            clearActiveImportPickerRequest(PICK_AUDIO_REQUEST)
            throw it
        }
    }

    private fun applyLocalClipTimingUpdate(
        update: MultiTrackTimelineView.ClipUpdate,
        refreshVisualState: Boolean = true,
    ): Boolean {
        return when {
            update.clipId.startsWith("text-") -> {
                val overlayId = update.clipId.removePrefix("text-").toIntOrNull() ?: return false
                val overlay = OverlayStore.get(overlayId) ?: return false
                val startTimeMs = update.startTimeMs
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                val endTimeMs = (update.startTimeMs + update.durationMs.coerceAtLeast(1L))
                    .coerceAtLeast(startTimeMs.toLong() + 1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                overlay.startTimeMs = startTimeMs
                overlay.endTimeMs = endTimeMs
                OverlayStore.put(overlay)
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
                if (refreshVisualState) {
                    applyTextOverlayState(overlay)
                    applyTextOverlayPose(overlay)
                }
                true
            }
            update.clipId.startsWith("sticker-") -> {
                val stickerId = update.clipId.removePrefix("sticker-").toIntOrNull() ?: return false
                val sticker = StickerClipStore.all().find { it.id == stickerId } ?: return false
                sticker.startTimeMs = update.startTimeMs.toInt()
                sticker.durationMs = update.durationMs.toInt().coerceAtLeast(1)
                if (refreshVisualState) {
                    applyStickerOverlayPose(sticker)
                }
                true
            }
            update.clipId.startsWith("audio-") -> {
                val audioId = update.clipId.removePrefix("audio-").toIntOrNull() ?: return false
                val audio = AudioClipStore.get(audioId) ?: return false
                val safeUpdate = clampMultiTrackClipUpdateToSource(update, allowSourceProbe = refreshVisualState)
                audio.startTimeMs = safeUpdate.startTimeMs
                audio.durationMs = safeUpdate.durationMs
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

    private val refreshTimelineRunnable: Runnable by lazy {
        Runnable {
        if (isFinishing || isDestroyed) {
            timelineRefreshScheduled = false
            timelineRefreshRequestedDuringRun = false
            return@Runnable
        }
        lastTimelineRefreshUptimeMs = SystemClock.uptimeMillis()
        timelineRefreshRequestedDuringRun = false
        refreshMainTimelineTracksInternal()
        if (timelineRefreshRequestedDuringRun && !isFinishing && !isDestroyed) {
            timelineRefreshRequestedDuringRun = false
            mainHandler.postDelayed(refreshTimelineRunnable, 12L)
        } else {
            timelineRefreshScheduled = false
        }
        }
    }

    private fun refreshMainTimelineTracks() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(Runnable { refreshMainTimelineTracks() })
            return
        }
        if (isFinishing || isDestroyed) return
        val startupDelayMs = startupHeavyWorkDelayMs()
        if (startupDelayMs > 0L) {
            if (!timelineRefreshScheduled) {
                timelineRefreshScheduled = true
                timelineRefreshRequestedDuringRun = false
                mainHandler.postDelayed(refreshTimelineRunnable, startupDelayMs)
            } else {
                timelineRefreshRequestedDuringRun = true
            }
            return
        }
        if (timelineRefreshScheduled) {
            timelineRefreshRequestedDuringRun = true
            return
        }
        val elapsedMs = SystemClock.uptimeMillis() - lastTimelineRefreshUptimeMs
        val minIntervalMs =
            if (shouldDeferHeavyUiWork()) {
                when (DeviceDetector.getDeviceTier()) {
                    DeviceDetector.DeviceTier.LOW -> 160L
                    DeviceDetector.DeviceTier.MID -> 96L
                    DeviceDetector.DeviceTier.HIGH -> 48L
                }
            } else {
                12L
            }
        val delayMs = if (elapsedMs >= minIntervalMs) 0L else minIntervalMs - elapsedMs
        timelineRefreshScheduled = true
        timelineRefreshRequestedDuringRun = false
        if (delayMs <= 0L) {
            mainHandler.post(refreshTimelineRunnable)
        } else {
            mainHandler.postDelayed(refreshTimelineRunnable, delayMs)
        }
    }

    private fun resolvedTrackVisibility(trackType: TrackType, computedDefault: Boolean): Boolean {
        return trackVisibilityOverrides[trackType] ?: computedDefault
    }

    private fun resolvedTrackLocked(trackType: TrackType): Boolean {
        return trackLockedOverrides[trackType] ?: false
    }

    private fun refreshMainTimelineTracksInternal() {
        var managerClips = timelineManager?.getClips().orEmpty()
        val fallbackClips = buildTimelineShellClipsFromNativeCache()
        val managerClipIds = managerClips.map { it.id }.toSet()
        val fallbackClipIds = fallbackClips.map { it.id }.toSet()
        val shouldResyncFromNative =
            fallbackClips.isNotEmpty() &&
                (managerClips.isEmpty() || managerClipIds != fallbackClipIds)
        if (shouldResyncFromNative) {
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
        val videoManagerClips = managerClips.filter { clip ->
            resolveShellTrackType(clip.id) == TrackType.VIDEO
        }
        val overlayManagerClips = managerClips.filter { clip ->
            resolveShellTrackType(clip.id) == TrackType.OVERLAY
        }
        val layerManagerClips = managerClips.filter { clip ->
            resolveShellTrackType(clip.id) == TrackType.LAYER
        }
        val audioManagerClips = managerClips.filter { clip ->
            resolveShellTrackType(clip.id) == TrackType.AUDIO
        }
        if (audioManagerClips.isNotEmpty()) {
            val audioStoreChanged = syncAudioClipStoreFromNativeLayout(
                trackTypes = audioManagerClips.associate { it.id to TrackType.AUDIO },
                startTimesMs = audioManagerClips.associate { it.id to (nativeClipStartMs[it.id] ?: 0L) },
                durationsMs = audioManagerClips.associate {
                    it.id to (nativeClipDurationMs[it.id] ?: it.durationMs.coerceAtLeast(1L))
                },
                sourcePaths = audioManagerClips.associate { it.id to nativeClipSourcePath[it.id].orEmpty() },
                laneIndices = audioManagerClips.associate { it.id to (nativeClipLane[it.id] ?: 0) },
                volumeGains = audioManagerClips.associate { it.id to (audioClipGainOverrides[it.id] ?: 1f) },
                fadeInValues = emptyMap(),
                fadeOutValues = emptyMap(),
                audioGainKeyframes = emptyMap(),
            )
            if (audioStoreChanged) {
                lastPreviewAudioSyncSignature = ""
            }
        }
        val audioStoreClips = AudioClipStore.all()
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
                val keyframes = clipPreviewKeyframeTimes(clip.id)
                rollingStartMs = maxOf(rollingStartMs, startTimeMs + durationMs)
                ClipSegment(
                    id = clip.id.toString(),
                    sourcePath = nativeClipSourcePath[clip.id].orEmpty().ifBlank { clip.title },
                    trackType = TrackType.VIDEO,
                    startTimeMs = startTimeMs,
                    durationMs = durationMs,
                    sourceInMs = sourceInMs,
                    sourceOutMs = sourceOutMs,
                    zOrder = 0,
                    isHidden = !(timelineManager?.getClipVisibility(clip.id) ?: true),
                    metadata = mapOf(
                        "reverse" to reverseEnabled.toString(),
                        "freezeAtMs" to (freeze?.first?.toString() ?: ""),
                        "freezeDurationMs" to (freeze?.second?.toString() ?: ""),
                        "curveSpeedProfile" to curveProfile,
                        "keyframesMs" to keyframes.joinToString(","),
                        "subTrack" to ((nativeClipLane[clip.id] ?: 0) + 1).toString(),
                    ) + nativeClipSourceDurationMetadata(clip.id),
                )
            },
            isLocked = resolvedTrackLocked(TrackType.VIDEO),
            isVisible = resolvedTrackVisibility(
                TrackType.VIDEO,
                true,
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
                        zOrder = 200,
                        isHidden = !(timelineManager?.getClipVisibility(clip.id) ?: true),
                        metadata = mapOf(
                            "subTrack" to ((nativeClipLane[clip.id] ?: 0) + 1).toString(),
                        ) + nativeClipSourceDurationMetadata(clip.id),
                    )
                },
            ),
            isLocked = resolvedTrackLocked(TrackType.OVERLAY),
            isVisible = resolvedTrackVisibility(
                TrackType.OVERLAY,
                true,
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
                        zOrder = 120,
                        isHidden = !(timelineManager?.getClipVisibility(clip.id) ?: true),
                        metadata = mapOf(
                            "subTrack" to ((nativeClipLane[clip.id] ?: 0) + 1).toString(),
                        ) + nativeClipSourceDurationMetadata(clip.id),
                    )
                }
            ),
            isLocked = resolvedTrackLocked(TrackType.LAYER),
            isVisible = resolvedTrackVisibility(
                TrackType.LAYER,
                true,
            ),
        )
        val topLayerTrack = TrackState(
            id = "top-text-sticker",
            type = TrackType.TEXT,
            clips = withAutoSubTracks(
                allTextOverlays().map { overlay ->
                    val duration = (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(1)
                    val textKeyframes = textOverlayKeyframes[overlay.id].orEmpty()
                    ClipSegment(
                        id = "text-${overlay.id}",
                        sourcePath = overlay.text,
                        trackType = TrackType.TEXT,
                        startTimeMs = overlay.startTimeMs.toLong(),
                        durationMs = duration.toLong(),
                        sourceInMs = 0L,
                        sourceOutMs = duration.toLong(),
                        zOrder = 400,
                        isHidden = !overlay.visible,
                        metadata = mapOf(
                            "contentType" to "text",
                            "keyframesMs" to textKeyframes.joinToString(","),
                        ),
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
                        zOrder = 400,
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
                true,
            ),
        )
        val audioTrack = TrackState(
            id = "main-audio",
            type = TrackType.AUDIO,
            clips = audioStoreClips
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
                        "peakLevels" to clip.peakLevelsCsv,
                    ) + nativeClipSourceDurationMetadata(clip.id),
                )
            },
            isLocked = resolvedTrackLocked(TrackType.AUDIO),
            isVisible = resolvedTrackVisibility(
                TrackType.AUDIO,
                true,
            ),
        )
        val trackStates = listOf(topLayerTrack, overlayTrack, layerTrack, videoTrack, audioTrack)
        val selectedClipKey =
            selectedTimelineClipKey ?: timelineManager?.getSelectedClipId()?.let(::selectionKeyForNativeClipId)

        val trackDataChanged = trackStates != lastSubmittedTimelineTracks
        val selectionChanged = selectedClipKey != lastSubmittedTimelineSelectionKey
        if (trackDataChanged) {
            lastSubmittedTimelineTracks = trackStates
        }
        if (selectionChanged) {
            lastSubmittedTimelineSelectionKey = selectedClipKey
        }

        activeMultiTrackTimelineView()?.let { timelineView ->
            timelineView.submitTracks(trackStates)
            timelineView.setSelectedClipId(selectedClipKey)
            timelineView.setCurrentTimeMs(currentTimeMs, animate = false)
        }

        activeCanvasTimelineView()?.let { canvasView ->
            canvasView.setTracks(trackStates)
            canvasView.setSelectedClipId(selectedClipKey)
            canvasView.setPlayheadMs(currentTimeMs)
        }

        val previewAudioChanged = syncPreviewAudioClipsToNative()
        if (trackDataChanged || previewAudioChanged) {
            NativeBridge.invalidatePreviewAudioResolutionCache()
        }
        if (trackDataChanged || selectionChanged) {
            updateBottomToolbarMode()
            updatePreviewEmptyState()
        }
    }

    private fun syncPreviewAudioClipsToNative(): Boolean {
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
            return false
        }
        lastPreviewAudioSyncSignature = signature
        NativeBridge.setPreviewAudioClips(clipStates)
        return true
    }

    private fun cacheAudioClipLayout(clip: AudioClip) {
        val durationMs = clip.durationMs.coerceAtLeast(1L)
        nativeClipTrackType[clip.id] = TrackType.AUDIO
        nativeClipLane[clip.id] = clip.layerIndex.coerceAtLeast(0)
        nativeClipZOrder[clip.id] = clip.layerIndex.coerceAtLeast(0)
        nativeClipStartMs[clip.id] = clip.startTimeMs.coerceAtLeast(0L)
        nativeClipDurationMs[clip.id] = durationMs
        nativeClipSourceInMs[clip.id] = clip.sourceInMs
        nativeClipSourceOutMs[clip.id] = if (clip.sourceOutMs > 0L) clip.sourceOutMs else (clip.sourceInMs + durationMs)
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
        val sourceRestoreGain = audioDuckingRestoreGainOverrides[targetClip.id]
        audioClipGainOverrides.remove(targetClip.id)
        audioDuckingRestoreGainOverrides.remove(targetClip.id)
        audioClipGainOverrides[leftClipId] = sourceGain
        audioClipGainOverrides[rightClipId] = sourceGain
        sourceRestoreGain?.let {
            audioDuckingRestoreGainOverrides[leftClipId] = it
            audioDuckingRestoreGainOverrides[rightClipId] = it
        }
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
        if (!aiAutoCutInFlight) {
            Toast.makeText(
                this,
                "Audio split at ${targetTimeMs}ms",
                Toast.LENGTH_SHORT,
            ).show()
        }
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
        noteAppHealthAction("audio_clip_split")
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
                    val sourcePath =
                        c.optString("originalSourcePath")
                            .ifBlank { c.optString("sourcePath", "") }
                    val trackType = mapNativeTrackType(
                        trackTypeRaw = c.optString("trackType", "VIDEO"),
                        zOrder = c.optInt("zOrder", 0),
                        mediaTypeRaw = c.optString("mediaType", ""),
                        sourcePath = sourcePath,
                        preferredTrackType = nativeClipTrackType[id],
                    )
                    val startTimeMs = c.optLong("startTimeMs", 0L)
                    val sourceInMs = c.optLong("sourceInMs", 0L)
                    val rawDurationMs = c.optLong("durationMs", 1L).coerceAtLeast(1L)
                    val rawSourceOutMs = c.optLong("sourceOutMs", sourceInMs + rawDurationMs)
                    val durationMs =
                        if (trackType == TrackType.AUDIO) {
                            clampNativeAudioTimingToSource(
                                clipId = id,
                                sourcePath = sourcePath,
                                startTimeMs = startTimeMs,
                                durationMs = rawDurationMs,
                                sourceInMs = sourceInMs,
                                sourceOutMs = rawSourceOutMs,
                                updateNative = true,
                            )
                        } else {
                            rawDurationMs
                        }
                    nativeClipTrackType[id] = trackType
                    nativeClipStartMs[id] = startTimeMs
                    nativeClipDurationMs[id] = durationMs
                    nativeClipSourceInMs[id] = sourceInMs
                    nativeClipSourceOutMs[id] =
                        if (trackType == TrackType.AUDIO) sourceInMs + durationMs else rawSourceOutMs
                    nativeClipSourcePath[id] = sourcePath
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
        execCmd(
            action = "SPLIT",
            params = mapOf(
                "clipId" to clipId,
                "timeMs" to targetTimeMs,
            )
        ) { result ->
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
            return@execCmd
        }
        val rightClipId = result.data.optInt("rightClipId", -1).takeIf { it > 0 }
        if (rightClipId != null) {
            val keyframes = videoClipKeyframes[clipId].orEmpty()
            val poseKeyframes = videoClipPreviewKeyframes[clipId].orEmpty()
            val leftKeyframes = keyframes.filter { it <= targetTimeMs }.toMutableList()
            val rightKeyframes = keyframes.filter { it > targetTimeMs }.toMutableList()
            val leftPoseKeyframes = poseKeyframes.filter { it.timeMs <= targetTimeMs }.toMutableList()
            val rightPoseKeyframes = poseKeyframes.filter { it.timeMs > targetTimeMs }.toMutableList()
            if (leftKeyframes.isNotEmpty()) {
                videoClipKeyframes[clipId] = leftKeyframes
            } else {
                videoClipKeyframes.remove(clipId)
            }
            if (leftPoseKeyframes.isNotEmpty()) {
                videoClipPreviewKeyframes[clipId] = leftPoseKeyframes
            } else {
                videoClipPreviewKeyframes.remove(clipId)
            }
            if (rightKeyframes.isNotEmpty()) {
                videoClipKeyframes[rightClipId] = rightKeyframes
            } else {
                videoClipKeyframes.remove(rightClipId)
            }
            if (rightPoseKeyframes.isNotEmpty()) {
                videoClipPreviewKeyframes[rightClipId] = rightPoseKeyframes
            } else {
                videoClipPreviewKeyframes.remove(rightClipId)
            }
            videoClipReverseOverrides[clipId]?.let { videoClipReverseOverrides[rightClipId] = it }
            videoClipCurveProfiles[clipId]?.let { videoClipCurveProfiles[rightClipId] = it }
            videoClipFreezeOverrides[clipId]?.let { videoClipFreezeOverrides[rightClipId] = it }
            videoClipGainOverrides[clipId]?.let { videoClipGainOverrides[rightClipId] = it }
            clipPreviewTransforms[clipId]?.let {
                val copied = it.copy()
                clipPreviewTransforms[rightClipId] = copied
                previewView?.let { preview ->
                    syncClipPreviewTransformToNative(
                        clipId = rightClipId,
                        transform = copied,
                        preview = preview,
                    )
                }
            }
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

        val stabilizedPreviewTimeMs = resolvePostSplitPreviewTimeMs(
            anchorTimeMs = targetTimeMs,
            selectedClipId = rightClipId ?: clipId,
        )
        syncTimelineShellFromNative(selectedClipId = rightClipId ?: clipId)
        recordUndoDomain(UndoDomain.TIMELINE)
        runOnUiThread {
            stabilizeAfterSplit(stabilizedPreviewTimeMs, selectionKeyForNativeClipId(rightClipId ?: clipId))
            notePreviewInteractionBusy(2400L)
            playbackController?.refreshPausedPreviewAt(stabilizedPreviewTimeMs)
            mainHandler.postDelayed({
                if (!isPlaying && !isPreviewInteractionBusy()) {
                    playbackController?.refreshPausedPreviewAt(stabilizedPreviewTimeMs)
                }
            }, 720L)
            if (!aiAutoCutInFlight) {
                safeToast("$clipLabel split at ${targetTimeMs}ms", Toast.LENGTH_SHORT)
            }
        }
        recordTelemetryEvent(
            "edit",
            "split_success",
            JSONObject()
                .put("trackType", clipTrackType.name)
                .put("playheadMs", targetTimeMs)
                .put("previewTimeMs", stabilizedPreviewTimeMs)
                .put("targetClipId", clipId)
                .put("leftClipId", result.data.optInt("leftClipId", -1))
                .put("rightClipId", result.data.optInt("rightClipId", -1)),
        )
        noteAppHealthAction(
            when (clipTrackType) {
                TrackType.LAYER -> "layer_clip_split"
                TrackType.OVERLAY -> "overlay_clip_split"
                else -> "video_clip_split"
            },
        )
        captureDebugSnapshot("split_native_success")
        Log.d(
            TAG,
            "$clipLabel split complete: original=$clipId left=${result.data.optInt("leftClipId", -1)} right=${result.data.optInt("rightClipId", -1)} at=${targetTimeMs}ms",
        )
        }
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
        noteAppHealthAction("text_clip_split")
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
        noteAppHealthAction("sticker_clip_split")
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

    private fun performSelectedClipSplitAction(): Boolean {
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
                if (!aiAutoCutInFlight) {
                    safeToast("No clip at playhead to split", Toast.LENGTH_SHORT)
                }
            }
        } else {
            if (!aiAutoCutInFlight) {
                triggerEditorHaptic(EditorHapticEffect.HeavyClick)
            }
        }
        return handled
    }

    private fun Long.roundToNearest(stepMs: Long): Long {
        if (stepMs <= 1L) return this
        val halfStepMs = stepMs / 2L
        return ((this + halfStepMs) / stepMs) * stepMs
    }

    private fun resolveAiAutoCutPreset(label: String): AiAutoCutPreset? =
        aiAutoCutPresets.firstOrNull { it.label.equals(label, ignoreCase = true) }

    private fun resolveSelectedClipForAiAutoCut(): AiAutoCutSelection? {
        return when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: resolveSplitTargetVideoClipId(currentPlayheadMs()) ?: return null
                val timing = selectedVideoTiming(clipId) ?: return null
                val trackType = nativeClipTrackType[clipId] ?: TrackType.VIDEO
                AiAutoCutSelection(
                    kind = selectedClipKind(),
                    clipId = clipId,
                    startTimeMs = timing.first,
                    durationMs = timing.second.coerceAtLeast(1L),
                    label = if (trackType == TrackType.OVERLAY || trackType == TrackType.LAYER) "Overlay" else "Video",
                )
            }
            ClipKind.AUDIO -> {
                val clip = selectedAudioClipId()?.let(AudioClipStore::get)
                    ?: resolveSplitTargetAudioClip(currentPlayheadMs())
                    ?: return null
                selectedTimelineClipKey = "audio-${clip.id}"
                AiAutoCutSelection(
                    kind = ClipKind.AUDIO,
                    clipId = clip.id,
                    startTimeMs = clip.startTimeMs,
                    durationMs = clip.durationMs.coerceAtLeast(1L),
                    label = "Audio",
                )
            }
            ClipKind.NONE -> {
                resolveSplitTargetVideoClipId(currentPlayheadMs())?.let { clipId ->
                    val timing = selectedVideoTiming(clipId) ?: return@let null
                    val trackType = nativeClipTrackType[clipId] ?: TrackType.VIDEO
                    return AiAutoCutSelection(
                        kind = if (trackType == TrackType.OVERLAY || trackType == TrackType.LAYER) ClipKind.OVERLAY else ClipKind.VIDEO,
                        clipId = clipId,
                        startTimeMs = timing.first,
                        durationMs = timing.second.coerceAtLeast(1L),
                        label = if (trackType == TrackType.OVERLAY || trackType == TrackType.LAYER) "Overlay" else "Video",
                    )
                }
                resolveSplitTargetAudioClip(currentPlayheadMs())?.let { clip ->
                    selectedTimelineClipKey = "audio-${clip.id}"
                    return AiAutoCutSelection(
                        kind = ClipKind.AUDIO,
                        clipId = clip.id,
                        startTimeMs = clip.startTimeMs,
                        durationMs = clip.durationMs.coerceAtLeast(1L),
                        label = "Audio",
                    )
                }
                null
            }
            else -> null
        }
    }

    private fun buildAiAutoCutTimelinePoints(
        startTimeMs: Long,
        durationMs: Long,
        preset: AiAutoCutPreset,
    ): List<Long> {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        val edgeGuardMs = preset.edgeGuardMs.coerceAtLeast(120L)
        if (safeDurationMs < (preset.minSegmentMs * 2L) + edgeGuardMs) {
            return emptyList()
        }
        val clipEndExclusiveMs = startTimeMs + safeDurationMs
        val latestCutLocalMs = safeDurationMs - maxOf(preset.minSegmentMs, edgeGuardMs)
        if (latestCutLocalMs <= edgeGuardMs) {
            return emptyList()
        }

        val cutTimes = mutableListOf<Long>()
        var localCursorMs = edgeGuardMs
        var patternIndex = 0
        while (cutTimes.size < preset.maxCuts) {
            val cadence = preset.cadencePattern[patternIndex % preset.cadencePattern.size]
            val proposedStepMs =
                (preset.targetSegmentMs.toDouble() * cadence.toDouble()).roundToLong()
                    .coerceAtLeast(preset.minSegmentMs)
            val nextLocalMs = localCursorMs + proposedStepMs
            if (nextLocalMs >= latestCutLocalMs) {
                break
            }
            val absoluteCutMs = (startTimeMs + nextLocalMs)
                .roundToNearest(100L)
                .coerceIn(startTimeMs + edgeGuardMs, clipEndExclusiveMs - edgeGuardMs)
            if (cutTimes.isEmpty() || absoluteCutMs - cutTimes.last() >= preset.minSegmentMs) {
                cutTimes += absoluteCutMs
            }
            localCursorMs = nextLocalMs
            patternIndex += 1
        }
        return cutTimes.distinct()
    }

    private fun runAiAutoCutOnSelection(presetLabel: String): Boolean {
        val preset = resolveAiAutoCutPreset(presetLabel) ?: return false
        val selection = resolveSelectedClipForAiAutoCut() ?: run {
            safeToast("Select a video, overlay, or audio clip first", Toast.LENGTH_SHORT)
            return false
        }
        val cutTimes = buildAiAutoCutTimelinePoints(
            startTimeMs = selection.startTimeMs,
            durationMs = selection.durationMs,
            preset = preset,
        )
        if (cutTimes.isEmpty()) {
            safeToast("${selection.label} clip is too short for AI Cut", Toast.LENGTH_SHORT)
            return false
        }

        playbackController?.nativePause()
        aiAutoCutInFlight = true
        var appliedCuts = 0
        try {
            cutTimes.forEach { cutTimeMs ->
                applyEditorPlayhead(cutTimeMs, continueAudio = false)
                val applied = when (selection.kind) {
                    ClipKind.AUDIO -> splitAudioAtPlayhead()
                    ClipKind.VIDEO, ClipKind.OVERLAY -> splitSelectedVideoAtPlayhead()
                    else -> false
                }
                if (applied) {
                    appliedCuts += 1
                }
            }
        } finally {
            aiAutoCutInFlight = false
        }

        if (appliedCuts <= 0) {
            safeToast("AI Cut could not find a clean split run", Toast.LENGTH_SHORT)
            return false
        }

        noteAppHealthAction("ai_auto_cut_applied")
        captureDebugSnapshot("ai_auto_cut_success")
        triggerEditorHaptic(EditorHapticEffect.Success)
        safeToast("AI Cut created $appliedCuts cuts", Toast.LENGTH_SHORT)
        return true
    }

    private fun showSelectedClipAiAutoCutSheet() {
        val selection = resolveSelectedClipForAiAutoCut() ?: run {
            safeToast("Select a video, overlay, or audio clip first", Toast.LENGTH_SHORT)
            return
        }
        ModernSheet.show(this, "AI Auto Cut") {
            chips(
                "Style",
                aiAutoCutPresets.map { "${it.label} • ~${it.targetSegmentMs / 1000.0}s" },
                -1,
            ) { _, option ->
                val presetLabel = option.substringBefore("•").trim()
                if (runAiAutoCutOnSelection(presetLabel)) {
                    Log.i(
                        TAG,
                        "[AI] auto_cut applied preset=$presetLabel kind=${selection.kind.name} clip=${selection.clipId}",
                    )
                }
            }
        }
    }

    private fun resolvePostSplitPreviewTimeMs(anchorTimeMs: Long, selectedClipId: Int): Long {
        val clipStartMs = nativeClipStartMs[selectedClipId] ?: anchorTimeMs
        val clipDurationMs = nativeClipDurationMs[selectedClipId]?.coerceAtLeast(1L) ?: 1L
        val clipEndExclusiveMs = clipStartMs + clipDurationMs
        val safeMaxMs = (clipEndExclusiveMs - 1L).coerceAtLeast(clipStartMs)
        return when {
            clipDurationMs <= 1L -> clipStartMs
            anchorTimeMs <= clipStartMs -> (clipStartMs + 1L).coerceAtMost(safeMaxMs)
            anchorTimeMs >= clipEndExclusiveMs -> safeMaxMs
            else -> anchorTimeMs.coerceIn(clipStartMs, safeMaxMs)
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
        updateTimelineTimeText(currentTimeMs)
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
        execCmd(
            action = "TRIM_CLIP",
            params = mapOf(
                "clipId" to clipId,
                "timeMs" to targetTimeMs,
                "edge" to edge,
            ),
        ) { result ->
            if (!result.success) {
                Log.d(TAG, "Video trim rejected: ${result.message}")
            } else {
                syncTimelineShellFromNative(selectedClipId = clipId)
                recordUndoDomain(UndoDomain.TIMELINE)
                playbackController?.scrubTo(targetTimeMs)
                Toast.makeText(
                    this,
                    if (edge == "start") "Trim in at ${targetTimeMs}ms" else "Trim out at ${targetTimeMs}ms",
                    Toast.LENGTH_SHORT,
                ).show()
                Log.d(TAG, "Video trim complete: clip=$clipId edge=$edge at=${targetTimeMs}ms")
            }
        }
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
        execCmd(
            action = "TRIM_CLIP",
            params = mapOf(
                "clipId" to audioId,
                "timeMs" to targetTimeMs,
                "edge" to edge,
            ),
        ) { result ->
            if (!result.success) {
                Log.d(TAG, "Audio trim rejected: ${result.message}")
            } else {
                lastLayoutFetchMs = 0L
                syncTimelineShellFromNative(selectedClipId = audioId)
                recordUndoDomain(UndoDomain.TIMELINE)
                playbackController?.scrubTo(targetTimeMs)
                Toast.makeText(
                    this,
                    if (edge == "start") "Audio trim in at ${targetTimeMs}ms" else "Audio trim out at ${targetTimeMs}ms",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
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
                        this@VideoEditorActivity,
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
                    Toast.makeText(this@VideoEditorActivity, "Select video clip first", Toast.LENGTH_SHORT).show()
                    return
                }
                val sourcePath = nativeClipSourcePath[clipId] ?: run {
                    Toast.makeText(this@VideoEditorActivity, "Source clip unavailable", Toast.LENGTH_SHORT).show()
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
                        execCmd(
                            "TRIM_CLIP",
                            mapOf(
                                "clipId" to clipId,
                                "sourceInMs" to newIn,
                                "sourceOutMs" to newOut,
                            ),
                        ) {
                            lastLayoutFetchMs = 0L
                            refreshMainTimelineTracks()
                            safeToast("Trimmed", Toast.LENGTH_SHORT)
                        }
                    },
                ).show()
            }
            else -> Toast.makeText(this@VideoEditorActivity, "Trim for selected clip not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedAudioClip(): Boolean {
        val anchorTimeMs = currentPlayheadMs().coerceAtLeast(0L)
        val audioId = selectedAudioClipId()
            ?: parseTimelineManagedClipId(selectedTimelineClipKey)
            ?: selectedTimelineClipKey?.toIntOrNull()
            ?: return false
        AudioClipStore.remove(audioId)
        audioClipGainOverrides.remove(audioId)
        audioDuckingRestoreGainOverrides.remove(audioId)
        duckingEnabledForKey.remove("audio-$audioId")
        duckingEnabledForKey.remove(audioId.toString())
        // Clean native tracking maps so refreshMainTimelineTracks doesn't resurrect the clip
        nativeClipTrackType.remove(audioId)
        nativeClipStartMs.remove(audioId)
        nativeClipDurationMs.remove(audioId)
        nativeClipSourcePath.remove(audioId)
        nativeClipSourceInMs.remove(audioId)
        nativeClipSourceOutMs.remove(audioId)
        nativeClipLane.remove(audioId)
        nativeClipZOrder.remove(audioId)
        nativeClipAudioGainKeyframes.remove(audioId)
        initializedAudioImportClipIds.remove(audioId)
        // Remove from native engine
        execCmd(
            action = "DELETE_CLIP",
            params = mapOf("clipId" to audioId),
        ) { result ->
            if (result.success != true) {
                previewView?.removeClip(audioId)
            }
        }
        // Force preview audio re-sync so the deleted clip stops playing immediately
        lastPreviewAudioSyncSignature = ""
        NativeBridge.syncAudioClips()
        selectedTimelineClipKey = null
        lastLayoutFetchMs = 0L
        refreshMainTimelineTracks()
        syncTimelineShellFromNative()
        val revealTimeMs = resolvePostDeleteRevealTime(anchorTimeMs)
        playbackController?.scrubTo(revealTimeMs, syncTimelineUi = false)
        stabilizeAfterDelete(revealTimeMs)
        recordUndoDomain(UndoDomain.TIMELINE)
        safeToast("Audio deleted", Toast.LENGTH_SHORT)
        noteAppHealthAction("audio_clip_deleted")
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
        execCmd(
            action = "DELETE_CLIP",
            params = mapOf("clipId" to clipId)
        ) { result ->
            if (result.success) {
                clipPreviewTransforms.remove(clipId)
                nativeAppliedClipPreviewTransforms.remove(clipId)
                previewView?.clearClipPreviewTransform(clipId)
                videoClipReverseOverrides.remove(clipId)
                videoClipFreezeOverrides.remove(clipId)
                videoClipCurveProfiles.remove(clipId)
                videoClipKeyframes.remove(clipId)
                videoClipPreviewKeyframes.remove(clipId)
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
                noteAppHealthAction("video_clip_deleted")
                Log.d(TAG, "Video deleted: id=$clipId")
            }
        }
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
        noteAppHealthAction("text_clip_deleted")
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
        noteAppHealthAction("sticker_clip_deleted")
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
        setPreviewCropMode(false)
        updateBottomToolbarMode()
    }

    private fun bindClipToolbarAction(buttonId: Int, action: () -> Unit) {
        findViewById<View>(buttonId)?.setOnClickListener {
            val trackType = selectedTrackType()
            if (trackType != null && !ensureTrackEditable(trackType)) {
                return@setOnClickListener
            }
            noteUiButtonTap(
                control = resources.getResourceEntryName(buttonId),
                surface = "clip_toolbar",
            )
            action()
        }
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }
            .getOrDefault("unknown")

    private fun appVersionCode(): Int {
        val packageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return 0
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
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
        val key = selectedTimelineClipKey
        val nativeClipId = key?.let { parseTimelineManagedClipId(it) ?: it.toIntOrNull() }
        return when {
            key?.startsWith("audio-") == true -> ClipKind.AUDIO
            key?.startsWith("text-") == true -> ClipKind.TEXT
            key?.startsWith("sticker-") == true -> ClipKind.STICKER
            key?.startsWith("overlay-") == true || key?.startsWith("layer-") == true -> ClipKind.OVERLAY
            nativeClipId?.let { nativeClipTrackType[it] == TrackType.AUDIO || AudioClipStore.get(it) != null } == true -> ClipKind.AUDIO
            nativeClipId?.let { isOverlayNativeClip(it) || isLayerNativeClip(it) } == true -> ClipKind.OVERLAY
            nativeClipId != null -> ClipKind.VIDEO
            AudioClipStore.all().isNotEmpty() -> ClipKind.AUDIO
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

    private fun selectedImportTrackType(): TrackType? {
        return selectedTimelineImportTrackType ?: selectedTrackType()
    }

    private fun setSelectedImportTrackType(trackType: TrackType?) {
        selectedTimelineImportTrackType = trackType
        multiTrackTimelineView?.setSelectedTrackType(trackType)
    }

    private fun syncSelectedImportTrackFromSelection(clipKey: String?) {
        val clipId = parseNativeClipId(clipKey)
        val trackType =
            when {
                clipKey?.startsWith("audio-") == true -> TrackType.AUDIO
                clipKey?.startsWith("text-") == true || clipKey?.startsWith("sticker-") == true -> TrackType.TEXT
                clipId != null -> nativeClipTrackType[clipId] ?: TrackType.VIDEO
                else -> selectedTimelineImportTrackType
            }
        setSelectedImportTrackType(trackType)
    }

    private fun isVisualPickerRequest(requestCode: Int): Boolean {
        return requestCode == PICK_VIDEO_REQUEST ||
            requestCode == PICK_OVERLAY_REQUEST ||
            requestCode == PICK_LAYER_REQUEST
    }

    private fun openPreferredVisualImport() {
        when (selectedImportTrackType()) {
            TrackType.OVERLAY -> openOverlayTrackImport()
            TrackType.LAYER -> openLayerTrackImport()
            else -> openVideoTrackImport()
        }
    }

    private fun selectedVideoClipId(): Int? {
        return parseNativeClipId(selectedTimelineClipKey)
    }

    private fun selectedAudioClipId(): Int? {
        val key = selectedTimelineClipKey ?: return null
        val parsed = parseTimelineManagedClipId(key) ?: key.toIntOrNull()
        if (parsed != null && (key.startsWith("audio-") || nativeClipTrackType[parsed] == TrackType.AUDIO || AudioClipStore.get(parsed) != null)) {
            return parsed
        }
        return null
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

    private fun isVisualTrackType(trackType: TrackType?): Boolean {
        return trackType == TrackType.VIDEO || trackType == TrackType.OVERLAY || trackType == TrackType.LAYER
    }

    private fun resolveSelectedVisualClipId(
        syncSelectionIfNeeded: Boolean = false,
        preferredTrackType: TrackType? = null,
    ): Int? {
        selectedVideoClipId()?.let { return it }

        val managedSelection =
            timelineManager?.getSelectedClipId()
                ?.takeIf { clipId -> isVisualTrackType(nativeClipTrackType[clipId]) }
        if (managedSelection != null) {
            if (syncSelectionIfNeeded) {
                selectTimelineClipKey(selectionKeyForNativeClipId(managedSelection), revealPreview = false)
            }
            return managedSelection
        }

        val targetTrackType =
            preferredTrackType?.takeIf(::isVisualTrackType)
                ?: selectedTrackType()?.takeIf(::isVisualTrackType)
                ?: selectedImportTrackType()?.takeIf(::isVisualTrackType)
                ?: TrackType.VIDEO
        val selectionKey =
            resolveSelectionKeyForTrack(
                trackType = targetTrackType,
                playheadMs = currentPlayheadMs().coerceAtLeast(0L),
            ) ?: return null
        val clipId = parseNativeClipId(selectionKey) ?: return null
        if (syncSelectionIfNeeded) {
            selectTimelineClipKey(selectionKey, revealPreview = false)
        }
        return clipId
    }

    private fun resolveSelectedTextOverlayForStudio(selectIfNeeded: Boolean = true): TextOverlay? {
        selectedTextOverlayId()?.let { overlayId ->
            OverlayStore.get(overlayId)?.let { return it }
        }

        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        val selectionKey =
            allTextOverlays()
                .asSequence()
                .filter { overlay ->
                    overlay.visible &&
                        playheadMs in overlay.startTimeMs.toLong() until overlay.endTimeMs.toLong().coerceAtLeast(overlay.startTimeMs.toLong() + 1L)
                }
                .maxWithOrNull(compareBy<TextOverlay> { it.layerIndex }.thenBy { it.id })
                ?.let { "text-${it.id}" }
                ?: allTextOverlays()
                    .maxWithOrNull(compareBy<TextOverlay> { it.layerIndex }.thenBy { it.id })
                    ?.let { "text-${it.id}" }
                ?: return null
        if (selectIfNeeded) {
            selectTimelineClipKey(selectionKey, revealPreview = false)
        }
        val overlayId = selectionKey.removePrefix("text-").toIntOrNull() ?: return null
        return OverlayStore.get(overlayId)
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

    private fun resolveTransitionStartTimeMs(outgoingClipId: Int): Long {
        val timing = selectedVideoTiming(outgoingClipId)
        val clipStartMs = timing?.first?.coerceAtLeast(0L)
        val clipDurationMs = timing?.second?.coerceAtLeast(1L)
        return if (clipStartMs != null && clipDurationMs != null) {
            clipStartMs + clipDurationMs
        } else {
            currentPlayheadMs().coerceAtLeast(0L)
        }
    }

    private fun resolveVisualClipPreviewTimeMs(clipId: Int): Long {
        val currentPlayhead = currentPlayheadMs().coerceAtLeast(0L)
        val timing = selectedVideoTiming(clipId) ?: return currentPlayhead
        val clipStartMs = timing.first.coerceAtLeast(0L)
        val clipEndMs = clipStartMs + timing.second.coerceAtLeast(1L)
        return if (currentPlayhead in clipStartMs until clipEndMs) {
            currentPlayhead
        } else {
            clipStartMs
        }
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
        textOverlayKeyframes.remove(overlayId)
        if (selectedTimelineClipKey == "text-$overlayId") {
            selectedTimelineClipKey = null
        }
        refreshMainTimelineTracks()
        stabilizeAfterDelete(anchorTimeMs)
        safeToast("Text deleted", Toast.LENGTH_SHORT)
    }

    private fun showSelectedTextStudio() {
        val overlay = resolveSelectedTextOverlayForStudio(selectIfNeeded = true) ?: run {
            safeToast("Select text clip first", Toast.LENGTH_SHORT)
            return
        }
        val preview = previewView ?: run {
            safeToast("Preview unavailable", Toast.LENGTH_SHORT)
            return
        }
        if (isPlaying) {
            isPlaying = false
            playbackController?.pauseRendering()
        }
        val overlayStartMs = overlay.startTimeMs.toLong().coerceAtLeast(0L)
        val overlayEndMs = overlay.endTimeMs.toLong().coerceAtLeast(overlayStartMs + 1L)
        val playheadMs = currentPlayheadMs().coerceAtLeast(0L)
        if (playheadMs !in overlayStartMs until overlayEndMs) {
            applyEditorPlayhead(overlayStartMs, continueAudio = false)
        }
        val lockedStartTimeMs = overlay.startTimeMs
        val lockedEndTimeMs = overlay.endTimeMs
        editorState?.recordLayerSnapshot()
        recordUndoDomain(UndoDomain.EDITOR)
        TextEditorPanel(
            activity = this,
            previewView = preview,
            overlay = overlay,
            onLiveUpdate = { updated, refreshTimeline ->
                preserveTextOverlayTiming(updated, lockedStartTimeMs, lockedEndTimeMs)
                overlayViews[updated.id]?.setText(updated.text)
                applyTextOverlayState(updated)
                applyTextOverlayPose(updated)
                if (refreshTimeline) {
                    refreshMainTimelineTracks()
                }
            },
            onDone = { updated ->
                preserveTextOverlayTiming(updated, lockedStartTimeMs, lockedEndTimeMs)
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
        return extension in setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "tif", "tiff", "heic", "heif", "avif")
    }

    private fun updateNativeClipDuration(clipId: Int, newDurationMs: Long, clipLabel: String): Boolean {
        val currentTiming = selectedVideoTiming(clipId) ?: return false
        val sourceInMs = nativeClipSourceInMs[clipId] ?: 0L
        val sourceOutMs = (nativeClipSourceOutMs[clipId] ?: (sourceInMs + 1L)).coerceAtLeast(sourceInMs + 1L)
        val safeDurationMs = newDurationMs.coerceAtLeast(150L)
        execCmd(
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
            )
        ) { result ->
            if (!result.success) {
                Log.w(TAG, "$clipLabel duration update failed: clip=$clipId message=${result.message}")
            } else {
                lastLayoutFetchMs = 0L
                nativeClipDurationMs[clipId] = safeDurationMs
                syncTimelineShellFromNative(selectedClipId = clipId)
                refreshPreviewAtPlayhead()
                recordUndoDomain(UndoDomain.TIMELINE)
                safeToast("$clipLabel duration ${safeDurationMs}ms", Toast.LENGTH_SHORT)
            }
        }
        return true
    }

    private fun showStillImageDurationSheet(clipId: Int, clipLabel: String) {
        ModernSheet.show(this, "$clipLabel Duration") {
            chips("Presets", listOf("2s", "3s", "5s", "8s", "10s")) { _, opt ->
                val durationMs = ((opt.removeSuffix("s").toFloatOrNull() ?: 5f) * 1000f).roundToLong()
                if (!updateNativeClipDuration(clipId, durationMs, clipLabel)) {
                    Toast.makeText(this@VideoEditorActivity, "$clipLabel duration update failed", Toast.LENGTH_SHORT).show()
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
                execCmd(
                    action = "SET_CLIP_TRACK",
                    params = mapOf(
                        "clipId" to clipId,
                        "trackType" to trackType.nativeRoleName(),
                        "trackLane" to currentLane,
                        "zOrder" to nextZ,
                    )
                ) { result ->
                    if (result.success) {
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
        val immediateNative = previewTransformNativeImmediatePending
        previewTransformNativeImmediatePending = false
        previewTransformApplyScheduled = false
        previewView?.removeCallbacks(previewTransformApplyRunnable)
        val preview = previewView ?: return
        val clipId = selectedVideoClipId()
        if (!previewPlaybackSurfaceTransformActive) {
            if (preview.scaleX != 1f) preview.scaleX = 1f
            if (preview.scaleY != 1f) preview.scaleY = 1f
            if (preview.translationX != 0f) preview.translationX = 0f
            if (preview.translationY != 0f) preview.translationY = 0f
            if (preview.rotation != 0f) preview.rotation = 0f
        }
        if (clipId != null) {
            val sampledTransform = sampleClipPreviewKeyframeTransform(clipId, currentPlayheadMs().coerceAtLeast(0L))
            val liveGestureActive =
                previewTransformGestureClipId == clipId ||
                    previewTransformDragging ||
                    previewTransformPinching ||
                    previewTransformTouchArmed ||
                    previewResizeSession?.clipId == clipId
            val rawTransform =
                if (liveGestureActive) {
                    clipPreviewTransforms[clipId] ?: sampledTransform ?: ClipPreviewTransform()
                } else {
                    sampledTransform ?: clipPreviewTransforms[clipId] ?: ClipPreviewTransform()
                }
            val transform = normalizeClipPreviewTransform(clipId, rawTransform)
            if (sampledTransform == null && transform != rawTransform) {
                clipPreviewTransforms[clipId] = transform
            }
            if (!(isPlaying && previewPlaybackSurfaceTransformActive)) {
                syncClipPreviewTransformToNative(
                    clipId = clipId,
                    transform = transform,
                    preview = preview,
                    immediate = immediateNative,
                )
            }
        }
        if (shouldShowDirectPreviewEdit()) {
            if (isPreviewInteractionBusy()) {
                maybeRefreshPreviewCropStatusDuringGesture()
            } else {
                refreshPreviewCropStatus()
            }
        }
    }

    private fun requestSelectedClipPreviewTransformApply(immediate: Boolean = false) {
        val preview = previewView ?: return
        if (immediate) {
            previewTransformNativeImmediatePending = true
            preview.removeCallbacks(previewTransformApplyRunnable)
            previewTransformApplyScheduled = false
            applySelectedClipPreviewTransform()
            return
        }
        if (previewTransformApplyScheduled) {
            return
        }
        previewTransformApplyScheduled = true
        preview.postOnAnimation(previewTransformApplyRunnable)
    }

    private fun updateSelectedVideoPreviewTransform(
        immediate: Boolean = false,
        mutator: (ClipPreviewTransform) -> ClipPreviewTransform,
    ): Boolean {
        val clipId = selectedVideoClipId() ?: return false
        val updatedRaw = mutator(currentClipPreviewTransform(clipId))
        val updated = normalizeClipPreviewTransform(clipId, updatedRaw)
        clipPreviewTransforms[clipId] = updated
        syncClipPreviewKeyframePoseIfNeeded(
            clipId = clipId,
            transform = updated,
            persistToNative = true,
            autoCreate = true,
        )
        requestSelectedClipPreviewTransformApply(immediate = immediate)
        return true
    }

    private fun normalizeClipPreviewTransform(
        clipId: Int,
        transform: ClipPreviewTransform,
    ): ClipPreviewTransform {
        val activeGesture =
            previewTransformGestureClipId == clipId &&
                (previewTransformDragging || previewTransformPinching || previewResizeSession?.clipId == clipId || previewTransformTouchArmed)
        if (activeGesture) {
            return normalizeActiveGesturePreviewTransform(clipId, transform)
        }
        if (isPreviewInteractionBusy()) {
            return normalizeClipPreviewTransformLocal(clipId, transform, allowNativeMetrics = false)
        }
        val nativeResult =
            previewView?.computeNormalizedPreviewTransform(
                clipId = clipId,
                zoom = transform.zoom,
                scaleX = transform.scaleX,
                scaleY = transform.scaleY,
                panXPx = transform.panXPx,
                panYPx = transform.panYPx,
                rotationDeg = transform.rotationDeg,
                mirrorX = transform.mirrorX,
            )
        if (nativeResult != null && nativeResult.size >= 5) {
            return ClipPreviewTransform(
                zoom = nativeResult[0],
                panXPx = nativeResult[1],
                panYPx = nativeResult[2],
                rotationDeg = nativeResult[3],
                mirrorX = nativeResult[4] >= 0.5f,
                scaleX = nativeResult.getOrNull(5) ?: transform.scaleX,
                scaleY = nativeResult.getOrNull(6) ?: transform.scaleY,
            )
        }
        return normalizeClipPreviewTransformLocal(clipId, transform)
    }

    private fun syncClipPreviewTransformToNative(
        clipId: Int,
        transform: ClipPreviewTransform,
        preview: View,
        immediate: Boolean = false,
    ) {
        val normalized = normalizeClipPreviewTransform(clipId, transform)
        applyNormalizedClipPreviewTransformToNative(clipId, normalized, immediate)
    }

    private fun applyNormalizedClipPreviewTransformToNative(
        clipId: Int,
        normalized: ClipPreviewTransform,
        immediate: Boolean = false,
    ) {
        val previewApi = previewView ?: return
        val isIdentity =
            normalized.zoom <= 1.001f &&
                abs(normalized.scaleX - 1.0f) <= 0.001f &&
                abs(normalized.scaleY - 1.0f) <= 0.001f &&
                abs(normalized.panXPx) <= 0.5f &&
                abs(normalized.panYPx) <= 0.5f &&
                abs(normalized.rotationDeg) <= 0.001f &&
                !normalized.mirrorX
        val nativeState =
            NativeClipPreviewTransformState(
                zoom = normalized.zoom,
                scaleX = normalized.scaleX,
                scaleY = normalized.scaleY,
                panXPx = normalized.panXPx,
                panYPx = normalized.panYPx,
                rotationDeg = normalized.rotationDeg,
                mirrorX = normalized.mirrorX,
                cleared = isIdentity,
            )
        val lastState = nativeAppliedClipPreviewTransforms[clipId]
        if (!immediate && lastState == nativeState) {
            return
        }
        if (isIdentity) {
            previewApi.clearClipPreviewTransform(clipId, immediate = immediate && !isPreviewInteractionBusy())
            nativeAppliedClipPreviewTransforms[clipId] = nativeState
        } else {
            previewApi.setClipPreviewTransform(
                clipId = clipId,
                zoom = normalized.zoom,
                scaleX = normalized.scaleX,
                scaleY = normalized.scaleY,
                panXPx = normalized.panXPx,
                panYPx = normalized.panYPx,
                rotationDeg = normalized.rotationDeg,
                mirrorX = normalized.mirrorX,
                immediate = immediate,
            )
            nativeAppliedClipPreviewTransforms[clipId] = nativeState
        }
    }

    private fun motionEventInsideView(event: MotionEvent, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        return rect.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
    }

    private fun canDirectPreviewTransformSelectedClip(): Boolean {
        return shouldShowDirectPreviewEdit() &&
            (selectedClipKind() == ClipKind.VIDEO || selectedClipKind() == ClipKind.OVERLAY) &&
            selectedVideoClipId() != null
    }

    private fun clearPreviewTransformGestureState() {
        previewTransformDragging = false
        previewTransformPinching = false
        previewTransformTouchOwner = null
        previewTransformGestureClipId = null
        previewTransformTouchArmed = false
        previewTransformMovedSinceDown = false
        previewTransformSuppressTapCycle = false
        resetPlaybackGestureSurfaceTransform()
        previewTransformStartX = 0f
        previewTransformStartY = 0f
        previewPendingGestureGeometry = null
        previewLastAppliedGestureGeometry = null
        previewGestureStartGeometry = null
        lastPreviewGestureFrameApplyElapsedMs = 0L
        previewGestureFrameScheduled = false
        resetPreviewGestureMetrics()
        previewView?.removeCallbacks(previewGestureFrameRunnable)
        previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
        clearPreviewSelectionLock()
    }

    private fun previewCenterOffsetForRaw(rawX: Float, rawY: Float): Pair<Float, Float> {
        val preview = previewView ?: return 0f to 0f
        val location = IntArray(2)
        preview.getLocationOnScreen(location)
        val centerX = location[0] + (preview.width * 0.5f)
        val centerY = location[1] + (preview.height * 0.5f)
        return (rawX - centerX) to (rawY - centerY)
    }

    private data class PreviewPointerGeometry(
        val centroidOffsetXPx: Float,
        val centroidOffsetYPx: Float,
        val spanPx: Float,
        val angleDeg: Float,
    )

    private fun resetPreviewGestureMetrics() {
        previewGestureCenterXPx = Float.NaN
        previewGestureCenterYPx = Float.NaN
        previewGestureViewportWidthPx = 1f
        previewGestureViewportHeightPx = 1f
        previewGestureMinZoom = PREVIEW_OBJECT_MIN_ZOOM
        previewGestureMaxZoom = PREVIEW_OBJECT_MAX_ZOOM
        previewGestureAllowRotation = false
    }

    private fun cachePreviewGestureMetrics(clipId: Int) {
        val preview = previewView ?: return resetPreviewGestureMetrics()
        val location = IntArray(2)
        preview.getLocationOnScreen(location)
        previewGestureViewportWidthPx = preview.width.coerceAtLeast(1).toFloat()
        previewGestureViewportHeightPx = preview.height.coerceAtLeast(1).toFloat()
        previewGestureCenterXPx = location[0] + (previewGestureViewportWidthPx * 0.5f)
        previewGestureCenterYPx = location[1] + (previewGestureViewportHeightPx * 0.5f)
        previewGestureMinZoom = resolvePreviewMinZoom(clipId, allowNativeFetch = false)
        previewGestureMaxZoom = resolvePreviewMaxZoom(clipId)
    }

    private fun normalizeActiveGesturePreviewTransform(
        clipId: Int,
        transform: ClipPreviewTransform,
    ): ClipPreviewTransform {
        if (previewTransformGestureClipId != clipId || previewGestureCenterXPx.isNaN()) {
            return normalizeClipPreviewTransformLocal(clipId, transform, allowNativeMetrics = false)
        }
        val clampedZoom = transform.zoom.coerceIn(previewGestureMinZoom, previewGestureMaxZoom)
        val clampedScaleX = transform.scaleX.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
        val clampedScaleY = transform.scaleY.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
        val panLimitX = previewGestureViewportWidthPx * maxOf(0.55f, clampedZoom * clampedScaleX * 1.1f)
        val panLimitY = previewGestureViewportHeightPx * maxOf(0.55f, clampedZoom * clampedScaleY * 1.1f)
        return transform.copy(
            zoom = clampedZoom,
            scaleX = clampedScaleX,
            scaleY = clampedScaleY,
            panXPx = transform.panXPx.coerceIn(-panLimitX, panLimitX),
            panYPx = transform.panYPx.coerceIn(-panLimitY, panLimitY),
            rotationDeg = normalizePreviewRotationDeg(transform.rotationDeg),
        )
    }

    private fun rotatePreviewVector(x: Float, y: Float, rotationDeg: Float): Pair<Float, Float> {
        val radians = Math.toRadians(rotationDeg.toDouble())
        val cosValue = kotlin.math.cos(radians).toFloat()
        val sinValue = kotlin.math.sin(radians).toFloat()
        return ((x * cosValue) - (y * sinValue)) to ((x * sinValue) + (y * cosValue))
    }

    private fun resizePreviewTransformFromHandle(
        clipId: Int,
        base: ClipPreviewTransform,
        startGeometry: PreviewPointerGeometry,
        geometry: PreviewPointerGeometry,
        edgeSignX: Float,
        edgeSignY: Float,
    ): ClipPreviewTransform {
        val signX = when {
            edgeSignX < -0.5f -> -1f
            edgeSignX > 0.5f -> 1f
            else -> 0f
        }
        val signY = when {
            edgeSignY < -0.5f -> -1f
            edgeSignY > 0.5f -> 1f
            else -> 0f
        }
        if (signX == 0f && signY == 0f) {
            return normalizeActiveGesturePreviewTransform(clipId, base)
        }

        val (objectBaseWidth, objectBaseHeight) = previewObjectBaseSizePx(clipId, allowMediaProbe = false)
        val viewportWidth = objectBaseWidth.coerceAtLeast(1f)
        val viewportHeight = objectBaseHeight.coerceAtLeast(1f)
        val baseZoom = base.zoom.coerceIn(previewGestureMinZoom, previewGestureMaxZoom)
        val baseScaleX = base.scaleX.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
        val baseScaleY = base.scaleY.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
        val baseWidth = (viewportWidth * baseZoom * baseScaleX).coerceAtLeast(1f)
        val baseHeight = (viewportHeight * baseZoom * baseScaleY).coerceAtLeast(1f)
        val deltaWorldX = geometry.centroidOffsetXPx - startGeometry.centroidOffsetXPx
        val deltaWorldY = geometry.centroidOffsetYPx - startGeometry.centroidOffsetYPx
        val localDelta = rotatePreviewVector(deltaWorldX, deltaWorldY, -base.rotationDeg)

        val minRenderedSide = previewDp(24f)
        val rawTargetWidth =
            if (signX == 0f) baseWidth else (baseWidth + (signX * localDelta.first)).coerceAtLeast(minRenderedSide)
        val rawTargetHeight =
            if (signY == 0f) baseHeight else (baseHeight + (signY * localDelta.second)).coerceAtLeast(minRenderedSide)
        val targetScaleX =
            if (signX == 0f) {
                baseScaleX
            } else {
                (rawTargetWidth / (viewportWidth * baseZoom).coerceAtLeast(1f))
                    .coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
            }
        val targetScaleY =
            if (signY == 0f) {
                baseScaleY
            } else {
                (rawTargetHeight / (viewportHeight * baseZoom).coerceAtLeast(1f))
                    .coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
            }
        val nextWidth = viewportWidth * baseZoom * targetScaleX
        val nextHeight = viewportHeight * baseZoom * targetScaleY
        val centerLocalX =
            if (signX == 0f) {
                0f
            } else {
                (nextWidth - baseWidth) * signX * 0.5f
            }
        val centerLocalY =
            if (signY == 0f) {
                0f
            } else {
                (nextHeight - baseHeight) * signY * 0.5f
            }
        val centerShift = rotatePreviewVector(centerLocalX, centerLocalY, base.rotationDeg)
        return normalizeActiveGesturePreviewTransform(
            clipId,
            base.copy(
                zoom = baseZoom,
                scaleX = targetScaleX,
                scaleY = targetScaleY,
                panXPx = base.panXPx + centerShift.first,
                panYPx = base.panYPx + centerShift.second,
            ),
        )
    }

    private fun resolvePreviewPointerGeometry(event: MotionEvent): PreviewPointerGeometry {
        val preview = previewView
        if (preview == null || event.pointerCount < 2) {
            return resolvePreviewSinglePointerGeometry(event.rawX, event.rawY)
        }
        val centerX =
            previewGestureCenterXPx.takeUnless { it.isNaN() }
                ?: (run {
                    val location = IntArray(2)
                    preview.getLocationOnScreen(location)
                    location[0] + (preview.width * 0.5f)
                })
        val centerY =
            previewGestureCenterYPx.takeUnless { it.isNaN() }
                ?: (run {
                    val location = IntArray(2)
                    preview.getLocationOnScreen(location)
                    location[1] + (preview.height * 0.5f)
                })
        val (rawX0, rawY0) = rawPointerPosition(event, 0)
        val (rawX1, rawY1) = rawPointerPosition(event, 1)
        val dx = rawX1 - rawX0
        val dy = rawY1 - rawY0
        return PreviewPointerGeometry(
            centroidOffsetXPx = ((rawX0 + rawX1) * 0.5f) - centerX,
            centroidOffsetYPx = ((rawY0 + rawY1) * 0.5f) - centerY,
            spanPx = hypot(dx.toDouble(), dy.toDouble()).toFloat(),
            angleDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(),
        )
    }

    private fun rawPointerPosition(event: MotionEvent, pointerIndex: Int): Pair<Float, Float> {
        val rawBaseX = event.rawX - event.x
        val rawBaseY = event.rawY - event.y
        return (rawBaseX + event.getX(pointerIndex)) to (rawBaseY + event.getY(pointerIndex))
    }

    private fun resolvePreviewSinglePointerGeometry(rawX: Float, rawY: Float): PreviewPointerGeometry {
        val centerX = previewGestureCenterXPx.takeUnless { it.isNaN() }
        val centerY = previewGestureCenterYPx.takeUnless { it.isNaN() }
        val (offsetX, offsetY) =
            if (centerX != null && centerY != null) {
                (rawX - centerX) to (rawY - centerY)
            } else {
                previewCenterOffsetForRaw(rawX, rawY)
            }
        return PreviewPointerGeometry(
            centroidOffsetXPx = offsetX,
            centroidOffsetYPx = offsetY,
            spanPx = 0f,
            angleDeg = 0f,
        )
    }

    private fun remainingPointerGeometryAfterPointerUp(event: MotionEvent): Pair<PreviewPointerGeometry, Pair<Float, Float>>? {
        if (event.pointerCount < 2) return null
        val liftedIndex = event.actionIndex
        val remainingIndex =
            (0 until event.pointerCount).firstOrNull { it != liftedIndex } ?: return null
        val rawPosition = rawPointerPosition(event, remainingIndex)
        return resolvePreviewSinglePointerGeometry(rawPosition.first, rawPosition.second) to rawPosition
    }

    private fun cacheNativePreviewTransformState(
        clipId: Int,
        transform: ClipPreviewTransform,
        markNativeApplied: Boolean = true,
    ) {
        val isIdentity =
            transform.zoom <= 1.001f &&
                abs(transform.scaleX - 1.0f) <= 0.001f &&
                abs(transform.scaleY - 1.0f) <= 0.001f &&
                abs(transform.panXPx) <= 0.5f &&
                abs(transform.panYPx) <= 0.5f &&
                abs(transform.rotationDeg) <= 0.001f &&
                !transform.mirrorX
        if (markNativeApplied) {
            nativeAppliedClipPreviewTransforms[clipId] =
                NativeClipPreviewTransformState(
                    zoom = transform.zoom,
                    scaleX = transform.scaleX,
                    scaleY = transform.scaleY,
                    panXPx = transform.panXPx,
                    panYPx = transform.panYPx,
                    rotationDeg = transform.rotationDeg,
                    mirrorX = transform.mirrorX,
                    cleared = isIdentity,
                )
        }
        if (isIdentity) {
            clipPreviewTransforms.remove(clipId)
        } else {
            clipPreviewTransforms[clipId] = transform
        }
        if (!previewTransformDragging && !previewTransformPinching && previewResizeSession == null) {
            syncClipPreviewKeyframePoseIfNeeded(
                clipId = clipId,
                transform = transform,
                persistToNative = false,
                autoCreate = true,
            )
        }
    }

    private fun cacheLocalPreviewTransformState(
        clipId: Int,
        transform: ClipPreviewTransform,
        syncKeyframePose: Boolean = true,
    ) {
        val isIdentity =
            transform.zoom <= 1.001f &&
                abs(transform.scaleX - 1.0f) <= 0.001f &&
                abs(transform.scaleY - 1.0f) <= 0.001f &&
                abs(transform.panXPx) <= 0.5f &&
                abs(transform.panYPx) <= 0.5f &&
                abs(transform.rotationDeg) <= 0.001f &&
                !transform.mirrorX
        if (isIdentity) {
            clipPreviewTransforms.remove(clipId)
        } else {
            clipPreviewTransforms[clipId] = transform
        }
        if (!syncKeyframePose) return
        syncClipPreviewKeyframePoseIfNeeded(
            clipId = clipId,
            transform = transform,
            persistToNative = false,
            autoCreate = true,
        )
    }

    private fun applyPlaybackGestureSurfaceTransform(
        base: ClipPreviewTransform,
        updated: ClipPreviewTransform,
    ) {
        val preview = previewView ?: return
        val scaleRatioX =
            ((updated.zoom * updated.scaleX) / maxOf(base.zoom * base.scaleX, 0.001f))
                .coerceIn(0.2f, 6.0f)
        val scaleRatioY =
            ((updated.zoom * updated.scaleY) / maxOf(base.zoom * base.scaleY, 0.001f))
                .coerceIn(0.2f, 6.0f)
        previewPlaybackSurfaceTransformActive = true
        preview.pivotX = preview.width * 0.5f
        preview.pivotY = preview.height * 0.5f
        preview.translationX = updated.panXPx - base.panXPx
        preview.translationY = updated.panYPx - base.panYPx
        preview.scaleX = scaleRatioX
        preview.scaleY = scaleRatioY
        preview.rotation = normalizePreviewRotationDeg(updated.rotationDeg - base.rotationDeg)
        if (preview.layerType != View.LAYER_TYPE_NONE) {
            preview.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun resetPlaybackGestureSurfaceTransform() {
        val preview = previewView ?: return
        previewPlaybackSurfaceTransformActive = false
        preview.scaleX = 1f
        preview.scaleY = 1f
        preview.translationX = 0f
        preview.translationY = 0f
        preview.rotation = 0f
        if (preview.layerType != View.LAYER_TYPE_NONE) {
            preview.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun commitPlaybackGestureTransformToNative(clipId: Int) {
        val preview = previewView ?: return
        val transform = normalizeClipPreviewTransformLocal(
            clipId,
            currentClipPreviewTransform(clipId, allowNativeFetch = false),
            allowNativeMetrics = false,
        )
        val resetDelayMs = if (isPlaying) 96L else 0L
        preview.postDelayed({
            syncClipPreviewTransformToNative(
                clipId = clipId,
                transform = transform,
                preview = preview,
                immediate = false,
            )
            resetPlaybackGestureSurfaceTransform()
        }, resetDelayMs)
    }

    private fun beginNativePreviewTransformGesture(
        clipId: Int,
        mode: PreviewTransformGestureMode,
        event: MotionEvent,
        edgeSignX: Float = 0f,
        edgeSignY: Float = 0f,
        allowRotation: Boolean = false,
    ): ClipPreviewTransform? {
        previewPendingGestureGeometry = null
        previewLastAppliedGestureGeometry = null
        cachePreviewGestureMetrics(clipId)
        val geometry = resolvePreviewPointerGeometry(event)
        previewGestureStartGeometry = geometry
        val current = currentClipPreviewTransform(clipId, allowNativeFetch = false)
        val normalized = normalizeClipPreviewTransformLocal(clipId, current, allowNativeMetrics = false)
        previewTransformBase = normalized
        previewGestureAllowRotation = allowRotation
        if (isPlaying) {
            cacheLocalPreviewTransformState(clipId, normalized, syncKeyframePose = false)
            resetPlaybackGestureSurfaceTransform()
        } else {
            cacheNativePreviewTransformState(clipId, normalized)
        }
        return normalized
    }

    private fun updateNativePreviewTransformGesture(geometry: PreviewPointerGeometry): ClipPreviewTransform? {
        val clipId = previewTransformGestureClipId ?: selectedVideoClipId() ?: return null
        val startGeometry = previewGestureStartGeometry ?: geometry
        val base = previewTransformBase
        val updated =
            when {
                previewResizeSession?.clipId == clipId -> {
                    val session = previewResizeSession ?: return null
                    resizePreviewTransformFromHandle(
                        clipId,
                        base,
                        startGeometry,
                        geometry,
                        session.cornerSignX,
                        session.cornerSignY,
                    )
                }
                previewTransformPinching -> {
                    val startSpan = startGeometry.spanPx.coerceAtLeast(1f)
                    val clampedAccumulator = (geometry.spanPx / startSpan).coerceIn(0.15f, 8.0f)
                    // Linear 1:1 pinch-to-zoom — no artificial multiplier
                    val targetZoom = base.zoom * clampedAccumulator
                    val zoomRatio = targetZoom / maxOf(base.zoom, 0.001f)
                    val angleDelta =
                        if (previewGestureAllowRotation) {
                            normalizePreviewRotationDeg(geometry.angleDeg - startGeometry.angleDeg)
                                .takeIf { abs(it) >= 2.5f } ?: 0f
                        } else {
                            0f
                        }
                    val baseOffsetX = base.panXPx - startGeometry.centroidOffsetXPx
                    val baseOffsetY = base.panYPx - startGeometry.centroidOffsetYPx
                    val rotationRad = Math.toRadians(angleDelta.toDouble())
                    val cos = kotlin.math.cos(rotationRad).toFloat()
                    val sin = kotlin.math.sin(rotationRad).toFloat()
                    val scaledOffsetX = baseOffsetX * zoomRatio
                    val scaledOffsetY = baseOffsetY * zoomRatio
                    normalizeActiveGesturePreviewTransform(
                        clipId,
                        base.copy(
                            zoom = targetZoom,
                            panXPx = geometry.centroidOffsetXPx + (scaledOffsetX * cos - scaledOffsetY * sin),
                            panYPx = geometry.centroidOffsetYPx + (scaledOffsetX * sin + scaledOffsetY * cos),
                            rotationDeg = base.rotationDeg + angleDelta,
                        ),
                    )
                }
                else -> {
                    val deltaXPx = geometry.centroidOffsetXPx - startGeometry.centroidOffsetXPx
                    val deltaYPx = geometry.centroidOffsetYPx - startGeometry.centroidOffsetYPx
                    normalizeActiveGesturePreviewTransform(
                        clipId,
                        base.copy(
                            panXPx = base.panXPx + deltaXPx,
                            panYPx = base.panYPx + deltaYPx,
                        ),
                    )
                }
            }
        if (isPlaying) {
            cacheLocalPreviewTransformState(clipId, updated, syncKeyframePose = false)
            applyPlaybackGestureSurfaceTransform(base, updated)
        } else {
            cacheNativePreviewTransformState(clipId, updated, markNativeApplied = false)
            requestSelectedClipPreviewTransformApply(immediate = false)
        }
        if (previewCropOverlayView?.visibility == View.VISIBLE) {
            layoutSelectedPreviewObjectFrame()
        }
        return updated
    }

    private fun queueNativePreviewTransformGestureUpdate(event: MotionEvent) {
        val preview = previewView ?: return
        val geometry = resolvePreviewPointerGeometry(event)
        val baselineGeometry = previewPendingGestureGeometry ?: previewLastAppliedGestureGeometry
        if (
            baselineGeometry != null &&
            abs(geometry.centroidOffsetXPx - baselineGeometry.centroidOffsetXPx) < 0.5f &&
            abs(geometry.centroidOffsetYPx - baselineGeometry.centroidOffsetYPx) < 0.5f &&
            abs(geometry.spanPx - baselineGeometry.spanPx) < 0.5f &&
            abs(normalizePreviewRotationDeg(geometry.angleDeg - baselineGeometry.angleDeg)) < 0.5f
        ) {
            return
        }
        previewPendingGestureGeometry = geometry
        if (previewGestureFrameScheduled) {
            return
        }
        previewGestureFrameScheduled = true
        val now = SystemClock.elapsedRealtime()
        val remainingDelayMs =
            (resolvePreviewGestureFrameIntervalMs() - (now - lastPreviewGestureFrameApplyElapsedMs))
                .coerceAtLeast(0L)
        if (remainingDelayMs <= 0L) {
            preview.postOnAnimation(previewGestureFrameRunnable)
        } else {
            preview.postDelayed(previewGestureFrameRunnable, remainingDelayMs)
        }
    }

    private fun flushQueuedNativePreviewTransformGestureUpdate() {
        val preview = previewView ?: return
        preview.removeCallbacks(previewGestureFrameRunnable)
        previewGestureFrameScheduled = false
        val geometry = previewPendingGestureGeometry ?: return
        previewPendingGestureGeometry = null
        previewLastAppliedGestureGeometry = geometry
        updateNativePreviewTransformGesture(geometry)?.let {
            maybeRefreshPreviewCropStatusDuringGesture()
        }
    }

    private fun applyPlaybackPreviewGestureUpdateNow(event: MotionEvent): Boolean {
        if (!isPlaying) return false
        val geometry = resolvePreviewPointerGeometry(event)
        previewPendingGestureGeometry = null
        previewLastAppliedGestureGeometry = geometry
        lastPreviewGestureFrameApplyElapsedMs = SystemClock.elapsedRealtime()
        return updateNativePreviewTransformGesture(geometry) != null
    }

    private fun endNativePreviewTransformGesture() {
        previewGestureStartGeometry = null
    }

    private fun lockPreviewSelectionTo(clipId: Int?, windowMs: Long = 900L) {
        if (clipId == null) return
        previewSelectionLockClipId = clipId
        previewSelectionLockUntilElapsedMs =
            maxOf(previewSelectionLockUntilElapsedMs, SystemClock.elapsedRealtime() + windowMs.coerceAtLeast(120L))
    }

    private fun clearPreviewSelectionLock() {
        previewSelectionLockClipId = null
        previewSelectionLockUntilElapsedMs = 0L
    }

    private fun shouldIgnorePreviewSelectionChange(selectedClipId: Int?): Boolean {
        val lockedClipId = previewSelectionLockClipId ?: return false
        if (SystemClock.elapsedRealtime() >= previewSelectionLockUntilElapsedMs) {
            clearPreviewSelectionLock()
            return false
        }
        val currentVisualClipId = parseNativeClipId(selectedTimelineClipKey)
        val effectiveLockId = currentVisualClipId ?: lockedClipId
        return when (selectedClipId) {
            null -> effectiveLockId != null
            else -> selectedClipId != effectiveLockId
        }
    }

    private fun notePreviewInteractionBusy(windowMs: Long = 1800L) {
        val until = SystemClock.elapsedRealtime() + windowMs.coerceAtLeast(250L)
        previewInteractionBusyUntilElapsedMs = maxOf(previewInteractionBusyUntilElapsedMs, until)
        TimelineThumbnailCache.suspendRequests(windowMs + 700L)
        AudioWaveformCache.suspendRequests(windowMs + 700L)
        timelineCanvasView()?.suspendAssetRequests(windowMs + 700L)
    }

    private fun schedulePostImportClipHydration(clipId: Int, revealTimeMs: Long) {
        pendingImportedClipSelectionId = clipId
        pendingImportedClipRevealTimeMs = revealTimeMs.coerceAtLeast(0L)
        mainHandler.removeCallbacks(postImportClipHydrationRunnable)
        val delayMs = when {
            isPreviewInteractionBusy() -> {
                when (DeviceDetector.getDeviceTier()) {
                    DeviceDetector.DeviceTier.LOW -> 1100L
                    DeviceDetector.DeviceTier.MID -> 850L
                    DeviceDetector.DeviceTier.HIGH -> 650L
                }
            }
            timelineRefreshScheduled -> 220L
            else -> 0L
        }
        if (delayMs == 0L) {
            mainHandler.post(postImportClipHydrationRunnable)
        } else {
            mainHandler.postDelayed(postImportClipHydrationRunnable, delayMs)
        }
    }

    private fun isPreviewInteractionBusy(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return previewTransformDragging ||
            previewTransformPinching ||
            previewTransformTouchArmed ||
            previewResizeSession != null ||
            previewTrimSession != null ||
            now < previewInteractionBusyUntilElapsedMs
    }

    private fun shouldDeferHeavyUiWork(): Boolean =
        isPlaying || isPreviewInteractionBusy()

    private fun canStartPreviewTransformAt(event: MotionEvent): Boolean {
        val insideTransformSurface =
            motionEventInsideView(event, previewCropOverlayView) ||
                motionEventInsideView(event, previewCropFrameGuideView) ||
                motionEventInsideView(event, previewViewportFrame) ||
                motionEventInsideView(event, overlayContainer) ||
                motionEventInsideView(event, previewView) ||
                motionEventInsideView(event, previewContainerView)
        if (!insideTransformSurface) {
            return false
        }
        val blockedViews =
            listOf(
                findViewById<View?>(R.id.previewPanelHeader),
                findViewById<View?>(R.id.playbackUndoRedoRow),
                findViewById<View?>(R.id.previewHud),
                findViewById<View?>(R.id.previewCropTopRail),
                findViewById<View?>(R.id.previewCropActionRail),
            )
        return blockedViews.none { motionEventInsideView(event, it) }
    }

    private fun isTouchInsideSelectedPreviewBounds(event: MotionEvent): Boolean {
        val objectHitRect = selectedPreviewObjectHitRectOnScreen()
        if (objectHitRect != null && objectHitRect.contains(event.rawX, event.rawY)) {
            return true
        }
        return false
    }

    private fun updateNativeClipTiming(
        clipId: Int,
        newStartTimeMs: Long,
        newDurationMs: Long,
        newSourceInMs: Long,
        newSourceOutMs: Long,
        originalStartTimeMs: Long,
        originalDurationMs: Long,
        originalSourceInMs: Long,
        originalSourceOutMs: Long,
        previewOnly: Boolean,
    ): Boolean {
        val params =
            mapOf(
                "clipId" to clipId,
                "newStartTimeMs" to newStartTimeMs,
                "newDurationMs" to newDurationMs,
                "newSourceInMs" to newSourceInMs,
                "newSourceOutMs" to newSourceOutMs,
                "originalStartTimeMs" to originalStartTimeMs,
                "originalDurationMs" to originalDurationMs,
                "originalSourceInMs" to originalSourceInMs,
                "originalSourceOutMs" to originalSourceOutMs,
                "previewOnly" to previewOnly,
                "applyMagnetic" to (nativeClipTrackType[clipId] == TrackType.VIDEO),
            )
        return if (previewOnly) {
            NativeBridge.executeCommandAsync("UPDATE_CLIP_TIMING", params)
            true
        } else {
            execCmd(
                action = "UPDATE_CLIP_TIMING",
                params = params
            ) {}
            true
        }
    }

    private fun dispatchPreviewTrimUpdate(
        session: PreviewTrimSession,
        timing: ClipTimingSnapshot,
        previewOnly: Boolean,
    ): Boolean {
        return updateNativeClipTiming(
            clipId = session.clipId,
            newStartTimeMs = timing.startTimeMs,
            newDurationMs = timing.durationMs,
            newSourceInMs = timing.sourceInMs,
            newSourceOutMs = timing.sourceOutMs,
            originalStartTimeMs = session.originalStartTimeMs,
            originalDurationMs = session.originalDurationMs,
            originalSourceInMs = session.originalSourceInMs,
            originalSourceOutMs = session.originalSourceOutMs,
            previewOnly = previewOnly,
        )
    }

    private fun buildPreviewTrimSnapshot(session: PreviewTrimSession, rawDeltaX: Float): ClipTimingSnapshot {
        val frameWidthPx =
            (previewCropFrameGuideView?.width ?: previewView?.width ?: 0)
                .coerceAtLeast(1)
        val deltaMs =
            ((rawDeltaX / frameWidthPx.toFloat()) * session.originalDurationMs.toFloat())
                .roundToLong()
        return if (session.edge == "start") {
            val minDelta = -minOf(session.originalStartTimeMs, session.originalSourceInMs)
            val maxDelta = (session.originalSourceOutMs - session.originalSourceInMs - MIN_PREVIEW_TRIM_DURATION_MS)
                .coerceAtLeast(0L)
            val appliedDelta = deltaMs.coerceIn(minDelta, maxDelta)
            val newSourceInMs = session.originalSourceInMs + appliedDelta
            val newStartTimeMs = session.originalStartTimeMs + appliedDelta
            val newDurationMs = (session.originalDurationMs - appliedDelta).coerceAtLeast(MIN_PREVIEW_TRIM_DURATION_MS)
            ClipTimingSnapshot(
                startTimeMs = newStartTimeMs,
                durationMs = newDurationMs,
                sourceInMs = newSourceInMs,
                sourceOutMs = session.originalSourceOutMs,
            )
        } else {
            val minOutMs = session.originalSourceInMs + MIN_PREVIEW_TRIM_DURATION_MS
            val newSourceOutMs = (session.originalSourceOutMs + deltaMs)
                .coerceIn(minOutMs, session.sourceLimitMs.coerceAtLeast(minOutMs))
            val durationDeltaMs = newSourceOutMs - session.originalSourceOutMs
            ClipTimingSnapshot(
                startTimeMs = session.originalStartTimeMs,
                durationMs = (session.originalDurationMs + durationDeltaMs).coerceAtLeast(MIN_PREVIEW_TRIM_DURATION_MS),
                sourceInMs = session.originalSourceInMs,
                sourceOutMs = newSourceOutMs,
            )
        }
    }

    private fun applyPreviewTrimSnapshot(
        clipId: Int,
        timing: ClipTimingSnapshot,
        focusEdge: String,
    ) {
        videoClipTimingOverrides[clipId] = timing
        refreshMainTimelineTracks()
        val focusTimeMs =
            if (focusEdge == "start") {
                timing.startTimeMs
            } else {
                (timing.startTimeMs + timing.durationMs - 1L).coerceAtLeast(timing.startTimeMs)
            }
        applyEditorPlayhead(focusTimeMs, continueAudio = false)
        refreshPreviewCropStatus()
    }

    private fun restorePreviewTrimState(
        session: PreviewTrimSession,
        dispatchPreview: Boolean,
    ) {
        videoClipTimingOverrides.remove(session.clipId)
        refreshMainTimelineTracks()
        if (dispatchPreview) {
            dispatchPreviewTrimUpdate(
                session = session,
                timing = ClipTimingSnapshot(
                    startTimeMs = session.originalStartTimeMs,
                    durationMs = session.originalDurationMs,
                    sourceInMs = session.originalSourceInMs,
                    sourceOutMs = session.originalSourceOutMs,
                ),
                previewOnly = true,
            )
        }
        applyEditorPlayhead(session.originalStartTimeMs, continueAudio = false)
        refreshPreviewCropStatus()
    }

    private fun handlePreviewTrimTouch(
        edge: String,
        event: MotionEvent,
    ): Boolean {
        if (!shouldShowDirectPreviewEdit()) return false
        val clipId = selectedVideoClipId() ?: return false
        if (!canPreviewTrimClip(clipId)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val timing = selectedVideoTiming(clipId) ?: return false
                val sourceInMs = videoClipTimingOverrides[clipId]?.sourceInMs ?: (nativeClipSourceInMs[clipId] ?: 0L)
                val sourceOutMs = videoClipTimingOverrides[clipId]?.sourceOutMs
                    ?: (nativeClipSourceOutMs[clipId] ?: (sourceInMs + timing.second))
                previewTrimSession =
                    PreviewTrimSession(
                        clipId = clipId,
                        edge = edge,
                        originalStartTimeMs = timing.first,
                        originalDurationMs = timing.second,
                        originalSourceInMs = sourceInMs,
                        originalSourceOutMs = sourceOutMs,
                        sourceLimitMs = maxOf(resolveClipSourceDurationMs(clipId), sourceOutMs),
                )
                previewTrimStartRawX = event.rawX
                previewTrimLastDispatchElapsedMs = 0L
                playbackController?.nativePause()
                previewAudioPlayer?.pause()
                syncPreviewCropModeUi()
                noteUiButtonTap("crop_trim_$edge", "preview_crop")
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val session = previewTrimSession?.takeIf { it.edge == edge && it.clipId == clipId } ?: return false
                val timing = buildPreviewTrimSnapshot(session, event.rawX - previewTrimStartRawX)
                applyPreviewTrimSnapshot(clipId = session.clipId, timing = timing, focusEdge = edge)
                val now = SystemClock.elapsedRealtime()
                if (now - previewTrimLastDispatchElapsedMs >= PREVIEW_TRIM_DISPATCH_INTERVAL_MS) {
                    previewTrimLastDispatchElapsedMs = now
                    dispatchPreviewTrimUpdate(session = session, timing = timing, previewOnly = true)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val session = previewTrimSession?.takeIf { it.edge == edge && it.clipId == clipId } ?: return false
                val timing = buildPreviewTrimSnapshot(session, event.rawX - previewTrimStartRawX)
                val committed = dispatchPreviewTrimUpdate(session = session, timing = timing, previewOnly = false)
                previewTrimSession = null
                syncPreviewCropModeUi()
                return if (committed) {
                    videoClipTimingOverrides.remove(session.clipId)
                    syncTimelineShellFromNative(selectedClipId = session.clipId)
                    applyEditorPlayhead(
                        if (edge == "start") timing.startTimeMs else (timing.startTimeMs + timing.durationMs - 1L),
                        continueAudio = false,
                    )
                    recordUndoDomain(UndoDomain.TIMELINE)
                    refreshPreviewCropStatus()
                    true
                } else {
                    restorePreviewTrimState(session, dispatchPreview = true)
                    safeToast("Preview trim failed", Toast.LENGTH_SHORT)
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                previewTrimSession?.takeIf { it.edge == edge && it.clipId == clipId }?.let {
                    restorePreviewTrimState(it, dispatchPreview = true)
                }
                previewTrimSession = null
                syncPreviewCropModeUi()
                syncPreviewAudioAt(currentTimeMs, continuePlaying = false)
                return true
            }
        }
        return false
    }

    private fun resolvePreviewTrimEdge(event: MotionEvent): String? {
        val frameWidthPx = (previewCropFrameGuideView?.width ?: 0).coerceAtLeast(1)
        val edgeTouchSlopPx = maxOf(18f, resources.displayMetrics.density * PREVIEW_TRIM_EDGE_TOUCH_SLOP_DP)
        val touchX = event.x.coerceIn(0f, frameWidthPx.toFloat())
        return when {
            touchX <= edgeTouchSlopPx -> "start"
            touchX >= frameWidthPx - edgeTouchSlopPx -> "end"
            else -> null
        }
    }

    private fun handlePreviewFrameTouch(event: MotionEvent): Boolean {
        if (!shouldShowDirectPreviewEdit()) return false
        previewTrimSession = null
        if (previewResizeSession != null ||
            (event.actionMasked == MotionEvent.ACTION_DOWN && resolvePreviewResizeEdgeSigns(event) != null)
        ) {
            return handlePreviewResizeEdgeTouch(event)
        }
        return handlePreviewTransformTouch(event)
    }

    private fun resolvePreviewResizeEdgeSigns(event: MotionEvent): Pair<Float, Float>? {
        val frameView = previewCropFrameGuideView ?: return null
        val frameWidthPx = frameView.width.coerceAtLeast(1)
        val frameHeightPx = frameView.height.coerceAtLeast(1)
        val edgeTouchSlopPx = maxOf(20f, resources.displayMetrics.density * PREVIEW_RESIZE_EDGE_TOUCH_SLOP_DP)
        val frameLocation = IntArray(2)
        frameView.getLocationOnScreen(frameLocation)
        val touchX = (event.rawX - frameLocation[0]).coerceIn(0f, frameWidthPx.toFloat())
        val touchY = (event.rawY - frameLocation[1]).coerceIn(0f, frameHeightPx.toFloat())
        val expandedFrame =
            RectF(
                frameLocation[0] - edgeTouchSlopPx,
                frameLocation[1] - edgeTouchSlopPx,
                frameLocation[0] + frameWidthPx + edgeTouchSlopPx,
                frameLocation[1] + frameHeightPx + edgeTouchSlopPx,
            )
        if (!expandedFrame.contains(event.rawX, event.rawY)) {
            return null
        }
        val signX =
            when {
                touchX <= edgeTouchSlopPx -> -1f
                touchX >= frameWidthPx - edgeTouchSlopPx -> 1f
                else -> 0f
            }
        val signY =
            when {
                touchY <= edgeTouchSlopPx -> -1f
                touchY >= frameHeightPx - edgeTouchSlopPx -> 1f
                else -> 0f
            }
        return if (signX != 0f || signY != 0f) signX to signY else null
    }

    private fun handlePreviewResizeEdgeTouch(event: MotionEvent): Boolean {
        if (!canDirectPreviewTransformSelectedClip()) {
            if (!isPlaying) {
                restorePreviewEditableSelection()
                syncPreviewCropModeUi()
            }
            if (!canDirectPreviewTransformSelectedClip()) {
                return false
            }
        }
        val preview = previewView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val edgeSigns = resolvePreviewResizeEdgeSigns(event) ?: return false
                val clipId = selectedVideoClipId() ?: return false
                notePreviewInteractionBusy(2200L)
                previewTransformSuppressTapCycle = true
                previewTransformMovedSinceDown = false
                lockPreviewSelectionTo(clipId)
                pausePreviewAudioForDirectGesture()
                previewResizeSession =
                    PreviewResizeSession(
                        clipId = clipId,
                        cornerSignX = edgeSigns.first,
                        cornerSignY = edgeSigns.second,
                    )
                clearPreviewTransformGestureState()
                previewTransformGestureClipId = clipId
                beginNativePreviewTransformGesture(
                    clipId = clipId,
                    mode = PreviewTransformGestureMode.EDGE_RESIZE,
                    event = event,
                    edgeSignX = edgeSigns.first,
                    edgeSignY = edgeSigns.second,
                )
                preview.setLayerType(View.LAYER_TYPE_NONE, null)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val session = previewResizeSession ?: return false
                notePreviewInteractionBusy(1400L)
                previewTransformMovedSinceDown = true
                previewTransformSuppressTapCycle = true
                lockPreviewSelectionTo(session.clipId, windowMs = 1200L)
                if (isPlaying) {
                    applyPlaybackPreviewGestureUpdateNow(event)
                    return true
                }
                queueNativePreviewTransformGestureUpdate(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finishedSession = previewResizeSession
                if (previewResizeSession != null) {
                    flushQueuedNativePreviewTransformGestureUpdate()
                    endNativePreviewTransformGesture()
                }
                if (previewTransformMovedSinceDown) {
                    previewTransformSuppressTapCycle = true
                    previewSelectionLockUntilElapsedMs =
                        maxOf(previewSelectionLockUntilElapsedMs, SystemClock.elapsedRealtime() + 240L)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        finishedSession?.clipId?.let { clipId ->
                            val finishedTransform = currentClipPreviewTransform(clipId, allowNativeFetch = false)
                            if (isPlaying) {
                                val keyframeTimeMs = currentPlayheadMs().coerceAtLeast(0L)
                                syncClipPreviewKeyframePoseIfNeeded(
                                    clipId = clipId,
                                    transform = finishedTransform,
                                    persistToNative = false,
                                    autoCreate = true,
                                ) { shouldPersistKeyframe ->
                                    if (shouldPersistKeyframe) {
                                        mainHandler.postDelayed({
                                            addNativeClipKeyframeAsync(clipId, keyframeTimeMs, finishedTransform)
                                        }, 120L)
                                    }
                                }
                                commitPlaybackGestureTransformToNative(clipId)
                            } else {
                                syncClipPreviewKeyframePoseIfNeeded(
                                    clipId = clipId,
                                    transform = finishedTransform,
                                    persistToNative = true,
                                    autoCreate = true,
                                )
                            }
                        }
                    }
                } else {
                    clearPreviewSelectionLock()
                }
                previewResizeSession = null
                previewTransformGestureClipId = null
                preview.setLayerType(View.LAYER_TYPE_NONE, null)
                if (!isPlaying) {
                    refreshPreviewCropStatus()
                } else {
                    maybeRefreshPreviewCropStatusDuringGesture()
                }
                schedulePreviewAudioResumeAfterGesture()
                return true
            }
        }
        return false
    }

    private fun setupPreviewTransformGestures() {
        previewTransformTouchSlop = maxOf(2, (ViewConfiguration.get(this).scaledTouchSlop * 0.55f).roundToInt())
        previewTransformGestureDetector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (!canStartPreviewTransformAt(e)) return false
                        if (previewTransformSuppressTapCycle || previewTransformMovedSinceDown) {
                            previewTransformSuppressTapCycle = false
                            previewTransformMovedSinceDown = false
                            return true
                        }
                        if (canDirectPreviewTransformSelectedClip() && isTouchInsideSelectedPreviewBounds(e)) {
                            return true
                        }
                        clearPreviewSelectionLock()
                        return cyclePreviewEditableSelection()
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        return handlePreviewTransformDoubleTap(e)
                    }
                },
            )
        bindPreviewTransformTouchTarget(previewView, frameOnly = false)
        bindPreviewTransformTouchTarget(previewViewportFrame, frameOnly = false)
        bindPreviewTransformTouchTarget(previewCropOverlayView, frameOnly = false)
        bindPreviewTransformTouchTarget(previewCropFrameGuideView, frameOnly = true)
        previewResizeHandleViews().forEach { bindPreviewTransformTouchTarget(it, frameOnly = true) }
        bindPreviewTransformTouchTarget(overlayContainer, frameOnly = false)
        bindPreviewTransformTouchTarget(previewContainerView, frameOnly = false)
    }

    private fun handlePreviewTransformTouch(event: MotionEvent): Boolean {
        if (!canDirectPreviewTransformSelectedClip()) {
            if (!isPlaying) {
                restorePreviewEditableSelection()
                syncPreviewCropModeUi()
            }
            if (!canDirectPreviewTransformSelectedClip()) {
                return false
            }
        }
        if (selectedClipKind() != ClipKind.VIDEO && selectedClipKind() != ClipKind.OVERLAY) {
            clearPreviewTransformGestureState()
            return false
        }

        previewTransformGestureDetector?.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!canStartPreviewTransformAt(event)) {
                    previewTransformTouchArmed = false
                    previewTransformMovedSinceDown = false
                    previewTransformSuppressTapCycle = false
                    previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                    return false
                }
                if (!isTouchInsideSelectedPreviewBounds(event)) {
                    previewTransformTouchArmed = false
                    previewTransformMovedSinceDown = false
                    previewTransformSuppressTapCycle = false
                    clearPreviewSelectionLock()
                    previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                    return true
                }
                notePreviewInteractionBusy(2200L)
                previewTransformGestureClipId = selectedVideoClipId()
                previewTransformBase =
                    previewTransformGestureClipId?.let { currentClipPreviewTransform(it, allowNativeFetch = false) } ?: ClipPreviewTransform()
                previewTransformStartX = event.rawX
                previewTransformStartY = event.rawY
                previewTransformDragging = false
                previewTransformTouchArmed = true
                previewTransformMovedSinceDown = false
                previewTransformSuppressTapCycle = false
                pausePreviewAudioForDirectGesture()
                previewTransformGestureClipId?.let {
                    lockPreviewSelectionTo(it)
                    beginNativePreviewTransformGesture(
                        clipId = it,
                        mode = PreviewTransformGestureMode.DRAG,
                        event = event,
                    )
                }
                previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val clipId = previewTransformGestureClipId ?: selectedVideoClipId() ?: return false
                if (event.pointerCount < 2) return false
                notePreviewInteractionBusy(2200L)
                flushQueuedNativePreviewTransformGestureUpdate()
                previewTransformPinching = true
                previewTransformTouchArmed = false
                previewTransformMovedSinceDown = true
                previewTransformSuppressTapCycle = true
                pausePreviewAudioForDirectGesture()
                lockPreviewSelectionTo(clipId, windowMs = 1200L)
                beginNativePreviewTransformGesture(
                    clipId = clipId,
                    mode = PreviewTransformGestureMode.PINCH_ROTATE,
                    event = event,
                    allowRotation = false,
                )
                previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || previewTransformPinching) {
                    notePreviewInteractionBusy(1600L)
                    previewTransformPinching = true
                    previewTransformMovedSinceDown = true
                    previewTransformSuppressTapCycle = true
                    lockPreviewSelectionTo(previewTransformGestureClipId ?: selectedVideoClipId(), windowMs = 1200L)
                    if (isPlaying) {
                        applyPlaybackPreviewGestureUpdateNow(event)
                        return true
                    }
                    queueNativePreviewTransformGestureUpdate(event)
                    return true
                }
                if (!previewTransformTouchArmed) {
                    return false
                }
                notePreviewInteractionBusy(1400L)
                val totalDx = event.rawX - previewTransformStartX
                val totalDy = event.rawY - previewTransformStartY
                if (hypot(totalDx.toDouble(), totalDy.toDouble()) >= 1.5) {
                    previewTransformMovedSinceDown = true
                    previewTransformSuppressTapCycle = true
                }
                if (!previewTransformDragging &&
                    hypot(totalDx.toDouble(), totalDy.toDouble()) < previewTransformTouchSlop.toDouble()
                ) {
                    return true
                }
                val clipId = previewTransformGestureClipId ?: selectedVideoClipId() ?: return false
                lockPreviewSelectionTo(clipId, windowMs = 1200L)
                if (!previewTransformDragging) {
                    previewTransformDragging = true
                    previewTransformBase = currentClipPreviewTransform(clipId, allowNativeFetch = false)
                    previewGestureStartGeometry = resolvePreviewPointerGeometry(event)
                    previewPendingGestureGeometry = null
                    previewLastAppliedGestureGeometry = null
                    previewTransformStartX = event.rawX
                    previewTransformStartY = event.rawY
                    return true
                }
                if (isPlaying) {
                    applyPlaybackPreviewGestureUpdateNow(event)
                    return true
                }
                queueNativePreviewTransformGestureUpdate(event)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remainingPointer = remainingPointerGeometryAfterPointerUp(event)
                if (previewTransformPinching) {
                    flushQueuedNativePreviewTransformGestureUpdate()
                    endNativePreviewTransformGesture()
                }
                previewTransformPinching = false
                previewTransformTouchArmed = remainingPointer != null && event.pointerCount - 1 == 1
                previewTransformDragging = previewTransformTouchArmed
                previewTransformGestureClipId?.let { clipId ->
                    previewTransformBase = currentClipPreviewTransform(clipId, allowNativeFetch = false)
                    previewGestureStartGeometry = remainingPointer?.first ?: resolvePreviewPointerGeometry(event)
                }
                previewPendingGestureGeometry = null
                previewLastAppliedGestureGeometry = null
                val rawPosition = remainingPointer?.second
                previewTransformStartX = rawPosition?.first ?: event.rawX
                previewTransformStartY = rawPosition?.second ?: event.rawY
                previewTransformMovedSinceDown = true
                previewTransformSuppressTapCycle = true
                previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                    refreshPreviewCropStatus(updateFrame = false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finishedClipId = previewTransformGestureClipId
                if (previewTransformTouchArmed) {
                    flushQueuedNativePreviewTransformGestureUpdate()
                    endNativePreviewTransformGesture()
                }
                if (previewTransformPinching) {
                    flushQueuedNativePreviewTransformGestureUpdate()
                    endNativePreviewTransformGesture()
                }
                if (previewTransformMovedSinceDown) {
                    previewTransformSuppressTapCycle = true
                    previewSelectionLockUntilElapsedMs =
                        maxOf(previewSelectionLockUntilElapsedMs, SystemClock.elapsedRealtime() + 240L)
                    finishedClipId?.let { clipId ->
                        val finishedTransform = currentClipPreviewTransform(clipId, allowNativeFetch = false)
                        if (isPlaying) {
                            val keyframeTimeMs = currentPlayheadMs().coerceAtLeast(0L)
                            syncClipPreviewKeyframePoseIfNeeded(
                                clipId = clipId,
                                transform = finishedTransform,
                                persistToNative = false,
                                autoCreate = true,
                            ) { shouldPersistKeyframe ->
                                if (shouldPersistKeyframe) {
                                    mainHandler.postDelayed({
                                        addNativeClipKeyframeAsync(clipId, keyframeTimeMs, finishedTransform)
                                    }, 120L)
                                }
                            }
                        } else {
                            syncClipPreviewKeyframePoseIfNeeded(
                                clipId = clipId,
                                transform = finishedTransform,
                                persistToNative = true,
                                autoCreate = true,
                            )
                        }
                        if (isPlaying) {
                            commitPlaybackGestureTransformToNative(clipId)
                        }
                    }
                } else {
                    clearPreviewSelectionLock()
                    if (isPlaying) {
                        resetPlaybackGestureSurfaceTransform()
                    }
                }
                previewTransformDragging = false
                previewTransformPinching = false
                previewTransformGestureClipId = null
                previewTransformTouchArmed = false
                previewTransformStartX = 0f
                previewTransformStartY = 0f
                previewTransformMovedSinceDown = false
                resetPreviewGestureMetrics()
                if (!isPlaying) {
                    previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
                    refreshPreviewCropStatus()
                } else {
                    maybeRefreshPreviewCropStatusDuringGesture()
                }
                schedulePreviewAudioResumeAfterGesture()
                return true
            }
        }

        return false
    }

    private fun bindPreviewTransformTouchTarget(view: View?, frameOnly: Boolean) {
        view ?: return
        view.isClickable = true
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.setOnTouchListener { target, event ->
            val currentOwner = previewTransformTouchOwner
            val ownerGestureActive =
                previewTransformDragging ||
                    previewTransformPinching ||
                    previewTransformTouchArmed ||
                    previewResizeSession != null ||
                    previewTrimSession != null
            if (
                currentOwner != null &&
                currentOwner !== target &&
                (event.actionMasked != MotionEvent.ACTION_DOWN || ownerGestureActive)
            ) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                    target.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            var handled = if (frameOnly) {
                handlePreviewFrameTouch(event)
            } else {
                handlePreviewTransformTouch(event)
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                Log.d(
                    TAG,
                    "Preview touch action=${event.actionMasked} handled=$handled frameOnly=$frameOnly key=${selectedTimelineClipKey ?: "none"} kind=${selectedClipKind().name} raw=${event.rawX.roundToInt()},${event.rawY.roundToInt()}",
                )
            }
            if (!handled &&
                event.actionMasked == MotionEvent.ACTION_DOWN &&
                canStartPreviewTransformAt(event)
            ) {
                restorePreviewEditableSelection()
                syncPreviewCropModeUi()
                handled = if (frameOnly) {
                    handlePreviewFrameTouch(event)
                } else {
                    handlePreviewTransformTouch(event)
                }
                Log.d(
                    TAG,
                    "Preview touch fallback handled=$handled key=${selectedTimelineClipKey ?: "none"} kind=${selectedClipKind().name}",
                )
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (handled) {
                        previewTransformTouchOwner = target
                    } else if (previewTransformTouchOwner === target) {
                        previewTransformTouchOwner = null
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (previewTransformTouchOwner === target) {
                        previewTransformTouchOwner = null
                    }
                }
            }
            handled
        }
    }

    private fun handlePreviewTransformDoubleTap(event: MotionEvent): Boolean {
        if (!shouldShowDirectPreviewEdit()) return false
        val clipId = selectedVideoClipId() ?: return false
        val preview = previewView ?: return false
        val current = currentClipPreviewTransform(clipId)
        val centerX = preview.width * 0.5f
        val centerY = preview.height * 0.5f
        val nativeResult =
            preview.computeDoubleTapPreviewTransform(
                clipId = clipId,
                currentZoom = current.zoom,
                currentPanXPx = current.panXPx,
                currentPanYPx = current.panYPx,
                tapOffsetXPx = event.x - centerX,
                tapOffsetYPx = event.y - centerY,
            )
        if (nativeResult != null && nativeResult.size >= 3) {
            clipPreviewTransforms[clipId] =
                current.copy(
                    zoom = nativeResult[0],
                    panXPx = nativeResult[1],
                    panYPx = nativeResult[2],
                )
        }
        requestSelectedClipPreviewTransformApply(immediate = true)
        refreshPreviewCropStatus()
        noteUiButtonTap("crop_double_tap", "preview_crop")
        return true
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
        header.text = ""
        header.visibility = View.GONE
    }

    private fun showClipToolPending(toolName: String) {
        safeToast("$toolName next", Toast.LENGTH_SHORT)
    }

    private fun formatAutomationTime(timeMs: Long): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val tenths = (safeMs % 1000L) / 100L
        val totalSeconds = safeMs / 1000L
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
    }

    private fun formatTimelineClock(timeMs: Long, durationMs: Long = currentProjectDurationMs()): String {
        val safeCurrentMs = timeMs.coerceAtLeast(0L)
        val safeDurationMs = durationMs.coerceAtLeast(safeCurrentMs)
        return "${formatAutomationTime(safeCurrentMs)} / ${formatAutomationTime(safeDurationMs)}"
    }

    private fun updateTimelineTimeText(timeMs: Long) {
        timelineCurrentTimeText?.text = formatTimelineClock(timeMs)
    }

    private fun applyEditorPlayhead(
        timeMs: Long,
        continueAudio: Boolean = false,
    ) {
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        if (targetTimeMs <= PREVIEW_DUPLICATE_NATIVE_SEEK_TOLERANCE_MS) {
            previewLifecycleAnchorTimeMs = 0L
            previewLifecycleRestoreUntilElapsedMs = 0L
        }
        currentTimeMs = targetTimeMs
        allTextOverlays().forEach { overlay ->
            applyTextOverlayState(overlay)
            applyTextOverlayPose(overlay)
        }
        StickerClipStore.all().forEach { clip ->
            applyStickerLayerState(clip)
            if (clip.aiTrackKeyframes.isNotEmpty()) {
                applyStickerOverlayPose(clip)
            }
        }
        playbackController?.scrubTo(targetTimeMs)
        timelineManager?.updateDisplayedTime(targetTimeMs)
        multiTrackTimelineView?.setCurrentTimeMs(targetTimeMs, animate = false)
        activeCanvasTimelineView()?.setPlayheadMs(targetTimeMs)
        updateTimelineTimeText(targetTimeMs)
        applySelectedClipKeyframedPreviewTransform(targetTimeMs, immediate = true)
        syncPreviewAudioAt(targetTimeMs, continuePlaying = continueAudio && isPlaying)
        updateBottomToolbarMode()
    }

    private fun selectedClipTimeRange(): Pair<Long, Long>? {
        return when (selectedClipKind()) {
            ClipKind.VIDEO,
            ClipKind.OVERLAY,
            -> {
                val clipId = selectedVideoClipId() ?: return null
                val timing = selectedVideoTiming(clipId) ?: return null
                timing.first to (timing.first + timing.second.coerceAtLeast(1L))
            }
            ClipKind.TEXT -> {
                val overlay = selectedTextOverlayId()?.let(OverlayStore::get) ?: return null
                overlay.startTimeMs.toLong() to overlay.endTimeMs.toLong().coerceAtLeast(overlay.startTimeMs.toLong() + 1L)
            }
            ClipKind.STICKER -> {
                val sticker = StickerClipStore.all().firstOrNull { it.id == selectedStickerClipId() } ?: return null
                sticker.startTimeMs.toLong() to (sticker.startTimeMs + sticker.durationMs).toLong().coerceAtLeast(sticker.startTimeMs.toLong() + 1L)
            }
            ClipKind.AUDIO -> {
                val audio = selectedAudioClipId()?.let(AudioClipStore::get) ?: return null
                audio.startTimeMs to (audio.startTimeMs + audio.durationMs).coerceAtLeast(audio.startTimeMs + 1L)
            }
            ClipKind.NONE -> null
        }
    }

    private fun revealSelectedClipInPreview(force: Boolean = false) {
        if (isPlaying && !force) return
        val range = selectedClipTimeRange() ?: return
        val current = currentPlayheadMs().coerceAtLeast(0L)
        if (!force && current >= range.first && current < range.second) return
        applyEditorPlayhead(range.first, continueAudio = false)
    }

    private fun selectTimelineClipKey(
        key: String,
        revealPreview: Boolean = false,
        forceRevealPreview: Boolean = false,
    ): Boolean {
        val normalizedKey = normalizeTimelineSelectionKey(key) ?: key
        selectedTimelineClipKey = normalizedKey
        rememberPreviewEditableSelection(normalizedKey)
        parseTimelineManagedClipId(normalizedKey)?.let { managedId ->
            timelineManager?.selectClip(managedId)
        }
        multiTrackTimelineView?.setSelectedClipId(normalizedKey)
        multiTrackTimelineView?.revealClip(normalizedKey)
        activeCanvasTimelineView()?.setSelectedClipId(normalizedKey)
        val kind = selectedClipKind()
        if (kind == ClipKind.TEXT || kind == ClipKind.STICKER) {
            setPreviewCropMode(false)
        }
        updateBottomToolbarMode()
        if (revealPreview) {
            revealSelectedClipInPreview(force = forceRevealPreview)
        }
        syncPreviewProOverlayUi()
        return true
    }

    private fun selectLatestClipForTrack(trackType: TrackType): Boolean {
        val selectionKey = resolveSelectionKeyForTrack(trackType, currentPlayheadMs().coerceAtLeast(0L))
            ?: return false
        val selected = selectTimelineClipKey(selectionKey, revealPreview = true, forceRevealPreview = false)
        Log.i(
            TAG,
            "[Automation] select_track_clip track=${trackType.name} key=$selectionKey selected=$selected playhead=${currentPlayheadMs()}",
        )
        return selected
    }

    private fun selectPreviewActiveClipForTrack(trackType: TrackType): Boolean {
        val selectionKey = resolveSelectionKeyForTrack(trackType, currentPlayheadMs().coerceAtLeast(0L))
            ?: return false
        return selectTimelineClipKey(selectionKey, revealPreview = true, forceRevealPreview = false)
    }

    private fun resolveSelectionKeyForTrack(trackType: TrackType, playheadMs: Long): String? {
        return when (trackType) {
            TrackType.VIDEO,
            TrackType.OVERLAY,
            TrackType.LAYER,
            -> resolveNativeSelectionKeyForTrack(trackType, playheadMs)
            TrackType.TEXT -> resolveTextSelectionKeyForTrack(playheadMs)
            TrackType.AUDIO -> resolveAudioSelectionKeyForTrack(playheadMs)
        }
    }

    private fun resolveNativeSelectionKeyForTrack(trackType: TrackType, playheadMs: Long): String? {
        val activeClipId =
            resolvePreviewEditableClipCandidatesAtPlayhead(playheadMs)
                .firstOrNull { nativeClipTrackType[it] == trackType }
        if (activeClipId != null) {
            return selectionKeyForNativeClipId(activeClipId)
        }
        return nativeClipTrackType.keys
            .filter { nativeClipTrackType[it] == trackType }
            .maxWithOrNull(
                compareBy<Int> { nativeClipZOrder[it] ?: 0 }
                    .thenBy { nativeClipStartMs[it] ?: 0L }
                    .thenBy { it },
            )
            ?.let(::selectionKeyForNativeClipId)
    }

    private fun resolveTextSelectionKeyForTrack(playheadMs: Long): String? {
        val activeText =
            allTextOverlays()
                .asSequence()
                .filter { overlay ->
                    overlay.visible &&
                        playheadMs in overlay.startTimeMs.toLong() until overlay.endTimeMs.toLong().coerceAtLeast(overlay.startTimeMs.toLong() + 1L)
                }
                .maxWithOrNull(compareBy<TextOverlay> { it.layerIndex }.thenBy { it.id })
                ?.let { "text-${it.id}" }
        if (activeText != null) return activeText
        val activeSticker =
            StickerClipStore.all()
                .asSequence()
                .filter { clip ->
                    clip.visible &&
                        playheadMs in clip.startTimeMs.toLong() until (clip.startTimeMs.toLong() + clip.durationMs.toLong().coerceAtLeast(1L))
                }
                .maxWithOrNull(compareBy<StickerClip> { it.layerIndex }.thenBy { it.id })
                ?.let { "sticker-${it.id}" }
        if (activeSticker != null) return activeSticker
        return allTextOverlays()
            .maxWithOrNull(compareBy<TextOverlay> { it.layerIndex }.thenBy { it.id })
            ?.let { "text-${it.id}" }
            ?: StickerClipStore.all()
                .maxWithOrNull(compareBy<StickerClip> { it.layerIndex }.thenBy { it.id })
                ?.let { "sticker-${it.id}" }
    }

    private fun resolveAudioSelectionKeyForTrack(playheadMs: Long): String? {
        val activeAudio =
            AudioClipStore.all()
                .asSequence()
                .filter { clip ->
                    clip.visible &&
                        playheadMs in clip.startTimeMs until (clip.startTimeMs + clip.durationMs.coerceAtLeast(1L))
                }
                .maxWithOrNull(compareBy<AudioClip> { it.layerIndex }.thenBy { it.id })
                ?.let { "audio-${it.id}" }
        if (activeAudio != null) return activeAudio
        return AudioClipStore.all()
            .maxWithOrNull(compareBy<AudioClip> { it.layerIndex }.thenBy { it.id })
            ?.let { "audio-${it.id}" }
    }

    private fun scaleSelectedClipForPreview(factor: Float): Boolean {
        val safeFactor = factor.coerceIn(0.5f, 2.5f)
        return when (selectedClipKind()) {
            ClipKind.VIDEO,
            ClipKind.OVERLAY,
            -> {
                val clipId = selectedVideoClipId() ?: return false
                val zoomRange = resolvePreviewMinZoom(clipId)..resolvePreviewMaxZoom(clipId)
                val tunedFactor =
                    if (safeFactor >= 1.0f) {
                        1.0f + ((safeFactor - 1.0f) * 1.12f)
                    } else {
                        1.0f - ((1.0f - safeFactor) * 1.55f)
                    }.coerceIn(PREVIEW_OBJECT_MIN_ZOOM, PREVIEW_OBJECT_MAX_ZOOM)
                updateSelectedVideoPreviewTransform { current ->
                    current.copy(zoom = (current.zoom * tunedFactor).coerceIn(zoomRange.start, zoomRange.endInclusive))
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return false
                val overlay = OverlayStore.get(overlayId) ?: return false
                overlay.scale = (overlay.scale * safeFactor).coerceIn(0.35f, 6.0f)
                applyTextOverlayState(overlay)
                applyTextOverlayPose(overlay)
                true
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return false
                val clip = StickerClipStore.all().firstOrNull { it.id == stickerId } ?: return false
                clip.scale = (clip.scale * safeFactor).coerceIn(0.35f, 6.0f)
                applyStickerLayerState(clip)
                applyStickerOverlayPose(clip)
                true
            }
            else -> false
        }
    }

    private fun setAutomationPlayhead(timeMs: Long) {
        val targetTimeMs = timeMs.coerceAtLeast(0L)
        applyEditorPlayhead(targetTimeMs, continueAudio = false)
        if (!isPlaying) {
            playbackController?.refreshPausedPreviewAt(targetTimeMs)
            mainHandler.postDelayed({
                if (!isPlaying) {
                    playbackController?.refreshPausedPreviewAt(targetTimeMs)
                }
            }, 90L)
        }
        Log.i(TAG, "[Automation] set_playhead_ms=$targetTimeMs")
    }

    private fun rawCurrentPlayheadMs(): Long {
        val canvasMs = activeCanvasTimelineView()?.getPlayheadMs()
        if (canvasMs != null && canvasMs >= 0L) return canvasMs
        return activeMultiTrackTimelineView()?.currentTimeMs() ?: currentTimeMs
    }

    private fun timelineTrackEndTimeMs(trackType: TrackType): Long {
        var endTimeMs = 0L
        nativeClipTrackType.forEach { (clipId, clipTrackType) ->
            if (clipTrackType == trackType) {
                val startMs = nativeClipStartMs[clipId] ?: videoClipTimingOverrides[clipId]?.startTimeMs ?: 0L
                val durationMs = nativeClipDurationMs[clipId] ?: videoClipTimingOverrides[clipId]?.durationMs ?: 0L
                endTimeMs = maxOf(endTimeMs, startMs + durationMs.coerceAtLeast(0L))
            }
        }
        if (trackType == TrackType.TEXT) {
            OverlayStore.all().forEach { overlay ->
                endTimeMs = maxOf(endTimeMs, overlay.endTimeMs.toLong().coerceAtLeast(0L))
            }
        }
        if (trackType == TrackType.OVERLAY) {
            StickerClipStore.all().forEach { clip ->
                val startMs = clip.startTimeMs.toLong().coerceAtLeast(0L)
                val durationMs = clip.durationMs.toLong().coerceAtLeast(1L)
                endTimeMs = maxOf(endTimeMs, startMs + durationMs)
            }
        }
        return endTimeMs
    }

    private fun defaultAudioImportStartTimeMs(): Long {
        return currentPlayheadMs().coerceAtLeast(0L)
    }

    private fun currentPlayheadMs(): Long {
        return resolveLifecycleStablePlayhead(rawCurrentPlayheadMs())
    }

    private fun applyTrackVisibilityChange(trackType: TrackType, isVisible: Boolean) {
        trackVisibilityOverrides[trackType] = isVisible
        if (trackType == TrackType.OVERLAY) {
            trackVisibilityOverrides[TrackType.LAYER] = isVisible
        }
        when (trackType) {
            TrackType.VIDEO, TrackType.OVERLAY, TrackType.LAYER -> {
                val affectedTrackTypes =
                    if (trackType == TrackType.OVERLAY) setOf(TrackType.OVERLAY, TrackType.LAYER) else setOf(trackType)
                nativeClipTrackType
                    .filterValues { it in affectedTrackTypes }
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
        syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
    }

    private fun applyTrackLockChange(trackType: TrackType, isLocked: Boolean) {
        trackLockedOverrides[trackType] = isLocked
        if (trackType == TrackType.OVERLAY) {
            trackLockedOverrides[TrackType.LAYER] = isLocked
        }
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
                ClipKind.VIDEO -> clipToolbarItems
                    .map { it.buttonId }
                    .filterNot { it == R.id.clipAiTrackButton }
                    .toSet()
                ClipKind.OVERLAY -> setOf(
                    R.id.clipDeleteButton,
                    R.id.clipSplitButton,
                    R.id.clipAiCaptionButton,
                    R.id.clipVolumeButton,
                    R.id.clipSpeedButton,
                    R.id.clipFilterButton,
                    R.id.clipTrimButton,
                    R.id.clipPanZoomButton,
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
                    R.id.clipAiCaptionButton,
                    R.id.clipVolumeButton,
                    R.id.clipBrightnessButton,
                    R.id.clipSpeedButton,
                    R.id.clipFilterButton,
                    R.id.clipGraphicsButton,
                    R.id.clipCutoutButton,
                    R.id.clipTrimButton,
                    R.id.clipReplaceButton,
                    R.id.clipDuplicateButton,
                    R.id.clipAddLayerButton,
                    R.id.clipKeyframeButton,
                    R.id.clipReverseButton,
                    R.id.clipDuckingButton,
                )
                ClipKind.TEXT -> setOf(
                    R.id.clipDeleteButton,
                    R.id.clipSplitButton,
                    R.id.clipAiTrackButton,
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
                    R.id.clipAiTrackButton,
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
                    labelOverrides[R.id.clipAiCaptionLabel] = "Caption"
                    labelOverrides[R.id.clipVolumeLabel] = "Gain"
                    labelOverrides[R.id.clipBrightnessLabel] = "Fade"
                    labelOverrides[R.id.clipSpeedLabel] = "Stretch"
                    labelOverrides[R.id.clipFilterLabel] = "FX"
                    labelOverrides[R.id.clipGraphicsLabel] = "Studio"
                    labelOverrides[R.id.clipAddLayerLabel] = "Layer"
                    labelOverrides[R.id.clipKeyframeLabel] = "Keyframe"
                    labelOverrides[R.id.clipReverseLabel] = "Reverse"
                    labelOverrides[R.id.clipDuckingLabel] = "Ducking"
                    labelOverrides[R.id.clipCutoutLabel] = "AI Cut"
                }
                ClipKind.TEXT -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Opacity"
                    labelOverrides[R.id.clipSpeedLabel] = "Duration"
                    labelOverrides[R.id.clipPanZoomLabel] = "Position"
                    labelOverrides[R.id.clipFilterLabel] = "Palette"
                    labelOverrides[R.id.clipGraphicsLabel] = "Studio"
                    labelOverrides[R.id.clipBrightnessLabel] = "Fade"
                    labelOverrides[R.id.clipReplaceLabel] = "Text Pro"
                    labelOverrides[R.id.clipRotateMirrorLabel] = "Rotate"
                    labelOverrides[R.id.clipKeyframeLabel] = "Keyframe"
                    labelOverrides[R.id.clipAiTrackLabel] = "AI Track"
                }
                ClipKind.STICKER -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Opacity"
                    labelOverrides[R.id.clipSpeedLabel] = "Duration"
                    labelOverrides[R.id.clipPanZoomLabel] = "Position"
                    labelOverrides[R.id.clipGraphicsLabel] = "Change"
                    labelOverrides[R.id.clipBrightnessLabel] = "Fade"
                    labelOverrides[R.id.clipReplaceLabel] = "Replace"
                    labelOverrides[R.id.clipKeyframeLabel] = "Keyframe"
                    labelOverrides[R.id.clipAiTrackLabel] = "AI Track"
                }
                ClipKind.OVERLAY -> {
                    labelOverrides[R.id.clipVolumeLabel] = "Volume"
                    labelOverrides[R.id.clipPanZoomLabel] = "Pan/Zoom"
                    labelOverrides[R.id.clipBrightnessLabel] = "Adjust"
                    labelOverrides[R.id.clipChromaKeyLabel] = "AI Matte"
                    labelOverrides[R.id.clipAiCaptionLabel] = "Caption"
                    labelOverrides[R.id.clipCutoutLabel] = "AI Cut"
                    labelOverrides[R.id.clipGraphicsLabel] = "Add"
                    labelOverrides[R.id.clipAddLayerLabel] = "Layer"
                }
                ClipKind.VIDEO -> {
                    labelOverrides[R.id.clipAiCaptionLabel] = "Caption"
                    labelOverrides[R.id.clipPanZoomLabel] = "Pan/Zoom"
                    labelOverrides[R.id.clipChromaKeyLabel] = "AI Matte"
                    labelOverrides[R.id.clipCutoutLabel] = "AI Cut"
                    labelOverrides[R.id.clipGraphicsLabel] = "Add"
                    labelOverrides[R.id.clipAddLayerLabel] = "Layer"
                }
                else -> Unit
            }

            val featuredButtons = when (kind) {
                ClipKind.VIDEO -> setOf(
                    R.id.clipGraphicsButton,
                    R.id.clipAddLayerButton,
                    R.id.clipAiCaptionButton,
                    R.id.clipPanZoomButton,
                    R.id.clipTrimButton,
                    R.id.clipChromaKeyButton,
                    R.id.clipTransitionButton,
                    R.id.clipFilterButton,
                    R.id.clipBrightnessButton,
                    R.id.clipVolumeButton,
                    R.id.clipCutoutButton,
                )
                ClipKind.OVERLAY -> setOf(
                    R.id.clipGraphicsButton,
                    R.id.clipAddLayerButton,
                    R.id.clipAiCaptionButton,
                    R.id.clipVolumeButton,
                    R.id.clipPanZoomButton,
                    R.id.clipTrimButton,
                    R.id.clipChromaKeyButton,
                    R.id.clipCutoutButton,
                )
                ClipKind.AUDIO -> setOf(
                    R.id.clipAiCaptionButton,
                    R.id.clipVolumeButton,
                    R.id.clipBrightnessButton,
                    R.id.clipSpeedButton,
                    R.id.clipFilterButton,
                    R.id.clipGraphicsButton,
                    R.id.clipKeyframeButton,
                    R.id.clipDuckingButton,
                    R.id.clipCutoutButton,
                )
                ClipKind.TEXT -> setOf(
                    R.id.clipAiTrackButton,
                    R.id.clipGraphicsButton,
                    R.id.clipPanZoomButton,
                    R.id.clipReplaceButton,
                    R.id.clipKeyframeButton,
                )
                ClipKind.STICKER -> setOf(
                    R.id.clipAiTrackButton,
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
                val isCropModeButton =
                    previewPanZoomControlsVisible &&
                        item.buttonId == R.id.clipPanZoomButton &&
                        (kind == ClipKind.VIDEO || kind == ClipKind.OVERLAY)
                if (button != null) {
                    when {
                        isDestructive -> {
                            button.setBackgroundResource(R.drawable.toolbar_item_danger_background)
                            val dangerColor = Color.parseColor("#FF8C86")
                            icon?.setColorFilter(dangerColor)
                            label?.setTextColor(dangerColor)
                            button.alpha = 1f
                        }
                        isCropModeButton -> {
                            button.setBackgroundResource(R.drawable.toolbar_item_active_background)
                            icon?.setColorFilter(resources.getColor(R.color.accent_blue))
                            label?.setTextColor(resources.getColor(R.color.accent_blue))
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

            clipToolbarScrollView?.post restoreToolbarScroll@{
                val scrollView = clipToolbarScrollView ?: return@restoreToolbarScroll
                val container = scrollView.getChildAt(0) ?: return@restoreToolbarScroll
                val maxScroll = (container.width - scrollView.width).coerceAtLeast(0)
                val targetScroll = clipToolbarScrollX.coerceIn(0, maxScroll)
                if (scrollView.scrollX != targetScroll) {
                    scrollView.scrollTo(targetScroll, 0)
                }
            }
            if (kind == ClipKind.VIDEO || kind == ClipKind.OVERLAY) {
                syncEffectSlidersForClip(resolveSelectedVisualClipId(syncSelectionIfNeeded = false))
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
                val initialGain = videoClipGainOverrides[clipId] ?: 1f

                ModernSheet.showModal(
                    context = this,
                    title = "$clipLabel Volume",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Volume applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        videoClipGainOverrides[clipId] = initialGain
                        execCmd("SET_CLIP_VOLUME", mapOf("clipId" to clipId, "volume" to initialGain.toDouble())) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    },
                ) {
                    section("Volume Level")
                    sliderWithBubble(
                        label = "Volume",
                        min = 0f,
                        max = 200f,
                        value = initialGain * 100f,
                        unit = "%",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        val gain = v / 100f
                        videoClipGainOverrides[clipId] = gain
                        execCmd("SET_CLIP_VOLUME", mapOf("clipId" to clipId, "volume" to gain.toDouble())) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    }
                    divider()
                    section("Quick Levels")
                    chips("Presets", listOf("Mute 0%", "Soft 50%", "Standard 100%", "Boost 150%", "Max 200%")) { _, opt ->
                        val gain = when (opt) {
                            "Mute 0%" -> 0f
                            "Soft 50%" -> 0.5f
                            "Standard 100%" -> 1.0f
                            "Boost 150%" -> 1.5f
                            "Max 200%" -> 2.0f
                            else -> 1.0f
                        }
                        videoClipGainOverrides[clipId] = gain
                        execCmd("SET_CLIP_VOLUME", mapOf("clipId" to clipId, "volume" to gain.toDouble())) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val audioClip = AudioClipStore.get(audioId) ?: return
                val initialGain = audioClipGainOverrides[audioId] ?: audioClip.gain

                ModernSheet.showModal(
                    context = this,
                    title = "Audio Volume & Gain",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Audio gain applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        audioClipGainOverrides[audioId] = initialGain
                        audioClip.gain = initialGain
                        audioClip.muted = initialGain <= 0.001f
                        execCmd(
                            "SET_CLIP_VOLUME",
                            mapOf("clipId" to audioId, "volume" to initialGain.toDouble()),
                        ) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    },
                ) {
                    section("Audio Gain")
                    sliderWithBubble(
                        label = "Gain Level",
                        min = 0f,
                        max = 200f,
                        value = initialGain * 100f,
                        unit = "%",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        val gain = v / 100f
                        audioClipGainOverrides[audioId] = gain
                        audioClip.gain = gain
                        audioClip.muted = gain <= 0.001f
                        execCmd(
                            "SET_CLIP_VOLUME",
                            mapOf("clipId" to audioId, "volume" to gain.toDouble()),
                        ) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    }
                    divider()
                    chips("Quick Gain", listOf("Mute 0%", "50%", "100%", "150%", "200%")) { _, opt ->
                        val gain = when (opt) {
                            "Mute 0%" -> 0f
                            "50%" -> 0.5f
                            "100%" -> 1.0f
                            "150%" -> 1.5f
                            "200%" -> 2.0f
                            else -> 1.0f
                        }
                        audioClipGainOverrides[audioId] = gain
                        audioClip.gain = gain
                        audioClip.muted = gain <= 0.001f
                        execCmd(
                            "SET_CLIP_VOLUME",
                            mapOf("clipId" to audioId, "volume" to gain.toDouble()),
                        ) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                val initialOpacity = overlay.opacity

                ModernSheet.showModal(
                    context = this,
                    title = "Text Opacity",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Text opacity applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        overlay.opacity = initialOpacity
                        previewView?.updateTextOverlayOpacity(overlay.id, initialOpacity, 0, 0)
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                    },
                ) {
                    sliderWithBubble(
                        label = "Opacity",
                        min = 0f,
                        max = 100f,
                        value = initialOpacity * 100f,
                        unit = "%",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        overlay.opacity = v / 100f
                        previewView?.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                val initialOpacity = clip.opacity

                ModernSheet.showModal(
                    context = this,
                    title = "Overlay Opacity",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Overlay opacity applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        clip.opacity = initialOpacity
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                    },
                ) {
                    sliderWithBubble(
                        label = "Opacity",
                        min = 0f,
                        max = 100f,
                        value = initialOpacity * 100f,
                        unit = "%",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        clip.opacity = v / 100f
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                    }
                }
            }
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun pausePlaybackForSpeedSheet() {
        if (isPlaying) {
            isPlaying = false
            playbackController?.pauseRendering()
        }
    }

    private fun applyNativeClipSpeedChange(
        clipId: Int,
        speed: Float,
        curveProfile: String = "linear",
    ) {
        val safeSpeed = speed.coerceIn(0.10f, 6.0f)
        execCmd("SPEED", mapOf("clipId" to clipId, "speed" to safeSpeed.toDouble())) {
            execCmd(
                "CURVE_SPEED",
                mapOf(
                    "clipId" to clipId,
                    "profile" to curveProfile,
                    "strength" to safeSpeed.toDouble(),
                )
            ) {
                nativeClipPlaybackSpeed[clipId] = safeSpeed
                nativeClipCurveSpeedProfile[clipId] = curveProfile
                nativeClipCurveSpeedStrength[clipId] = safeSpeed
                if (curveProfile == "linear") {
                    videoClipCurveProfiles.remove(clipId)
                } else {
                    videoClipCurveProfiles[clipId] = curveProfile
                }
                lastLayoutFetchMs = 0L
                syncTimelineShellFromNative(selectedClipId = clipId)
                revealSelectedClipInPreview(force = true)
                refreshPreviewAtPlayhead()
            }
        }
    }

    private fun applyAudioClipSpeedChange(audioId: Int, speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        execCmd("SPEED", mapOf("clipId" to audioId, "speed" to safeSpeed.toDouble())) {
            nativeClipPlaybackSpeed[audioId] = safeSpeed
            lastLayoutFetchMs = 0L
            syncTimelineShellFromNative(selectedClipId = audioId)
            refreshPreviewAtPlayhead()
        }
    }

    private fun applyTextOverlaySpeedChange(overlay: TextOverlay, baseDurationMs: Int, speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val newDurationMs = (baseDurationMs / safeSpeed).roundToInt().coerceAtLeast(150)
        overlay.endTimeMs = overlay.startTimeMs + newDurationMs
        applyTextOverlayState(overlay)
        applyTextOverlayPose(overlay)
        refreshMainTimelineTracks()
        refreshPreviewAtPlayhead(resyncAudio = false)
    }

    private fun applyStickerClipSpeedChange(clip: StickerClip, baseDurationMs: Int, speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        clip.durationMs = (baseDurationMs / safeSpeed).roundToInt().coerceAtLeast(150)
        applyStickerLayerState(clip)
        applyStickerOverlayPose(clip)
        refreshMainTimelineTracks()
        refreshPreviewAtPlayhead(resyncAudio = false)
    }

    private fun isReverseEnabledForClip(clipId: Int): Boolean {
        return videoClipReverseOverrides[clipId] ?: nativeClipReversePlayback[clipId] ?: false
    }

    private fun applyNativeClipReverseChange(clipId: Int, enabled: Boolean): Boolean {
        execCmd(
            action = "REVERSE_CLIP",
            params = mapOf(
                "clipId" to clipId,
                "enabled" to enabled,
            )
        ) { result ->
            if (result.success) {
                videoClipReverseOverrides[clipId] = enabled
                nativeClipReversePlayback[clipId] = enabled
                lastLayoutFetchMs = 0L
                syncTimelineShellFromNative(selectedClipId = clipId)
                revealSelectedClipInPreview(force = true)
                refreshPreviewAtPlayhead()
            }
        }
        return true
    }

    private fun performSelectedClipSpeedAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: run {
                    safeToast("Select clip first", Toast.LENGTH_SHORT)
                    return
                }
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    showStillImageDurationSheet(clipId, clipLabel)
                    return
                }
                pausePlaybackForSpeedSheet()
                val initialSpeed = nativeClipPlaybackSpeed[clipId] ?: 1f
                val initialCurve = nativeClipCurveSpeedProfile[clipId] ?: "linear"
                val curveProfiles = listOf("linear", "ease_in", "ease_out", "speed_ramp", "hyperlapse")
                val curveLabels = listOf("Linear", "Ease In", "Ease Out", "Ramp", "Hyper")
                val curveSpeeds = listOf(1.0f, 0.80f, 1.15f, 1.45f, 2.0f)
                var keepPitch = true

                ModernSheet.showModal(
                    context = this,
                    title = "$clipLabel Speed",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Speed applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        applyNativeClipSpeedChange(clipId, initialSpeed, curveProfile = initialCurve)
                    },
                ) {
                    tabs(listOf("Standard", "Curve"), selected = 0) { _ -> }
                    toggle("Keep Audio Pitch", keepPitch) { keepPitch = it }
                    divider()
                    section("Speed Presets")
                    chips("Speed", listOf("0.25x", "0.5x", "0.75x", "1.0x", "1.5x", "2.0x", "3.0x", "5.0x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        applyNativeClipSpeedChange(clipId, speed, curveProfile = "linear")
                    }
                    sliderWithBubble(
                        label = "Speed Multiplier",
                        min = 0.10f,
                        max = 6.0f,
                        value = initialSpeed,
                        unit = "x",
                        format = { "%.2f".format(it) },
                    ) { speed ->
                        applyNativeClipSpeedChange(clipId, speed, curveProfile = "linear")
                    }
                    divider()
                    section("Motion Curves")
                    chips(
                        "Curve Profiles",
                        curveLabels,
                        selected = curveProfiles.indexOf(initialCurve).coerceAtLeast(0),
                    ) { i, _ ->
                        applyNativeClipSpeedChange(clipId, curveSpeeds[i], curveProfile = curveProfiles[i])
                        safeToast("${curveLabels[i]} curve applied", Toast.LENGTH_SHORT)
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                pausePlaybackForSpeedSheet()
                val currentSpeed = nativeClipPlaybackSpeed[audioId] ?: 1f
                ModernSheet.show(this, "Audio Speed") {
                    chips("Slow / Fast", listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "3x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        applyAudioClipSpeedChange(audioId, speed)
                    }
                    slider("Preview Speed", 0.25f, 4f, currentSpeed, { "%.2fx".format(it) }) { speed ->
                        applyAudioClipSpeedChange(audioId, speed)
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlay = resolveSelectedTextOverlayForStudio(selectIfNeeded = true) ?: return
                pausePlaybackForSpeedSheet()
                val baseDurationMs = (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(150)
                ModernSheet.show(this, "Text Speed") {
                    chips("Presets", listOf("0.5x", "0.75x", "1x", "1.5x", "2x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        applyTextOverlaySpeedChange(overlay, baseDurationMs, speed)
                    }
                    slider("Duration Speed", 0.25f, 4f, 1f, { "%.2fx".format(it) }) { speed ->
                        applyTextOverlaySpeedChange(overlay, baseDurationMs, speed)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                pausePlaybackForSpeedSheet()
                val baseDurationMs = clip.durationMs.coerceAtLeast(150)
                ModernSheet.show(this, "Overlay Speed") {
                    chips("Presets", listOf("0.5x", "0.75x", "1x", "1.5x", "2x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        applyStickerClipSpeedChange(clip, baseDurationMs, speed)
                    }
                    slider("Duration Speed", 0.25f, 4f, 1f, { "%.2fx".format(it) }) { speed ->
                        applyStickerClipSpeedChange(clip, baseDurationMs, speed)
                    }
                }
            }
            else -> safeToast("Select a clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun enableSelectedClipPanZoomMode(showToast: Boolean = true): Int? {
        val clipId =
            resolveSelectedVisualClipId(
                syncSelectionIfNeeded = true,
                preferredTrackType = selectedTrackType(),
            ) ?: run {
                safeToast("Select video or layer clip first", Toast.LENGTH_SHORT)
                return null
            }
        val trackType = nativeClipTrackType[clipId] ?: selectedTrackType() ?: TrackType.VIDEO
        if (!ensureTrackEditable(trackType, "pan/zoom")) return null

        val selectionKey = selectionKeyForNativeClipId(clipId)
        selectedTimelineClipKey = selectionKey
        timelineManager?.selectClip(clipId)
        multiTrackTimelineView?.setSelectedClipId(selectionKey)
        activeCanvasTimelineView()?.setSelectedClipId(selectionKey)
        rememberPreviewEditableSelection(selectionKey)
        lockPreviewSelectionTo(clipId, windowMs = 2_400L)
        previewSelectionStickyUntilElapsedMs =
            maxOf(previewSelectionStickyUntilElapsedMs, SystemClock.elapsedRealtime() + 30_000L)
        previewPanZoomControlsVisible = true
        previewSafeAreaVisible = true
        previewGridVisible = true
        revealSelectedClipInPreview(force = true)
        setPreviewCropMode(true)
        syncPreviewCropModeUi()
        syncPreviewProOverlayUi()
        updateBottomToolbarMode()
        findViewById<View?>(R.id.clipPanZoomButton)?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (showToast) {
            safeToast("Pan/Zoom on: preview par drag, pinch, double tap. Push/Pull presets upar hain.", Toast.LENGTH_SHORT)
        }
        return clipId
    }

    private fun previewViewportSizePx(): Pair<Float, Float> {
        val width =
            (previewView?.width ?: previewCropOverlayView?.width ?: previewViewportFrame?.width ?: previewContainerView?.width ?: 1)
                .coerceAtLeast(1)
                .toFloat()
        val height =
            (previewView?.height ?: previewCropOverlayView?.height ?: previewViewportFrame?.height ?: previewContainerView?.height ?: 1)
                .coerceAtLeast(1)
                .toFloat()
        return width to height
    }

    private fun resetSelectedClipPanZoom(clipId: Int) {
        clearClipPreviewKeyframes(clipId)
        runCatching { clearNativeClipKeyframes(clipId) }
        clipPreviewTransforms[clipId] = ClipPreviewTransform()
        nativeAppliedClipPreviewTransforms.remove(clipId)
        previewView?.clearClipPreviewTransform(clipId)
        requestSelectedClipPreviewTransformApply(immediate = true)
        refreshMainTimelineTracks()
        refreshPreviewCropStatus()
        syncPreviewProOverlayUi()
    }

    private fun applySelectedClipPanZoomKeyframedPreset(
        clipId: Int,
        label: String,
        startTransform: ClipPreviewTransform,
        endTransform: ClipPreviewTransform,
    ): Boolean {
        val timing = selectedVideoTiming(clipId) ?: return false
        val startMs = timing.first.coerceAtLeast(0L)
        val endMs = (timing.first + timing.second - 1L).coerceAtLeast(startMs + 1L)
        val normalizedStart = normalizeClipPreviewTransform(clipId, startTransform)
        val normalizedEnd = normalizeClipPreviewTransform(clipId, endTransform)
        clearClipPreviewKeyframes(clipId)
        runCatching { clearNativeClipKeyframes(clipId) }
        upsertClipPreviewKeyframe(clipId, startMs, normalizedStart)
        upsertClipPreviewKeyframe(clipId, endMs, normalizedEnd)
        addNativeClipKeyframeAsync(clipId, startMs, normalizedStart)
        addNativeClipKeyframeAsync(clipId, endMs, normalizedEnd)
        val sampled = sampleClipPreviewKeyframeTransform(clipId, currentPlayheadMs().coerceAtLeast(0L)) ?: normalizedStart
        clipPreviewTransforms[clipId] = sampled
        requestSelectedClipPreviewTransformApply(immediate = true)
        refreshMainTimelineTracks()
        refreshPreviewAtPlayhead(resyncAudio = false)
        refreshPreviewCropStatus()
        syncPreviewProOverlayUi()
        safeToast("$label Pan/Zoom applied", Toast.LENGTH_SHORT)
        return true
    }

    private fun applySelectedClipPanZoomPreset(preset: String): Boolean {
        val clipId = enableSelectedClipPanZoomMode(showToast = false) ?: return false
        val (viewportWidthPx, viewportHeightPx) = previewViewportSizePx()
        val minZoom = resolvePreviewMinZoom(clipId)
        val fillZoom = maxOf(minZoom, 1.18f)
        val cinematicZoom = maxOf(minZoom, 1.34f)
        val panXPx = maxOf(48f, viewportWidthPx * 0.16f)
        val panYPx = maxOf(36f, viewportHeightPx * 0.11f)
        return when (preset.lowercase(Locale.US)) {
            "reset" -> {
                resetSelectedClipPanZoom(clipId)
                safeToast("Pan/Zoom reset", Toast.LENGTH_SHORT)
                true
            }
            "fit" -> {
                updateSelectedVideoPreviewTransform(immediate = true) { fitPreviewTransformForClip(clipId) }
                refreshPreviewCropStatus()
                syncPreviewProOverlayUi()
                safeToast("Fit full frame", Toast.LENGTH_SHORT)
                true
            }
            "fill" -> {
                updateSelectedVideoPreviewTransform(immediate = true) { current ->
                    current.copy(zoom = maxOf(current.zoom, fillZoom), panXPx = 0f, panYPx = 0f)
                }
                refreshPreviewCropStatus()
                syncPreviewProOverlayUi()
                true
            }
            "push_in",
            "push",
            -> applySelectedClipPanZoomKeyframedPreset(
                clipId = clipId,
                label = "Push In",
                startTransform = ClipPreviewTransform(zoom = maxOf(minZoom, 1.0f), panXPx = -panXPx * 0.24f, panYPx = 0f),
                endTransform = ClipPreviewTransform(zoom = cinematicZoom, panXPx = panXPx * 0.28f, panYPx = -panYPx * 0.18f),
            )
            "pull_out",
            "pull",
            -> applySelectedClipPanZoomKeyframedPreset(
                clipId = clipId,
                label = "Pull Out",
                startTransform = ClipPreviewTransform(zoom = cinematicZoom, panXPx = panXPx * 0.22f, panYPx = -panYPx * 0.14f),
                endTransform = ClipPreviewTransform(zoom = maxOf(minZoom, 1.0f), panXPx = 0f, panYPx = 0f),
            )
            "left_right",
            "slide",
            -> applySelectedClipPanZoomKeyframedPreset(
                clipId = clipId,
                label = "Slide",
                startTransform = ClipPreviewTransform(zoom = maxOf(minZoom, 1.26f), panXPx = -panXPx, panYPx = 0f),
                endTransform = ClipPreviewTransform(zoom = maxOf(minZoom, 1.26f), panXPx = panXPx, panYPx = 0f),
            )
            else -> {
                safeToast("Unknown Pan/Zoom preset", Toast.LENGTH_SHORT)
                false
            }
        }
    }

    private fun performSelectedClipPanZoomAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                enableSelectedClipPanZoomMode(showToast = true)
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
        execCmd(
            action = "EXTRACT_AUDIO",
            params = mapOf("clipId" to clipId)
        ) { result ->
            if (!result.success) {
                safeToast("Extract audio failed", Toast.LENGTH_SHORT)
            } else {
                val sourcePath = result.data.optString("sourcePath")
                if (sourcePath.isBlank()) {
                    safeToast("Audio source unavailable", Toast.LENGTH_SHORT)
                } else {
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
            }
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
                showSelectedTextStudio()
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
                        Toast.makeText(this@VideoEditorActivity, "Overlay replaced", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@VideoEditorActivity, "Select clip first", Toast.LENGTH_SHORT).show()
                    return
                }
                val clipLabel = selectedNativeClipLabel()
                execCmd(
                    action = "DUPLICATE_CLIP",
                    params = mapOf("clipId" to clipId)
                ) { result ->
                    if (result.success) {
                        val newClipId = result.data.optInt("newClipId", -1).takeIf { it > 0 }
                        if (newClipId != null) {
                            videoClipReverseOverrides[clipId]?.let { videoClipReverseOverrides[newClipId] = it }
                            videoClipFreezeOverrides[clipId]?.let { videoClipFreezeOverrides[newClipId] = it }
                            videoClipCurveProfiles[clipId]?.let { videoClipCurveProfiles[newClipId] = it }
                            videoClipKeyframes[clipId]?.let { keyframes ->
                                videoClipKeyframes[newClipId] = keyframes.toMutableList()
                            }
                            videoClipPreviewKeyframes[clipId]?.let { keyframes ->
                                videoClipPreviewKeyframes[newClipId] = keyframes.toMutableList()
                            }
                            duckingEnabledForKey[clipId.toString()]?.let { duckingEnabledForKey[newClipId.toString()] = it }
                        }
                        syncTimelineShellFromNative(selectedClipId = newClipId ?: clipId)
                        Toast.makeText(this@VideoEditorActivity, "$clipLabel duplicated", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@VideoEditorActivity, "Duplicate failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                execCmd(
                    action = "DUPLICATE_CLIP",
                    params = mapOf("clipId" to audioId)
                ) { result ->
                    val newId = result.data?.optInt("newClipId", -1)?.takeIf { result.success && it > 0 }
                    if (newId == null) {
                        Toast.makeText(this@VideoEditorActivity, "Audio duplicate failed", Toast.LENGTH_SHORT).show()
                    } else {
                        execCmd(
                            action = "MOVE_CLIP",
                            params = mapOf(
                                "clipId" to newId,
                                "newTimeMs" to (clip.startTimeMs + clip.durationMs + 50L)
                            )
                        ) {
                            audioClipGainOverrides[newId] = audioClipGainOverrides[audioId] ?: if (clip.muted) 0f else 1f
                            audioDuckingRestoreGainOverrides[audioId]?.let { audioDuckingRestoreGainOverrides[newId] = it }
                            duckingEnabledForKey["audio-$newId"] = duckingEnabledForKey["audio-$audioId"] ?: false
                            selectedTimelineClipKey = "audio-$newId"
                            lastLayoutFetchMs = 0L
                            syncTimelineShellFromNative(selectedClipId = newId)
                            safeToast("Audio duplicated", Toast.LENGTH_SHORT)
                        }
                    }
                }
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
                textOverlayKeyframes[overlay.id]?.let { keyframes ->
                    textOverlayKeyframes[duplicate.id] = keyframes.toMutableList()
                }
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
                stickerClipKeyframes[clip.id]?.let { keyframes ->
                    stickerClipKeyframes[duplicate.id] = keyframes.toMutableList()
                }
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
                val initial = currentClipPreviewTransform(selected)
                var currentTransform = initial

                ModernSheet.showModal(
                    context = this,
                    title = "$clipLabel Rotate & Flip",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Transform applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        updateSelectedVideoPreviewTransform { initial }
                    },
                ) {
                    section("Quick Orientation")
                    chips("Rotate", listOf("Rotate 90°", "Rotate 180°", "Rotate 270°", "Reset 0°")) { _, opt ->
                        updateSelectedVideoPreviewTransform { c ->
                            when (opt) {
                                "Rotate 90°" -> c.copy(rotationDeg = (c.rotationDeg + 90f) % 360f)
                                "Rotate 180°" -> c.copy(rotationDeg = (c.rotationDeg + 180f) % 360f)
                                "Rotate 270°" -> c.copy(rotationDeg = (c.rotationDeg + 270f) % 360f)
                                "Reset 0°" -> c.copy(rotationDeg = 0f)
                                else -> c
                            }.also { currentTransform = it }
                        }
                    }
                    divider()
                    section("Flip / Mirror")
                    toggle("Flip Horizontal", initial.mirrorX) { v ->
                        updateSelectedVideoPreviewTransform { c ->
                            c.copy(mirrorX = v).also { currentTransform = it }
                        }
                    }
                    divider()
                    section("Custom Angle")
                    sliderWithBubble(
                        label = "Rotation Angle",
                        min = -180f,
                        max = 180f,
                        value = initial.rotationDeg,
                        unit = "°",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        updateSelectedVideoPreviewTransform { c ->
                            c.copy(rotationDeg = v).also { currentTransform = it }
                        }
                    }
                }
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                val initialRotation = overlay.rotation

                ModernSheet.showModal(
                    context = this,
                    title = "Rotate Text",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Text rotation applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        overlay.rotation = initialRotation
                        applyTextOverlayPose(overlay)
                    },
                ) {
                    section("Angle Presets")
                    chips("Quick Angles", listOf("+90°", "-90°", "+15°", "-15°", "0° Reset")) { _, opt ->
                        when (opt) {
                            "+90°" -> overlay.rotation = (overlay.rotation + 90f) % 360f
                            "-90°" -> overlay.rotation = (overlay.rotation - 90f) % 360f
                            "+15°" -> overlay.rotation += 15f
                            "-15°" -> overlay.rotation -= 15f
                            "0° Reset" -> overlay.rotation = 0f
                        }
                        applyTextOverlayPose(overlay)
                    }
                    divider()
                    sliderWithBubble(
                        label = "Text Angle",
                        min = -180f,
                        max = 180f,
                        value = initialRotation,
                        unit = "°",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        overlay.rotation = v
                        applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                val initialRotation = clip.rotation
                val initialMirror = clip.mirrorX

                ModernSheet.showModal(
                    context = this,
                    title = "Rotate Overlay",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Overlay rotation applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        clip.rotation = initialRotation
                        clip.mirrorX = initialMirror
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                    },
                ) {
                    toggle("Flip Horizontal", clip.mirrorX) { v ->
                        clip.mirrorX = v
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                    }
                    divider()
                    sliderWithBubble(
                        label = "Rotation Angle",
                        min = -180f,
                        max = 180f,
                        value = initialRotation,
                        unit = "°",
                        format = { "%.0f".format(it) },
                    ) { v ->
                        clip.rotation = v
                        applyStickerOverlayPose(clip)
                    }
                }
            }
            ClipKind.AUDIO -> safeToast("Rotate is not for audio clips", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipKeyframeAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = selectedVideoClipId() ?: return
                showNativeClipKeyframeStudio(clipId, selectedNativeClipLabel())
            }
            ClipKind.TEXT -> {
                val overlayId = selectedTextOverlayId() ?: return
                val overlay = OverlayStore.get(overlayId) ?: return
                showTextKeyframeStudio(overlay)
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                showStickerKeyframeStudio(StickerClipStore.all().find { it.id == stickerId } ?: return)
            }
            ClipKind.AUDIO -> {
                val playheadMs = currentPlayheadMs()
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
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    safeToast("$clipLabel image cannot be reversed", Toast.LENGTH_SHORT)
                    return
                }
                pausePlaybackForSpeedSheet()
                val reverseEnabled = isReverseEnabledForClip(clipId)
                val currentSpeed = nativeClipPlaybackSpeed[clipId] ?: 1f
                ModernSheet.show(this, "$clipLabel Reverse") {
                    chips(
                        "Playback Direction",
                        listOf("Forward", "Reverse", "Toggle"),
                        selected = if (reverseEnabled) 1 else 0,
                    ) { _, option ->
                        val enabled =
                            when (option) {
                                "Forward" -> false
                                "Reverse" -> true
                                else -> !isReverseEnabledForClip(clipId)
                            }
                        if (applyNativeClipReverseChange(clipId, enabled)) {
                            safeToast(
                                if (enabled) "$clipLabel Reverse ON" else "$clipLabel Reverse OFF",
                                Toast.LENGTH_SHORT,
                            )
                        } else {
                            safeToast("Reverse update failed", Toast.LENGTH_SHORT)
                        }
                    }
                    chips("Reverse Speed", listOf("0.25x", "0.5x", "1x", "1.5x", "2x", "4x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        if (applyNativeClipReverseChange(clipId, true)) {
                            applyNativeClipSpeedChange(clipId, speed, curveProfile = "linear")
                            safeToast("$clipLabel reverse ${opt}", Toast.LENGTH_SHORT)
                        } else {
                            safeToast("Reverse speed update failed", Toast.LENGTH_SHORT)
                        }
                    }
                    chips("Creative", listOf("Reverse Slow", "Reverse Normal", "Reverse Fast", "Forward Reset")) { _, option ->
                        val desiredSpeed =
                            when (option) {
                                "Reverse Slow" -> 0.50f
                                "Reverse Fast" -> 2.0f
                                else -> 1.0f
                            }
                        val desiredReverse = option != "Forward Reset"
                        if (applyNativeClipReverseChange(clipId, desiredReverse)) {
                            applyNativeClipSpeedChange(clipId, desiredSpeed, curveProfile = "linear")
                            safeToast("$clipLabel $option", Toast.LENGTH_SHORT)
                        } else {
                            safeToast("Reverse update failed", Toast.LENGTH_SHORT)
                        }
                    }
                    slider("Preview Speed", 0.25f, 4f, currentSpeed, { "%.2fx".format(it) }) { speed ->
                        if (isReverseEnabledForClip(clipId) || applyNativeClipReverseChange(clipId, true)) {
                            applyNativeClipSpeedChange(clipId, speed, curveProfile = "linear")
                        }
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                pausePlaybackForSpeedSheet()
                val clipLabel = clip.displayName.ifBlank { "Audio $audioId" }
                val reverseEnabled = isReverseEnabledForClip(audioId)
                val currentSpeed = nativeClipPlaybackSpeed[audioId] ?: 1f
                ModernSheet.show(this, "$clipLabel Reverse") {
                    chips(
                        "Playback Direction",
                        listOf("Forward", "Reverse", "Toggle"),
                        selected = if (reverseEnabled) 1 else 0,
                    ) { _, option ->
                        val enabled =
                            when (option) {
                                "Forward" -> false
                                "Reverse" -> true
                                else -> !isReverseEnabledForClip(audioId)
                            }
                        if (applyNativeClipReverseChange(audioId, enabled)) {
                            safeToast(
                                if (enabled) "$clipLabel Reverse ON" else "$clipLabel Reverse OFF",
                                Toast.LENGTH_SHORT,
                            )
                        } else {
                            safeToast("Reverse update failed", Toast.LENGTH_SHORT)
                        }
                    }
                    chips("Reverse Speed", listOf("0.5x", "0.75x", "1x", "1.5x", "2x")) { _, opt ->
                        val speed = opt.removeSuffix("x").toFloatOrNull() ?: 1f
                        if (applyNativeClipReverseChange(audioId, true)) {
                            applyAudioClipSpeedChange(audioId, speed)
                            safeToast("$clipLabel reverse $opt", Toast.LENGTH_SHORT)
                        } else {
                            safeToast("Reverse speed update failed", Toast.LENGTH_SHORT)
                        }
                    }
                    chips("Creative", listOf("Reverse Slow", "Reverse Normal", "Reverse Fast", "Forward Reset")) { _, option ->
                        val desiredSpeed =
                            when (option) {
                                "Reverse Slow" -> 0.50f
                                "Reverse Fast" -> 2.0f
                                else -> 1.0f
                            }
                        val desiredReverse = option != "Forward Reset"
                        if (applyNativeClipReverseChange(audioId, desiredReverse)) {
                            applyAudioClipSpeedChange(audioId, desiredSpeed)
                            safeToast("$clipLabel $option", Toast.LENGTH_SHORT)
                        } else {
                            safeToast("Reverse update failed", Toast.LENGTH_SHORT)
                        }
                    }
                    slider("Preview Speed", 0.25f, 4f, currentSpeed, { "%.2fx".format(it) }) { speed ->
                        if (isReverseEnabledForClip(audioId) || applyNativeClipReverseChange(audioId, true)) {
                            applyAudioClipSpeedChange(audioId, speed)
                        }
                    }
                }
            }
            ClipKind.TEXT, ClipKind.STICKER -> safeToast("Reverse only for video", Toast.LENGTH_SHORT)
            ClipKind.NONE -> {
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: run {
                    safeToast("Select clip first", Toast.LENGTH_SHORT)
                    return
                }
                selectTimelineClipKey(selectionKeyForNativeClipId(clipId), revealPreview = false)
                performSelectedClipReverseAction()
            }
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
                var freezeDurationSec = 2.0f

                ModernSheet.showModal(
                    context = this,
                    title = "$clipLabel Freeze Frame",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        val durationMs = (freezeDurationSec * 1000f).toLong()
                        execCmd(
                            "FREEZE_FRAME", mapOf(
                                "clipId" to clipId, "timeMs" to playheadMs, "durationMs" to durationMs,
                            )
                        ) {
                            videoClipFreezeOverrides[clipId] = playheadMs to durationMs
                            lastLayoutFetchMs = 0L
                            refreshMainTimelineTracks()
                            refreshPreviewAtPlayhead()
                            safeToast("Freeze frame inserted (${freezeDurationSec}s)", Toast.LENGTH_SHORT)
                        }
                    },
                ) {
                    section("Freeze Duration")
                    sliderWithBubble(
                        label = "Hold Duration",
                        min = 0.5f,
                        max = 10.0f,
                        value = freezeDurationSec,
                        unit = "s",
                        format = { "%.1f".format(it) },
                    ) { sec ->
                        freezeDurationSec = sec
                    }
                    divider()
                    chips("Quick Presets", listOf("0.5s", "1.0s", "2.0s", "3.0s", "5.0s")) { _, opt ->
                        freezeDurationSec = opt.removeSuffix("s").toFloatOrNull() ?: 2.0f
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
                val preservedGain = audioDuckingRestoreGainOverrides[clip.id]
                    ?: audioClipGainOverrides[clip.id]
                    ?: clip.gain.coerceIn(0f, 2f)
                audioClipGainOverrides[clip.id] = preservedGain
                clip.gain = preservedGain
                clip.muted = preservedGain <= 0.001f
                return@forEach
            }
            val clipStart = clip.startTimeMs
            val clipEnd = clip.startTimeMs + clip.durationMs
            val overlaps = clipStart < endMs && clipEnd > startMs
            if (!overlaps) return@forEach
            val currentGain = (audioClipGainOverrides[clip.id] ?: clip.gain).coerceIn(0f, 2f)
            val baseGain = audioDuckingRestoreGainOverrides[clip.id] ?: currentGain
            val targetGain =
                if (enabled) {
                    audioDuckingRestoreGainOverrides.putIfAbsent(clip.id, baseGain)
                    (baseGain * duckAmount).coerceIn(0f, 2f)
                } else {
                    audioDuckingRestoreGainOverrides.remove(clip.id) ?: baseGain
                }
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
                execCmd(
                    action = "AUDIO_DUCKING",
                    params = mapOf(
                        "clipId" to clipId,
                        "enabled" to enabled,
                        "amount" to duckAmount,
                    )
                ) { result ->
                    if (!result.success) {
                        safeToast("$clipLabel ducking update failed", Toast.LENGTH_SHORT)
                    } else {
                        applyAudioDuckingLocallyForRange(startMs, endMs, enabled, duckAmount, excludeAudioId = null)
                        duckingEnabledForKey[key] = enabled
                        lastLayoutFetchMs = 0L
                        syncTimelineShellFromNative(selectedClipId = clipId)
                        syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        triggerEditorHaptic(if (enabled) EditorHapticEffect.Success else EditorHapticEffect.LightClick)
                        safeToast(if (enabled) "$clipLabel Ducking ON" else "$clipLabel Ducking OFF", Toast.LENGTH_SHORT)
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                val key = "audio-$audioId"
                val enabled = !(duckingEnabledForKey[key] ?: false)
                execCmd(
                    action = "AUDIO_DUCKING",
                    params = mapOf(
                        "clipId" to audioId,
                        "enabled" to enabled,
                        "amount" to duckAmount,
                    )
                ) { result ->
                    if (!result.success) {
                        safeToast("Audio ducking update failed", Toast.LENGTH_SHORT)
                    } else {
                        applyAudioDuckingLocallyForRange(
                            startMs = clip.startTimeMs,
                            endMs = clip.startTimeMs + clip.durationMs,
                            enabled = enabled,
                            amount = duckAmount,
                            excludeAudioId = audioId,
                        )
                        duckingEnabledForKey[key] = enabled
                        lastLayoutFetchMs = 0L
                        syncTimelineShellFromNative(selectedClipId = audioId)
                        syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        triggerEditorHaptic(if (enabled) EditorHapticEffect.Success else EditorHapticEffect.LightClick)
                        safeToast(if (enabled) "Audio focus ON" else "Audio focus OFF", Toast.LENGTH_SHORT)
                    }
                }
            }
            ClipKind.TEXT, ClipKind.STICKER -> safeToast("Ducking for video/audio clips", Toast.LENGTH_SHORT)
            ClipKind.NONE -> safeToast("Select clip first", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipCurveSpeedAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true) ?: return
                val clipLabel = selectedNativeClipLabel()
                if (isStillImageClip(clipId)) {
                    showStillImageDurationSheet(clipId, clipLabel)
                    return
                }
                pausePlaybackForSpeedSheet()
                val labels = listOf("Linear 1x", "Ease In 0.8x", "Ease Out 1.2x", "Speed Ramp 1.4x", "Hyperlapse 2x")
                val profiles = listOf("linear", "ease_in", "ease_out", "speed_ramp", "hyperlapse")
                val strengths = listOf(1.0f, 0.8f, 1.2f, 1.4f, 2.0f)
                ModernSheet.show(this, "$clipLabel Curve Speed") {
                    chips("Profile", labels, -1) { i, _ ->
                        applyNativeClipSpeedChange(clipId, strengths[i], curveProfile = profiles[i])
                        Toast.makeText(this@VideoEditorActivity, labels[i], Toast.LENGTH_SHORT).show()
                    }
                }
            }
            else -> safeToast("Curve speed for video clips", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipFilterAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                resolveSelectedVisualClipId(syncSelectionIfNeeded = true)?.let { timelineManager?.selectClip(it) }
                uiChromeController?.showEffectsSheet() ?: showClipToolPending("Effects")
                noteAppHealthAction("clip_filter_action_handled")
            }
            ClipKind.TEXT -> {
                val overlay = resolveSelectedTextOverlayForStudio(selectIfNeeded = true) ?: return
                noteAppHealthAction("clip_filter_action_handled")
                ModernSheet.show(this, "Text Color") {
                    chips("Style", listOf("Regular", "Bold"), if (overlay.bold) 1 else 0) { _, option ->
                        overlay.bold = option == "Bold"
                        NativeBridge.setTextOverlayBitmap(previewView ?: return@chips, overlay)
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                        refreshMainTimelineTracks()
                    }
                    chips("Color", listOf("White", "Yellow", "Cyan", "Green", "Red", "Black"), -1) { i, _ ->
                        overlay.color = listOf(
                            0xFFFFFFFF.toInt(),
                            0xFFFFEB3B.toInt(),
                            0xFF00E5FF.toInt(),
                            0xFF76FF03.toInt(),
                            0xFFFF5252.toInt(),
                            0xFF111111.toInt(),
                        )[i]
                        NativeBridge.setTextOverlayBitmap(previewView ?: return@chips, overlay)
                        previewView?.updateTextOverlay(overlay.id, overlay.x, overlay.y, overlay.scale, overlay.rotation, overlay.color, overlay.fontSize, overlay.startTimeMs, overlay.endTimeMs)
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                        refreshMainTimelineTracks()
                    }
                }
            }
            ClipKind.STICKER -> {
                noteAppHealthAction("clip_filter_action_handled")
                performSelectedClipGraphicsAction()
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                noteAppHealthAction("clip_filter_action_handled")
                showAudioClipFilterSheet(audioId, clip)
            }
            ClipKind.NONE -> {
                val preferredTrack = selectedImportTrackType()?.takeIf(::isVisualTrackType)
                val hasManagedVisualSelection =
                    timelineManager?.getSelectedClipId()?.let { clipId ->
                        isVisualTrackType(nativeClipTrackType[clipId])
                    } == true
                val clipId =
                    if (preferredTrack != null || hasManagedVisualSelection) {
                        resolveSelectedVisualClipId(
                            syncSelectionIfNeeded = true,
                            preferredTrackType = preferredTrack,
                        )
                    } else {
                        null
                    }
                if (clipId != null) {
                    timelineManager?.selectClip(clipId)
                    uiChromeController?.showEffectsSheet() ?: showClipToolPending("Effects")
                } else {
                    Toast.makeText(this@VideoEditorActivity, "Select clip first", Toast.LENGTH_SHORT).show()
                }
                noteAppHealthAction("clip_filter_action_handled")
            }
        }
    }

    private fun performSelectedClipBrightnessAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                resolveSelectedVisualClipId(syncSelectionIfNeeded = true)?.let { timelineManager?.selectClip(it) }
                val opened = uiChromeController?.showSelectedClipColorStudio() == true
                if (!opened) showClipToolPending("Color")
                noteAppHealthAction("clip_color_action_handled")
            }
            ClipKind.TEXT -> {
                val overlay = resolveSelectedTextOverlayForStudio(selectIfNeeded = true) ?: return
                noteAppHealthAction("clip_color_action_handled")
                ModernSheet.show(this@VideoEditorActivity, "Text Fade") {
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
                noteAppHealthAction("clip_color_action_handled")
                ModernSheet.show(this@VideoEditorActivity, "Overlay Fade") {
                    chips("Opacity", listOf("100%", "85%", "70%", "55%"), -1) { i, _ ->
                        clip.opacity = listOf(1f, 0.85f, 0.70f, 0.55f)[i]
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                    }
                }
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                val initialFadeIn = clip.fadeInMs
                val initialFadeOut = clip.fadeOutMs
                val maxFadeMsInt = clip.durationMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val maxFadeSec = (maxFadeMsInt / 1000f).coerceAtLeast(1f)
                noteAppHealthAction("clip_color_action_handled")

                ModernSheet.showModal(
                    context = this@VideoEditorActivity,
                    title = "Audio Fade In & Out",
                    showClose = true,
                    showApply = true,
                    onApply = {
                        safeToast("Audio fades applied", Toast.LENGTH_SHORT)
                    },
                    onCancel = {
                        clip.fadeInMs = initialFadeIn
                        clip.fadeOutMs = initialFadeOut
                        execCmd(
                            action = "SET_CLIP_AUDIO_FADES",
                            params = mapOf(
                                "clipId" to audioId,
                                "fadeInMs" to initialFadeIn,
                                "fadeOutMs" to initialFadeOut,
                            ),
                        ) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    },
                ) {
                    section("Fade Duration")
                    sliderWithBubble(
                        label = "Fade In",
                        min = 0f,
                        max = maxFadeSec,
                        value = (initialFadeIn / 1000f).coerceIn(0f, maxFadeSec),
                        unit = "s",
                        format = { "%.1f".format(it) },
                    ) { sec ->
                        val fadeInMs = (sec * 1000f).toInt().coerceIn(0, maxFadeMsInt)
                        clip.fadeInMs = fadeInMs
                        execCmd(
                            action = "SET_CLIP_AUDIO_FADES",
                            params = mapOf(
                                "clipId" to audioId,
                                "fadeInMs" to clip.fadeInMs,
                                "fadeOutMs" to clip.fadeOutMs,
                            ),
                        ) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    }
                    sliderWithBubble(
                        label = "Fade Out",
                        min = 0f,
                        max = maxFadeSec,
                        value = (initialFadeOut / 1000f).coerceIn(0f, maxFadeSec),
                        unit = "s",
                        format = { "%.1f".format(it) },
                    ) { sec ->
                        val fadeOutMs = (sec * 1000f).toInt().coerceIn(0, maxFadeMsInt)
                        clip.fadeOutMs = fadeOutMs
                        execCmd(
                            action = "SET_CLIP_AUDIO_FADES",
                            params = mapOf(
                                "clipId" to audioId,
                                "fadeInMs" to clip.fadeInMs,
                                "fadeOutMs" to clip.fadeOutMs,
                            ),
                        ) {
                            refreshMainTimelineTracks()
                            syncPreviewAudioAt(currentTimeMs, continuePlaying = isPlaying)
                        }
                    }
                }
            }
            ClipKind.NONE -> {
                val preferredTrack = selectedImportTrackType()?.takeIf(::isVisualTrackType)
                val hasManagedVisualSelection =
                    timelineManager?.getSelectedClipId()?.let { clipId ->
                        isVisualTrackType(nativeClipTrackType[clipId])
                    } == true
                val clipId =
                    if (preferredTrack != null || hasManagedVisualSelection) {
                        resolveSelectedVisualClipId(
                            syncSelectionIfNeeded = true,
                            preferredTrackType = preferredTrack,
                        )
                    } else {
                        null
                    }
                if (clipId != null) {
                    timelineManager?.selectClip(clipId)
                    val opened = uiChromeController?.showSelectedClipColorStudio() == true
                    if (!opened) showClipToolPending("Color")
                } else {
                    Toast.makeText(this@VideoEditorActivity, "Select clip first", Toast.LENGTH_SHORT).show()
                }
                noteAppHealthAction("clip_color_action_handled")
            }
        }
    }

    private fun performSelectedClipTransitionAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO -> {
                resolveSelectedVisualClipId(syncSelectionIfNeeded = true, preferredTrackType = TrackType.VIDEO)
                    ?.let { timelineManager?.selectClip(it) }
                uiChromeController?.showTransitionSheet() ?: showClipToolPending("Transition")
                noteAppHealthAction("clip_transition_action_handled")
            }
            ClipKind.OVERLAY -> {
                noteAppHealthAction("clip_transition_action_handled")
                safeToast("Transitions are for main video cuts", Toast.LENGTH_SHORT)
            }
            ClipKind.TEXT, ClipKind.STICKER -> {
                noteAppHealthAction("clip_transition_action_handled")
                safeToast("Transitions are for video clips", Toast.LENGTH_SHORT)
            }
            ClipKind.AUDIO -> {
                noteAppHealthAction("clip_transition_action_handled")
                safeToast("Transitions are not for audio clips", Toast.LENGTH_SHORT)
            }
            ClipKind.NONE -> {
                val preferredTrack = selectedImportTrackType()?.takeIf(::isVisualTrackType)
                val hasManagedVideoSelection =
                    timelineManager?.getSelectedClipId()?.let { clipId ->
                        nativeClipTrackType[clipId] == TrackType.VIDEO
                    } == true
                val clipId = if (preferredTrack == TrackType.VIDEO || hasManagedVideoSelection) {
                    resolveSelectedVisualClipId(
                        syncSelectionIfNeeded = true,
                        preferredTrackType = TrackType.VIDEO,
                    )
                } else {
                    null
                }
                if (clipId != null && nativeClipTrackType[clipId] == TrackType.VIDEO) {
                    timelineManager?.selectClip(clipId)
                    uiChromeController?.showTransitionSheet() ?: showClipToolPending("Transition")
                } else {
                    safeToast("Select clip first", Toast.LENGTH_SHORT)
                }
                noteAppHealthAction("clip_transition_action_handled")
            }
        }
    }

    private fun performSelectedClipGraphicsAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO -> {
                setSelectedImportTrackType(TrackType.VIDEO)
                importController?.setNextImportTrackType(TrackType.VIDEO)
                openVideoTrackImport()
                noteAppHealthAction("clip_graphics_action_handled")
            }
            ClipKind.TEXT -> {
                noteAppHealthAction("clip_graphics_action_handled")
                showSelectedTextStudio()
            }
            ClipKind.STICKER -> {
                noteAppHealthAction("clip_graphics_action_handled")
                performSelectedClipReplaceAction()
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: return
                val clip = AudioClipStore.get(audioId) ?: return
                noteAppHealthAction("clip_graphics_action_handled")
                showAudioClipStudioSheet(audioId, clip)
            }
            ClipKind.OVERLAY -> {
                noteAppHealthAction("clip_graphics_action_handled")
                performSelectedClipReplaceAction()
            }
            ClipKind.NONE -> {
                if (selectedImportTrackType() == TrackType.TEXT && resolveSelectedTextOverlayForStudio(selectIfNeeded = true) != null) {
                    showSelectedTextStudio()
                } else {
                    Toast.makeText(this@VideoEditorActivity, "Select clip first", Toast.LENGTH_SHORT).show()
                }
                noteAppHealthAction("clip_graphics_action_handled")
            }
        }
    }

    private fun setTransientAiStatus(label: String) {
        mainHandler.post {
            editorAiStatusPill?.visibility = View.VISIBLE
            editorAiStatusPill?.text = label
        }
    }

    private fun clearTransientAiStatus() {
        mainHandler.post { refreshAiCompanionUi() }
    }

    private fun mapTimelineTimeToSourceTimeMs(clipId: Int, timelineMs: Long): Long {
        val timing = selectedVideoTiming(clipId) ?: return timelineMs.coerceAtLeast(0L)
        val clipStartMs = timing.first.coerceAtLeast(0L)
        val clipDurationMs = timing.second.coerceAtLeast(1L)
        val sourceInMs = nativeClipSourceInMs[clipId] ?: 0L
        val sourceOutMs = nativeClipSourceOutMs[clipId] ?: (sourceInMs + clipDurationMs)
        val relativeMs = (timelineMs - clipStartMs).coerceIn(0L, clipDurationMs - 1L)
        return (sourceInMs + relativeMs).coerceIn(sourceInMs, maxOf(sourceInMs, sourceOutMs - 1L))
    }

    private fun resolveAiTrackVisualClipId(targetTimeMs: Long): Int? {
        val selectedVisual = resolveSelectedVisualClipId(syncSelectionIfNeeded = false)
        if (selectedVisual != null && !isStillImageClip(selectedVisual)) {
            return selectedVisual
        }
        return resolvePreviewEditableClipCandidatesAtPlayhead(targetTimeMs)
            .firstOrNull { clipId ->
                isVisualTrackType(nativeClipTrackType[clipId]) && !isStillImageClip(clipId)
            }
    }

    private fun buildAiMotionSamples(clipId: Int, startTimeMs: Long, endTimeMs: Long): List<AiMotionSample> {
        val safeEndExclusive = endTimeMs.coerceAtLeast(startTimeMs + 1L)
        val durationMs = (safeEndExclusive - startTimeMs).coerceAtLeast(1L)
        val sampleCount = ((durationMs / 350L) + 2L).toInt().coerceIn(5, 18)
        val stepMs = if (sampleCount <= 1) durationMs else (durationMs / (sampleCount - 1)).coerceAtLeast(120L)
        val samples = mutableListOf<AiMotionSample>()
        for (index in 0 until sampleCount) {
            val timelineMs =
                if (index == sampleCount - 1) {
                    safeEndExclusive - 1L
                } else {
                    (startTimeMs + (index * stepMs)).coerceAtMost(safeEndExclusive - 1L)
                }
            samples += AiMotionSample(
                timelineMs = timelineMs,
                sourceTimeMs = mapTimelineTimeToSourceTimeMs(clipId, timelineMs),
            )
        }
        return samples.distinctBy { it.timelineMs }
    }

    private fun addTextOverlaysBatch(overlays: List<TextOverlay>, message: String) {
        val preview = previewView ?: run {
            safeToast("Preview unavailable", Toast.LENGTH_SHORT)
            return
        }
        if (overlays.isEmpty()) {
            safeToast("Nothing to add", Toast.LENGTH_SHORT)
            return
        }
        if (isPlaying) {
            isPlaying = false
            playbackController?.pauseRendering()
        }
        editorState?.recordLayerSnapshot()
        recordUndoDomain(UndoDomain.EDITOR)
        overlays.forEach { overlay ->
            OverlayStore.put(overlay)
            addTextOverlayToPreview(overlay)
        }
        preview.setActiveTextOverlayId(overlays.last().id)
        selectedTimelineClipKey = "text-${overlays.last().id}"
        refreshMainTimelineTracks()
        refreshPreviewAtPlayhead(resyncAudio = false)
        safeToast(message, Toast.LENGTH_SHORT)
    }

    private fun buildCaptionOverlay(text: String, startTimeMs: Long, endTimeMs: Long): TextOverlay {
        return TextOverlay(
            id = nextTextOverlayId++,
            text = text,
            startTimeMs = startTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            endTimeMs = endTimeMs.coerceAtLeast(startTimeMs + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            x = 0.5f,
            y = 0.84f,
            scale = 1.0f,
            rotation = 0.0f,
            opacity = 1.0f,
            color = 0xFFFFFFFF.toInt(),
            fontSize = 30f,
            fontName = "sans-serif-medium",
            bold = true,
            italic = false,
            layerIndex = 0,
            visible = true,
        )
    }

    private fun performSelectedClipAiMatteAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val trackType = selectedTrackType() ?: TrackType.VIDEO
                if (!ensureTrackEditable(trackType, "AI matte")) return
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true, preferredTrackType = trackType) ?: run {
                    safeToast("Select visual clip first", Toast.LENGTH_SHORT)
                    return
                }
                val mediaPath = nativeClipSourcePath[clipId].orEmpty()
                if (mediaPath.isBlank()) {
                    uiChromeController?.showChromaKeyPanel(clipId)
                    return
                }
                val playheadMs = resolveVisualClipPreviewTimeMs(clipId)
                val sourceTimeMs = mapTimelineTimeToSourceTimeMs(clipId, playheadMs)
                setTransientAiStatus("AI MATTE")
                safeToast("AI Matte analyzing frame", Toast.LENGTH_SHORT)
                commandExecutor.execute {
                    val suggestion = AiAssistTools.inferAiMatte(mediaPath, sourceTimeMs)
                    mainHandler.post {
                        clearTransientAiStatus()
                        if (suggestion == null) {
                            safeToast("No strong matte color found. Tune manually.", Toast.LENGTH_SHORT)
                            uiChromeController?.showChromaKeyPanel(clipId)
                            return@post
                        }
                        execCmd(
                            action = "SET_CHROMA_KEY",
                            params = mapOf(
                                "clipId" to clipId,
                                "enabled" to true,
                                "color" to if (suggestion.useBlueKey) 1 else 0,
                                "similarity" to suggestion.similarity,
                                "smoothness" to suggestion.smoothness,
                                "spill" to suggestion.spill,
                            ),
                        ) {
                            previewView?.let { pv -> NativeBridge.seekToTime(pv, playheadMs) }
                            safeToast("${suggestion.label} ready", Toast.LENGTH_SHORT)
                            uiChromeController?.showChromaKeyPanel(clipId)
                        }
                    }
                }
            }
            else -> safeToast("AI Matte works on visual clips", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipAiCaptionAction() {
        if (!ensureTrackEditable(TrackType.TEXT, "captions")) return
        val sourcePath: String
        val clipStartMs: Long
        val clipDurationMs: Long
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY -> {
                val clipId = resolveSelectedVisualClipId(syncSelectionIfNeeded = true, preferredTrackType = selectedTrackType()) ?: run {
                    safeToast("Select clip first", Toast.LENGTH_SHORT)
                    return
                }
                val timing = selectedVideoTiming(clipId) ?: run {
                    safeToast("Clip timing unavailable", Toast.LENGTH_SHORT)
                    return
                }
                sourcePath = nativeClipSourcePath[clipId].orEmpty()
                clipStartMs = timing.first
                clipDurationMs = timing.second
            }
            ClipKind.AUDIO -> {
                val audioId = selectedAudioClipId() ?: run {
                    safeToast("Select audio first", Toast.LENGTH_SHORT)
                    return
                }
                val clip = AudioClipStore.get(audioId) ?: run {
                    safeToast("Audio clip unavailable", Toast.LENGTH_SHORT)
                    return
                }
                sourcePath = clip.sourcePath
                clipStartMs = clip.startTimeMs
                clipDurationMs = clip.durationMs
            }
            else -> {
                safeToast("Caption works on video or audio clips", Toast.LENGTH_SHORT)
                return
            }
        }
        if (sourcePath.isBlank()) {
            safeToast("Media path unavailable", Toast.LENGTH_SHORT)
            return
        }
        setTransientAiStatus("AI CAPTION")
        safeToast("AI Caption reading transcript", Toast.LENGTH_SHORT)
        commandExecutor.execute {
            val cues = AiAssistTools.loadCaptionCues(sourcePath, clipDurationMs).take(18)
            val overlays =
                cues.mapNotNull { cue ->
                    val startMs = clipStartMs + cue.startMs
                    val endMs = clipStartMs + cue.endMs
                    val text = cue.text.trim()
                    if (text.isBlank()) null else buildCaptionOverlay(text, startMs, endMs)
                }
            mainHandler.post {
                clearTransientAiStatus()
                if (overlays.isEmpty()) {
                    safeToast("No captions found", Toast.LENGTH_SHORT)
                    return@post
                }
                addTextOverlaysBatch(overlays, "AI Caption added ${overlays.size} cues")
            }
        }
    }

    private fun performSelectedClipAiTrackAction() {
        if (!ensureTrackEditable(TrackType.TEXT, "AI track")) return
        when (selectedClipKind()) {
            ClipKind.TEXT -> {
                val overlay = resolveSelectedTextOverlayForStudio(selectIfNeeded = true) ?: run {
                    safeToast("Select text first", Toast.LENGTH_SHORT)
                    return
                }
                val trackStartMs = overlay.startTimeMs.toLong().coerceAtLeast(0L)
                val visualClipId = resolveAiTrackVisualClipId(trackStartMs) ?: run {
                    safeToast("Put text over a video clip first", Toast.LENGTH_SHORT)
                    return
                }
                val timing = selectedVideoTiming(visualClipId) ?: run {
                    safeToast("Video timing unavailable", Toast.LENGTH_SHORT)
                    return
                }
                val mediaPath = nativeClipSourcePath[visualClipId].orEmpty()
                if (mediaPath.isBlank()) {
                    safeToast("Video source missing", Toast.LENGTH_SHORT)
                    return
                }
                val trackEndMs =
                    minOf(
                        overlay.endTimeMs.toLong(),
                        timing.first + timing.second.coerceAtLeast(1L),
                    )
                if (trackEndMs - trackStartMs < 240L) {
                    safeToast("Text duration too short for tracking", Toast.LENGTH_SHORT)
                    return
                }
                setTransientAiStatus("AI TRACK")
                safeToast("AI Track locking text", Toast.LENGTH_SHORT)
                commandExecutor.execute {
                    val samples = buildAiMotionSamples(visualClipId, trackStartMs, trackEndMs)
                    val keyframes =
                        AiAssistTools.buildMotionTrack(
                            mediaPath = mediaPath,
                            samples = samples,
                            anchorX = overlay.x,
                            anchorY = overlay.y,
                            baseScale = overlay.scale,
                            baseRotation = overlay.rotation,
                            opacity = overlay.opacity,
                        )
                    mainHandler.post {
                        clearTransientAiStatus()
                        if (keyframes.isEmpty()) {
                            safeToast("AI Track could not lock subject", Toast.LENGTH_SHORT)
                            return@post
                        }
                        overlay.aiTrackKeyframes = keyframes
                        previewView?.clearTextKeyframes(overlay.id)
                        keyframes.forEach { keyframe ->
                            previewView?.addTextKeyframe(
                                overlay.id,
                                keyframe.timeMs,
                                keyframe.x,
                                keyframe.y,
                                keyframe.scale,
                                keyframe.rotation,
                                keyframe.opacity,
                            )
                        }
                        textOverlayKeyframes[overlay.id] = keyframes.map { it.timeMs }.toMutableList()
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                        refreshMainTimelineTracks()
                        refreshPreviewAtPlayhead(resyncAudio = false)
                        safeToast("AI Track linked text", Toast.LENGTH_SHORT)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: run {
                    safeToast("Select overlay first", Toast.LENGTH_SHORT)
                    return
                }
                val clip = StickerClipStore.all().firstOrNull { it.id == stickerId } ?: run {
                    safeToast("Overlay missing", Toast.LENGTH_SHORT)
                    return
                }
                val trackStartMs = clip.startTimeMs.toLong().coerceAtLeast(0L)
                val visualClipId = resolveAiTrackVisualClipId(trackStartMs) ?: run {
                    safeToast("Put overlay over a video clip first", Toast.LENGTH_SHORT)
                    return
                }
                val timing = selectedVideoTiming(visualClipId) ?: run {
                    safeToast("Video timing unavailable", Toast.LENGTH_SHORT)
                    return
                }
                val mediaPath = nativeClipSourcePath[visualClipId].orEmpty()
                if (mediaPath.isBlank()) {
                    safeToast("Video source missing", Toast.LENGTH_SHORT)
                    return
                }
                val trackEndMs =
                    minOf(
                        clip.startTimeMs.toLong() + clip.durationMs.toLong().coerceAtLeast(1L),
                        timing.first + timing.second.coerceAtLeast(1L),
                    )
                if (trackEndMs - trackStartMs < 240L) {
                    safeToast("Overlay duration too short for tracking", Toast.LENGTH_SHORT)
                    return
                }
                setTransientAiStatus("AI TRACK")
                safeToast("AI Track locking overlay", Toast.LENGTH_SHORT)
                commandExecutor.execute {
                    val samples = buildAiMotionSamples(visualClipId, trackStartMs, trackEndMs)
                    val keyframes =
                        AiAssistTools.buildMotionTrack(
                            mediaPath = mediaPath,
                            samples = samples,
                            anchorX = clip.x,
                            anchorY = clip.y,
                            baseScale = clip.scale,
                            baseRotation = clip.rotation,
                            opacity = clip.opacity,
                        )
                    mainHandler.post {
                        clearTransientAiStatus()
                        if (keyframes.isEmpty()) {
                            safeToast("AI Track could not lock subject", Toast.LENGTH_SHORT)
                            return@post
                        }
                        clip.aiTrackKeyframes = keyframes
                        stickerClipKeyframes[clip.id] = keyframes.map { it.timeMs }.toMutableList()
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                        refreshPreviewAtPlayhead(resyncAudio = false)
                        safeToast("AI Track linked overlay", Toast.LENGTH_SHORT)
                    }
                }
            }
            else -> safeToast("AI Track works on text or overlay clips", Toast.LENGTH_SHORT)
        }
    }

    private fun performSelectedClipCutoutAction() {
        when (selectedClipKind()) {
            ClipKind.VIDEO, ClipKind.OVERLAY, ClipKind.AUDIO -> showSelectedClipAiAutoCutSheet()
            ClipKind.TEXT -> {
                val overlay = resolveSelectedTextOverlayForStudio(selectIfNeeded = true) ?: return
                ModernSheet.show(this, "Text Cutout") {
                    chips("Mode", listOf("Solid", "Ghost", "Punch", "Accent")) { _, option ->
                        when (option) {
                            "Ghost" -> {
                                overlay.opacity = 0.55f
                                overlay.color = 0xFFFFFFFF.toInt()
                            }
                            "Punch" -> {
                                overlay.opacity = 1.0f
                                overlay.color = 0xFF111111.toInt()
                                overlay.bold = true
                            }
                            "Accent" -> {
                                overlay.opacity = 0.90f
                                overlay.color = 0xFF00E5FF.toInt()
                                overlay.bold = true
                            }
                            else -> {
                                overlay.opacity = 1.0f
                                overlay.color = 0xFFFFFFFF.toInt()
                            }
                        }
                        NativeBridge.setTextOverlayBitmap(previewView ?: return@chips, overlay)
                        previewView?.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                        refreshMainTimelineTracks()
                    }
                    slider("Opacity", 0.15f, 1f, overlay.opacity, { "%.0f%%".format(it * 100f) }) { value ->
                        overlay.opacity = value
                        previewView?.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
                        applyTextOverlayState(overlay)
                        applyTextOverlayPose(overlay)
                    }
                }
            }
            ClipKind.STICKER -> {
                val stickerId = selectedStickerClipId() ?: return
                val clip = StickerClipStore.all().find { it.id == stickerId } ?: return
                ModernSheet.show(this, "Overlay Cutout") {
                    chips("Mode", listOf("Solid", "Soft", "Ghost", "Pop")) { _, option ->
                        when (option) {
                            "Soft" -> {
                                clip.opacity = 0.72f
                                clip.scale = clip.scale.coerceAtLeast(0.35f)
                            }
                            "Ghost" -> clip.opacity = 0.45f
                            "Pop" -> {
                                clip.opacity = 1.0f
                                clip.scale = (clip.scale * 1.08f).coerceIn(0.35f, 6.0f)
                            }
                            else -> clip.opacity = 1.0f
                        }
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                        refreshMainTimelineTracks()
                    }
                    slider("Opacity", 0.15f, 1f, clip.opacity, { "%.0f%%".format(it * 100f) }) { value ->
                        clip.opacity = value
                        applyStickerLayerState(clip)
                        applyStickerOverlayPose(clip)
                    }
                }
            }
            ClipKind.NONE -> Toast.makeText(this@VideoEditorActivity, "Select clip first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSelectedClipAddLayerAction() {
        noteAppHealthAction("clip_layer_action_handled")
        setSelectedImportTrackType(TrackType.LAYER)
        openLayerTrackImport()
    }

    private fun setupBottomToolbarModes() {
        bindClipToolbarAction(R.id.clipDeleteButton) { performSelectedClipDeleteAction() }
        bindClipToolbarAction(R.id.clipSplitButton) { performSelectedClipSplitAction() }
        findViewById<View>(R.id.clipSplitButton)?.setOnLongClickListener {
            val trackType = selectedTrackType()
            if (trackType != null && !ensureTrackEditable(trackType)) {
                return@setOnLongClickListener true
            }
            noteUiButtonTap(
                control = "clipSplitButton_ai",
                surface = "clip_toolbar",
                mode = "long_press",
            )
            showSelectedClipAiAutoCutSheet()
            true
        }
        bindClipToolbarAction(R.id.clipAiCaptionButton) { performSelectedClipAiCaptionAction() }
        bindClipToolbarAction(R.id.clipAiTrackButton) { performSelectedClipAiTrackAction() }
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
        bindClipToolbarAction(R.id.clipChromaKeyButton) { performSelectedClipAiMatteAction() }
        bindClipToolbarAction(R.id.clipCutoutButton) { performSelectedClipCutoutAction() }
        bindClipToolbarAction(R.id.clipTrimButton) { showSelectedClipTrimSheet() }
        updateBottomToolbarMode()
    }

    private fun updateBottomToolbarMode() {
        val key = selectedTimelineClipKey
        val kind = selectedClipKind()
        val toolbarProfileChanged = key != lastClipToolbarSelectionKey || kind != lastClipToolbarKind
        if (toolbarProfileChanged) {
            clipToolbarScrollX = 0
            lastClipToolbarSelectionKey = key
            lastClipToolbarKind = kind
        } else {
            clipToolbarScrollX = clipToolbarScrollView?.scrollX?.coerceAtLeast(0) ?: clipToolbarScrollX
        }
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
        syncPreviewCropModeUi()
        applySelectedClipPreviewTransform()
    }

}
