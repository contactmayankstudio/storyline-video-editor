package com.video.engine

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.video.engine.pro.model.TrackType
import com.video.engine.timeline.MultiClipTimeline
import com.video.engine.timeline.TimelineManager
import java.io.File
import java.io.FileOutputStream

class ImportController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val timelineProvider: () -> MultiClipTimeline,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val playheadTimeMsProvider: () -> Long,
    private val onImportedClip: (clipId: Int, importPath: String, importedDurationMs: Long, trackType: TrackType) -> Long,
) {
    companion object {
        private const val TAG = "[UI]"
        private const val PROXY_POLL_INTERVAL_MS = 1200L
        private const val PROXY_MAX_POLL_ATTEMPTS = 180
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "avi", "mkv", "webm", "m4v", "3gp", "3gpp", "ts", "mts", "m2ts", "mpeg", "mpg")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff")
    }

    private var pendingImportPath: String? = null
    private var pendingImportTrackType: TrackType = TrackType.VIDEO
    private var nextImportTrackType: TrackType = TrackType.VIDEO
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setNextImportTrackType(trackType: TrackType) {
        nextImportTrackType = trackType
    }

    fun importFromPath(path: String) {
        pendingImportPath = path
        pendingImportTrackType = nextImportTrackType
        mainHandler.post { maybeImportPendingClip() }
    }

    fun importQuickSample(): Boolean {
        val candidate = findQuickImportCandidate() ?: return false
        pendingImportPath = candidate.absolutePath
        pendingImportTrackType = nextImportTrackType
        mainHandler.post { maybeImportPendingClip() }
        return true
    }

    fun handlePickerResult(requestCode: Int, expectedRequestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != expectedRequestCode || resultCode != Activity.RESULT_OK) return false
        val uri = data?.data ?: return true

        // Copy file on background thread to avoid UI freeze
        Thread {
            val resolvedVideoPath = resolveImportPath(uri) ?: run {
                mainHandler.post {
                    Toast.makeText(activity, "Unable to open selected media", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            mainHandler.post {
                pendingImportPath = resolvedVideoPath
                pendingImportTrackType = nextImportTrackType
                maybeImportPendingClip()
            }
        }.start()
        return true
    }

    private var importRetryCount = 0
    private val maxImportRetries = 15

    fun maybeImportPendingClip() {
        val importPath = pendingImportPath ?: return
        val previewView = previewViewProvider() ?: return
        val trackType = pendingImportTrackType

        // Heavy JNI (FFmpeg open + probe) — run off main thread
        Thread {
            val existingClipCount =
                timelineManagerProvider()?.getClips()?.size
                    ?: timelineProvider().getClips().size
            val requestedStartTimeMs = playheadTimeMsProvider().coerceAtLeast(0L)
            val requestedZOrder = resolveImportZOrder(trackType, existingClipCount)
            val requestedTrackLane = resolveImportTrackLane(trackType, requestedZOrder)
            Log.d(TAG, "Attempting NativeBridge.addClip for: $importPath at start=$requestedStartTimeMs track=$trackType")
            val clipId = NativeBridge.addClip(
                previewView = previewView,
                videoPath = importPath,
                trackType = trackType.nativeRoleName(),
                startTimeMs = requestedStartTimeMs,
                trackLane = requestedTrackLane,
                zOrder = requestedZOrder,
            )
            Log.d(TAG, "NativeBridge.addClip result clipId: $clipId")
            if (clipId <= 0) {
                if (importRetryCount >= maxImportRetries) {
                    Log.e(TAG, "Import failed after $maxImportRetries retries: $importPath")
                    importRetryCount = 0
                    pendingImportPath = null
                    mainHandler.post {
                        Toast.makeText(activity, "Import failed. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                importRetryCount++
                Log.w(TAG, "Deferring clip import until preview is ready: $importPath (clipId was $clipId, retry $importRetryCount)")
                mainHandler.postDelayed({ maybeImportPendingClip() }, 300)
                return@Thread
            }
            importRetryCount = 0

            mainHandler.post {
                pendingImportPath = null
                pendingImportTrackType = TrackType.VIDEO
                nextImportTrackType = TrackType.VIDEO
                val timeline = timelineProvider()
                timeline.syncFromEngine(previewView)
                val timelineManager = timelineManagerProvider()
                timelineManager?.syncClips(timeline.getClips())
                timelineManager?.setClipLayerIndex(clipId, requestedZOrder)
                timelineManager?.setClipVisibility(clipId, true)
                timelineManager?.selectClip(clipId)

                val importedDurationMs = NativeBridge.getClipDuration(previewView, clipId)
                val revealTimeMs = onImportedClip(clipId, importPath, importedDurationMs, trackType)

                // Force first frame render — wait for surface to settle after picker close
                val pv = previewView
                if (pv != null) {
                    Thread {
                        for (attempt in 0..8) {
                            Thread.sleep(300L)
                            val result = runCatching {
                                NativeBridge.executeCommand("SEEK", mapOf("timeMs" to revealTimeMs))
                            }.getOrNull()
                            if (result?.success == true) break
                            pv.forceNativeSurfaceRebind()
                        }
                    }.start()
                }

                maybeStartGhostProxyBuild(clipId, importPath, trackType)
            }
        }.start()
    }

    private fun resolveImportZOrder(trackType: TrackType, existingClipCount: Int): Int {
        return when (trackType) {
            TrackType.LAYER,
            TrackType.OVERLAY,
            TrackType.TEXT,
            -> trackType.defaultZOrder(existingClipCount.coerceAtLeast(0))
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

    private fun maybeStartGhostProxyBuild(clipId: Int, importPath: String, trackType: TrackType) {
        if (trackType != TrackType.VIDEO && trackType != TrackType.LAYER) {
            return
        }
        val longEdgePx = resolveVideoLongEdgePx(importPath) ?: return
        if (!DeviceDetector.shouldPreferProxyFor(longEdgePx)) {
            return
        }
        val profile = DeviceDetector.getQualityProfile()
        startProxyBuild(
            clipId = clipId,
            importPath = importPath,
            maxLongEdgePx = profile.proxyLongEdgePx,
            targetFps = profile.previewFps.coerceAtMost(30),
        )
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
                    val activated = runCatching { NativeBridge.activateClipProxy(clipId) }.getOrDefault(false)
                    Log.d(TAG, "Proxy activation clip=$clipId activated=$activated")
                    // Force first frame render after proxy is ready
                    val pv = previewViewProvider()
                    if (pv != null) {
                        Thread { NativeBridge.seekToTime(pv, 0L) }.start()
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

    private fun resolveImportPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return uri.toString()

        val importsDir = File(activity.cacheDir, "imports").apply { mkdirs() }
        val mimeType = runCatching { activity.contentResolver.getType(uri) }.getOrNull()
        val fileName = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
            ?: buildFallbackImportFileName(mimeType)
        val targetFile = File(importsDir, sanitizeImportFileName(fileName))

        // If already copied (same name), reuse
        if (targetFile.exists() && targetFile.length() > 0) return targetFile.absolutePath

        return try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                }
            } ?: return null
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy import URI: ${e.message}")
            null
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

    private fun sanitizeImportFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun buildFallbackImportFileName(mimeType: String?): String {
        val extension = mimeType
            ?.substringAfter('/', "")
            ?.substringBefore(';')
            ?.lowercase()
            ?.let { subtype ->
                when {
                    subtype.isBlank() -> null
                    subtype == "jpeg" -> "jpg"
                    subtype == "quicktime" -> "mov"
                    subtype == "3gpp" -> "3gp"
                    subtype == "x-matroska" -> "mkv"
                    subtype == "x-msvideo" -> "avi"
                    subtype == "mpeg" -> "mpg"
                    else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: subtype
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
        return "import_${System.currentTimeMillis()}.$extension"
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
            File(activity.cacheDir, "pro_imports"),
            File(activity.cacheDir, "imports"),
        )
        val externalCandidates = sequenceOf(
            File("/sdcard/DCIM/Camera"),
            File("/sdcard/Movies"),
            File("/sdcard/Download"),
        )
        return (cacheCandidates + externalCandidates)
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir ->
                dir.listFiles()
                    ?.asSequence()
                    ?.filter { file ->
                        file.isFile &&
                            file.canRead() &&
                            supportsTrackImport(file, nextImportTrackType) &&
                            file.length() > 0L
                    }
                    ?: emptySequence()
            }
            .sortedByDescending { it.lastModified() }
            .firstOrNull()
    }
}
