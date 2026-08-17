package com.video.engine

import android.util.Log
import android.os.SystemClock
import com.video.engine.audio.AudioClip
import com.video.engine.audio.AudioGainKeyframe
import com.video.engine.effects.EffectParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * NativeBridge: JNI wrapper for native video preview operations.
 *
 * Provides high-level Kotlin API for:
 * - Seeking to timeline positions (scrubbing)
 * - Playback control (play, pause, stop)
 * - Video loading
 * - State queries (duration, playback state)
 *
 * Thread-safe: All JNI calls are serialized through VideoPreviewView's internal mutex.
 *
 * Why a separate bridge class?
 * - Separates UI logic (MainActivity) from native marshaling
 * - Provides single point for logging/debugging JNI calls
 * - Easier to mock for testing
 * - Cleaner API than raw JNI method calls
 */
object NativeBridge {
    private const val TAG = "[NativeBridge]"
    private const val MAX_JOURNAL_ENTRIES = 4000
    private val dependentNativeLibs = listOf("avutil", "swresample", "swscale", "avcodec", "avformat")

    private fun loadNativeRuntime(): Boolean {
        synchronized(nativeLoadLock) {
            if (nativeLibraryLoaded) return true
            return runCatching {
                // Force-load libnativewindow so ANativeWindow_release resolves in prebuilt libavutil
                try { System.load("/system/lib64/libnativewindow.so") } catch (_: Throwable) {}
                try { System.load("/system/lib/libnativewindow.so") } catch (_: Throwable) {}
                try { System.load("/system/lib64/libandroid.so") } catch (_: Throwable) {}
                try { System.load("/system/lib/libandroid.so") } catch (_: Throwable) {}
                dependentNativeLibs.forEach { lib -> System.loadLibrary(lib) }
                System.loadLibrary("video_engine")
                true
            }.getOrElse { error ->
                Log.w(TAG, "Native library load failed: ${error.message}")
                false
            }.also { nativeLibraryLoaded = it }
        }
    }
    // Single-thread executor for all blocking native commands — prevents ANR when called from main thread
    private val commandExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile
    private var nativeLibraryLoaded = false
    private val nativeLoadLock = Any()
    private val mutatingActions = setOf(
        "ADD_CLIP",
        "DELETE",
        "DELETE_CLIP",
        "SPLIT",
        "TRIM_CLIP",
        "MOVE_CLIP",
        "UPDATE_CLIP_TIMING",
        "SPEED",
        "CURVE_SPEED",
        "SET_CLIP_VOLUME",
        "SET_CLIP_AUDIO_FADES",
        "SET_CLIP_AUDIO_KEYFRAMES",
        "AUDIO_DUCKING",
        "DUPLICATE_CLIP",
        "REPLACE_CLIP_SOURCE",
        "KEYFRAME_ADD",
        "KEYFRAME_DELETE",
        "KEYFRAME_CLEAR",
        "REVERSE_CLIP",
        "FREEZE_FRAME",
        "TRANSITION",
        "SET_CLIP_EFFECTS",
        "SET_TIMELINE_ZOOM",
        "SET_CLIP_TRACK",
        "SET_CHROMA_KEY",
    )
    private val commandJournal = ArrayDeque<String>()
    @Volatile
    private var telemetrySink: ((CommandTelemetry) -> Unit)? = null
    @Volatile
    private var lastCancelExportRequestElapsedMs: Long = 0L
    @Volatile
    private var engineWarmupQueued = false

    init {
        nativeLibraryLoaded = loadNativeRuntime()
    }

    data class CommandResult(
        val success: Boolean,
        val action: String,
        val message: String,
        val data: JSONObject = JSONObject()
    )

    data class CommandTelemetry(
        val phase: String,
        val action: String,
        val params: JSONObject? = null,
        val result: JSONObject? = null,
        val success: Boolean? = null,
        val message: String? = null,
        val durationMs: Long? = null,
        val async: Boolean = false,
    )

    data class ProxyStatus(
        val known: Boolean,
        val building: Boolean,
        val ready: Boolean,
        val failed: Boolean,
        val progress: Int,
        val sourcePath: String,
        val proxyPath: String,
        val message: String,
    )

    data class AudioPeakBuildResult(
        val peakMapPath: String,
        val bucketMs: Int,
        val sampleRate: Int,
        val peakCount: Int,
        val durationMs: Long,
    )

    data class AudioPeakRange(
        val peakMapPath: String,
        val bucketMs: Int,
        val sampleRate: Int,
        val peakCount: Int,
        val durationMs: Long,
        val startIndex: Int,
        val returnedCount: Int,
        val peaks: List<Int>,
    )

