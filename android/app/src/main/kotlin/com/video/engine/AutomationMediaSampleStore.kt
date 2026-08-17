package com.video.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object AutomationMediaSampleStore {
    private const val SAMPLE_DIR = "automation_samples"
    private const val VIDEO_ASSET_PATH = "$SAMPLE_DIR/quick_sample_video.mp4"
    private const val AUDIO_ASSET_PATH = "$SAMPLE_DIR/quick_sample_audio.m4a"

    fun ensureVideoSample(context: Context): File? {
        if (!LocalAutomationGate.isAllowed(context)) return null
        return copyAssetToCacheIfNeeded(context, VIDEO_ASSET_PATH)
    }

    fun ensureAudioSample(context: Context): File? {
        if (!LocalAutomationGate.isAllowed(context)) return null
        return copyAssetToCacheIfNeeded(context, AUDIO_ASSET_PATH)
    }

    private fun copyAssetToCacheIfNeeded(context: Context, assetPath: String): File? {
        val fileName = assetPath.substringAfterLast('/')
        val targetDir = File(context.cacheDir, SAMPLE_DIR).apply { mkdirs() }
        val targetFile = File(targetDir, fileName)
        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile
        }
        return runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        }.getOrNull()
    }
}
