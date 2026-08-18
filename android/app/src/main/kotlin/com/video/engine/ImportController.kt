package com.video.engine

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.video.engine.UiToast as Toast
import com.video.engine.pro.model.TrackType
import com.video.engine.timeline.MultiClipTimeline
import com.video.engine.timeline.TimelineManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

enum class ImportProgressPhase {
    OPENING,
    COPYING,
    PREPARING,
    TIMELINE,
    READY,
    FAILED,
}

data class ImportProgressUpdate(
    val phase: ImportProgressPhase,
    val displayName: String,
    val trackType: TrackType,
    val path: String,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = -1L,
    val elapsedMs: Long = 0L,
    val etaMs: Long? = null,
    val percent: Int? = null,
    val message: String? = null,
)

class ImportController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val timelineProvider: () -> MultiClipTimeline,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val playheadTimeMsProvider: () -> Long,
    private val trackEndTimeMsProvider: (TrackType) -> Long = { 0L },
    private val isPlayingProvider: () -> Boolean,
    private val shouldDeferBackgroundWorkProvider: () -> Boolean = { false },
    private val onImportStarted: ((path: String, trackType: TrackType) -> Unit)? = null,
    private val onImportProgress: ((ImportProgressUpdate) -> Unit)? = null,
    private val onImportFinished: ((success: Boolean, path: String, trackType: TrackType, clipId: Int?, importedDurationMs: Long, error: String?) -> Unit)? = null,
    private val onImportedClip: (clipId: Int, importPath: String, importedDurationMs: Long, trackType: TrackType, requestedStartTimeMs: Long) -> Long,
) {
    private data class PendingImportRequest(
        val path: String,
        val requestedTrackType: TrackType,
        val requestedStartTimeMs: Long,
    )

    companion object {
        private const val TAG = "[UI]"
        private const val AUTO_GHOST_PROXY_BUILD_ENABLED = true
        private const val PROXY_POLL_INTERVAL_MS = 1200L
        private const val PROXY_MAX_POLL_ATTEMPTS = 180
        private const val LOW_END_PROXY_START_DELAY_MS = 5200L
        private const val MID_TIER_PROXY_START_DELAY_MS = 3200L
        private const val HIGH_TIER_PROXY_START_DELAY_MS = 1800L
        private const val PROXY_BUSY_RETRY_DELAY_MS = 2200L
        private const val MAX_NATIVE_IMAGE_LONG_EDGE_PX = 4096
        @Volatile private var sharedVideoAppendCursorMs = 0L
        private val sharedTrackAppendCursorMs = ConcurrentHashMap<String, Long>()
        private val VIDEO_EXTENSIONS =
            setOf(
                "mp4",
                "mov",
                "avi",
                "mkv",
                "webm",
                "m4v",
                "3gp",
                "3gpp",
                "3g2",
                "3gp2",
                "ts",
                "mts",
                "m2ts",
                "mpeg",
                "mpg",
                "mxf",
                "wmv",
                "flv",
                "hevc",
                "h265",
                "h264",
            )
        private val IMAGE_EXTENSIONS =
            setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "tif", "tiff", "heic", "heif", "avif")
    }

    private var nextImportTrackType: TrackType = TrackType.VIDEO
    private val pickerImportTrackTypes = mutableMapOf<Int, TrackType>()
    private val pickerImportStartTimesMs = mutableMapOf<Int, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingProxyBuilds = mutableMapOf<Int, Runnable>()
    private val pendingImports = ArrayDeque<PendingImportRequest>()
    private var activeImportRequest: PendingImportRequest? = null
    @Volatile private var latestWarmupGeneration = 0
    @Volatile private var importInFlight = false
    @Volatile private var lastImportCompletedElapsedMs = 0L
    @Volatile private var lastResolveImportError: String? = null

    private fun dispatchImportProgress(update: ImportProgressUpdate) {
        mainHandler.post {
            onImportProgress?.invoke(update)
        }
    }

    private fun progressPercent(bytesCopied: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L || bytesCopied < 0L) return null
        return ((bytesCopied.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    private fun progressEtaMs(bytesCopied: Long, totalBytes: Long, elapsedMs: Long): Long? {
        if (totalBytes <= 0L || bytesCopied <= 0L || elapsedMs < 400L) return null
        val remainingBytes = (totalBytes - bytesCopied).coerceAtLeast(0L)
        return ((remainingBytes.toDouble() * elapsedMs.toDouble()) / bytesCopied.toDouble()).roundToInt().toLong()
    }

    private fun displayNameFor(path: String): String {
        return File(path).name.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast('/').substringAfterLast(':').ifBlank { "media" }
    }

    fun setNextImportTrackType(trackType: TrackType) {
        nextImportTrackType = trackType
    }

    fun preparePickerImportTrackType(
        requestCode: Int,
        trackType: TrackType,
        requestedStartTimeMs: Long = playheadTimeMsProvider().coerceAtLeast(0L),
    ) {
        nextImportTrackType = trackType
        pickerImportTrackTypes[requestCode] = trackType
        pickerImportStartTimesMs[requestCode] = requestedStartTimeMs.coerceAtLeast(0L)
    }

    fun importFromPath(path: String) {
        enqueueImport(path, nextImportTrackType, playheadTimeMsProvider().coerceAtLeast(0L))
    }

    fun importQuickSample(): Boolean {
        val candidate = findQuickImportCandidate() ?: return false
        enqueueImport(candidate.absolutePath, nextImportTrackType, playheadTimeMsProvider().coerceAtLeast(0L))
        return true
    }

    fun resetPendingState() {
        if (importInFlight) {
            Log.d(TAG, "Skipping import reset while import is still running")
            return
        }
        pendingImports.clear()
        activeImportRequest = null
        pickerImportTrackTypes.clear()
        pickerImportStartTimesMs.clear()
        importRetryCount = 0
        sharedVideoAppendCursorMs = 0L
        sharedTrackAppendCursorMs.clear()
        nextImportTrackType = TrackType.VIDEO
    }

    fun handlePickerResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        fallbackTrackType: TrackType? = null,
        fallbackStartTimeMs: Long? = null,
    ): Boolean {
        val preparedTrackType = pickerImportTrackTypes.remove(requestCode)
        val preparedStartTimeMs = pickerImportStartTimesMs.remove(requestCode)
        val requestedTrackType = preparedTrackType ?: fallbackTrackType ?: return false
        val capturedPlayheadMs = playheadTimeMsProvider().coerceAtLeast(0L)
        val finalRequestedStartTimeMs = preparedStartTimeMs ?: fallbackStartTimeMs ?: capturedPlayheadMs
        Log.d(
            TAG,
            "handlePickerResult requestCode=$requestCode resultCode=$resultCode preparedTrack=$preparedTrackType fallbackTrack=$fallbackTrackType preparedStart=$preparedStartTimeMs fallbackStart=$fallbackStartTimeMs hasUri=${data?.data != null}",
        )
        if (resultCode != Activity.RESULT_OK) {
            Log.d(TAG, "Picker cancelled for requestCode=$requestCode track=$requestedTrackType")
            return true
        }
        val pickerData = data ?: run {
            Log.w(TAG, "Picker returned without intent data for requestCode=$requestCode track=$requestedTrackType")
            return true
        }
        val uri = pickerData.data ?: run {
            Log.w(TAG, "Picker returned without data for requestCode=$requestCode track=$requestedTrackType")
            return true
        }
        persistPickerReadPermission(pickerData, uri)
        val displayName = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "media"
        dispatchImportProgress(
            ImportProgressUpdate(
                phase = ImportProgressPhase.OPENING,
                displayName = displayName,
                trackType = requestedTrackType,
                path = uri.toString(),
                message = "Opening selected media",
            ),
        )

        // Copy file on background thread to avoid UI freeze
        Thread {
            lastResolveImportError = null
            val resolvedVideoPath = resolveImportPath(uri, requestedTrackType, displayName) ?: run {
                val resolveError = lastResolveImportError ?: "Unable to open selected media"
                lastResolveImportError = null
                Log.e(TAG, "Unable to resolve picker uri for requestCode=$requestCode track=$requestedTrackType uri=$uri error=$resolveError")
                mainHandler.post {
                    onImportProgress?.invoke(
                        ImportProgressUpdate(
                            phase = ImportProgressPhase.FAILED,
                            displayName = displayName,
                            trackType = requestedTrackType,
                            path = uri.toString(),
                            message = resolveError,
                        ),
                    )
                    onImportFinished?.invoke(
                        false,
                        uri.toString(),
                        requestedTrackType,
                        null,
                        0L,
                        resolveError,
                    )
                    Toast.makeText(activity, resolveError, Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            Log.d(
                TAG,
                "Picker media resolved for requestCode=$requestCode track=$requestedTrackType path=$resolvedVideoPath",
            )
            dispatchImportProgress(
                ImportProgressUpdate(
                    phase = ImportProgressPhase.PREPARING,
                    displayName = displayNameFor(resolvedVideoPath),
                    trackType = requestedTrackType,
                    path = resolvedVideoPath,
                    percent = 100,
                    message = "Preparing timeline",
                ),
            )
            mainHandler.postDelayed({
                enqueueImport(
                    resolvedVideoPath,
                    requestedTrackType,
                    finalRequestedStartTimeMs,
                )
            }, importRetryDelayMs)
        }.start()
        return true
    }

    private fun persistPickerReadPermission(data: Intent, uri: Uri) {
        val readFlag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persistFlags =
            if (readFlag != 0) {
                readFlag
            } else {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, persistFlags)
        }.onFailure { error ->
            Log.d(TAG, "Picker URI permission was transient for $uri: ${error.message}")
        }
    }

    private var importRetryCount = 0
    private val maxImportRetries = 40
    private val importRetryDelayMs = 450L

    private fun enqueueImport(path: String, trackType: TrackType, requestedStartTimeMs: Long) {
        pendingImports.addLast(
            PendingImportRequest(
                path = path,
                requestedTrackType = trackType,
                requestedStartTimeMs = requestedStartTimeMs.coerceAtLeast(0L),
            ),
        )
        mainHandler.post { maybeImportPendingClip() }
    }

    fun maybeImportPendingClip() {
        if (importInFlight) {
            return
        }
        val request =
            activeImportRequest
                ?: pendingImports.removeFirstOrNull()?.also { activeImportRequest = it }
                ?: return
        val importPath = request.path
        val previewView = previewViewProvider() ?: return
        val mediaKind = TimelineImportRouter.detectMediaKind(importPath)
        val trackType = TimelineImportRouter.resolveTrack(request.requestedTrackType, mediaKind)
        if (importRetryCount == 0) {
            onImportStarted?.invoke(importPath, trackType)
            dispatchImportProgress(
                ImportProgressUpdate(
                    phase = ImportProgressPhase.TIMELINE,
                    displayName = displayNameFor(importPath),
                    trackType = trackType,
                    path = importPath,
                    percent = 100,
                    message = "Adding clip to timeline",
                ),
            )
        }
        importInFlight = true

        // Heavy JNI (FFmpeg open + probe) — run off main thread
        Thread {
            try {
                val nativeImportPath = preparePathForNativeImport(importPath)
                val existingClipCount =
                    timelineManagerProvider()?.getClips()?.size
                        ?: timelineProvider().getClips().size
                val requestedStartTimeMs = resolveRequestedStartTimeMs(request.requestedStartTimeMs)
                val requestedZOrder = resolveImportZOrder(trackType)
                val requestedTrackLane = resolveImportTrackLane(trackType, requestedZOrder)
                Log.d(TAG, "Attempting NativeBridge.addClip for: $nativeImportPath at start=$requestedStartTimeMs track=$trackType")
                val clipId = NativeBridge.addClip(
                    previewView = previewView,
                    videoPath = nativeImportPath,
                    trackType = trackType.nativeRoleName(),
                    startTimeMs = requestedStartTimeMs,
                    trackLane = requestedTrackLane,
                    zOrder = requestedZOrder,
                )
                Log.d(TAG, "NativeBridge.addClip result clipId: $clipId")
                if (clipId <= 0) {
                    if (importRetryCount >= maxImportRetries) {
                        Log.e(TAG, "Import failed after $maxImportRetries retries: $nativeImportPath")
                        importRetryCount = 0
                        importInFlight = false
                        activeImportRequest = null
                        dispatchImportProgress(
                            ImportProgressUpdate(
                                phase = ImportProgressPhase.FAILED,
                                displayName = displayNameFor(nativeImportPath),
                                trackType = trackType,
                                path = nativeImportPath,
                                message = "Import failed",
                            ),
                        )
                        onImportFinished?.invoke(
                            false,
                            nativeImportPath,
                            trackType,
                            null,
                            0L,
                            "native_clip_timeout",
                        )
                        mainHandler.post {
                            Toast.makeText(activity, "Import failed. Please try again.", Toast.LENGTH_SHORT).show()
                            maybeImportPendingClip()
                        }
                        return@Thread
                    }
                    importRetryCount++
                    importInFlight = false
                    Log.w(TAG, "Deferring clip import until preview is ready: $nativeImportPath (clipId was $clipId, retry $importRetryCount)")
                    dispatchImportProgress(
                        ImportProgressUpdate(
                            phase = ImportProgressPhase.TIMELINE,
                            displayName = displayNameFor(nativeImportPath),
                            trackType = trackType,
                            path = nativeImportPath,
                            percent = 100,
                            message = "Waiting for preview renderer",
                        ),
                    )
                    mainHandler.postDelayed({ maybeImportPendingClip() }, importRetryDelayMs)
                    return@Thread
                }
                importRetryCount = 0

                    val nativeClipIds = previewView.getClipIds()
                    val nativeClipDurations = nativeClipIds.associateWith { previewView.getClipDuration(it) }
                    val importedDurationMs = nativeClipDurations[clipId]?.coerceAtLeast(1L) ?: 1L
                    val safeDurationMs = importedDurationMs

                mainHandler.post {
                    importInFlight = false
                    activeImportRequest = null
                    nextImportTrackType = trackType
                    latestWarmupGeneration += 1
                    val importCompletedElapsedMs = android.os.SystemClock.elapsedRealtime()
                    val burstImport =
                        (importCompletedElapsedMs - lastImportCompletedElapsedMs) in 1..1400L
                    lastImportCompletedElapsedMs = importCompletedElapsedMs
                    val shouldDeferUiHydration =
                        (shouldDeferBackgroundWorkProvider() || burstImport) && existingClipCount > 0
                    timelineManagerProvider()?.setClipVisibility(clipId, true)
                    previewView.toggleLayerVisibility(clipId, true)
                    if (!shouldDeferUiHydration) {
                        val timeline = timelineProvider()
                        timeline.clear()
                        val layoutResult = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
                        val clipsJson = layoutResult?.data?.optJSONArray("clips")
                        val nativeStartTimes = mutableMapOf<Int, Long>()
                        if (clipsJson != null) {
                            for (i in 0 until clipsJson.length()) {
                                val node = clipsJson.optJSONObject(i) ?: continue
                                nativeStartTimes[node.optInt("clipId")] = node.optLong("startTimeMs", 0L)
                            }
                        }
                        val existingClipMap = timelineManagerProvider()?.getClips()?.associate { it.id to it.startTimeMs }.orEmpty()
                        for (id in nativeClipIds) {
                            val startMs = nativeStartTimes[id] ?: if (id == clipId) requestedStartTimeMs.coerceAtLeast(0L) else (existingClipMap[id] ?: 0L)
                            timeline.addClip(clipId = id, durationMs = nativeClipDurations[id] ?: 0L, startTimeMs = startMs)
                        }
                        val timelineManager = timelineManagerProvider()
                        timelineManager?.syncClips(timeline.getClips())
                        timelineManager?.setClipLayerIndex(clipId, requestedZOrder)
                        timelineManager?.setClipVisibility(clipId, true)
                        timelineManager?.selectClip(clipId)
                    }

                    forceNativeClipTiming(
                        clipId = clipId,
                        trackType = trackType,
                        requestedStartTimeMs = requestedStartTimeMs,
                        durationMs = safeDurationMs,
                    )
                    onImportFinished?.invoke(
                        true,
                        nativeImportPath,
                        trackType,
                        clipId,
                        safeDurationMs,
                        null,
                    )
                    val revealTimeMs = onImportedClip(clipId, nativeImportPath, safeDurationMs, trackType, requestedStartTimeMs)
                    dispatchImportProgress(
                        ImportProgressUpdate(
                            phase = ImportProgressPhase.READY,
                            displayName = displayNameFor(nativeImportPath),
                            trackType = trackType,
                            path = nativeImportPath,
                            percent = 100,
                            message = "Ready in editor",
                        ),
                    )
                    updateTrackAppendCursor(trackType, requestedStartTimeMs + importedDurationMs.coerceAtLeast(1L))

                    warmImportedPreview(
                        previewView = previewView,
                        revealTimeMs = revealTimeMs,
                        importedDurationMs = importedDurationMs,
                        trackType = trackType,
                        generation = latestWarmupGeneration,
                        burstImport = burstImport,
                    )

                    Toast.makeText(
                        activity,
                        TimelineImportRouter.importSuccessMessage(trackType, mediaKind),
                        Toast.LENGTH_SHORT,
                    ).show()

                    maybeStartGhostProxyBuild(clipId, nativeImportPath, trackType)
                    maybeImportPendingClip()
                }
            } catch (error: Exception) {
                importInFlight = false
                importRetryCount = 0
                activeImportRequest = null
                dispatchImportProgress(
                    ImportProgressUpdate(
                        phase = ImportProgressPhase.FAILED,
                        displayName = displayNameFor(importPath),
                        trackType = trackType,
                        path = importPath,
                        message = error.message ?: "Import failed",
                    ),
                )
                onImportFinished?.invoke(
                    false,
                    importPath,
                    trackType,
                    null,
                    0L,
                    error.message ?: "import_exception",
                )
                Log.e(TAG, "Import thread failed for $importPath", error)
                mainHandler.post { maybeImportPendingClip() }
            }
        }.start()
    }

    private fun forceNativeClipTiming(
        clipId: Int,
        trackType: TrackType,
        requestedStartTimeMs: Long,
        durationMs: Long,
    ) {
        val safeStartMs = requestedStartTimeMs.coerceAtLeast(0L)
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "UPDATE_CLIP_TIMING",
                params = mapOf(
                    "clipId" to clipId,
                    "newStartTimeMs" to safeStartMs,
                    "newDurationMs" to safeDurationMs,
                    "newSourceInMs" to 0L,
                    "newSourceOutMs" to safeDurationMs,
                    "originalStartTimeMs" to safeStartMs,
                    "originalDurationMs" to safeDurationMs,
                    "originalSourceInMs" to 0L,
                    "originalSourceOutMs" to safeDurationMs,
                    "previewOnly" to false,
                    "applyMagnetic" to false,
                ),
            )
        }.getOrNull()
        if (result?.success != true) {
            Log.w(TAG, "Unable to force import timing for clip=$clipId start=$safeStartMs: ${result?.message}")
        }
    }

    private fun updateTrackAppendCursor(trackType: TrackType, endTimeMs: Long) {
        val safeEndMs = endTimeMs.coerceAtLeast(0L)
        sharedTrackAppendCursorMs.merge(trackType.name, safeEndMs, ::maxOf)
        if (trackType == TrackType.VIDEO) {
            sharedVideoAppendCursorMs = maxOf(sharedVideoAppendCursorMs, safeEndMs)
        }
    }

    private fun preparePathForNativeImport(path: String): String {
        val resolvedPath = MediaPathResolver.normalizeForFileAccess(path)
        val source = File(resolvedPath)
        if (!source.exists() || !source.isFile) {
            return path
        }
        if (!shouldStageExternalPathForNative(source)) {
            return normalizeImageForNativeImport(source.absolutePath, mimeType = null)
        }

        val stagedDir = File(activity.filesDir, "native_imports").apply { mkdirs() }
        val safeName = sanitizeImportFileName(source.name).ifBlank { "import.mp4" }
        val baseName = safeName.substringBeforeLast('.', safeName).take(48).ifBlank { "import" }
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "mp4"
        val fingerprint = Integer.toHexString("${source.absolutePath}:${source.length()}:${source.lastModified()}".hashCode())
        val target = File(stagedDir, "${baseName}_${fingerprint}.${extension}")

        if (!target.exists() || target.length() != source.length()) {
            if (!ImportStorageGuard.hasEnoughSpace(stagedDir, source.length())) {
                throw IllegalStateException(ImportStorageGuard.failureMessage(stagedDir, source.length()))
            }
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Staged external import for native access: ${source.absolutePath} -> ${target.absolutePath}")
        }
        return normalizeImageForNativeImport(target.absolutePath, mimeType = null)
    }

    private fun shouldStageExternalPathForNative(source: File): Boolean {
        val absolute = source.absolutePath
        val cachePath = activity.cacheDir.absolutePath
        val filesPath = activity.filesDir.absolutePath
        if (absolute.startsWith(cachePath) || absolute.startsWith(filesPath)) {
            return false
        }
        return MediaPathResolver.isPrimaryExternalPath(absolute)
    }

    private fun warmImportedPreview(
        previewView: VideoPreviewView,
        revealTimeMs: Long,
        importedDurationMs: Long,
        trackType: TrackType,
        generation: Int,
        burstImport: Boolean,
    ) {
        if (trackType != TrackType.VIDEO) return
        if (burstImport || shouldDeferBackgroundWorkProvider()) return

        val profile = DeviceDetector.getQualityProfile()
        val revealMs = revealTimeMs.coerceAtLeast(0L)
        val warmStepMs = profile.predictiveSampleStepMs.toLong().coerceIn(90L, 220L)
        val forwardWarmTargetMs =
            when {
                trackType == TrackType.TEXT -> revealMs
                importedDurationMs <= 1L -> revealMs
                else -> {
                    val clipEndMs = (revealMs + importedDurationMs - 1L).coerceAtLeast(revealMs)
                    (revealMs + maxOf(warmStepMs * 2L, 120L)).coerceAtMost(clipEndMs)
                }
            }

        val warmTargets = mutableListOf(revealMs)
        if (forwardWarmTargetMs > revealMs) {
            if (DeviceDetector.getDeviceTier() == DeviceDetector.DeviceTier.LOW) {
                warmTargets += forwardWarmTargetMs
            } else {
                val midTarget = (revealMs + warmStepMs).coerceAtMost(forwardWarmTargetMs)
                if (midTarget > revealMs) {
                    warmTargets += midTarget
                }
                if (forwardWarmTargetMs > midTarget) {
                    warmTargets += forwardWarmTargetMs
                }
                warmTargets += revealMs
            }
        }

        Thread {
            previewView.ensureNativeSurfaceBinding()
            previewView.syncNativeSurfaceSizeToView()
            warmTargets.forEachIndexed { index, targetMs ->
                val shouldDefer = shouldDeferBackgroundWorkProvider()
                val canDeferThisStep = shouldDefer && index > 0
                if (generation != latestWarmupGeneration || isPlayingProvider() || canDeferThisStep) {
                    Log.d(
                        TAG,
                        "Import preview warmup canceled step=${index + 1} track=$trackType playing=${isPlayingProvider()} busy=$shouldDefer",
                    )
                    return@Thread
                }
                var success = attemptPreviewWarmSeek(targetMs)
                if (!success) {
                    previewView.ensureNativeSurfaceBinding()
                    previewView.syncNativeSurfaceSizeToView()
                    Thread.sleep(40L)
                    val shouldDeferAfterRebind = shouldDeferBackgroundWorkProvider()
                    val canDeferAfterRebind = shouldDeferAfterRebind && index > 0
                    if (generation != latestWarmupGeneration || isPlayingProvider() || canDeferAfterRebind) {
                        Log.d(
                            TAG,
                            "Import preview warmup canceled after rebind step=${index + 1} track=$trackType busy=$shouldDeferAfterRebind",
                        )
                        return@Thread
                    }
                    success = attemptPreviewWarmSeek(targetMs)
                }
                Log.d(
                    TAG,
                    "Import preview warmup step=${index + 1}/${warmTargets.size} track=$trackType target=${targetMs}ms success=$success",
                )
                Thread.sleep(if (index == warmTargets.lastIndex) 24L else 36L)
            }
        }.start()
    }

    private fun attemptPreviewWarmSeek(targetTimeMs: Long): Boolean {
        val result =
            runCatching {
                NativeBridge.executeCommand(
                    action = "SEEK",
                    params = mapOf("timeMs" to targetTimeMs.coerceAtLeast(0L)),
                )
            }.getOrNull()
        return result?.success == true
    }

    private fun resolveImportZOrder(trackType: TrackType): Int {
        return when (trackType) {
            TrackType.TEXT -> 400
            TrackType.OVERLAY -> 200
            TrackType.LAYER -> 120
            else -> 0
        }
    }

    private fun resolveImportTrackLane(trackType: TrackType, requestedZOrder: Int): Int {
        return when (trackType) {
            TrackType.LAYER -> 0
            TrackType.OVERLAY -> 0
            TrackType.TEXT -> 0
            else -> requestedZOrder.coerceAtLeast(0)
        }
    }

    private fun resolveRequestedStartTimeMs(requestedStartTimeMs: Long): Long {
        return requestedStartTimeMs.coerceAtLeast(0L)
    }

    private fun maybeStartGhostProxyBuild(clipId: Int, importPath: String, trackType: TrackType) {
        if (!AUTO_GHOST_PROXY_BUILD_ENABLED) {
            return
        }
        if (trackType != TrackType.VIDEO) {
            return
        }
        if (isAutomationSeedMediaPath(importPath)) {
            return
        }
        val longEdgePx = resolveVideoLongEdgePx(importPath) ?: return
        if (!DeviceDetector.shouldPreferProxyFor(longEdgePx)) {
            return
        }
        val profile = DeviceDetector.getQualityProfile()
        scheduleProxyBuild(
            clipId = clipId,
            importPath = importPath,
            maxLongEdgePx = profile.proxyLongEdgePx,
            targetFps = profile.previewFps.coerceAtMost(30),
        )
    }

    private fun isAutomationSeedMediaPath(importPath: String): Boolean {
        return importPath.contains("/automation_samples/") &&
            importPath.endsWith("quick_sample_video.mp4", ignoreCase = true)
    }

    private fun scheduleProxyBuild(
        clipId: Int,
        importPath: String,
        maxLongEdgePx: Int,
        targetFps: Int,
        delayOverrideMs: Long? = null,
    ) {
        pendingProxyBuilds.remove(clipId)?.let(mainHandler::removeCallbacks)
        val delayMs =
            delayOverrideMs
                ?: when (DeviceDetector.getDeviceTier()) {
                    DeviceDetector.DeviceTier.LOW -> LOW_END_PROXY_START_DELAY_MS
                    DeviceDetector.DeviceTier.HIGH -> HIGH_TIER_PROXY_START_DELAY_MS
                    DeviceDetector.DeviceTier.MID -> MID_TIER_PROXY_START_DELAY_MS
                }
        val runnable =
            Runnable {
                pendingProxyBuilds.remove(clipId)
                val shouldDefer = shouldDeferBackgroundWorkProvider()
                if (isPlayingProvider() || shouldDefer) {
                    Log.d(TAG, "Proxy build deferred while busy clip=$clipId playing=${isPlayingProvider()} busy=$shouldDefer")
                    scheduleProxyBuild(
                        clipId = clipId,
                        importPath = importPath,
                        maxLongEdgePx = maxLongEdgePx,
                        targetFps = targetFps,
                        delayOverrideMs = PROXY_BUSY_RETRY_DELAY_MS,
                    )
                    return@Runnable
                }
                startProxyBuild(
                    clipId = clipId,
                    importPath = importPath,
                    maxLongEdgePx = maxLongEdgePx,
                    targetFps = targetFps,
                )
            }
        pendingProxyBuilds[clipId] = runnable
        Log.d(TAG, "Proxy build scheduled clip=$clipId delay=${delayMs}ms path=$importPath")
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun startProxyBuild(
        clipId: Int,
        importPath: String,
        maxLongEdgePx: Int,
        targetFps: Int,
    ) {
        val proxyOutputPath = proxyOutputPathFor(clipId, importPath)
        val started = runCatching {
            NativeBridge.buildClipProxy(
                clipId = clipId,
                sourcePath = importPath,
                outputPath = proxyOutputPath,
                maxLongEdgePx = maxLongEdgePx.coerceIn(240, 720),
                targetFps = targetFps.coerceIn(24, 30),
            )
        }.getOrDefault(false)
        if (!started) {
            Log.w(TAG, "Proxy build not started for clip=$clipId path=$importPath")
            return
        }
        Log.d(TAG, "Proxy build started for clip=$clipId output=$proxyOutputPath")
        pollProxyStatus(clipId, attempt = 0)
    }

    private fun pollProxyStatus(clipId: Int, attempt: Int) {
        if (attempt >= PROXY_MAX_POLL_ATTEMPTS) {
            Log.w(TAG, "Proxy poll timeout clip=$clipId")
            return
        }
        val status = runCatching { NativeBridge.getClipProxyStatus(clipId) }.getOrNull() ?: return
        when {
            status.ready -> {
                Log.d(TAG, "Proxy ready clip=$clipId proxy=${status.proxyPath}")
                val timelineClips = timelineManagerProvider()?.getClips().orEmpty()
                if (timelineClips.size == 1 && timelineClips.firstOrNull()?.id == clipId) {
                    val shouldDefer = shouldDeferBackgroundWorkProvider()
                    if (isPlayingProvider() || shouldDefer) {
                        Log.d(TAG, "Proxy activation deferred while busy clip=$clipId playing=${isPlayingProvider()} busy=$shouldDefer")
                        mainHandler.postDelayed(
                            { pollProxyStatus(clipId, attempt + 1) },
                            PROXY_BUSY_RETRY_DELAY_MS,
                        )
                        return
                    }
                    val activated = runCatching { NativeBridge.activateClipProxy(clipId) }.getOrDefault(false)
                    Log.d(TAG, "Proxy activation clip=$clipId activated=$activated")
                    if (activated) {
                        // Force first frame render after proxy is ready, but only while idle.
                        val pv = previewViewProvider()
                        if (pv != null) {
                            Thread { NativeBridge.seekToTime(pv, playheadTimeMsProvider().coerceAtLeast(0L)) }.start()
                        }
                    }
                }
            }
            status.failed -> {
                Log.w(TAG, "Proxy build failed clip=$clipId message=${status.message}")
            }
            else -> {
                mainHandler.postDelayed(
                    { pollProxyStatus(clipId, attempt + 1) },
                    PROXY_POLL_INTERVAL_MS,
                )
            }
        }
    }

    private fun proxyOutputPathFor(clipId: Int, sourcePath: String): String {
        val proxyDir = File(activity.cacheDir, "ghost_proxy").apply { mkdirs() }
        val baseName = File(sourcePath).nameWithoutExtension
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "clip_$clipId"
        return File(proxyDir, "${baseName}_${clipId}_p360.mp4").absolutePath
    }

    private fun resolveVideoLongEdgePx(sourcePath: String): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourcePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            maxOf(width, height).takeIf { it > 0 }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to probe media dimensions for proxy: ${error.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveImportPath(uri: Uri, trackType: TrackType, importDisplayName: String): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return uri.toString()

        val importsDir = File(activity.filesDir, "imports").apply { mkdirs() }
        val mimeType = runCatching { activity.contentResolver.getType(uri) }.getOrNull()
        val fileName = resolveImportFileName(uri, mimeType)
        val targetFile = File(importsDir, uniqueImportFileName(sanitizeImportFileName(fileName), uri))
        val sourceBytes = ContentUriImportResolver.queryLength(activity.contentResolver, uri)

        // If already copied (same name), reuse
        if (targetFile.exists() && targetFile.length() > 0) {
            dispatchImportProgress(
                ImportProgressUpdate(
                    phase = ImportProgressPhase.PREPARING,
                    displayName = importDisplayName,
                    trackType = trackType,
                    path = targetFile.absolutePath,
                    percent = 100,
                    message = "Using existing imported media",
                ),
            )
            return normalizeImageForNativeImport(targetFile.absolutePath, mimeType)
        }

        if (!ImportStorageGuard.hasEnoughSpace(importsDir, sourceBytes)) {
            val message = ImportStorageGuard.failureMessage(importsDir, sourceBytes)
            lastResolveImportError = message
            dispatchImportProgress(
                ImportProgressUpdate(
                    phase = ImportProgressPhase.FAILED,
                    displayName = importDisplayName,
                    trackType = trackType,
                    path = uri.toString(),
                    bytesCopied = 0L,
                    totalBytes = sourceBytes,
                    message = message,
                ),
            )
            Log.w(TAG, "Import rejected for storage: uri=$uri size=$sourceBytes message=$message")
            return null
        }

        val copied = ContentUriImportResolver.copyToFile(
            contentResolver = activity.contentResolver,
            uri = uri,
            targetFile = targetFile,
            logTag = TAG,
            label = "import",
            onProgress = { bytesCopied, totalBytes, elapsedMs ->
                dispatchImportProgress(
                    ImportProgressUpdate(
                        phase = ImportProgressPhase.COPYING,
                        displayName = importDisplayName,
                        trackType = trackType,
                        path = targetFile.absolutePath,
                        bytesCopied = bytesCopied,
                        totalBytes = totalBytes,
                        elapsedMs = elapsedMs,
                        etaMs = progressEtaMs(bytesCopied, totalBytes, elapsedMs),
                        percent = progressPercent(bytesCopied, totalBytes),
                        message = "Copying media",
                    ),
                )
            },
        )
        return if (copied) {
            normalizeImageForNativeImport(targetFile.absolutePath, mimeType)
        } else {
            lastResolveImportError = "Unable to copy selected media"
            null
        }
    }

    private fun normalizeImageForNativeImport(path: String, mimeType: String?): String {
        if (!isImageImportPath(path, mimeType)) return path

        val source = File(MediaPathResolver.normalizeForFileAccess(path))
        if (!source.exists() || !source.isFile || source.length() <= 0L) return path
        if (source.parentFile?.name == "native_image_imports" && source.extension.equals("png", ignoreCase = true)) {
            return source.absolutePath
        }

        val outputDir = File(activity.filesDir, "native_image_imports").apply { mkdirs() }
        val baseName = sanitizeImportFileName(source.nameWithoutExtension).take(48).ifBlank { "image" }
        val fingerprint = Integer.toHexString("${source.absolutePath}:${source.length()}:${source.lastModified()}".hashCode())
        val target = File(outputDir, "${baseName}_${fingerprint}.png")
        if (target.exists() && target.length() > 0L) return target.absolutePath

        var bitmap: Bitmap? = null
        return try {
            bitmap = decodeImageForNativeImport(source)
            val decoded = bitmap ?: return path
            target.outputStream().use { output ->
                if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    return path
                }
                output.flush()
            }
            if (target.length() <= 0L) {
                runCatching { target.delete() }
                path
            } else {
                Log.d(TAG, "Normalized image import for native preview: ${source.absolutePath} -> ${target.absolutePath}")
                target.absolutePath
            }
        } catch (error: Exception) {
            runCatching { target.delete() }
            Log.w(TAG, "Image normalization skipped for ${source.absolutePath}: ${error.message}")
            path
        } finally {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun isImageImportPath(path: String, mimeType: String?): Boolean {
        if (mimeType?.lowercase()?.startsWith("image/") == true) return true
        return path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    private fun decodeImageForNativeImport(source: File): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val longEdge = maxOf(width, height)
                if (longEdge > MAX_NATIVE_IMAGE_LONG_EDGE_PX) {
                    val scale = MAX_NATIVE_IMAGE_LONG_EDGE_PX.toFloat() / longEdge.toFloat()
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (longEdge / sampleSize > MAX_NATIVE_IMAGE_LONG_EDGE_PX) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sampleSize.coerceAtLeast(1)
                },
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query display name: ${e.message}")
            null
        }
    }

    private fun resolveImportFileName(uri: Uri, mimeType: String?): String {
        val displayName = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
        val mimeExtension = extensionFromMimeType(mimeType)
        if (displayName.isNullOrBlank()) {
            return buildFallbackImportFileName(mimeType)
        }

        val safeName = sanitizeImportFileName(displayName)
        val extension = safeName.substringAfterLast('.', "").lowercase()
        if (mimeExtension.isNullOrBlank()) {
            return safeName
        }

        val extensionKind =
            if (extension.isBlank()) {
                TimelineImportRouter.MediaKind.UNKNOWN
            } else {
                TimelineImportRouter.detectMediaKind("import.$extension")
            }
        val shouldReplaceExtension =
            extension.isBlank() ||
                extension in setOf("bin", "tmp", "download") ||
                extensionKind == TimelineImportRouter.MediaKind.UNKNOWN
        if (!shouldReplaceExtension) {
            return safeName
        }

        val baseName = safeName.substringBeforeLast('.', safeName).ifBlank { "import_${System.currentTimeMillis()}" }
        return "$baseName.$mimeExtension"
    }

    private fun sanitizeImportFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun uniqueImportFileName(fileName: String, uri: Uri): String {
        val safeName = fileName.ifBlank { buildFallbackImportFileName(null) }
        val baseName = safeName.substringBeforeLast('.', safeName).take(48).ifBlank { "import" }
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val uriKey = Integer.toHexString(uri.toString().hashCode())
        return if (extension == null) {
            "${baseName}_$uriKey"
        } else {
            "${baseName}_$uriKey.$extension"
        }
    }

    private fun buildFallbackImportFileName(mimeType: String?): String {
        val extension = extensionFromMimeType(mimeType)
            ?: "bin"
        return "import_${System.currentTimeMillis()}.$extension"
    }

    private fun extensionFromMimeType(mimeType: String?): String? {
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val topLevel = normalizedMime.substringBefore('/', "")
        if (topLevel !in setOf("video", "image", "audio")) {
            return MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(normalizedMime)
                ?.takeIf { it.isNotBlank() }
        }
        return normalizedMime
            .substringAfter('/', "")
            .let { subtype ->
                when {
                    subtype.isBlank() -> null
                    subtype == "mp4" -> "mp4"
                    subtype == "jpeg" -> "jpg"
                    subtype == "quicktime" -> "mov"
                    subtype == "3gpp" -> "3gp"
                    subtype == "3gpp2" -> "3g2"
                    subtype == "x-matroska" -> "mkv"
                    subtype == "x-msvideo" -> "avi"
                    subtype == "x-flv" -> "flv"
                    subtype == "x-ms-wmv" -> "wmv"
                    subtype == "mp2t" -> "ts"
                    subtype == "x-m4v" -> "m4v"
                    subtype == "hevc" || subtype == "h265" -> "hevc"
                    subtype == "avc" || subtype == "h264" -> "h264"
                    subtype == "mpeg" -> "mpg"
                    else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: subtype
                }
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun supportsTrackImport(file: File, trackType: TrackType): Boolean {
        val extension = file.extension.lowercase()
        return when (trackType) {
            TrackType.VIDEO, TrackType.OVERLAY, TrackType.LAYER -> extension in VIDEO_EXTENSIONS || extension in IMAGE_EXTENSIONS
            TrackType.AUDIO -> extension in setOf("mp3", "aac", "wav", "flac", "ogg", "m4a", "wma", "opus")
            TrackType.TEXT -> false
        }
    }

    private fun findQuickImportCandidate(): File? {
        val cacheCandidates = sequenceOf(
            File(activity.filesDir, "imports"),
            File(activity.filesDir, "native_imports"),
            File(activity.cacheDir, "pro_imports"),
            File(activity.cacheDir, "imports"),
        )
        val externalCandidates = MediaPathResolver.commonExternalMediaDirs()
        val candidateDirs = (cacheCandidates + externalCandidates)
            .filter { it.exists() && it.isDirectory }
            .toList()
        fun newestMatching(predicate: (String) -> Boolean): File? {
            return candidateDirs
                .asSequence()
                .flatMap { dir ->
                    dir.listFiles()
                        ?.asSequence()
                        ?.filter { file ->
                            val extension = file.extension.lowercase()
                            file.isFile &&
                                file.canRead() &&
                                file.length() > 0L &&
                                predicate(extension)
                        }
                        ?: emptySequence()
                }
                .sortedByDescending { it.lastModified() }
                .firstOrNull()
        }

        return when (nextImportTrackType) {
            TrackType.VIDEO, TrackType.OVERLAY, TrackType.LAYER ->
                newestMatching { it in VIDEO_EXTENSIONS }
                    ?: AutomationMediaSampleStore.ensureVideoSample(activity)
                    ?: newestMatching { it in IMAGE_EXTENSIONS }
            TrackType.AUDIO ->
                newestMatching { it in setOf("mp3", "aac", "wav", "flac", "ogg", "m4a", "wma", "opus") }
            TrackType.TEXT -> null
        }
    }
}
