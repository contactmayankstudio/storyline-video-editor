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
                expectedActions = setOf("text_tool_sheet_opened", "text_composer_opened"),
            )
            "main_toolbar" to "media" -> ExpectationTemplate(
                key = "main_toolbar:media",
                source = "media_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("media_source_sheet_opened", "video_import_picker_opened"),
            )
            "main_toolbar" to "overlay" -> ExpectationTemplate(
                key = "main_toolbar:overlay",
                source = "overlay_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("overlay_source_sheet_opened", "overlay_import_picker_opened"),
            )
            "main_toolbar" to "layers" -> ExpectationTemplate(
                key = "main_toolbar:layers",
                source = "layers_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("layers_source_sheet_opened", "layer_import_picker_opened"),
            )
            "main_toolbar" to "audio" -> ExpectationTemplate(
                key = "main_toolbar:audio",
                source = "audio_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("audio_source_sheet_opened", "audio_import_picker_opened"),
            )
            "main_toolbar" to "effects" -> ExpectationTemplate(
                key = "main_toolbar:effects",
                source = "effects_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("effects_tool_sheet_opened"),
            )
            "main_toolbar" to "graphics" -> ExpectationTemplate(
                key = "main_toolbar:graphics",
                source = "graphics_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("graphics_tool_sheet_opened"),
            )
            "main_toolbar" to "transition" -> ExpectationTemplate(
                key = "main_toolbar:transition",
                source = "transition_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("transition_tool_sheet_opened"),
            )
            "main_toolbar" to "voiceover" -> ExpectationTemplate(
                key = "main_toolbar:voiceover",
                source = "voiceover_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("voiceover_tool_sheet_opened"),
            )
            "main_toolbar" to "color" -> ExpectationTemplate(
                key = "main_toolbar:color",
                source = "color_button_no_followup",
                timeoutMs = 1_800L,
                expectedActions = setOf("color_tool_sheet_opened"),
            )
            "clip_toolbar" to "clipFilterButton" -> ExpectationTemplate(
                key = "clip_toolbar:clipFilterButton",
                source = "clip_filter_button_no_followup",
                timeoutMs = 1_600L,
                expectedActions = setOf("clip_filter_action_handled", "effects_tool_sheet_opened"),
            )
            "clip_toolbar" to "clipBrightnessButton" -> ExpectationTemplate(
                key = "clip_toolbar:clipBrightnessButton",
                source = "clip_color_button_no_followup",
                timeoutMs = 1_600L,
                expectedActions = setOf("clip_color_action_handled"),
            )
            "clip_toolbar" to "clipTransitionButton" -> ExpectationTemplate(
                key = "clip_toolbar:clipTransitionButton",
                source = "clip_transition_button_no_followup",
                timeoutMs = 1_600L,
                expectedActions = setOf("clip_transition_action_handled", "transition_tool_sheet_opened"),
            )
            "clip_toolbar" to "clipGraphicsButton" -> ExpectationTemplate(
                key = "clip_toolbar:clipGraphicsButton",
                source = "clip_graphics_button_no_followup",
                timeoutMs = 1_600L,
                expectedActions = setOf("clip_graphics_action_handled", "graphics_tool_sheet_opened"),
            )
            "clip_toolbar" to "clipAddLayerButton" -> ExpectationTemplate(
                key = "clip_toolbar:clipAddLayerButton",
                source = "clip_overlay_button_no_followup",
                timeoutMs = 1_600L,
                expectedActions = setOf("clip_layer_action_handled", "overlay_import_picker_opened"),
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
