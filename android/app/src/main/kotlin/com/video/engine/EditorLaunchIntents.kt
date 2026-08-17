package com.video.engine

import android.content.Context
import android.content.Intent
import java.util.UUID

object EditorLaunchIntents {
    const val EXTRA_FORCE_EDITOR_BOOT = "com.video.engine.extra.FORCE_EDITOR_BOOT"
    const val EXTRA_START_SHELL_ACTION = "com.video.engine.extra.START_SHELL_ACTION"
    const val EXTRA_START_PROJECT_PATH = "com.video.engine.extra.START_PROJECT_PATH"
    const val START_ACTION_NEW = "new"
    const val START_ACTION_OPEN = "open"
    const val START_ACTION_IMPORT = "import"

    private const val EXTRA_FORCE_EDITOR_BOOT_TOKEN = "com.video.engine.extra.FORCE_EDITOR_BOOT_TOKEN"
    private const val PREFS_NAME = "editor_launch_intents"
    private const val PREF_FORCE_EDITOR_BOOT_TOKEN = "force_editor_boot_token"
    @Volatile private var pendingForceEditorBootToken: String? = null

    fun markForEditorBoot(context: Context, intent: Intent): Intent {
        val bootToken = UUID.randomUUID().toString()
        pendingForceEditorBootToken = bootToken
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_FORCE_EDITOR_BOOT_TOKEN, bootToken)
            .commit()
        return intent
            .putExtra(EXTRA_FORCE_EDITOR_BOOT, true)
            .putExtra(EXTRA_FORCE_EDITOR_BOOT_TOKEN, bootToken)
    }

    fun shouldHonorForceEditorBoot(context: Context, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_FORCE_EDITOR_BOOT, false) != true) return false
        val providedToken = intent.getStringExtra(EXTRA_FORCE_EDITOR_BOOT_TOKEN)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val expectedToken = prefs.getString(PREF_FORCE_EDITOR_BOOT_TOKEN, null) ?: pendingForceEditorBootToken
        if (expectedToken != null && providedToken == expectedToken) {
            pendingForceEditorBootToken = null
            prefs.edit().remove(PREF_FORCE_EDITOR_BOOT_TOKEN).commit()
            return true
        }
        return LocalAutomationGate.isAllowed(context)
    }
}
