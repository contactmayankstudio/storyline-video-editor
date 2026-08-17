package com.video.engine

import android.os.StatFs
import java.io.File
import kotlin.math.roundToInt

object ImportStorageGuard {
    private const val STORAGE_SAFETY_BYTES = 160L * 1024L * 1024L

    fun hasEnoughSpace(directory: File, mediaBytes: Long): Boolean {
        if (mediaBytes <= 0L) return true
        val requiredBytes = mediaBytes + STORAGE_SAFETY_BYTES
        val availableBytes = availableBytes(directory)
        return availableBytes >= requiredBytes
    }

    fun failureMessage(directory: File, mediaBytes: Long): String {
        val requiredBytes = mediaBytes.coerceAtLeast(0L) + STORAGE_SAFETY_BYTES
        val freeBytes = availableBytes(directory)
        return "Not enough storage. Need ${formatSize(requiredBytes)}, free ${formatSize(freeBytes)}"
    }

    private fun availableBytes(directory: File): Long {
        return try {
            directory.mkdirs()
            StatFs(directory.absolutePath).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun formatSize(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val gb = safeBytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return "${oneDecimal(gb)} GB"
        val mb = safeBytes / (1024.0 * 1024.0)
        if (mb >= 100.0) return "${mb.roundToInt()} MB"
        return "${oneDecimal(mb)} MB"
    }

    private fun oneDecimal(value: Double): String {
        val scaled = (value * 10.0).roundToInt().coerceAtLeast(0)
        return "${scaled / 10}.${scaled % 10}"
    }
}
