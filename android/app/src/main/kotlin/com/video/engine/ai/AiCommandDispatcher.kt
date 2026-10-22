package com.video.engine.ai

/**
 * Interface decoupling the AI Copilot Sheet from MainActivity / Timeline operations.
 */
interface AiCommandDispatcher {
    /**
     * Executes autonomous full auto-pilot edit pipeline (silence removal, hook zoom, color grade, captions, titles).
     */
    fun fullAutoPilotEdit(style: String = "ViralReel"): Boolean {
        val editingStyle = EditingStyle.fromString(style)
        return AutoPilotCoordinator(this).executeSync(editingStyle).success
    }

    /**
     * Splits the active or selected clip at playhead.
     */
    fun splitClip(): Boolean

    /**
     * Generates rhythmic beat cuts on selected/active clip.
     */
    fun fastBeatCuts(preset: String = "Punchy"): Boolean

    /**
     * Scans active audio clip for silent intervals and trims or removes them.
     */
    fun removeSilence(thresholdDb: Float = -30f): Boolean

    /**
     * Applies dynamic pan & zoom preset (e.g. push_in, pull_out, fill, reset).
     */
    fun dynamicZoom(preset: String = "push_in"): Boolean

    /**
     * Applies a color grading filter or 3D LUT preset to the active clip.
     */
    fun applyColorGrade(lutName: String): Boolean

    /**
     * Performs AI speech caption generation and adds subtitle overlays.
     */
    fun autoCaptions(): Boolean

    /**
     * Adds a text title overlay with the given text.
     */
    fun addTitle(text: String, preset: String = "Title"): Boolean

    /**
     * Sets playback speed for selected clip.
     */
    fun setSpeed(speed: Float): Boolean

    /**
     * Toggles timeline playback.
     */
    fun togglePlayback(): Boolean

    /**
     * Undoes the last editing action.
     */
    fun undo(): Boolean

    /**
     * Redoes the last undone editing action.
     */
    fun redo(): Boolean

    /**
     * Dispatches a ParsedCommand to the appropriate action.
     * Returns a pair of (success: Boolean, feedbackMessage: String).
     */
    fun dispatch(command: ParsedCommand): Pair<Boolean, String> {
        val success = when (val intent = command.intent) {
            is VideoEditIntent.AutoPilotEdit -> fullAutoPilotEdit(intent.style)
            is VideoEditIntent.SplitClip -> splitClip()
            is VideoEditIntent.FastBeatCuts -> fastBeatCuts(intent.preset)
            is VideoEditIntent.RemoveSilence -> removeSilence(intent.thresholdDb)
            is VideoEditIntent.DynamicZoom -> dynamicZoom(intent.preset)
            is VideoEditIntent.ApplyColorGrade -> applyColorGrade(intent.lutName)
            is VideoEditIntent.AutoCaptions -> autoCaptions()
            is VideoEditIntent.AddTitle -> addTitle(intent.text, intent.preset)
            is VideoEditIntent.SetSpeed -> setSpeed(intent.speed)
            is VideoEditIntent.TogglePlayback -> togglePlayback()
            is VideoEditIntent.Conversational -> {
                intent.executeIntent?.let { subIntent ->
                    when (subIntent) {
                        is VideoEditIntent.AutoPilotEdit -> fullAutoPilotEdit(subIntent.style)
                        is VideoEditIntent.SplitClip -> splitClip()
                        is VideoEditIntent.FastBeatCuts -> fastBeatCuts(subIntent.preset)
                        is VideoEditIntent.RemoveSilence -> removeSilence(subIntent.thresholdDb)
                        is VideoEditIntent.DynamicZoom -> dynamicZoom(subIntent.preset)
                        is VideoEditIntent.ApplyColorGrade -> applyColorGrade(subIntent.lutName)
                        is VideoEditIntent.AutoCaptions -> autoCaptions()
                        is VideoEditIntent.AddTitle -> addTitle(subIntent.text, subIntent.preset)
                        is VideoEditIntent.SetSpeed -> setSpeed(subIntent.speed)
                        is VideoEditIntent.TogglePlayback -> togglePlayback()
                        is VideoEditIntent.Undo -> undo()
                        is VideoEditIntent.Redo -> redo()
                        else -> true
                    }
                } ?: true
            }
            is VideoEditIntent.Undo -> undo()
            is VideoEditIntent.Redo -> redo()
            is VideoEditIntent.Unknown -> false
        }
        val feedback = if (success) {
            when (val intent = command.intent) {
                is VideoEditIntent.Conversational -> intent.reply
                else -> command.feedbackMessage
            }
        } else {
            when (command.intent) {
                is VideoEditIntent.Unknown -> command.feedbackMessage
                else -> "Could not execute '${command.rawText}'. Please ensure media or a clip is selected on the timeline."
            }
        }
        return Pair(success, feedback)
    }
}
