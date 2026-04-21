package com.video.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Device detection and adaptive quality settings.
 * 
 * Low-end Android devices (< 2GB RAM, old CPUs) often crash during GPU rendering
 * and video encoding. This utility detects device capabilities and auto-downgrades
 * preview resolution and export quality.
 * 
 * Why lightweight editors work on low-end phones:
 * 1. Detect device capabilities early (RAM, CPU, GPU)
 * 2. Downgrade resolution/FPS on low-RAM devices
 * 3. Use simpler shaders on older GPUs
 * 4. Reduce texture sizes to avoid OOM
 * 5. Throttle export bitrate on slow devices
 */
object DeviceDetector {
    private const val TAG = "[DeviceDetector]"
    private const val LOCKED_PREVIEW_WIDTH = 640
    private const val LOCKED_PREVIEW_HEIGHT = 360
    private const val LOCKED_PREVIEW_FPS = 24
    private const val LOCKED_EXPORT_MAX_FPS = 30
    private const val LOCKED_EXPORT_BITRATE_KBPS = 4000

    /**
     * Detected device tier based on RAM and API level.
     */
    enum class DeviceTier {
        LOW,      // < 2GB RAM: 480p preview, 30fps export, low bitrate
        MID,      // 2-4GB RAM: 720p preview, 30fps export, medium bitrate
        HIGH      // > 4GB RAM: 1080p preview, 60fps export, high bitrate
    }

    /**
     * Quality profile for export based on device tier.
     */
    data class QualityProfile(
        val previewWidth: Int,
        val previewHeight: Int,
        val previewFps: Int,
        val exportBitrate: Int,  // Kbps
        val maxExportFps: Int,
        val glTextureSize: Int   // Max texture size
    )

    private lateinit var activityManager: ActivityManager
    private var deviceTier = DeviceTier.MID
    private var qualityProfile: QualityProfile? = null

    /**
     * Initialize device detector. Call once in MainActivity.onCreate().
     */
    fun init(context: Context) {
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        detectDeviceTier()
        generateQualityProfile()
        logDeviceInfo()
    }

    private fun detectDeviceTier() {
        val ramGb = getDeviceRamGb()
        val apiLevel = Build.VERSION.SDK_INT

        deviceTier = when {
            ramGb < 2 || apiLevel < Build.VERSION_CODES.N -> {
                Log.w(TAG, "Low-end device detected: ${ramGb}GB RAM, API $apiLevel")
                DeviceTier.LOW
            }
            ramGb < 4 -> {
                Log.i(TAG, "Mid-range device detected: ${ramGb}GB RAM, API $apiLevel")
                DeviceTier.MID
            }
            else -> {
                Log.i(TAG, "High-end device detected: ${ramGb}GB RAM, API $apiLevel")
                DeviceTier.HIGH
            }
        }
    }

    private fun generateQualityProfile() {
        qualityProfile = when (deviceTier) {
            DeviceTier.LOW -> QualityProfile(
                previewWidth = LOCKED_PREVIEW_WIDTH,
                previewHeight = LOCKED_PREVIEW_HEIGHT,
                previewFps = LOCKED_PREVIEW_FPS,
                exportBitrate = 1500,  // 1.5 Mbps
                maxExportFps = LOCKED_EXPORT_MAX_FPS,
                glTextureSize = 1024
            )
            DeviceTier.MID -> QualityProfile(
                previewWidth = LOCKED_PREVIEW_WIDTH,
                previewHeight = LOCKED_PREVIEW_HEIGHT,
                previewFps = LOCKED_PREVIEW_FPS,
                exportBitrate = LOCKED_EXPORT_BITRATE_KBPS,
                maxExportFps = LOCKED_EXPORT_MAX_FPS,
                glTextureSize = 2048
            )
            DeviceTier.HIGH -> QualityProfile(
                previewWidth = LOCKED_PREVIEW_WIDTH,
                previewHeight = LOCKED_PREVIEW_HEIGHT,
                previewFps = LOCKED_PREVIEW_FPS,
                exportBitrate = LOCKED_EXPORT_BITRATE_KBPS,
                maxExportFps = LOCKED_EXPORT_MAX_FPS,
                glTextureSize = 4096
            )
        }
    }

    private fun logDeviceInfo() {
        val profile = qualityProfile ?: return
        Log.i(TAG, "Device Tier: $deviceTier")
        Log.i(TAG, "Preview: ${profile.previewWidth}x${profile.previewHeight} @ ${profile.previewFps}fps")
        Log.i(TAG, "Export: ${profile.exportBitrate}kbps @ ${profile.maxExportFps}fps")
        Log.i(TAG, "Max GL texture: ${profile.glTextureSize}")
    }

    fun getDeviceTier(): DeviceTier = deviceTier

    fun getQualityProfile(): QualityProfile =
        qualityProfile ?: QualityProfile(
            LOCKED_PREVIEW_WIDTH,
            LOCKED_PREVIEW_HEIGHT,
            LOCKED_PREVIEW_FPS,
            LOCKED_EXPORT_BITRATE_KBPS,
            LOCKED_EXPORT_MAX_FPS,
            2048,
        )

    fun isLowEndDevice(): Boolean = deviceTier == DeviceTier.LOW

    fun isHighEndDevice(): Boolean = deviceTier == DeviceTier.HIGH

    /**
     * Check if device has available memory to start export.
     * Returns true if safe to export; false if memory pressure is high.
     */
    fun hasAvailableMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usagePercent = (usedMemory * 100) / maxMemory

        if (usagePercent > 85) {
            Log.w(TAG, "Memory pressure high: $usagePercent% used")
            return false
        }
        return true
    }

    /**
     * Get recommended preview frame rate based on device.
     * Reduces to 24fps on low-end to prevent jank.
     */
    fun getRecommendedPreviewFps(): Int {
        return LOCKED_PREVIEW_FPS
    }
}

/**
 * Get total device RAM in GB.
 */
fun getDeviceRamGb(): Long {
    val runtime = Runtime.getRuntime()
    val maxMemory = runtime.maxMemory()
    return maxMemory / (1024L * 1024L * 1024L)
}
