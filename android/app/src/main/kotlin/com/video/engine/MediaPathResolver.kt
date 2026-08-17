package com.video.engine

import java.io.File

object MediaPathResolver {
    private const val PRIMARY_EXTERNAL_ROOT = "/storage/emulated/0"
    private val primaryExternalAliases = listOf(
        "/sdcard",
        "/mnt/sdcard",
        "/storage/self/primary",
    )

    fun normalizeForFileAccess(path: String): String {
        if (path.isBlank() || path.startsWith("content://")) return path
        return normalizePrimaryExternalAlias(path.trim())
    }

    fun cacheKey(path: String): String {
        if (path.isBlank() || path.startsWith("content://")) return path.trim()
        return normalizePrimaryExternalAlias(path.trim())
    }

    fun isPrimaryExternalPath(path: String): Boolean {
        val normalized = normalizePrimaryExternalAlias(path.trim())
        return normalized == PRIMARY_EXTERNAL_ROOT ||
            normalized.startsWith("$PRIMARY_EXTERNAL_ROOT/")
    }

    fun defaultCameraVideoFile(): File {
        return primaryExternalFile("DCIM/Camera/video.mp4")
    }

    fun commonExternalMediaDirs(): Sequence<File> {
        return sequenceOf(
            primaryExternalFile("DCIM/Camera"),
            primaryExternalFile("Movies"),
            primaryExternalFile("Download"),
        )
    }

    fun primaryExternalFile(relativePath: String): File {
        return File(PRIMARY_EXTERNAL_ROOT, relativePath.trimStart('/'))
    }

    private fun normalizePrimaryExternalAlias(path: String): String {
        primaryExternalAliases.forEach { alias ->
            if (path == alias) return PRIMARY_EXTERNAL_ROOT
            if (path.startsWith("$alias/")) {
                return PRIMARY_EXTERNAL_ROOT + path.removePrefix(alias)
            }
        }
        return path
    }
}
