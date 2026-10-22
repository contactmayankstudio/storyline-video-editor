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
     * Executes autonomous full auto-pilot edit pipeline AND immediately launches video export.
     */
    fun fullAutoPilotEditAndExport(style: String = "ViralReel", resolution: String = "1080p"): Boolean {
        val editSuccess = fullAutoPilotEdit(style)
        return if (editSuccess) {
            exportVideo(resolution)
        } else {
            false
        }
    }

    /**
     * Triggers video export with requested resolution profile (e.g. 1080p, 4K, 720p).
     */
    fun exportVideo(resolution: String = "1080p"): Boolean = false

    /**
     * Changes project preview and export aspect ratio (e.g. 9:16, 16:9, 1:1, 4:5).
     */
    fun setAspectRatio(ratio: String): Boolean = false

    /**
     * Adds a transition preset (e.g. Crossfade, Zoom, Fade) between clips.
     */
    fun addTransition(preset: String = "Crossfade"): Boolean = false

    /**
     * Trims active/selected clip from start or end by specified seconds.
     */
    fun trimClip(seconds: Float, fromStart: Boolean = true): Boolean = false

    /**
     * Sets volume level for selected clip (0.0f to 2.0f).
     */
    fun setVolume(volume: Float): Boolean = false

    /**
     * Mutes selected audio/video clip.
     */
    fun muteClip(): Boolean = setVolume(0f)

    /**
     * Rotates selected video/overlay clip clockwise by degrees (90, 180, 270).
     */
    fun rotateClip(degrees: Int = 90): Boolean = false

    /**
     * Flips or mirrors selected clip horizontally.
     */
    fun flipClip(horizontal: Boolean = true): Boolean = false

    /**
     * Adds background music/beat track to timeline.
     */
    fun addMusic(trackName: String = "lofi"): Boolean = false

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
     * Synthesizes AI voiceover from text and imports to timeline.
     */
    fun generateTtsVoiceover(text: String, lang: String = "hi"): Boolean = false

    /**
     * Automatically syncs cuts and zooms to detected audio rhythm/beats.
     */
    fun autoBeatSync(bpm: Int = 125): Boolean = false

    /**
     * Toggles A/B Before/After split comparison screen.
     */
    fun toggleBeforeAfter(enable: Boolean? = null): Boolean = false

    /**
     * Assembles autonomous complete reel from a topic prompt.
     */
    fun topicToReel(topic: String): Boolean = false

    /**
     * Adds algorithmic studio sound effect (Whoosh, Pop, Ding, Glitch, Thud).
     */
    fun addSoundEffect(sfxType: String = "whoosh"): Boolean = false

    /**
     * Applies AI face-tracking centered dynamic zoom.
     */
    fun faceTrackingZoom(zoomFactor: Float = 1.25f): Boolean = false

    /**
     * Applies AI studio voice clarity & noise reduction to active speech track.
     */
    fun studioVoiceClarity(level: String = "High"): Boolean = false

    /**
     * Applies Alex Hormozi / MrBeast style dynamic punchy captions with 3D stroke & emojis.
     */
    fun dynamicCaptions(style: String = "Hormozi"): Boolean = false

    /**
     * Generates a viral high-resolution composite thumbnail and saves to gallery.
     */
    fun generateThumbnail(headline: String = ""): Boolean = false

    /**
     * Adds dynamic B-roll sticker overlay at playhead.
     */
    fun addStickerOverlay(symbol: String = "🔥"): Boolean = false

    /**
     * Exports project using platform-optimized zero-compression preset.
     */
    fun platformExport(platform: String = "Instagram"): Boolean = false

    /**
     * Self-healing preview reset & recalibrate (fixes blur, freeze, black screen).
     */
    fun resetPreview(): Boolean = false

    /**
     * Dispatches a ParsedCommand to the appropriate action.
     * Returns a pair of (success: Boolean, feedbackMessage: String).
     */
    fun dispatch(command: ParsedCommand): Pair<Boolean, String> {
        val success = when (val intent = command.intent) {
            is VideoEditIntent.AutoPilotEdit -> fullAutoPilotEdit(intent.style)
            is VideoEditIntent.AutoPilotEditAndExport -> fullAutoPilotEditAndExport(intent.style, intent.resolution)
            is VideoEditIntent.ExportVideo -> exportVideo(intent.resolution)
            is VideoEditIntent.SetAspectRatio -> setAspectRatio(intent.ratio)
            is VideoEditIntent.AddTransition -> addTransition(intent.preset)
            is VideoEditIntent.TrimClip -> trimClip(intent.seconds, intent.fromStart)
            is VideoEditIntent.SetVolume -> setVolume(intent.volume)
            is VideoEditIntent.MuteClip -> muteClip()
            is VideoEditIntent.RotateClip -> rotateClip(intent.degrees)
            is VideoEditIntent.FlipClip -> flipClip(intent.horizontal)
            is VideoEditIntent.AddMusic -> addMusic(intent.trackName)
            is VideoEditIntent.SplitClip -> splitClip()
            is VideoEditIntent.FastBeatCuts -> fastBeatCuts(intent.preset)
            is VideoEditIntent.RemoveSilence -> removeSilence(intent.thresholdDb)
            is VideoEditIntent.DynamicZoom -> dynamicZoom(intent.preset)
            is VideoEditIntent.ApplyColorGrade -> applyColorGrade(intent.lutName)
            is VideoEditIntent.AutoCaptions -> autoCaptions()
            is VideoEditIntent.AddTitle -> addTitle(intent.text, intent.preset)
            is VideoEditIntent.SetSpeed -> setSpeed(intent.speed)
            is VideoEditIntent.GenerateTtsVoiceover -> generateTtsVoiceover(intent.text, intent.lang)
            is VideoEditIntent.AutoBeatSync -> autoBeatSync(intent.bpm)
            is VideoEditIntent.ToggleBeforeAfter -> toggleBeforeAfter(intent.enable)
            is VideoEditIntent.TopicToReel -> topicToReel(intent.topic)
            is VideoEditIntent.AddSoundEffect -> addSoundEffect(intent.sfxType)
            is VideoEditIntent.FaceTrackingZoom -> faceTrackingZoom(intent.zoomFactor)
            is VideoEditIntent.StudioVoiceClarity -> studioVoiceClarity(intent.level)
            is VideoEditIntent.DynamicCaptions -> dynamicCaptions(intent.style)
            is VideoEditIntent.GenerateThumbnail -> generateThumbnail(intent.headline)
            is VideoEditIntent.AddStickerOverlay -> addStickerOverlay(intent.emojiOrSymbol)
            is VideoEditIntent.PlatformExport -> platformExport(intent.platform)
            is VideoEditIntent.ResetPreview -> resetPreview()
            is VideoEditIntent.TogglePlayback -> togglePlayback()
            is VideoEditIntent.Conversational -> {
                intent.executeIntent?.let { subIntent ->
                    when (subIntent) {
                        is VideoEditIntent.AutoPilotEdit -> fullAutoPilotEdit(subIntent.style)
                        is VideoEditIntent.AutoPilotEditAndExport -> fullAutoPilotEditAndExport(subIntent.style, subIntent.resolution)
                        is VideoEditIntent.ExportVideo -> exportVideo(subIntent.resolution)
                        is VideoEditIntent.SetAspectRatio -> setAspectRatio(subIntent.ratio)
                        is VideoEditIntent.AddTransition -> addTransition(subIntent.preset)
                        is VideoEditIntent.TrimClip -> trimClip(subIntent.seconds, subIntent.fromStart)
                        is VideoEditIntent.SetVolume -> setVolume(subIntent.volume)
                        is VideoEditIntent.MuteClip -> muteClip()
                        is VideoEditIntent.RotateClip -> rotateClip(subIntent.degrees)
                        is VideoEditIntent.FlipClip -> flipClip(subIntent.horizontal)
                        is VideoEditIntent.AddMusic -> addMusic(subIntent.trackName)
                        is VideoEditIntent.SplitClip -> splitClip()
                        is VideoEditIntent.FastBeatCuts -> fastBeatCuts(subIntent.preset)
                        is VideoEditIntent.RemoveSilence -> removeSilence(subIntent.thresholdDb)
                        is VideoEditIntent.DynamicZoom -> dynamicZoom(subIntent.preset)
                        is VideoEditIntent.ApplyColorGrade -> applyColorGrade(subIntent.lutName)
                        is VideoEditIntent.AutoCaptions -> autoCaptions()
                        is VideoEditIntent.AddTitle -> addTitle(subIntent.text, subIntent.preset)
                        is VideoEditIntent.SetSpeed -> setSpeed(subIntent.speed)
                        is VideoEditIntent.GenerateTtsVoiceover -> generateTtsVoiceover(subIntent.text, subIntent.lang)
                        is VideoEditIntent.AutoBeatSync -> autoBeatSync(subIntent.bpm)
                        is VideoEditIntent.ToggleBeforeAfter -> toggleBeforeAfter(subIntent.enable)
                        is VideoEditIntent.TopicToReel -> topicToReel(subIntent.topic)
                        is VideoEditIntent.AddSoundEffect -> addSoundEffect(subIntent.sfxType)
                        is VideoEditIntent.FaceTrackingZoom -> faceTrackingZoom(subIntent.zoomFactor)
                        is VideoEditIntent.StudioVoiceClarity -> studioVoiceClarity(subIntent.level)
                        is VideoEditIntent.DynamicCaptions -> dynamicCaptions(subIntent.style)
                        is VideoEditIntent.GenerateThumbnail -> generateThumbnail(subIntent.headline)
                        is VideoEditIntent.AddStickerOverlay -> addStickerOverlay(subIntent.emojiOrSymbol)
                        is VideoEditIntent.PlatformExport -> platformExport(subIntent.platform)
                        is VideoEditIntent.ResetPreview -> resetPreview()
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
