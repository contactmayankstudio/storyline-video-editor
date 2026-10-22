package com.video.engine.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.video.engine.DeviceDetector
import java.util.Locale

/**
 * Provides comprehensive host device hardware, thermal, memory, and timeline telemetry
 * for Antigravity AI Copilot and Gemini 1.5 Flash agents.
 */
object DeviceTelemetryProvider {

    data class DeviceTelemetry(
        val manufacturer: String,
        val model: String,
        val brand: String,
        val hardware: String,
        val androidRelease: String,
        val sdkInt: Int,
        val totalRamGb: Float,
        val freeRamGb: Float,
        val freeStorageGb: Float,
        val hasHevcEncoder: Boolean,
        val hasAvcEncoder: Boolean,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val deviceTier: String,
    )

    /**
     * Inspects the host Android device at runtime and builds the telemetry data snapshot.
     */
    fun getTelemetry(context: Context): DeviceTelemetry {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val freeRamGb = memInfo.availMem / (1024f * 1024f * 1024f)

        var freeStorageGb = 0f
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            freeStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024f * 1024f * 1024f)
        } catch (_: Throwable) {}

        var batteryPercent = -1
        var isCharging = false
        try {
            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryPercent = ((level.toFloat() / scale.toFloat()) * 100).toInt()
            }
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Throwable) {}

        var hasHevc = false
        var hasAvc = false
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals("video/hevc", ignoreCase = true)) hasHevc = true
                    if (type.equals("video/avc", ignoreCase = true)) hasAvc = true
                }
            }
        } catch (_: Throwable) {
            hasAvc = true
        }

        val tier = DeviceDetector.getDeviceTier().name

        return DeviceTelemetry(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
            model = Build.MODEL,
            brand = Build.BRAND,
            hardware = Build.HARDWARE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            totalRamGb = totalRamGb,
            freeRamGb = freeRamGb,
            freeStorageGb = freeStorageGb,
            hasHevcEncoder = hasHevc,
            hasAvcEncoder = hasAvc,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            deviceTier = tier,
        )
    }

    /**
     * Formats device and timeline telemetry as a structured prompt block for Gemini system instruction.
     */
    fun buildSystemContextPrompt(context: Context, activeTimelineSummary: String? = null): String {
        val t = getTelemetry(context)
        val sb = StringBuilder()
        sb.appendLine("[HOST DEVICE & ENVIRONMENT TELEMETRY]")
        sb.appendLine("Device: ${t.manufacturer} ${t.model} (${t.hardware})")
        sb.appendLine("OS: Android ${t.androidRelease} (API Level ${t.sdkInt})")
        sb.appendLine(String.format(Locale.US, "Memory: %.1f GB Free / %.1f GB Total (Tier: %s)", t.freeRamGb, t.totalRamGb, t.deviceTier))
        sb.appendLine(String.format(Locale.US, "Internal Storage: %.1f GB Free", t.freeStorageGb))
        sb.appendLine("Hardware Video Encoders: H.264/AVC: ${if (t.hasAvcEncoder) "YES" else "NO"}, H.265/HEVC: ${if (t.hasHevcEncoder) "YES (4K/60fps capable)" else "NO"}")
        if (t.batteryPercent >= 0) {
            sb.appendLine("Battery: ${t.batteryPercent}% (${if (t.isCharging) "Charging" else "Discharging"})")
        }
        if (!activeTimelineSummary.isNullOrBlank()) {
            sb.appendLine("Active Timeline: $activeTimelineSummary")
        }
        return sb.toString().trim()
    }
}
