package com.video.engine

import android.content.ContentUris
import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

object ContentUriImportResolver {
    private const val COPY_BUFFER_BYTES = 256 * 1024

    fun queryLength(contentResolver: ContentResolver, uri: Uri): Long {
        return queryContentLength(contentResolver, buildCandidateUris(uri))
    }

    fun findDirectReadablePath(contentResolver: ContentResolver, uri: Uri): String? {
        val candidateUris = buildCandidateUris(uri)
        for (path in buildCandidateFilePaths(contentResolver, candidateUris)) {
            val file = File(path)
            if (file.exists() && file.isFile && file.canRead() && file.length() > 0L) {
                return file.absolutePath
            }
        }
        return null
    }

    fun copyToFile(
        contentResolver: ContentResolver,
        uri: Uri,
        targetFile: File,
        logTag: String,
        label: String,
        onProgress: ((bytesCopied: Long, totalBytes: Long, elapsedMs: Long) -> Unit)? = null,
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            runCatching { targetFile.delete() }
        }

        val failures = mutableListOf<String>()
        val candidateUris = buildCandidateUris(uri)
        val contentLength = queryContentLength(contentResolver, candidateUris)

        fun copyStream(input: InputStream, totalBytes: Long = contentLength): Boolean {
            val startedMs = SystemClock.elapsedRealtime()
            var copiedBytes = 0L
            var lastProgressMs = 0L
            fun notifyProgress(force: Boolean = false) {
                val elapsedMs = (SystemClock.elapsedRealtime() - startedMs).coerceAtLeast(0L)
                val nowMs = SystemClock.elapsedRealtime()
                if (!force && nowMs - lastProgressMs < 250L) return
                lastProgressMs = nowMs
                onProgress?.invoke(copiedBytes, totalBytes, elapsedMs)
            }
            notifyProgress(force = true)
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    copiedBytes += count.toLong()
                    notifyProgress()
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
            notifyProgress(force = true)
            return targetFile.exists() && targetFile.length() > 0L
        }

        fun noteFailure(stage: String, throwable: Throwable?) {
            val detail = throwable?.message?.takeIf { it.isNotBlank() } ?: throwable?.javaClass?.simpleName ?: "unknown"
            failures.add("$stage=$detail")
        }

        for (candidateUri in candidateUris) {
            try {
                val inputStream = contentResolver.openInputStream(candidateUri)
                if (inputStream != null) {
                    inputStream.use { input ->
                        if (copyStream(input)) return true
                        failures.add("openInputStream($candidateUri)=empty-copy")
                    }
                } else {
                    failures.add("openInputStream($candidateUri)=null")
                }
            } catch (error: Exception) {
                noteFailure("openInputStream($candidateUri)", error)
            }
        }

        for (candidateUri in candidateUris) {
            try {
                val assetFileDescriptor = contentResolver.openAssetFileDescriptor(candidateUri, "r")
                if (assetFileDescriptor != null) {
                    assetFileDescriptor.use { afd ->
                        afd.createInputStream().use { input ->
                            if (copyStream(input)) return true
                            failures.add("openAssetFileDescriptor($candidateUri)=empty-copy")
                        }
                    }
                } else {
                    failures.add("openAssetFileDescriptor($candidateUri)=null")
                }
            } catch (error: Exception) {
                noteFailure("openAssetFileDescriptor($candidateUri)", error)
            }
        }

        for (candidateUri in candidateUris) {
            try {
                val fileDescriptor = contentResolver.openFileDescriptor(candidateUri, "r")
                if (fileDescriptor != null) {
                    fileDescriptor.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            if (copyStream(input)) return true
                            failures.add("openFileDescriptor($candidateUri)=empty-copy")
                        }
                    }
                } else {
                    failures.add("openFileDescriptor($candidateUri)=null")
                }
            } catch (error: Exception) {
                noteFailure("openFileDescriptor($candidateUri)", error)
            }
        }

        for (filePath in buildCandidateFilePaths(contentResolver, candidateUris)) {
            try {
                val sourceFile = File(filePath)
                if (!sourceFile.exists() || !sourceFile.canRead()) {
                    failures.add("filePath($filePath)=missing")
                    continue
                }
                FileInputStream(sourceFile).use { input ->
                    if (copyStream(input, sourceFile.length().takeIf { it > 0L } ?: contentLength)) return true
                    failures.add("filePath($filePath)=empty-copy")
                }
            } catch (error: Exception) {
                noteFailure("filePath($filePath)", error)
            }
        }

        runCatching { targetFile.delete() }
        Log.e(logTag, "Failed to copy $label URI: uri=$uri target=${targetFile.absolutePath} failures=${failures.joinToString(" | ")}")
        return false
    }

    private fun queryContentLength(contentResolver: ContentResolver, candidateUris: List<Uri>): Long {
        for (candidateUri in candidateUris) {
            val size =
                runCatching {
                    contentResolver.query(candidateUri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) return@use null
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0) cursor.getLong(sizeIndex) else null
                        }
                }.getOrNull()
            if (size != null && size > 0L) return size
        }
        for (candidateUri in candidateUris) {
            val size =
                runCatching {
                    contentResolver.openAssetFileDescriptor(candidateUri, "r")?.use { afd ->
                        afd.length.takeIf { it > 0L }
                    }
                }.getOrNull()
            if (size != null && size > 0L) return size
        }
        return -1L
    }

    private fun buildCandidateUris(uri: Uri): List<Uri> {
        val candidates = linkedSetOf(uri)
        val authority = uri.authority.orEmpty()
        if (authority == "com.android.providers.media.documents") {
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            val parts = documentId.split(':', limit = 2)
            val mediaId = parts.getOrNull(1)?.toLongOrNull()
            val mediaUri = when (parts.firstOrNull()) {
                "video" -> mediaId?.let { ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it) }
                "image" -> mediaId?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
                "audio" -> mediaId?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                else -> null
            }
            if (mediaUri != null) {
                candidates.add(mediaUri)
            }
            mediaId?.let {
                candidates.add(ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it))
            }
        }
        return candidates.toList()
    }

    private fun buildCandidateFilePaths(contentResolver: ContentResolver, candidateUris: List<Uri>): List<String> {
        val paths = linkedSetOf<String>()
        val displayNames = linkedSetOf<String>()
        for (candidateUri in candidateUris) {
            try {
                contentResolver.query(
                    candidateUri,
                    arrayOf("_data", OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val dataIndex = cursor.getColumnIndex("_data")
                    if (dataIndex >= 0) {
                        cursor.getString(dataIndex)?.takeIf { it.isNotBlank() }?.let { paths.add(it) }
                    }
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val relativePathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val relativePath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex) else null
                    if (!displayName.isNullOrBlank()) {
                        displayNames.add(displayName)
                    }
                    if (!displayName.isNullOrBlank() && !relativePath.isNullOrBlank()) {
                        val normalizedRelativePath = relativePath.removePrefix("/").trim()
                        val basePath = if (normalizedRelativePath.endsWith("/")) {
                            normalizedRelativePath
                        } else {
                            "$normalizedRelativePath/"
                        }
                        paths.add(MediaPathResolver.primaryExternalFile("$basePath$displayName").absolutePath)
                    }
                }
            } catch (_: Exception) {
            }
        }
        for (displayName in displayNames) {
            for (directory in MediaPathResolver.commonExternalMediaDirs()) {
                paths.add(File(directory, displayName).absolutePath)
            }
        }
        return paths.toList()
    }
}
