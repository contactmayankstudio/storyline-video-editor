package com.video.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.math.max

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
    private const val LOW_PREVIEW_WIDTH = 640
    private const val LOW_PREVIEW_HEIGHT = 360
    private const val MID_PREVIEW_WIDTH = 960
    private const val MID_PREVIEW_HEIGHT = 540
    private const val HIGH_PREVIEW_WIDTH = 1280
    private const val HIGH_PREVIEW_HEIGHT = 720
    private const val GIGABYTE_BYTES = 1024L * 1024L * 1024L

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
        val glTextureSize: Int,  // Max texture size
        val previewLongEdgePx: Int,
        val minPreviewFps: Int,
        val predictiveLookAroundMs: Int,
        val predictiveSampleStepMs: Int,
        val predictiveCacheMaxFrames: Int,
        val proxyLongEdgePx: Int,
    )

    private lateinit var activityManager: ActivityManager
    private var deviceTier = DeviceTier.MID
    private var qualityProfile: QualityProfile? = null
    private var totalRamGb = 0
    private var memoryClassMb = 0

    /**
     * Initialize device detector. Call once in MainActivity.onCreate().
     */
    fun init(context: Context) {
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        memoryClassMb = activityManager.memoryClass
        totalRamGb = queryDeviceRamGb()
        detectDeviceTier()
        generateQualityProfile()
        logDeviceInfo()
    }

    private fun detectDeviceTier() {
        val apiLevel = Build.VERSION.SDK_INT

        deviceTier = when {
            totalRamGb <= 2 || memoryClassMb <= 192 || apiLevel < Build.VERSION_CODES.O -> {
                Log.w(TAG, "Low-end device detected: ${totalRamGb}GB RAM, memClass=${memoryClassMb}MB, API $apiLevel")
                DeviceTier.LOW
            }
            totalRamGb <= 4 || memoryClassMb <= 256 -> {
                Log.i(TAG, "Mid-range device detected: ${totalRamGb}GB RAM, memClass=${memoryClassMb}MB, API $apiLevel")
                DeviceTier.MID
            }
            else -> {
                Log.i(TAG, "High-end device detected: ${totalRamGb}GB RAM, memClass=${memoryClassMb}MB, API $apiLevel")
                DeviceTier.HIGH
            }
        }
    }

    private fun generateQualityProfile() {
        qualityProfile = when (deviceTier) {
            DeviceTier.LOW -> QualityProfile(
                previewWidth = LOW_PREVIEW_WIDTH,
                previewHeight = LOW_PREVIEW_HEIGHT,
                previewFps = 24,
                exportBitrate = 2500,
                maxExportFps = 30,
                glTextureSize = 1024,
                previewLongEdgePx = 320,
                minPreviewFps = 15,
                predictiveLookAroundMs = 420,
                predictiveSampleStepMs = 150,
                predictiveCacheMaxFrames = 8,
                proxyLongEdgePx = 360,
            )
            DeviceTier.MID -> QualityProfile(
                previewWidth = MID_PREVIEW_WIDTH,
                previewHeight = MID_PREVIEW_HEIGHT,
                previewFps = 30,
                exportBitrate = 6000,
                maxExportFps = 30,
                glTextureSize = 2048,
                previewLongEdgePx = 480,
                minPreviewFps = 18,
                predictiveLookAroundMs = 900,
                predictiveSampleStepMs = 110,
                predictiveCacheMaxFrames = 16,
                proxyLongEdgePx = 540,
            )
            DeviceTier.HIGH -> QualityProfile(
                previewWidth = HIGH_PREVIEW_WIDTH,
                previewHeight = HIGH_PREVIEW_HEIGHT,
                previewFps = 45,
                exportBitrate = 12000,
                maxExportFps = 60,
                glTextureSize = 4096,
                previewLongEdgePx = 720,
                minPreviewFps = 24,
                predictiveLookAroundMs = 1400,
                predictiveSampleStepMs = 80,
                predictiveCacheMaxFrames = 28,
                proxyLongEdgePx = 720,
            )
        }
    }

    private fun logDeviceInfo() {
        val profile = qualityProfile ?: return
        Log.i(TAG, "Device Tier: $deviceTier")
        Log.i(TAG, "RAM: ${totalRamGb}GB total, memoryClass=${memoryClassMb}MB")
        Log.i(TAG, "Preview: ${profile.previewWidth}x${profile.previewHeight} @ ${profile.previewFps}fps")
        Log.i(TAG, "Export: ${profile.exportBitrate}kbps @ ${profile.maxExportFps}fps")
        Log.i(TAG, "Ghost preview long edge: ${profile.previewLongEdgePx}px")
        Log.i(TAG, "Max GL texture: ${profile.glTextureSize}")
    }

    fun getDeviceTier(): DeviceTier = deviceTier

    fun getQualityProfile(): QualityProfile =
        qualityProfile ?: QualityProfile(
            MID_PREVIEW_WIDTH,
            MID_PREVIEW_HEIGHT,
            30,
            6000,
            30,
            2048,
            480,
            18,
            900,
            110,
            16,
            540,
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
     */
    fun getRecommendedPreviewFps(): Int {
        return getQualityProfile().previewFps
    }

    fun getRecommendedMinPreviewFps(): Int = getQualityProfile().minPreviewFps

    fun getRecommendedGhostLongEdgePx(): Int = getQualityProfile().previewLongEdgePx

    fun getRecommendedPredictiveLookAroundMs(): Int = getQualityProfile().predictiveLookAroundMs

    fun getRecommendedPredictiveSampleStepMs(): Int = getQualityProfile().predictiveSampleStepMs

    fun getRecommendedPredictiveCacheMaxFrames(): Int = getQualityProfile().predictiveCacheMaxFrames

    fun getRecommendedProxyLongEdgePx(): Int = getQualityProfile().proxyLongEdgePx

    fun shouldPreferProxyFor(longEdgePx: Int): Boolean {
        if (longEdgePx <= 0) return false
        val profile = getQualityProfile()
        return isLowEndDevice() || longEdgePx > (profile.previewWidth * 13 / 10)
    }

    private fun queryDeviceRamGb(): Int {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val totalBytes = max(info.totalMem, memoryClassMb.toLong() * 1024L * 1024L)
        return ((totalBytes + GIGABYTE_BYTES - 1L) / GIGABYTE_BYTES).toInt().coerceAtLeast(1)
    }
}
