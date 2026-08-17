package com.video.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import java.util.Locale

object LocalAutomationGate {
    const val EXTRA_ACTION = "adb_action"
    const val EXTRA_TOKEN = "adb_token"
    const val EXTRA_PATH = "adb_path"
    const val EXTRA_TRACK_TYPE = "adb_track_type"
    const val EXTRA_TIME_MS = "adb_time_ms"
    const val EXTRA_PRESET = "adb_preset"
    const val EXTRA_ENABLED = "adb_enabled"
    const val EXTRA_BLUE = "adb_blue"
    const val EXTRA_SIMILARITY = "adb_similarity"
    const val EXTRA_SMOOTHNESS = "adb_smoothness"
    const val EXTRA_SPILL = "adb_spill"
    const val EXTRA_TRANSITION = "adb_transition"
    const val EXTRA_DURATION_MS = "adb_duration_ms"
    const val EXTRA_FACTOR = "adb_factor"
    const val EXTRA_TEXT = "adb_text"
    const val EXTRA_TEXT_B64 = "adb_text_b64"

    fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun isFirebaseTestLab(context: Context): Boolean {
        return runCatching {
            Settings.System.getString(context.contentResolver, "firebase.test.lab") == "true"
        }.getOrDefault(false)
    }

    fun isAllowed(context: Context): Boolean {
        return isDebuggable(context) || isFirebaseTestLab(context)
    }

    fun actionFrom(context: Context, intent: Intent?): String {
        if (!isAllowed(context)) return ""
        return intent
            ?.getStringExtra(EXTRA_ACTION)
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
    }
}
