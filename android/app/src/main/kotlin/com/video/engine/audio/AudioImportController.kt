package com.video.engine.audio

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.video.engine.UiToast as Toast
import com.video.engine.NativeBridge
import java.io.File
import java.io.FileOutputStream

class AudioImportController(
    private val activity: Activity,
    private val nextAudioClipIdProvider: () -> Int,
    private val setNextAudioClipId: (Int) -> Unit,
    private val onImportStarted: ((path: String) -> Unit)? = null,
    private val onImportFinished: ((success: Boolean, path: String, clipId: Int?, error: String?) -> Unit)? = null,
    private val onImportedAudio: (AudioClip) -> Unit,
    private val nativeClipCreator: ((path: String, startTimeMs: Long, layerIndex: Int) -> Int?)? = null,
) {
    companion object {
        private const val TAG = "[UI]"
        private const val PEAK_BUCKET_MS = 20
        private const val PEAK_PREVIEW_POINTS = 1200
        private const val DEFAULT_IMPORTED_AUDIO_GAIN = 0.78f
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun openPickerOrQuickImport(requestCode: Int) {
        val quickSample = findQuickImportSample()
        if (quickSample != null) {
            Log.d(TAG, "Audio quick import candidate: ${quickSample.absolutePath}")
            val imported = importResolvedAudioPath(quickSample.absolutePath)
            if (imported != null) {
                Log.d(TAG, "Audio quick import success: id=${imported.id} path=${imported.sourcePath} duration=${imported.durationMs}ms")
                return
            }
            Log.w(TAG, "Audio quick import failed for: ${quickSample.absolutePath}")
        }
        Log.d(TAG, "Audio quick import unavailable, opening picker")
        openPicker(requestCode)
    }

    fun importQuickSample(): Boolean {
        val quickSample = findQuickImportSample() ?: return false
        Log.d(TAG, "Audio quick import candidate: ${quickSample.absolutePath}")
        val imported = importResolvedAudioPath(quickSample.absolutePath) ?: return false
        Log.d(
            TAG,
            "Audio quick import success: id=${imported.id} path=${imported.sourcePath} duration=${imported.durationMs}ms",
        )
        return true
    }

    fun openPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val initialDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                ?.takeIf { it.exists() }
            if (initialDir != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(initialDir))
            }
        }
        activity.startActivityForResult(intent, requestCode)
    }

    fun handlePickerResult(requestCode: Int, expectedRequestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != expectedRequestCode || resultCode != Activity.RESULT_OK) {
            return false
        }
        val uri = data?.data ?: return true
        Log.d(TAG, "Audio picker returned URI: $uri")
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val resolvedPath = resolveImportPath(uri) ?: run {
            Toast.makeText(activity, "Unable to open selected audio", Toast.LENGTH_SHORT).show()
            return true
        }
        val imported = importResolvedAudioPath(resolvedPath)
        if (imported == null) {
            Toast.makeText(activity, "Unable to read audio duration", Toast.LENGTH_SHORT).show()
            return true
        }
        Log.d(TAG, "Audio picker import success: id=${imported.id} path=${imported.sourcePath} duration=${imported.durationMs}ms")
        return true
    }

    fun importFromPath(
        path: String,
        startTimeMs: Long = 0L,
        durationOverrideMs: Long? = null,
        displayNameOverride: String? = null,
        layerIndexOverride: Int? = null,
    ): AudioClip? {
        return importResolvedAudioPath(
            path = path,
            startTimeMs = startTimeMs,
            durationOverrideMs = durationOverrideMs,
            displayNameOverride = displayNameOverride,
            layerIndexOverride = layerIndexOverride,
        )
    }

    private fun importResolvedAudioPath(
        path: String,
        startTimeMs: Long = 0L,
        durationOverrideMs: Long? = null,
        displayNameOverride: String? = null,
        layerIndexOverride: Int? = null,
    ): AudioClip? {
        onImportStarted?.invoke(path)
        val probedDurationMs = probeDurationMs(path)
        val durationMs = durationOverrideMs?.takeIf { it > 0L } ?: probedDurationMs
        if (durationMs <= 0L) {
            Log.w(TAG, "Audio duration probe failed for: $path")
            onImportFinished?.invoke(false, path, null, "duration_probe_failed")
            return null
        }
        val requestedLayerIndex = layerIndexOverride ?: (
            AudioClipStore.all().maxOfOrNull { it.layerIndex }?.plus(1) ?: 0
        )
        val nextId = nextAudioClipIdProvider()
        val clipId = nativeClipCreator?.invoke(path, startTimeMs.coerceAtLeast(0L), requestedLayerIndex)
            ?.takeIf { it > 0 }
            ?: nextId
        val clip = AudioClip(
            id = clipId,
            sourcePath = path,
            displayName = displayNameOverride?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension,
            startTimeMs = startTimeMs.coerceAtLeast(0L),
            durationMs = durationMs,
            gain = DEFAULT_IMPORTED_AUDIO_GAIN,
            layerIndex = requestedLayerIndex,
        )
        AudioClipStore.add(clip)
        setNextAudioClipId(maxOf(nextAudioClipIdProvider(), clip.id + 1))
        onImportedAudio(clip)
        startPeakMapBuild(clip)
        onImportFinished?.invoke(true, path, clip.id, null)
        return clip
    }

    private fun startPeakMapBuild(clip: AudioClip) {
        val outputPath = peakMapPathFor(clip)
        Thread {
            val build = NativeBridge.buildAudioPeakMap(
                sourcePath = clip.sourcePath,
                peakMapPath = outputPath,
                bucketMs = PEAK_BUCKET_MS,
            )
            if (build == null) {
                Log.w(TAG, "Audio peak map build failed: id=${clip.id} path=${clip.sourcePath}")
                return@Thread
            }
            val range = NativeBridge.getAudioPeakRange(
                peakMapPath = outputPath,
                startIndex = 0,
                maxPoints = PEAK_PREVIEW_POINTS,
            )
            if (build.durationMs > 0L && kotlin.math.abs(build.durationMs - clip.durationMs) > 1000L) {
                Log.d(
                    TAG,
                    "Audio duration corrected from native peaks: id=${clip.id} old=${clip.durationMs}ms new=${build.durationMs}ms",
                )
                clip.durationMs = build.durationMs
            }
            clip.peakMapPath = outputPath
            clip.peakBucketMs = build.bucketMs
            clip.peakLevels = range?.peaks.orEmpty()
            Log.d(
                TAG,
                "Audio peak map ready: id=${clip.id} peaks=${clip.peakLevels.size} bucketMs=${clip.peakBucketMs} path=$outputPath",
            )
            mainHandler.post {
                onImportedAudio(clip)
            }
        }.start()
    }

    private fun peakMapPathFor(clip: AudioClip): String {
        val peakDir = File(activity.cacheDir, "audio_peaks").apply { mkdirs() }
        val base = clip.displayName
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "audio_${clip.id}"
        return File(peakDir, "${base}_${clip.id}.vpk").absolutePath
    }

    private fun findQuickImportSample(): File? {
        val candidates = buildList {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.let(::add)
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let(::add)
            add(File(activity.cacheDir, "audio_imports"))
            add(File(activity.cacheDir, "imports"))
        }
        return candidates
            .asSequence()
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir ->
                dir.listFiles()
                    ?.asSequence()
                    ?.filter { file ->
                        file.isFile &&
                            file.canRead() &&
                            file.extension.lowercase() in setOf("mp3", "m4a", "aac", "wav", "ogg")
                    }
                    ?: emptySequence()
            }
            .sortedByDescending { it.lastModified() }
            .firstOrNull()
    }

    private fun resolveImportPath(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        if (uri.scheme != "content") {
            return uri.toString()
        }

        val importsDir = File(activity.cacheDir, "audio_imports").apply { mkdirs() }
        val fileName = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
            ?: "audio_${System.currentTimeMillis()}.m4a"
        val targetFile = File(importsDir, sanitizeImportFileName(fileName))

        return try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy audio import URI: ${e.message}")
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
            Log.w(TAG, "Failed to query audio display name: ${e.message}")
            null
        }
    }

    private fun probeDurationMs(path: String): Long {
        val retrieverDurationMs = probeDurationWithRetriever(path)
        val mediaPlayerDurationMs = probeDurationWithMediaPlayer(path)
        if (retrieverDurationMs > 0L && mediaPlayerDurationMs > 0L) {
            val larger = maxOf(retrieverDurationMs, mediaPlayerDurationMs)
            val smaller = minOf(retrieverDurationMs, mediaPlayerDurationMs)
            if (smaller > 0L && larger >= (smaller * 4L)) {
                Log.w(
                    TAG,
                    "Audio duration probe mismatch for $path retriever=${retrieverDurationMs}ms mediaPlayer=${mediaPlayerDurationMs}ms; using mediaPlayer",
                )
                return mediaPlayerDurationMs
            }
        }
        return when {
            mediaPlayerDurationMs > 0L -> mediaPlayerDurationMs
            retrieverDurationMs > 0L -> retrieverDurationMs
            else -> 0L
        }
    }

    private fun probeDurationWithRetriever(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audio duration with retriever: ${e.message}")
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probeDurationWithMediaPlayer(path: String): Long {
        val player = MediaPlayer()
        return try {
            player.setDataSource(path)
            player.prepare()
            player.duration.toLong().coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audio duration with media player: ${e.message}")
            0L
        } finally {
            runCatching { player.release() }
        }
    }

    private fun sanitizeImportFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
