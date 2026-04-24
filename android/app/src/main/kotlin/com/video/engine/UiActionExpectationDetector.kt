package com.video.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class UiActionExpectationDetector(
    private val onIssueDetected: (reportType: String, source: String, description: String) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private data class ExpectationTemplate(
        val key: String,
        val source: String,
        val timeoutMs: Long,
        val expectedActions: Set<String>,
    )

    private data class PendingExpectation(
        val key: String,
        val expectedActions: Set<String>,
        val runnable: Runnable,
    )

    private val pendingExpectations = linkedMapOf<String, PendingExpectation>()

    fun noteUiButtonTap(control: String, surface: String, mode: String, screen: String) {
        val template = expectationTemplate(control, surface, mode) ?: return
        pendingExpectations.remove(template.key)?.let { mainHandler.removeCallbacks(it.runnable) }
        val startedAtMs = SystemClock.elapsedRealtime()
        val runnable = Runnable {
            pendingExpectations.remove(template.key)
            val waitedMs = SystemClock.elapsedRealtime() - startedAtMs
            onIssueDetected(
                "ui",
                template.source,
                "Button ${control} on ${surface} did not trigger ${template.expectedActions.joinToString()} within ${waitedMs}ms on $screen.",
            )
        }
        pendingExpectations[template.key] = PendingExpectation(
            key = template.key,
            expectedActions = template.expectedActions,
            runnable = runnable,
        )
        mainHandler.postDelayed(runnable, template.timeoutMs)
    }

    fun noteHealthAction(action: String) {
        if (pendingExpectations.isEmpty()) return
        val matchingKeys = pendingExpectations.values
            .filter { action in it.expectedActions }
            .map { it.key }
        matchingKeys.forEach { key ->
            pendingExpectations.remove(key)?.let { pending ->
                mainHandler.removeCallbacks(pending.runnable)
            }
        }
    }

    fun close() {
        pendingExpectations.values.forEach { pending ->
            mainHandler.removeCallbacks(pending.runnable)
        }
        pendingExpectations.clear()
    }

    private fun expectationTemplate(control: String, surface: String, mode: String): ExpectationTemplate? {
        if (mode != "tap") return null
        return when (surface to control) {
            "top_bar" to "export" -> ExpectationTemplate(
                key = "top_bar:export",
                source = "export_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("export_dialog_opened", "export_started"),
            )
            "top_bar" to "export_container" -> ExpectationTemplate(
                key = "top_bar:export",
                source = "export_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("export_dialog_opened", "export_started"),
            )
            "top_bar" to "save_project" -> ExpectationTemplate(
                key = "top_bar:save_project",
                source = "save_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("save_dialog_opened", "project_saved"),
            )
            "top_bar" to "aspect_ratio" -> ExpectationTemplate(
                key = "top_bar:aspect_ratio",
                source = "aspect_ratio_no_followup",
                timeoutMs = 1_500L,
                expectedActions = setOf("aspect_ratio_picker_opened"),
            )
            "main_toolbar" to "text" -> ExpectationTemplate(
                key = "main_toolbar:text",
                source = "text_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("text_composer_opened"),
            )
            "start_screen" to "start_open_project" -> ExpectationTemplate(
                key = "start_screen:open_project",
                source = "start_open_project_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("load_dialog_opened", "project_loaded"),
            )
            "start_screen" to "start_import_media" -> ExpectationTemplate(
                key = "start_screen:import_media",
                source = "start_import_media_no_followup",
                timeoutMs = 2_000L,
                expectedActions = setOf("video_import_picker_opened"),
            )
            else -> null
        }
    }
}