    data class PreviewAudioClipState(
        val path: String,
        val startTimeMs: Long,
        val durationMs: Long,
        val volume: Float,
        val fadeInMs: Int,
        val fadeOutMs: Int,
        val keyframesCsv: String,
        val layerIndex: Int,
        val visible: Boolean,
    )

    fun serializeAudioGainKeyframesCsv(keyframes: List<AudioGainKeyframe>): String {
        return keyframes
            .sortedBy { it.timeMs }
            .distinctBy { it.timeMs }
            .joinToString(separator = ",") { keyframe ->
                "${keyframe.timeMs.coerceAtLeast(0L)}:${keyframe.gain.coerceIn(0f, 2f)}"
            }
    }

    private fun parseAudioGainKeyframes(data: JSONArray?): List<AudioGainKeyframe> {
        if (data == null) return emptyList()
        val parsed = mutableListOf<AudioGainKeyframe>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            parsed += AudioGainKeyframe(
                timeMs = item.optLong("timeMs", 0L).coerceAtLeast(0L),
                gain = item.optDouble("gain", 1.0).toFloat().coerceIn(0f, 2f),
            )
        }
        return parsed.sortedBy { it.timeMs }.distinctBy { it.timeMs }
    }

    /**
     * Seek to a timeline position and render one preview frame.
     *
     * Used for timeline scrubbing:
     * - Calculate timeMs from scroll position
     * - Call nativeSeekPreview(timeMs)
     * - Native code decodes frame and renders to GL surface
     * - Result: Instant frame preview without playback
     *
     * Fast because:
     * 1. No thread creation (uses existing render thread)
     * 2. No surface recreation (reuses GL surface)
     * 3. Single frame render only (not playback loop)
     * 4. Decoder state kept (if FFmpeg supports frame caching)
     * 5. GPU rendering only (no CPU→GPU transfer)
     *
     * Thread-safe: Can call from any thread (main, bg, etc.)
     * Non-blocking: Returns immediately after queueing render
     *
     * @param previewView The VideoPreviewView instance
     * @param timelineMs Seek position in milliseconds
     */
    fun seekToTime(previewView: VideoPreviewView, timelineMs: Long) {
        previewView.ensureNativeSurfaceBinding()
        runCatching {
            previewView.seekToTime(timelineMs)
            true
        }.getOrElse {
            executeCommandAsync("SEEK", mapOf("timeMs" to timelineMs))
            false
        }
    }

    /**
     * Start video playback from a specific timeline position.
     *
     * Starts the continuous rendering loop (typically 30-60fps).
     * Frames are decoded and displayed at real-time rate.
     *
     * @param previewView The VideoPreviewView instance
     * @param timelineMs Start position in milliseconds
     */
    fun startPlayback(previewView: VideoPreviewView, timelineMs: Long) {
        previewView.ensureNativeSurfaceBinding()
        runCatching {
            previewView.startPlayback(timelineMs)
            true
        }.getOrElse {
            executeCommandAsync("PLAY", mapOf("timeMs" to timelineMs))
            false
        }
    }

    /**
     * Stop video playback.
     *
     * Freezes the preview on the current frame.
     * Safe to call multiple times.
     *
     * @param previewView The VideoPreviewView instance
     */
    fun stopPlayback(previewView: VideoPreviewView) {
        runCatching {
            previewView.stopPlayback()
            true
        }.getOrElse {
            executeCommandAsync("PAUSE")
            false
        }
    }

    /**
     * Load a video file for preview.
     *
     * Must be called before seeking or playback.
     * Blocks briefly to open file and extract metadata.
     *
     * @param previewView The VideoPreviewView instance
     * @param videoPath Absolute path to video file
     * @return true if video loaded successfully
     */
    fun loadVideo(previewView: VideoPreviewView, videoPath: String): Boolean {
        val resolvedVideoPath = MediaPathResolver.normalizeForFileAccess(videoPath)
        previewView.ensureNativeSurfaceBinding()
        Log.d(TAG, "Loading video: $resolvedVideoPath")
        var result = runCatching {
            executeCommand("LOAD_VIDEO", mapOf("videoPath" to resolvedVideoPath))
        }.getOrNull()
        if (result?.success != true) {
            Log.w(TAG, "LOAD_VIDEO command failed: ${result?.message ?: "unknown"}; forcing surface rebind")
            previewView.forceNativeSurfaceRebind()
            result = runCatching {
                executeCommand("LOAD_VIDEO", mapOf("videoPath" to resolvedVideoPath))
            }.getOrNull()
        }
        return if (result?.success == true) {
            result.data.optBoolean("loaded", true)
        } else {
            Log.e(TAG, "LOAD_VIDEO command failed after retry: ${result?.message ?: "unknown"}")
            false
        }
    }

    /**
     * Get video duration in milliseconds.
     *
     * Call after loadVideo() to get the total duration.
     * Used to configure timeline UI bounds.
     *
     * @param previewView The VideoPreviewView instance
     * @return Duration in milliseconds, or 0 if not loaded
     */
    fun getDuration(previewView: VideoPreviewView): Long {
        val durationMs = runCatching {
            executeCommand("GET_DURATION").takeIf { it.success }?.data?.optLong("durationMs", 0L)
        }.getOrNull() ?: previewView.getDuration()
        Log.d(TAG, "Video duration: ${durationMs} ms")
        return durationMs
    }

    fun getCurrentPlaybackTime(previewView: VideoPreviewView): Long {
        // Hot path for playback UI tick: use direct JNI clock read.
        // Command bridge path can block behind heavy render mutex and cause UI jank.
        return previewView.getCurrentPlaybackTime()
    }

    fun isPlaybackActive(previewView: VideoPreviewView): Boolean {
        return previewView.isPlaybackActive()
    }

    fun setTimelineZoomPxPerSecond(pxPerSecond: Float): Boolean {
        return runCatching {
            executeCommandAsync("SET_TIMELINE_ZOOM", mapOf("pxPerSecond" to pxPerSecond))
            true
        }.getOrDefault(false)
    }

    fun getTimelineZoomPxPerSecond(defaultValue: Float = 120f): Float {
        return runCatching {
            executeCommand("GET_TIMELINE_ZOOM")
                .takeIf { it.success }
                ?.data
                ?.optDouble("pxPerSecond", defaultValue.toDouble())
                ?.toFloat()
        }.getOrNull() ?: defaultValue
    }

    /**
     * Add a text overlay to the native renderer.
     */
    fun addTextOverlay(previewView: VideoPreviewView, overlay: com.video.engine.overlay.TextOverlay) {
        Log.d(TAG, "[Text] added id=${overlay.id}")
        Log.d("[Text]", "added id=${overlay.id}")
        previewView.addTextOverlay(
            overlay.id,
            overlay.text,
            overlay.x,
            overlay.y,
            overlay.scale,
            overlay.rotation,
            overlay.color,
            overlay.fontSize,
            overlay.startTimeMs,
            overlay.endTimeMs
        )
    }

    /**
     * Create a bitmap from overlay text and upload pixel data to native preview.
     * This uses the shared text bitmap helper so preview/export/edit all render consistently.
     */
    fun setTextOverlayBitmap(previewView: VideoPreviewView, overlay: com.video.engine.overlay.TextOverlay) {
        try {
            val (pixels, width, height) = TextBitmapHelper.createTextPixels(overlay)
            previewView.setTextOverlayBitmap(overlay.id, pixels, width, height)
            Log.d("[Text]", "uploaded bitmap id=${overlay.id} size=${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "setTextOverlayBitmap failed: ${e.message}")
        }
    }

    /**
     * Update overlay properties in native renderer.
     */
    fun updateTextOverlay(previewView: VideoPreviewView, overlay: com.video.engine.overlay.TextOverlay) {
        Log.d(TAG, "moved x=${String.format("%.2f", overlay.x)} y=${String.format("%.2f", overlay.y)}")
        previewView.updateTextOverlay(
            overlay.id,
            overlay.x,
            overlay.y,
            overlay.scale,
            overlay.rotation,
            overlay.color,
            overlay.fontSize,
            overlay.startTimeMs,
            overlay.endTimeMs
        )
    }

    /**
     * Remove overlay in native renderer.
     */
    fun removeTextOverlay(previewView: VideoPreviewView, id: Int) {
        Log.d(TAG, "[Text] removed id=$id")
        Log.d("[Text]", "removed id=$id")
        previewView.removeTextOverlay(id)
    }

    /**
     * Start export via native renderer. This call is fire-and-forget; progress
     * should be polled via `getExportProgress()` or reported via callbacks.
     */
    fun startExport(previewView: VideoPreviewView, outputPath: String, width: Int, height: Int, fps: Int) {
        lastCancelExportRequestElapsedMs = 0L
        Log.d(TAG, "[Export] started ${width}x${height} ${fps}fps")
        val audioClips = com.video.engine.audio.AudioClipStore.all()
        previewView.startExport(
            outputPath, width, height, fps,
            audioPaths   = audioClips.map { it.sourcePath }.toTypedArray(),
            audioStartMs = audioClips.map { it.startTimeMs }.toLongArray(),
            audioDurMs   = audioClips.map { it.durationMs  }.toLongArray(),
            audioVols    = audioClips.map { clip ->
                if (clip.muted || !clip.visible) 0f else clip.gain.coerceIn(0f, 2f)
            }.toFloatArray(),
            audioFadeInMs = audioClips.map { it.fadeInMs.coerceAtLeast(0) }.toIntArray(),
            audioFadeOutMs = audioClips.map { it.fadeOutMs.coerceAtLeast(0) }.toIntArray(),
            audioKeyframeCsvs = audioClips.map { serializeAudioGainKeyframesCsv(it.gainKeyframes) }.toTypedArray(),
        )
    }

    /**
     * Cancel an ongoing export.
     */
    fun cancelExport(previewView: VideoPreviewView) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCancelExportRequestElapsedMs < 1200L) {
            Log.d(TAG, "[Export] cancel ignored (debounced)")
            return
        }
        lastCancelExportRequestElapsedMs = now
        Log.d(TAG, "[Export] cancel requested")
        previewView.cancelExport()
    }

    /**
     * Poll native progress (0..100) or -1 if unavailable.
     */
    fun getExportProgress(previewView: VideoPreviewView): Int {
        return previewView.getExportProgress()
    }

    /**
     * Update per-clip GPU effect parameters through native preview view.
     */
    fun setClipEffects(previewView: VideoPreviewView, clipId: Int, brightness: Float, contrast: Float, saturation: Float) {
        Log.d(TAG, "[EffectsUI] brightness=${String.format("%.2f", brightness)} contrast=${String.format("%.2f", contrast)} saturation=${String.format("%.2f", saturation)}")
        // Always update the live preview path directly so paused preview reflects
        // grading changes immediately even if command execution only persists model state.
        previewView.setClipEffects(clipId, brightness, contrast, saturation)
        val result = runCatching {
            executeCommand(
                "SET_CLIP_EFFECTS",
                mapOf(
                    "clipId" to clipId,
                    "brightness" to brightness,
                    "contrast" to contrast,
                    "saturation" to saturation,
                ),
            )
        }.getOrNull()
        if (result?.success != true) {
            Log.w(TAG, "[EffectsUI] SET_CLIP_EFFECTS persist command failed for clip=$clipId")
        }
        Log.d("[GPU FX]", "updated in real-time")
    }

    /**
     * Add a clip to the native timeline. Returns clip ID or -1 if failed.
     */
    fun addClip(
        previewView: VideoPreviewView,
        videoPath: String,
        trackType: String = "VIDEO",
        startTimeMs: Long? = null,
        trackLane: Int = 0,
        zOrder: Int = 0,
    ): Int {
        val resolvedVideoPath = MediaPathResolver.normalizeForFileAccess(videoPath)
        previewView.ensureNativeSurfaceBinding()
        val params = mutableMapOf<String, Any>(
            "videoPath" to resolvedVideoPath,
            "trackType" to trackType,
            "trackLane" to trackLane,
            "zOrder" to zOrder,
        )
        if (startTimeMs != null && startTimeMs >= 0L) {
            params["startTimeMs"] = startTimeMs
        }
        var result = runCatching {
            executeCommand("ADD_CLIP", params)
        }.getOrNull()
        if (result?.success != true) {
            Log.w(TAG, "ADD_CLIP command failed: ${result?.message ?: "unknown"}; forcing surface rebind")
            previewView.forceNativeSurfaceRebind()
            result = runCatching {
                executeCommand("ADD_CLIP", params)
            }.getOrNull()
        }
        val clipId = if (result?.success == true) {
            result.data.optInt("clipId", -1)
        } else {
            Log.e(TAG, "ADD_CLIP command failed after retry: ${result?.message ?: "unknown"}")
            -1
        }
        if (clipId > 0) {
            Log.d(TAG, "[TimelineUI] clip added id=$clipId")
        }
        return clipId
    }

    fun setPreviewPolicy(
        ghostPreviewEnabled: Boolean = true,
        ghostLongEdgePx: Int = 640,
        adaptiveFrameDropEnabled: Boolean = true,
        targetPreviewFps: Int = 30,
        minPreviewFps: Int = 15,
    ): Boolean {
        val lockedGhostLongEdgePx =
            ghostLongEdgePx
                .coerceAtMost(DeviceDetector.getRecommendedGhostLongEdgePx())
                .coerceAtLeast(240)
        val lockedTargetPreviewFps = targetPreviewFps.coerceAtMost(DeviceDetector.getRecommendedPreviewFps()).coerceAtLeast(12)
        val lockedMinPreviewFps = minPreviewFps.coerceAtMost(lockedTargetPreviewFps).coerceAtLeast(12)
        val result = runCatching {
            executeCommand(
                action = "SET_PREVIEW_POLICY",
                params = mapOf(
                    "ghostPreviewEnabled" to ghostPreviewEnabled,
                    "ghostLongEdgePx" to lockedGhostLongEdgePx,
                    "adaptiveFrameDropEnabled" to adaptiveFrameDropEnabled,
                    "targetPreviewFps" to lockedTargetPreviewFps,
                    "minPreviewFps" to lockedMinPreviewFps,
                ),
            )
        }.getOrNull()
        return result?.success == true
    }

    fun setPerformancePolicy(
        dirtyRegionEnabled: Boolean = true,
        predictiveCachingEnabled: Boolean = true,
        predictiveLookAroundMs: Int = 2000,
        predictiveSampleStepMs: Int = 120,
        predictiveCacheMaxFrames: Int = 40,
    ): Boolean {
        val lockedPredictiveLookAroundMs =
            predictiveLookAroundMs
                .coerceAtMost(DeviceDetector.getRecommendedPredictiveLookAroundMs())
                .coerceAtLeast(200)
        val lockedPredictiveSampleStepMs =
            predictiveSampleStepMs
                .coerceAtLeast(DeviceDetector.getRecommendedPredictiveSampleStepMs())
                .coerceAtMost(1000)
        val lockedPredictiveCacheMaxFrames =
            predictiveCacheMaxFrames
                .coerceAtMost(DeviceDetector.getRecommendedPredictiveCacheMaxFrames())
                .coerceAtLeast(4)
        val result = runCatching {
            executeCommand(
                action = "SET_PERFORMANCE_POLICY",
                params = mapOf(
                    "dirtyRegionEnabled" to dirtyRegionEnabled,
                    "predictiveCachingEnabled" to predictiveCachingEnabled,
                    "predictiveLookAroundMs" to lockedPredictiveLookAroundMs,
                    "predictiveSampleStepMs" to lockedPredictiveSampleStepMs,
                    "predictiveCacheMaxFrames" to lockedPredictiveCacheMaxFrames,
                ),
            )
        }.getOrNull()
        return result?.success == true
    }

    fun setAudioMasterClockEnabled(enabled: Boolean): Boolean {
        return runCatching {
            executeCommandAsync(
                action = "SET_AUDIO_MASTER_CLOCK_ENABLED",
                params = mapOf("enabled" to enabled),
            )
            true
        }.getOrDefault(false)
    }

    fun updateAudioClockUs(ptsUs: Long): Boolean {
        return runCatching {
            executeCommandAsync(
                action = "UPDATE_AUDIO_CLOCK_US",
                params = mapOf("ptsUs" to ptsUs),
            )
            true
        }.getOrDefault(false)
    }

    fun buildAudioPeakMap(
        sourcePath: String,
        peakMapPath: String,
        bucketMs: Int = 20,
    ): AudioPeakBuildResult? {
        val result = runCatching {
            executeCommand(
                action = "BUILD_AUDIO_PEAK_MAP",
                params = mapOf(
                    "sourcePath" to sourcePath,
                    "peakMapPath" to peakMapPath,
                    "bucketMs" to bucketMs,
                ),
            )
        }.getOrNull() ?: return null
        if (!result.success) return null
        val data = result.data
        return AudioPeakBuildResult(
            peakMapPath = data.optString("peakMapPath"),
            bucketMs = data.optInt("bucketMs", bucketMs),
            sampleRate = data.optInt("sampleRate", 0),
            peakCount = data.optInt("peakCount", 0),
            durationMs = data.optLong("durationMs", 0L),
        )
    }

    fun getAudioPeakRange(
        peakMapPath: String,
        startIndex: Int = 0,
        maxPoints: Int = 1024,
    ): AudioPeakRange? {
        val result = runCatching {
            executeCommand(
                action = "GET_AUDIO_PEAK_RANGE",
                params = mapOf(
                    "peakMapPath" to peakMapPath,
                    "startIndex" to startIndex,
                    "maxPoints" to maxPoints,
                ),
            )
        }.getOrNull() ?: return null
        if (!result.success) return null
        val data = result.data
        val peaksJson = data.optJSONArray("peaks") ?: JSONArray()
        val peaks = buildList {
            for (index in 0 until peaksJson.length()) {
                add(peaksJson.optInt(index, 0))
            }
        }
        return AudioPeakRange(
            peakMapPath = data.optString("peakMapPath"),
            bucketMs = data.optInt("bucketMs", 20),
            sampleRate = data.optInt("sampleRate", 0),
            peakCount = data.optInt("peakCount", peaks.size),
            durationMs = data.optLong("durationMs", 0L),
            startIndex = data.optInt("startIndex", 0),
            returnedCount = data.optInt("returnedCount", peaks.size),
            peaks = peaks,
        )
    }

    fun buildClipProxy(
        clipId: Int,
        sourcePath: String,
        outputPath: String,
        maxLongEdgePx: Int = 640,
        targetFps: Int = 30,
    ): Boolean {
        val result = runCatching {
            executeCommand(
                action = "BUILD_CLIP_PROXY",
                params = mapOf(
                    "clipId" to clipId,
                    "sourcePath" to sourcePath,
                    "outputPath" to outputPath,
                    "maxLongEdgePx" to maxLongEdgePx,
                    "targetFps" to targetFps,
                ),
            )
        }.getOrNull()
        return result?.success == true
    }

    fun getClipProxyStatus(clipId: Int): ProxyStatus {
        val result = runCatching {
            executeCommand("GET_CLIP_PROXY_STATUS", mapOf("clipId" to clipId))
        }.getOrNull()
        val data = result?.data ?: JSONObject()
        return ProxyStatus(
            known = data.optBoolean("known", false),
            building = data.optBoolean("building", false),
            ready = data.optBoolean("ready", false),
            failed = data.optBoolean("failed", false),
            progress = data.optInt("progress", 0),
            sourcePath = data.optString("sourcePath"),
            proxyPath = data.optString("proxyPath"),
            message = data.optString("message"),
        )
    }

    fun activateClipProxy(clipId: Int): Boolean {
        val result = runCatching {
            executeCommand("ACTIVATE_CLIP_PROXY", mapOf("clipId" to clipId))
        }.getOrNull()
        return result?.success == true
    }

    fun snapshotCommandJournal(): List<String> {
        synchronized(commandJournal) {
            return commandJournal.toList()
        }
    }

    fun restoreCommandJournal(entries: List<String>) {
        synchronized(commandJournal) {
            commandJournal.clear()
            entries.takeLast(MAX_JOURNAL_ENTRIES).forEach { commandJournal.addLast(it) }
        }
    }

    fun clearCommandJournal() {
        synchronized(commandJournal) {
            commandJournal.clear()
        }
    }

    fun setTelemetrySink(sink: ((CommandTelemetry) -> Unit)?) {
        telemetrySink = sink
    }

    fun snapshotNativeCommandTelemetry(): JSONArray {
        if (!ensureNativeLibraryLoaded()) {
            return JSONArray()
        }
        return runCatching {
            JSONArray(nativeGetRecentCommandTelemetry())
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read native command telemetry: ${error.message}")
            JSONArray()
        }
    }

    fun clearNativeCommandTelemetry() {
        if (!ensureNativeLibraryLoaded()) {
            return
        }
        runCatching { nativeClearRecentCommandTelemetry() }
            .onFailure { error -> Log.w(TAG, "Failed to clear native command telemetry: ${error.message}") }
    }

    fun buildPreviewAudioClipStates(
        clips: List<AudioClip>,
        gainProvider: (AudioClip) -> Float,
    ): List<PreviewAudioClipState> {
        return clips.map { clip ->
            PreviewAudioClipState(
                path = clip.sourcePath,
                startTimeMs = clip.startTimeMs,
                durationMs = clip.durationMs,
                volume = gainProvider(clip),
                fadeInMs = clip.fadeInMs.coerceAtLeast(0),
                fadeOutMs = clip.fadeOutMs.coerceAtLeast(0),
                keyframesCsv = serializeAudioGainKeyframesCsv(clip.gainKeyframes),
                layerIndex = clip.layerIndex,
                visible = clip.visible,
            )
        }
    }

    fun setPreviewAudioClips(clips: List<PreviewAudioClipState>) {
        if (!ensureNativeLibraryLoaded()) {
            return
        }
        runCatching {
            nativeSetPreviewAudioClips(
                clips.map { it.path }.toTypedArray(),
                clips.map { it.startTimeMs }.toLongArray(),
                clips.map { it.durationMs }.toLongArray(),
                clips.map { it.volume }.toFloatArray(),
                clips.map { it.fadeInMs }.toIntArray(),
                clips.map { it.fadeOutMs }.toIntArray(),
                clips.map { it.keyframesCsv }.toTypedArray(),
                clips.map { it.layerIndex }.toIntArray(),
                clips.map { it.visible }.toBooleanArray(),
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to sync preview audio clips: ${error.message}")
        }
    }

    fun invalidatePreviewAudioResolutionCache() {
        if (!ensureNativeLibraryLoaded()) {
            return
        }
        runCatching {
            nativeInvalidatePreviewAudioResolutionCache()
        }.onFailure { error ->
            Log.w(TAG, "Failed to invalidate preview audio resolution cache: ${error.message}")
        }
    }

    fun resolvePreviewAudioSourceAt(timeMs: Long): PreviewAudioPlayer.SourceSelection? {
        if (!ensureNativeLibraryLoaded()) {
            return null
        }
        val rawJson = runCatching {
            nativeResolvePreviewAudioSourceAt(timeMs.coerceAtLeast(0L))
        }.getOrElse { error ->
            Log.w(TAG, "Failed to resolve preview audio source: ${error.message}")
            return null
        }
        if (rawJson.isBlank() || rawJson == "{}") {
            return null
        }
        val data = runCatching { JSONObject(rawJson) }.getOrElse { error ->
            Log.w(TAG, "Invalid preview audio source payload: ${error.message}")
            return null
        }
        val path = data.optString("path")
        if (path.isBlank()) {
            return null
        }
        return PreviewAudioPlayer.SourceSelection(
            key = data.optString("key", path),
            path = path,
            timelineStartMs = data.optLong("timelineStartMs", 0L),
            timelineEndMs = data.optLong("timelineEndMs", 0L),
            sourceInMs = data.optLong("sourceInMs", 0L),
            sourceOutMs = data.optLong("sourceOutMs", 0L),
            volume = data.optDouble("volume", 1.0).toFloat(),
            fadeInMs = data.optInt("fadeInMs", 0).coerceAtLeast(0),
            fadeOutMs = data.optInt("fadeOutMs", 0).coerceAtLeast(0),
            gainKeyframes = parseAudioGainKeyframes(data.optJSONArray("audioGainKeyframes")),
            playbackSpeed = data.optDouble("playbackSpeed", 1.0).toFloat(),
            reversePlayback = data.optBoolean("reversePlayback", false),
            freezeFrameEnabled = data.optBoolean("freezeFrameEnabled", false),
            freezeFrameTimeMs = data.optLong("freezeFrameTimeMs", 0L),
            freezeFrameDurationMs = data.optLong("freezeFrameDurationMs", 0L),
            curveSpeedProfile = data.optString("curveSpeedProfile", "linear"),
            curveSpeedStrength = data.optDouble("curveSpeedStrength", 1.0).toFloat(),
        )
    }

    fun resolvePreviewAudioSourceKeyAt(timeMs: Long): String? {
        if (!ensureNativeLibraryLoaded()) {
            return null
        }
        val key = runCatching {
            nativeResolvePreviewAudioSourceKeyAt(timeMs.coerceAtLeast(0L))
        }.getOrElse { error ->
            Log.w(TAG, "Failed to resolve preview audio source key: ${error.message}")
            return null
        }
        return key.ifBlank { null }
    }

    /**
     * Unified command bridge for the editor.
     *
     * This is the single master command path for Kotlin -> JNI -> C++ command dispatch.
     * UI can now send action + params without adding another dedicated JNI function.
     *
     * NOTE: This method now returns immediately and queues commands asynchronously.
     * For synchronous behavior, use executeCommandAsync with a callback.
     */
    fun executeCommand(action: String, params: Map<String, Any> = emptyMap()): CommandResult {
        if (!ensureNativeLibraryLoaded()) {
            return CommandResult(false, action, "Native library not loaded")
        }
        val startTime = SystemClock.elapsedRealtime()
        val payload = JSONObject()
        for ((key, value) in params) {
            payload.put(key, normalizeValue(value))
        }

        val resultStr = nativeExecuteCommand(action, payload.toString())
        val duration = SystemClock.elapsedRealtime() - startTime

        val resultJson = runCatching { JSONObject(resultStr) }.getOrElse {
            JSONObject().put("success", false).put("message", "Invalid JSON from native")
        }

        val success = resultJson.optBoolean("success", false)
        val message = resultJson.optString("message", "")
        val data = resultJson.optJSONObject("data") ?: JSONObject()

        val result = CommandResult(success, action, message, data)

        synchronized(commandJournal) {
            if (shouldJournalAction(action, payload, resultJson)) {
                commandJournal.addLast(action)
                if (commandJournal.size > MAX_JOURNAL_ENTRIES) commandJournal.removeFirst()
            }
        }

        publishTelemetry(
            CommandTelemetry(
                phase = "completed",
                action = action,
                params = payload,
                result = resultJson,
                success = success,
                message = message,
                durationMs = duration
            )
        )

        return result
    }

    fun applyProfessionalEffectPreset(clipId: Int, presetName: String): EffectParams? {
        val result = executeCommand(
            action = "APPLY_PRO_EFFECT_PRESET",
            params = mapOf(
                "clipId" to clipId,
                "preset" to presetName,
            ),
        )
        if (!result.success) return null
        return EffectParams(
            brightness = result.data.optDouble("brightness", 0.0).toFloat(),
            contrast = result.data.optDouble("contrast", 1.0).toFloat(),
            saturation = result.data.optDouble("saturation", 1.0).toFloat(),
        )
    }

    fun executeCommandAsync(action: String, params: Map<String, Any> = emptyMap()) {
        if (!ensureNativeLibraryLoaded()) {
            publishTelemetry(
                CommandTelemetry(
                    phase = "rejected",
                    action = action,
                    success = false,
                    message = "Native library unavailable",
                    async = true,
                ),
            )
            return
        }
        val payload = JSONObject()
        for ((key, value) in params) {
            payload.put(key, normalizeValue(value))
        }
        nativeExecuteCommandAsync(action, payload.toString())
        publishTelemetry(
            CommandTelemetry(
                phase = "queued",
                action = action,
                params = JSONObject(payload.toString()),
                async = true,
            ),
        )
    }

    private fun shouldJournalAction(action: String, payload: JSONObject, resultJson: JSONObject): Boolean {
        if (!mutatingActions.contains(action)) return false
        if (!resultJson.optBoolean("success", false)) return false
        if (action == "UPDATE_CLIP_TIMING" && payload.optBoolean("previewOnly", false)) return false
        return true
    }

    private fun normalizeValue(value: Any): Any {
        return when (value) {
            is Map<*, *> -> {
                val obj = JSONObject()
                value.forEach { (k, v) ->
                    if (k != null && v != null) {
                        obj.put(k.toString(), normalizeValue(v))
                    }
                }
                obj
            }
            is Iterable<*> -> {
                val array = JSONArray()
                value.forEach { item ->
                    if (item != null) {
                        array.put(normalizeValue(item))
                    }
                }
                array
            }
            is Array<*> -> {
                val array = JSONArray()
                value.forEach { item ->
                    if (item != null) {
                        array.put(normalizeValue(item))
                    }
                }
                array
            }
            is Number, is Boolean, is String, is JSONObject, is JSONArray -> value
            else -> value.toString()
        }
    }

    private fun ensureNativeLibraryLoaded(): Boolean {
        if (nativeLibraryLoaded) return true
        nativeLibraryLoaded = loadNativeRuntime()
        return nativeLibraryLoaded
    }

    private fun publishTelemetry(telemetry: CommandTelemetry) {
        runCatching {
            telemetrySink?.invoke(telemetry)
        }.onFailure { error ->
            Log.w(TAG, "Telemetry sink failed: ${error.message}")
        }
    }

    fun warmUpEngineAsync() {
        if (engineWarmupQueued) return
        engineWarmupQueued = true
        commandExecutor.execute {
            val loaded = ensureNativeLibraryLoaded()
            Log.d(TAG, "Engine warm-up complete loaded=$loaded")
        }
    }

    fun isNativeRuntimeReady(): Boolean = ensureNativeLibraryLoaded()

    fun syncAudioClips() {
        val clips = com.video.engine.audio.AudioClipStore.all().toList()
        commandExecutor.execute {
            if (!ensureNativeLibraryLoaded()) return@execute
            Log.d(TAG, "[AudioSync] syncing ${clips.size} clips to native engine")
            setPreviewAudioClips(
                buildPreviewAudioClipStates(clips) { if (it.muted || !it.visible) 0f else it.gain.coerceIn(0f, 2f) }
            )
        }
    }

    private external fun nativeExecuteCommand(action: String, payloadJson: String): String
    private external fun nativeExecuteCommandAsync(action: String, payloadJson: String)
    private external fun nativeGetRecentCommandTelemetry(): String
    private external fun nativeClearRecentCommandTelemetry()
    private external fun nativeSetPreviewAudioClips(
        paths: Array<String>,
        startTimesMs: LongArray,
        durationsMs: LongArray,
        volumes: FloatArray,
        fadeInMs: IntArray,
        fadeOutMs: IntArray,
        keyframeCsvs: Array<String>,
        layerIndices: IntArray,
        visibleFlags: BooleanArray,
    )
    private external fun nativeResolvePreviewAudioSourceAt(timeMs: Long): String
    private external fun nativeResolvePreviewAudioSourceKeyAt(timeMs: Long): String
    private external fun nativeInvalidatePreviewAudioResolutionCache()

    /**
     * Remove a clip from the native timeline.
     */
    fun removeClip(previewView: VideoPreviewView, clipId: Int) {
        Log.d(TAG, "[TimelineUI] clip removed id=$clipId")
        val result = runCatching {
            executeCommand("DELETE_CLIP", mapOf("clipId" to clipId))
        }.getOrNull()
        if (result?.success != true) {
            previewView.removeClip(clipId)
        }
    }

    /**
     * Get list of clip IDs from native timeline.
     */
    fun getClipIds(previewView: VideoPreviewView): IntArray {
        return previewView.getClipIds()
    }

    /**
     * Get clip duration.
     */
    fun getClipDuration(previewView: VideoPreviewView, clipId: Int): Long {
        return previewView.getClipDuration(clipId)
    }
}
