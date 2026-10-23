package com.video.engine.ai

import java.util.Locale
import java.util.regex.Pattern

/**
 * Sealed class hierarchy defining supported video editing actions triggered by AI Copilot.
 */
sealed class VideoEditIntent {
    data class AutoPilotEdit(val style: String = "ViralReel") : VideoEditIntent()
    data class AutoPilotEditAndExport(val style: String = "ViralReel", val resolution: String = "1080p") : VideoEditIntent()
    data class ExportVideo(val resolution: String = "1080p") : VideoEditIntent()
    data class SetAspectRatio(val ratio: String) : VideoEditIntent()
    data class AddTransition(val preset: String = "Crossfade") : VideoEditIntent()
    data class TrimClip(val seconds: Float, val fromStart: Boolean = true) : VideoEditIntent()
    data class SetVolume(val volume: Float) : VideoEditIntent()
    object MuteClip : VideoEditIntent()
    data class RotateClip(val degrees: Int = 90) : VideoEditIntent()
    data class FlipClip(val horizontal: Boolean = true) : VideoEditIntent()
    data class AddMusic(val trackName: String = "lofi") : VideoEditIntent()
    data class SplitClip(val atPlayhead: Boolean = true) : VideoEditIntent()
    data class FastBeatCuts(val preset: String = "Punchy") : VideoEditIntent()
    data class RemoveSilence(val thresholdDb: Float = -30f) : VideoEditIntent()
    data class DynamicZoom(val preset: String = "push_in") : VideoEditIntent()
    data class ApplyColorGrade(val lutName: String) : VideoEditIntent()
    object AutoCaptions : VideoEditIntent()
    data class AddTitle(val text: String, val preset: String = "Title") : VideoEditIntent()
    data class SetSpeed(val speed: Float) : VideoEditIntent()
    data class GenerateTtsVoiceover(val text: String, val lang: String = "hi") : VideoEditIntent()
    data class AutoBeatSync(val bpm: Int = 125) : VideoEditIntent()
    data class ToggleBeforeAfter(val enable: Boolean? = null) : VideoEditIntent()
    data class TopicToReel(val topic: String) : VideoEditIntent()
    data class AddSoundEffect(val sfxType: String = "whoosh") : VideoEditIntent()
    data class FaceTrackingZoom(val zoomFactor: Float = 1.25f) : VideoEditIntent()
    data class StudioVoiceClarity(val level: String = "High") : VideoEditIntent()
    data class DynamicCaptions(val style: String = "Hormozi") : VideoEditIntent()
    data class GenerateThumbnail(val headline: String = "") : VideoEditIntent()
    data class AddStickerOverlay(val emojiOrSymbol: String = "🔥") : VideoEditIntent()
    data class PlatformExport(val platform: String = "Instagram") : VideoEditIntent()
    object ResetPreview : VideoEditIntent()
    data class SpeedRamp(val curvePreset: String = "hero_drop") : VideoEditIntent()
    data class SmartJumpCuts(val pacing: String = "Snappy") : VideoEditIntent()
    data class AutoReframe(val targetRatio: String = "9:16") : VideoEditIntent()
    data class Compound(val steps: List<VideoEditIntent>) : VideoEditIntent()
    data class ExtractViralHighlight(val targetDurationSec: Int = 30) : VideoEditIntent()
    data class AutoBrollOverlay(val style: String = "StickersAndText") : VideoEditIntent()
    object InspectTimelineCritic : VideoEditIntent()
    data class SetPlaybackPreviewSpeed(val speed: Float = 1.0f) : VideoEditIntent()
    data class ToggleLoopPlayback(val enable: Boolean? = null) : VideoEditIntent()
    data class NudgeFrame(val forward: Boolean = true) : VideoEditIntent()
    data class JumpToCut(val forward: Boolean = true) : VideoEditIntent()
    data class ReplayLastSeconds(val seconds: Int = 5) : VideoEditIntent()
    object PlayVideo : VideoEditIntent()
    object PauseVideo : VideoEditIntent()
    object TogglePlayback : VideoEditIntent()
    object Undo : VideoEditIntent()
    object Redo : VideoEditIntent()
    data class StatusMakerPro(val style: String = "Trending") : VideoEditIntent()
    data class Conversational(val reply: String, val executeIntent: VideoEditIntent? = null) : VideoEditIntent()
    object Unknown : VideoEditIntent()
}

/**
 * Result of parsing a natural language command.
 */
data class ParsedCommand(
    val rawText: String,
    val intent: VideoEditIntent,
    val confidence: Float,
    val feedbackMessage: String,
)

/**
 * Pure Kotlin NLP intent parser supporting English, Devanagari Hindi, and Romanized Hinglish.
 */
object AiIntentParser {

    private val TITLE_PATTERNS = listOf(
        // Hinglish with verbs
        Pattern.compile("^(?:title\\s+dalo|title\\s+likho|title\\s+lagao)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:text\\s+likho|text\\s+dalo|text\\s+lagao)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:add\\s+caption|caption\\s+likho|caption\\s+dalo)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        // Hindi (Devanagari) with verbs
        Pattern.compile("^(?:टाइटल\\s+लिखो|टाइटल\\s+डालो|शीर्षक\\s+लिखो|शीर्षक\\s+डालो)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:टेक्स्ट\\s+लिखो|टेक्स्ट\\s+डालो)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        // English with verbs
        Pattern.compile("^(?:add\\s+title|insert\\s+title|put\\s+title|new\\s+title)(?:\\s+is|\\s+named|\\s+called|\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:add\\s+text|insert\\s+text|put\\s+text)(?:\\s+is|\\s+named|\\s+called|\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        // Bare fallback prefixes
        Pattern.compile("^(?:title|शीर्षक|टाइटल)(?:\\s+is|\\s+named|\\s+called|\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:text|टेक्स्ट)(?:\\s+is|\\s+named|\\s+called|\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
    )

    private val SPEED_PATTERN = Pattern.compile("(?:speed|pace|रफ्तार|गति)\\s*(?:to|set\\s*to|kar\\s*do|karo|\\s*:)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:x)?", Pattern.CASE_INSENSITIVE)

    /**
     * Parse natural language string and produce a ParsedCommand with intent and feedback message.
     */
    fun parse(rawText: String): ParsedCommand {
        val clean = rawText.trim()
        if (clean.isBlank()) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.Unknown,
                confidence = 0f,
                feedbackMessage = "Please type or speak a video editing command.",
            )
        }

        // 0. Check for compound chained commands (e.g. "pehle silence kato fir cyberpunk filter lagao aur 9:16 karo")
        tryParseCompound(clean)?.let { return it }

        return parseSingle(clean)
    }

    private fun parseSingle(rawText: String): ParsedCommand {
        val clean = rawText.trim()
        val normalized = clean.lowercase(Locale.ROOT)
            .replace(Regex("[?!.,]+$"), "")
            .trim()

        // 0a. Full AI Edit & Export Pipeline (Chat se puri video edit + export)
        if (matchesAutoPilotEditAndExport(normalized)) {
            val style = resolveAutoPilotStyle(normalized)
            val res = resolveExportResolution(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoPilotEditAndExport(style, res),
                confidence = 0.99f,
                feedbackMessage = "🚀 Full AI Video Editor: Puri video ko edit karke $res me render & export kar raha hoon! 🔥",
            )
        }

        // 0b. Platform-Optimized Zero-Compression Export
        if (matchesPlatformExport(normalized)) {
            val platform = resolvePlatform(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.PlatformExport(platform),
                confidence = 0.98f,
                feedbackMessage = "🚀 $platform export pipeline started (Zero-compression standard profile)!",
            )
        }

        // 0c. Export Video
        if (matchesExportVideo(normalized)) {
            val res = resolveExportResolution(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ExportVideo(res),
                confidence = 0.98f,
                feedbackMessage = "🚀 Video export pipeline started ($res Full HD). Video aapki phone gallery me save ho rahi hai!",
            )
        }

        // 0b. Status Maker Pro (1 photo se mast video maker jaisa status / reel)
        if (matchesStatusMakerPro(normalized)) {
            val style = resolveStatusMakerStyle(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.StatusMakerPro(style),
                confidence = 0.99f,
                feedbackMessage = "✨ Status Maker Pro: 1-Photo se dynamic transitions, beat zoom aur lyrical status video ban raha hai! 🔥",
            )
        }

        // 0c. Auto-Pilot Magic Edit Check (Autonomous timeline editing)
        if (matchesAutoPilotEdit(normalized)) {
            val style = resolveAutoPilotStyle(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoPilotEdit(style),
                confidence = 0.98f,
                feedbackMessage = "✨ Auto-Pilot Magic Edit complete! Silence removed, dynamic hook zoom, Teal & Orange color grade, auto captions, and title added.",
            )
        }

        // 0d. AI TTS Voiceover Generation
        if (matchesTtsVoiceover(normalized)) {
            val text = resolveTtsText(clean)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.GenerateTtsVoiceover(text),
                confidence = 0.98f,
                feedbackMessage = "🎙️ AI Voiceover generate karke timeline audio track par add kar diya hai: \"$text\"",
            )
        }

        // 0e. Audio Beat Drop & Rhythm Auto-Sync
        if (matchesAutoBeatSync(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoBeatSync(),
                confidence = 0.98f,
                feedbackMessage = "🎵 Audio beats detect karke video cuts ko music ke rhythm ke saath sync kar diya hai!",
            )
        }

        // 0f. Before / After Split Screen Toggle
        if (matchesBeforeAfter(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ToggleBeforeAfter(),
                confidence = 0.98f,
                feedbackMessage = "🌓 Before / After interactive split-screen comparison preview active kar diya hai!",
            )
        }

        // 0g. Topic to Reel AI Generator
        if (matchesTopicToReel(normalized)) {
            val topic = resolveTopicText(clean)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.TopicToReel(topic),
                confidence = 0.99f,
                feedbackMessage = "📝 '$topic' par full viral reel assemble ho rahi hai: script, AI voiceover, title overlays, BGM aur cuts!",
            )
        }

        // 0h. Sound Effects (SFX) on Cuts
        if (matchesSoundEffect(normalized)) {
            val sfx = resolveSfxType(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AddSoundEffect(sfx),
                confidence = 0.97f,
                feedbackMessage = "🔊 Studio SFX (${sfx.uppercase()}) transition cut par add kar diya hai!",
            )
        }

        // 0i. Face Tracking Zoom
        if (matchesFaceTrackingZoom(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.FaceTrackingZoom(1.25f),
                confidence = 0.97f,
                feedbackMessage = "👤 AI Face-Tracking Dynamic Zoom apply kar diya hai (speaker face centered)!",
            )
        }

        // 0j. Studio Voice Clarity & Noise Removal
        if (matchesStudioVoiceClarity(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.StudioVoiceClarity("High"),
                confidence = 0.98f,
                feedbackMessage = "🎙️ AI Studio Voice Clarity applied! Noise floor reduced aur voice clarity boost ho gayi hai.",
            )
        }

        // 0k. Dynamic Viral Captions (Hormozi / Beast style)
        if (matchesDynamicCaptions(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.DynamicCaptions("Hormozi"),
                confidence = 0.98f,
                feedbackMessage = "✨ Hormozi-style punchy dynamic captions (3D stroke + context emojis) timeline par add ho gaye!",
            )
        }

        // 0l. AI Viral Thumbnail & Cover Photo
        if (matchesGenerateThumbnail(normalized)) {
            val headline = resolveThumbnailHeadline(clean)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.GenerateThumbnail(headline),
                confidence = 0.98f,
                feedbackMessage = "📸 Viral HD Thumbnail generate karke phone gallery me save kar diya hai!",
            )
        }

        // 0m. Smart B-Roll & Dynamic Overlay Sticker
        if (matchesAddSticker(normalized)) {
            val symbol = resolveStickerSymbol(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AddStickerOverlay(symbol),
                confidence = 0.97f,
                feedbackMessage = "🎬 Dynamic sticker overlay ($symbol) playhead par add kar diya hai!",
            )
        }

        // 0n. AI Self-Healing Preview Reset / Calibrate (fixes blur, freeze, black screen)
        if (matchesResetPreview(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ResetPreview,
                confidence = 0.98f,
                feedbackMessage = "🔄 Preview surface buffer aur textures ko reset karke Full HD me recalibrate kar diya hai!",
            )
        }

        // 0o. AI Speed Ramp & Dynamic Curve
        if (matchesSpeedRamp(normalized)) {
            val preset = resolveSpeedRampPreset(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SpeedRamp(preset),
                confidence = 0.98f,
                feedbackMessage = "⚡ AI Speed Ramp ($preset: 2.5x fast -> 0.3x slow-mo) apply kar diya hai!",
            )
        }

        // 0p. Smart Jump-Cuts & Pacing Booster
        if (matchesSmartJumpCuts(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SmartJumpCuts("Snappy"),
                confidence = 0.98f,
                feedbackMessage = "✂️ Smart Jump-Cuts applied! Saare dead-air pauses aur micro-gaps tighten kar diye gaye hain.",
            )
        }

        // 0q. Auto-Reframe & Subject Tracking
        if (matchesAutoReframe(normalized)) {
            val ratio = resolveAutoReframeRatio(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoReframe(ratio),
                confidence = 0.98f,
                feedbackMessage = "🎯 Auto-Reframe ($ratio) complete! Subject automatically center me pan aur crop ho gaya hai.",
            )
        }

        // 0r. AI Video Critic & Timeline Inspector
        if (matchesInspectTimelineCritic(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.InspectTimelineCritic,
                confidence = 0.98f,
                feedbackMessage = "🎬 Analyzing timeline and generating AI Video Critic audit report...",
            )
        }

        // 0s. AI Viral Highlight Finder (Opus Clip Style)
        if (matchesExtractViralHighlight(normalized)) {
            val dur = resolveViralHighlightDuration(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ExtractViralHighlight(dur),
                confidence = 0.98f,
                feedbackMessage = "🎞️ Opus Clip AI: Long video se best ${dur}s high-retention viral short extract kiya ja raha hai!",
            )
        }

        // 0t. AI Auto B-Roll & Visual Overlays
        if (matchesAutoBrollOverlay(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoBrollOverlay("StickersAndText"),
                confidence = 0.98f,
                feedbackMessage = "🖼️ AI B-Roll & Visual Overlays: Dynamic retention stickers aur visual cues timeline par insert kar diye gaye hain!",
            )
        }



        // 1. Aspect Ratio Switcher (9:16, 16:9, 1:1, etc.)
        if (matchesSetAspectRatio(normalized)) {
            val ratio = resolveAspectRatio(normalized)
            val label = when (ratio) {
                "9:16" -> "9:16 (Reels / Shorts / TikTok)"
                "16:9" -> "16:9 (YouTube / Landscape)"
                "1:1" -> "1:1 (Square Post)"
                "4:5" -> "4:5 (Instagram Portrait)"
                "21:9" -> "21:9 (Cinematic Widescreen)"
                else -> ratio
            }
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SetAspectRatio(ratio),
                confidence = 0.96f,
                feedbackMessage = "Aspect ratio switched to $label",
            )
        }

        // 2. Add Background Music / BGM
        if (matchesAddMusic(normalized)) {
            val track = resolveMusicTrack(normalized)
            val trackTitle = when (track) {
                "lofi" -> "Lofi Chill Vlog Beat"
                "trap" -> "Energetic Reels Trap Beat"
                "attitude" -> "Attitude Bass Drop"
                "sad" -> "Sad Emotional Piano"
                "romantic" -> "Romantic Acoustic Love"
                "ambient" -> "Ambient Flute Vibe"
                else -> "Trending BGM"
            }
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AddMusic(track),
                confidence = 0.95f,
                feedbackMessage = "Adding background music: $trackTitle",
            )
        }

        // 3. Add Transitions
        if (matchesAddTransition(normalized)) {
            val preset = resolveTransitionPreset(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AddTransition(preset),
                confidence = 0.94f,
                feedbackMessage = "Applying $preset transition between clips",
            )
        }

        // 4. Remove Silence (Audio silence cutting)
        if (matchesRemoveSilence(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.RemoveSilence(),
                confidence = 0.92f,
                feedbackMessage = "Scanning and removing silent intervals from audio",
            )
        }

        // 5. Fast Beat Cuts / Rhythmic Cuts
        if (matchesFastBeatCuts(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.FastBeatCuts("Punchy"),
                confidence = 0.94f,
                feedbackMessage = "Applying fast rhythmic beat cuts to clip",
            )
        }

        // 6. Split / Cut Clip (at playhead)
        if (matchesSplitClip(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SplitClip(),
                confidence = 0.92f,
                feedbackMessage = "Splitting clip at playhead",
            )
        }

        // 6. Mute / Volume Control
        if (matchesMute(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.MuteClip,
                confidence = 0.95f,
                feedbackMessage = "Muting selected clip audio",
            )
        }
        if (matchesSetVolume(normalized)) {
            val volume = resolveVolumeLevel(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SetVolume(volume),
                confidence = 0.93f,
                feedbackMessage = "Setting volume to ${(volume * 100).toInt()}%",
            )
        }

        // 7. Rotate / Flip
        if (matchesRotate(normalized)) {
            val deg = resolveRotateDegrees(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.RotateClip(deg),
                confidence = 0.94f,
                feedbackMessage = "Rotating clip by $deg°",
            )
        }
        if (matchesFlip(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.FlipClip(true),
                confidence = 0.94f,
                feedbackMessage = "Flipping clip horizontally (mirror)",
            )
        }

        // 8. Trim Clip (Start / End)
        if (matchesTrimClip(normalized)) {
            val (sec, fromStart) = resolveTrimParams(normalized)
            val edgeName = if (fromStart) "start" else "end"
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.TrimClip(sec, fromStart),
                confidence = 0.93f,
                feedbackMessage = "Trimming ${sec}s from clip $edgeName",
            )
        }

        // 7. Check for Add Title / Text with parameter extraction
        for (pattern in TITLE_PATTERNS) {
            val matcher = pattern.matcher(clean)
            if (matcher.matches()) {
                val extracted = matcher.group(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
                if (!extracted.isNullOrBlank() && !isActionKeyword(extracted)) {
                    return ParsedCommand(
                        rawText = rawText,
                        intent = VideoEditIntent.AddTitle(extracted),
                        confidence = 0.95f,
                        feedbackMessage = "Adding title: \"$extracted\"",
                    )
                }
            }
        }

        // 8. Check for Speed parameter
        val speedMatcher = SPEED_PATTERN.matcher(normalized)
        if (speedMatcher.find()) {
            val speedVal = speedMatcher.group(1)?.toFloatOrNull()
            if (speedVal != null && speedVal > 0f) {
                return ParsedCommand(
                    rawText = rawText,
                    intent = VideoEditIntent.SetSpeed(speedVal),
                    confidence = 0.90f,
                    feedbackMessage = "Setting playback speed to ${speedVal}x",
                )
            }
        }

        // 11. Dynamic Zoom / Viral Hook Zoom
        if (matchesDynamicZoom(normalized)) {
            val preset = resolveZoomPreset(normalized)
            val desc = when (preset) {
                "pull_out" -> "pull-out"
                "reset" -> "normal"
                "fill" -> "fill"
                else -> "viral hook push-in"
            }
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.DynamicZoom(preset),
                confidence = 0.93f,
                feedbackMessage = "Applying $desc zoom animation",
            )
        }

        // 12. Color Grade / LUT Presets
        val matchedLut = matchColorGradeLut(normalized)
        if (matchedLut != null) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ApplyColorGrade(matchedLut),
                confidence = 0.95f,
                feedbackMessage = "Applying $matchedLut color grade",
            )
        }

        // 13. Auto Captions / Subtitles
        if (matchesAutoCaptions(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoCaptions,
                confidence = 0.95f,
                feedbackMessage = "Generating AI captions and subtitles",
            )
        }

        // 14. Advanced Timeline Track Playback Controls
        if (matchesPlaybackPreviewSpeed(normalized)) {
            val speed = resolvePlaybackPreviewSpeed(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SetPlaybackPreviewSpeed(speed),
                confidence = 0.95f,
                feedbackMessage = "⏩ Setting timeline preview playback speed to ${speed}x",
            )
        }

        if (matchesLoopPlayback(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ToggleLoopPlayback(),
                confidence = 0.95f,
                feedbackMessage = "🔁 Toggling A/B loop playback mode",
            )
        }

        if (matchesNudgeFrame(normalized)) {
            val fwd = resolveNudgeDirection(normalized)
            val label = if (fwd) "+1 frame forward" else "-1 frame backward"
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.NudgeFrame(fwd),
                confidence = 0.95f,
                feedbackMessage = "🎯 Nudging playhead $label (33ms precision)",
            )
        }

        if (matchesJumpToCut(normalized)) {
            val fwd = resolveCutJumpDirection(normalized)
            val label = if (fwd) "next cut" else "previous cut"
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.JumpToCut(fwd),
                confidence = 0.95f,
                feedbackMessage = "⏭️ Jumping playhead to $label point",
            )
        }

        if (matchesReplayLastSeconds(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ReplayLastSeconds(5),
                confidence = 0.95f,
                feedbackMessage = "⏪ Quick Replay: Playing last 5 seconds of timeline",
            )
        }

        // 15. Playback Controls (Play / Pause / Toggle)
        if (matchesPlayback(normalized)) {
            val isPause = normalized.contains("pause") || normalized.contains("roko") ||
                normalized.contains("rok") || normalized.contains("stop") ||
                normalized.contains("band") || normalized.contains("पॉज़") ||
                normalized.contains("रोको") || normalized.contains("रोक") || normalized.contains("बंद")
            val isPlay = !isPause && (normalized.contains("play") || normalized.contains("chalao") ||
                normalized.contains("chalu") || normalized.contains("shuru") ||
                normalized.contains("start") || normalized.contains("resume") ||
                normalized.contains("chalne") || normalized.contains("प्ले") ||
                normalized.contains("चलाओ") || normalized.contains("चालू") || normalized.contains("शुरू"))

            val intent = when {
                isPause -> VideoEditIntent.PauseVideo
                isPlay -> VideoEditIntent.PlayVideo
                else -> VideoEditIntent.TogglePlayback
            }
            val feedback = when (intent) {
                VideoEditIntent.PauseVideo -> "⏸️ Video pause kar di hai."
                VideoEditIntent.PlayVideo -> "▶️ Video play ho rahi hai."
                else -> "⏯️ Playback toggle kar diya hai."
            }
            return ParsedCommand(
                rawText = rawText,
                intent = intent,
                confidence = 0.95f,
                feedbackMessage = feedback,
            )
        }

        // 16. Undo / Redo
        if (matchesUndo(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.Undo,
                confidence = 0.92f,
                feedbackMessage = "Undoing last edit action",
            )
        }
        if (matchesRedo(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.Redo,
                confidence = 0.92f,
                feedbackMessage = "Redoing action",
            )
        }

        // 17. Smart Conversational Engine (Human chit-chat, advice, greetings, tips)
        SmartConversationalEngine.process(clean)?.let { return it }

        // Fallback: Unknown Command
        return ParsedCommand(
            rawText = rawText,
            intent = VideoEditIntent.Unknown,
            confidence = 0f,
            feedbackMessage = "Command not recognized: \"$clean\". Try: 'export 1080p', 'make 9:16 reel', 'split clip', 'remove silence', or 'add lofi music'.",
        )
    }

    private fun isActionKeyword(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower in listOf(
            "split", "cut", "zoom", "silence", "captions", "subtitles", "kato", "hatao", "banao",
        )
    }

    private fun matchesRemoveSilence(text: String): Boolean {
        // English
        if (text.contains("remove silence") || text.contains("cut silence") ||
            text.contains("delete silence") || text.contains("trim silence") ||
            text.contains("strip silence") || text.contains("clean silence") ||
            text.contains("clear silence") || text.contains("mute silence") ||
            text.contains("cut silent") || text.contains("remove silent")) {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("साइलेंस हटाओ") || text.contains("साइलेंस काटो") ||
            text.contains("साइलेंस रिमूव") || text.contains("सन्नाटा हटाओ") ||
            text.contains("सन्नाटा निकालो") || text.contains("खाली जगह काटो") ||
            text.contains("खाली पार्ट हटाओ") || text.contains("शांत हिस्सा हटाओ")) {
            return true
        }
        // Hinglish
        if (text.contains("silence hatao") || text.contains("silence cut") ||
            text.contains("silence nikalo") || text.contains("silence remove") ||
            text.contains("sannata hatao") || text.contains("sannata cut") ||
            text.contains("khali part hata") || text.contains("khali space hata") ||
            text.contains("quiet part remove") || text.contains("silence kaat") ||
            text.contains("silence kato") || text.contains("silence kaato")) {
            return true
        }
        // Single keyword exact or chip match
        return text == "silence" || text == "remove silence" || text == "साइलेंस"
    }

    private fun matchesFastBeatCuts(text: String): Boolean {
        // English
        if (text.contains("fast beat cuts") || text.contains("fast beat cut") ||
            text.contains("beat cut") || text.contains("beat cuts") ||
            text.contains("rhythmic cut") || text.contains("rhythmic cuts") ||
            text.contains("auto cut") || text.contains("auto cuts") ||
            text.contains("punchy cut") || text.contains("punchy cuts") ||
            text.contains("fast cuts") || text.contains("fast cut")) {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("फास्ट कट") || text.contains("बीट कट") ||
            text.contains("ऑटो कट") || text.contains("तेज़ कट") ||
            text.contains("बीट पर काटो") || text.contains("फास्ट कट्स") ||
            text.contains("बीट कट्स")) {
            return true
        }
        // Hinglish
        if (text.contains("fast cut") || text.contains("beat pe kato") ||
            text.contains("beat pe cut") || text.contains("beat cut karo") ||
            text.contains("auto cut kardo") || text.contains("punchy cut") ||
            text.contains("fast beat")) {
            return true
        }
        return false
    }

    private fun matchesDynamicZoom(text: String): Boolean {
        // English
        if (text.contains("viral hook zoom") || text.contains("viral zoom") ||
            text.contains("dynamic zoom") || text.contains("hook zoom") ||
            text.contains("push in") || text.contains("punch in") ||
            text.contains("zoom in") || text.contains("zoom out") ||
            text.contains("pull out") || text.contains("zoom reset") ||
            text.contains("zoom")) {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("ज़ूम करो") || text.contains("पुश इन करो") ||
            text.contains("वायरल ज़ूम") || text.contains("बड़ा करो") ||
            text.contains("ज़ूम इन") || text.contains("ज़ूम आउट") ||
            text.contains("ज़ूम") || text.contains("हुक ज़ूम")) {
            return true
        }
        // Hinglish
        if (text.contains("zoom karo") || text.contains("push in karo") ||
            text.contains("viral zoom lagao") || text.contains("zoom in kardo") ||
            text.contains("hook zoom") || text.contains("zoom out karo") ||
            text.contains("pull out karo") || text.contains("bada karo") ||
            text.contains("zoom daal")) {
            return true
        }
        return false
    }

    private fun resolveZoomPreset(text: String): String {
        return when {
            text.contains("pull out") || text.contains("zoom out") || text.contains("छोटा करो") -> "pull_out"
            text.contains("reset") || text.contains("normal") || text.contains("हटाओ") -> "reset"
            text.contains("fill") -> "fill"
            else -> "push_in"
        }
    }

    private fun matchColorGradeLut(text: String): String? {
        // Specific LUT names
        if (text.contains("teal and orange") || text.contains("teal & orange") ||
            text.contains("teal orange") || text.contains("टील एंड ऑरेंज") ||
            text.contains("टील और ऑरेंज") || text.contains("teal punch")) {
            return "Teal & Orange"
        }
        if (text.contains("moody dark") || text.contains("moody") || text.contains("मूडी डार्क")) {
            return "Moody Dark"
        }
        if (text.contains("vintage 70s") || text.contains("vintage") || text.contains("35mm") ||
            text.contains("विंटेज") || text.contains("retro print")) {
            return "Vintage 70s"
        }
        if (text.contains("vibrant pop") || text.contains("vibrant") ||
            text.contains("वाइब्रेंट") || text.contains("market pop")) {
            return "Vibrant Pop"
        }
        if (text.contains("cyberpunk") || text.contains("साइबरपंक") || text.contains("night neon")) {
            return "Cyberpunk"
        }
        if (text.contains("retro b&w") || text.contains("b&w") ||
            text.contains("black and white") || text.contains("ब्लैक एंड व्हाइट") ||
            text.contains("noir mono") || text.contains("noir") || text.contains("nolan")) {
            return "Retro B&W"
        }
        if (text.contains("golden hour") || text.contains("गोल्डन आवर") || text.contains("sunset")) {
            return "Golden Hour"
        }
        if (text.contains("cine matte") || text.contains("cinematic") || text.contains("सिनेमैटिक") || text.contains("hollywood")) {
            return "Cine Matte"
        }

        // Generic color grade triggers -> default to popular "Teal & Orange"
        if (text.contains("color grade") || text.contains("apply lut") ||
            text.contains("lut lagao") || text.contains("filter badlo") ||
            text.contains("कलर ग्रेड") || text.contains("रंग बदलो") ||
            text.contains("फिल्टर लगाओ") || text.contains("color look") ||
            text.contains("filter dalo") || text.contains("lut dalo") ||
            text == "lut" || text == "color" || text == "filter") {
            return "Teal & Orange"
        }

        return null
    }

    private fun matchesAutoCaptions(text: String): Boolean {
        // English
        if (text.contains("auto captions") || text.contains("auto caption") ||
            text.contains("generate subtitles") || text.contains("add captions") ||
            text.contains("create subtitles") || text.contains("add subtitles") ||
            text.contains("subtitles") || text.contains("captions") ||
            text.contains("generate captions")) {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("ऑटो कैप्शन") || text.contains("कैप्शन बनाओ") ||
            text.contains("सबटाइटल बनाओ") || text.contains("कैप्शन जोड़ो") ||
            text.contains("सबटाइटल जोड़ो") || text.contains("कैप्शन") ||
            text.contains("सबटाइटल")) {
            return true
        }
        // Hinglish
        if (text.contains("auto caption banao") || text.contains("subtitles generate karo") ||
            text.contains("caption daal") || text.contains("caption lagao") ||
            text.contains("subtitles banao") || text.contains("caption banao") ||
            text.contains("subtitles dalo")) {
            return true
        }
        return false
    }

    private fun matchesSplitClip(text: String): Boolean {
        // English
        if (text.contains("split clip") || text.contains("cut clip") ||
            text.contains("split here") || text.contains("cut here") ||
            text.contains("trim at playhead") || text.contains("cut video") ||
            text.contains("slice clip") || text.contains("divide clip") ||
            text.contains("cut at playhead") || text.contains("split at playhead") ||
            text == "split" || text == "cut") {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("क्लिप काटो") || text.contains("यहाँ से काटो") ||
            text.contains("स्प्लिट करो") || text.contains("कट करो") ||
            text.contains("टुकड़े करो") || text.contains("काट दो") ||
            text.contains("वीडियो काटो") || text.contains("काटो") ||
            text == "स्प्लिट" || text == "कट") {
            return true
        }
        // Hinglish
        if (text.contains("clip kato") || text.contains("split karo") ||
            text.contains("cut karo") || text.contains("cut kar do") ||
            text.contains("kaat do") || text.contains("yahan se kato") ||
            text.contains("yaha se kato") || text.contains("beech se") ||
            text.contains("beech me se") || text.contains("yahan cut karo") ||
            text.contains("clip split karo") || text.contains("clip cut karo") ||
            text.contains("video split") || text.contains("cut kar") ||
            text.contains("split kar") || text.contains("video kato") ||
            text == "kato" || text == "kaato") {
            return true
        }
        return false
    }

    private fun matchesPlayback(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        // Exclude speed controls (e.g. "2x play", "fast preview")
        if (t.contains("2x") || t.contains("1.5x") || t.contains("0.5x") ||
            t.contains("speed") || t.contains("fast") || t.contains("slow")) {
            return false
        }
        // Exclude loop controls
        if (t.contains("loop") || t.contains("लूप") || t.contains("repeat")) {
            return false
        }
        // Exclude replay last seconds
        if (t.contains("replay") || t.contains("rewind") || t.contains("dobara") ||
            t.contains("dubara") || t.contains("5 second") || t.contains("5s")) {
            return false
        }

        // Exact tokens
        if (t in listOf("play", "pause", "resume", "stop", "toggle playback", "play/pause", "play pause", "p")) return true
        if (t in listOf("चलाओ", "रोको", "प्ले करो", "पॉज़ करो", "बंद करो", "शुरू करो", "चालू करो")) return true
        if (t in listOf("chalao", "roko", "play karo", "pause karo", "pause kar do", "chalu karo", "shuru karo", "rok do", "band karo")) return true

        // Substring / keyword matches
        val hasPlay = t.contains("play") || t.contains("chalao") || t.contains("chalu") ||
            t.contains("shuru") || t.contains("resume") || t.contains("start") ||
            t.contains("chalne") || t.contains("प्ले") || t.contains("चलाओ") ||
            t.contains("चालू") || t.contains("शुरू")
        val hasPause = t.contains("pause") || t.contains("roko") || t.contains("rok do") ||
            t.contains("stop") || t.contains("band karo") || t.contains("पॉज़") ||
            t.contains("रोको") || t.contains("रोक") || t.contains("बंद")

        return hasPlay || hasPause
    }

    private fun matchesUndo(text: String): Boolean {
        return text.contains("undo") || text.contains("वापस करो") ||
            text.contains("अनडू करो") || text.contains("undo karo") ||
            text.contains("wapas karo") || text.contains("piche lo")
    }

    private fun matchesRedo(text: String): Boolean {
        return text.contains("redo") || text.contains("फिर से करो") ||
            text.contains("रीडू करो") || text.contains("redo karo") ||
            text.contains("aage badhao")
    }

    private fun matchesStatusMakerPro(text: String): Boolean {
        return text.contains("status") || text.contains("स्टेटस") ||
            text.contains("1 photo") || text.contains("ek photo") ||
            text.contains("one photo") || text.contains("1 pic") ||
            text.contains("photo se video") || text.contains("photo se reel") ||
            text.contains("mast video") || text.contains("mast jaisa") ||
            text.contains("photo video maker") || text.contains("status maker")
    }

    private fun resolveStatusMakerStyle(text: String): String {
        return when {
            text.contains("attitude") || text.contains("एटीट्यूड") -> "Attitude"
            text.contains("love") || text.contains("romantic") || text.contains("प्यार") -> "Love"
            text.contains("sad") || text.contains("emotional") || text.contains("दर्द") -> "Emotional"
            else -> "Trending"
        }
    }

    private fun matchesAutoPilotEdit(text: String): Boolean {
        // English
        if (text.contains("auto pilot") || text.contains("autopilot") ||
            text.contains("auto edit") || text.contains("magic edit") ||
            text.contains("full auto") || text.contains("viral reel edit") ||
            text.contains("auto reel") || text.contains("auto produce") ||
            text.contains("autonomous edit") || text.contains("auto-pilot") ||
            text.contains("smart edit") || text.contains("explain") ||
            text.contains("recap") || text.contains("summary") ||
            text.contains("story recap")) {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("ऑटो एडिट") || text.contains("ऑटोपायलट") ||
            text.contains("ऑटो पायलट") || text.contains("मैजिक एडिट") ||
            text.contains("पूरी वीडियो एडिट") || text.contains("पूरी वीडियो बनाओ") ||
            text.contains("पूरा एडिट करो") || text.contains("सब कुछ एडिट") ||
            text.contains("समझाओ") || text.contains("कहानी")) {
            return true
        }
        // Hinglish
        if (text.contains("auto edit") || text.contains("autopilot") ||
            text.contains("magic edit") || text.contains("puri video edit") ||
            text.contains("poori video edit") || text.contains("pura video edit") ||
            text.contains("poora video edit") || text.contains("pura edit") ||
            text.contains("poora edit") || text.contains("full video edit") ||
            text.contains("sab edit kar") || text.contains("viral reel bana") ||
            text.contains("reel edit kar") || text.contains("auto-pilot") ||
            text.contains("explain") || text.contains("samjhao") ||
            text.contains("kahani") || text.contains("recap")) {
            return true
        }
        return false
    }

    private fun resolveAutoPilotStyle(text: String): String {
        return when {
            text.contains("cinematic") || text.contains("सिनेमैटिक") -> "Cinematic"
            text.contains("vibrant") || text.contains("वाइब्रेंट") -> "Vibrant"
            text.contains("vintage") || text.contains("retro") -> "Vintage"
            else -> "ViralReel"
        }
    }

    private fun matchesAutoPilotEditAndExport(text: String): Boolean {
        val hasEdit = matchesAutoPilotEdit(text) || text.contains("pura video") ||
            text.contains("puri video") || text.contains("full video") ||
            text.contains("full edit") || text.contains("poori video") ||
            text.contains("पूरी वीडियो") || text.contains("पूरा वीडियो")
        val hasExport = text.contains("export") || text.contains("save") ||
            text.contains("render") || text.contains("सेव") || text.contains("एक्सपोर्ट")
        return hasEdit && hasExport
    }

    private fun matchesExportVideo(text: String): Boolean {
        if (matchesPlatformExport(text)) return false
        return text.contains("export") || text.contains("render video") ||
            text.contains("save video") || text.contains("video save") ||
            text.contains("video export") || text.contains("save kardo") ||
            text.contains("save kar do") || text.contains("export kardo") ||
            text.contains("export kar do") || text.contains("export karo") ||
            text.contains("export maaro") || text.contains("render karo") ||
            text.contains("एक्सपोर्ट") || text.contains("सेव करो") ||
            text == "export" || text == "save"
    }

    private fun resolveExportResolution(text: String): String {
        return when {
            text.contains("4k") || text.contains("2160") || text.contains("ultra") -> "4K"
            text.contains("720") || (text.contains("hd") && !text.contains("full") && !text.contains("1080")) -> "720p"
            else -> "1080p"
        }
    }

    private fun matchesSetAspectRatio(text: String): Boolean {
        return text.contains("9:16") || text.contains("16:9") || text.contains("1:1") ||
            text.contains("4:5") || text.contains("21:9") || text.contains("aspect ratio") ||
            text.contains("ratio") || text.contains("reel size") || text.contains("reels size") ||
            text.contains("shorts size") || text.contains("youtube size") || text.contains("portrait") ||
            text.contains("landscape") || text.contains("वर्टिकल") || text.contains("हॉरिजॉन्टल")
    }

    private fun resolveAspectRatio(text: String): String {
        return when {
            text.contains("9:16") || text.contains("reel") || text.contains("shorts") ||
                text.contains("tiktok") || text.contains("portrait") || text.contains("vertical") -> "9:16"
            text.contains("16:9") || text.contains("youtube") || text.contains("landscape") ||
                text.contains("horizontal") -> "16:9"
            text.contains("1:1") || text.contains("square") -> "1:1"
            text.contains("4:5") -> "4:5"
            text.contains("21:9") || text.contains("cinema") -> "21:9"
            else -> "9:16"
        }
    }

    private fun matchesAddMusic(text: String): Boolean {
        return text.contains("music") || text.contains("bgm") || text.contains("soundtrack") ||
            text.contains("song") || text.contains("gaana") || text.contains("gana") ||
            text.contains("म्यूजिक") || text.contains("गाना") || text.contains("संगीत") ||
            text.contains("beat lagao") || text.contains("sound dalo")
    }

    private fun resolveMusicTrack(text: String): String {
        return when {
            text.contains("lofi") || text.contains("chill") -> "lofi"
            text.contains("trap") || text.contains("energetic") -> "trap"
            text.contains("attitude") || text.contains("bass") -> "attitude"
            text.contains("sad") || text.contains("emotional") -> "sad"
            text.contains("romantic") || text.contains("love") -> "romantic"
            text.contains("flute") || text.contains("ambient") || text.contains("devotional") -> "ambient"
            else -> "lofi"
        }
    }

    private fun matchesAddTransition(text: String): Boolean {
        return text.contains("transition") || text.contains("crossfade") ||
            text.contains("ट्रांज़िशन") || text.contains("fade transition") ||
            text.contains("zoom transition")
    }

    private fun resolveTransitionPreset(text: String): String {
        return when {
            text.contains("zoom") -> "Zoom"
            text.contains("fade") -> "Fade"
            text.contains("slide") -> "Slide"
            else -> "Crossfade"
        }
    }

    private fun matchesMute(text: String): Boolean {
        return text.contains("mute") || text.contains("म्यूट") || text.contains("volume 0") ||
            text.contains("awaaz band") || text.contains("aawaz band") || text.contains("awaz band") ||
            text.contains("sound off") || text.contains("silent")
    }

    private fun matchesSetVolume(text: String): Boolean {
        return text.contains("volume") || text.contains("sound level") ||
            text.contains("awaaz") || text.contains("aawaz") || text.contains("awaz") ||
            text.contains("वॉल्यूम")
    }

    private fun resolveVolumeLevel(text: String): Float {
        val numPattern = Pattern.compile("(\\d+)")
        val matcher = numPattern.matcher(text)
        if (matcher.find()) {
            val num = matcher.group(1)?.toFloatOrNull() ?: 100f
            return if (num > 2.0f) (num / 100f).coerceIn(0f, 2.0f) else num.coerceIn(0f, 2.0f)
        }
        return 1.0f
    }

    private fun matchesRotate(text: String): Boolean {
        return text.contains("rotate") || text.contains("घूमा") ||
            text.contains("घुमा") || text.contains("रोटेट")
    }

    private fun resolveRotateDegrees(text: String): Int {
        return when {
            text.contains("180") -> 180
            text.contains("270") -> 270
            else -> 90
        }
    }

    private fun matchesFlip(text: String): Boolean {
        return text.contains("flip") || text.contains("mirror") ||
            text.contains("फ्लिप") || text.contains("मिरर") || text.contains("ulta")
    }

    private fun matchesTrimClip(text: String): Boolean {
        if (text.contains("silence") || text.contains("playhead")) return false
        return text.contains("trim") || text.contains("shuru ka") ||
            text.contains("pehle ka") || text.contains("last ka") || text.contains("end ka")
    }

    private fun resolveTrimParams(text: String): Pair<Float, Boolean> {
        val numPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)")
        val matcher = numPattern.matcher(text)
        val sec = if (matcher.find()) {
            matcher.group(1)?.toFloatOrNull() ?: 2f
        } else {
            2f
        }
        val fromStart = !text.contains("end") && !text.contains("last")
        return Pair(sec, fromStart)
    }

    private fun matchesTtsVoiceover(text: String): Boolean {
        return text.contains("voiceover") || text.contains("ai voice") ||
            text.contains("tts") || text.contains("bolkar sunao") ||
            text.contains("speech add") || text.contains("awaz dalo")
    }

    private fun resolveTtsText(raw: String): String {
        val parts = raw.split(":", limit = 2)
        if (parts.size > 1 && parts[1].trim().isNotBlank()) {
            return parts[1].trim()
        }
        val clean = raw.replace(Regex("(?i)^(?:ai\\s+voice|voiceover|tts|awaz\\s+dalo|lagao|add\\s+karo)\\s*"), "").trim()
        return if (clean.isNotBlank()) clean else "Welcome to Storyline Video Editor!"
    }

    private fun matchesAutoBeatSync(text: String): Boolean {
        if (text.contains("fast beat") || text.contains("beat cut") || text.contains("beat cuts") ||
            text.contains("rhythmic cut") || text.contains("auto cut") || text.contains("beat pe kato") ||
            text.contains("beat pe cut") || text.contains("बीट पर काटो") || text.contains("बीट कट")) {
            return false
        }
        return text.contains("beat drop") || text.contains("beat sync") || text.contains("sync to beat") ||
            text.contains("sync with beat") || text.contains("rhythm sync") || text.contains("beats pe sync") ||
            text.contains("music beat") || text.contains("audio beat") || text.contains("beat detect") ||
            text.contains("beats pe") || text.contains("beats par")
    }

    private fun matchesBeforeAfter(text: String): Boolean {
        return text.contains("before after") || text.contains("split screen") ||
            text.contains("compare") || text.contains("pehle aur baad") || text.contains("split view")
    }

    private fun matchesTopicToReel(text: String): Boolean {
        return text.startsWith("topic to reel") || text.startsWith("make reel on") ||
            text.startsWith("create reel on") || text.contains("par reel banao") ||
            text.contains("pe reel banao") || text.startsWith("reel banao")
    }

    private fun resolveTopicText(raw: String): String {
        val parts = raw.split(":", limit = 2)
        if (parts.size > 1 && parts[1].trim().isNotBlank()) {
            return parts[1].trim()
        }
        val clean = raw.replace(Regex("(?i)^(?:topic\\s+to\\s+reel|make\\s+reel\\s+on|create\\s+reel\\s+on|reel\\s+banao|par\\s+reel\\s+banao)\\s*"), "").trim()
        return if (clean.isNotBlank()) clean else "Trending Viral Reel"
    }

    private fun matchesSoundEffect(text: String): Boolean {
        return text.contains("whoosh") || text.contains("sfx") || text.contains("sound effect") ||
            text.contains("sound fx") || text.contains("sound effects") ||
            text.contains("pop sound") || text.contains("ding sound") || text.contains("glitch sound") ||
            text.contains("thud sound") || text.contains("transition sound") ||
            (text.contains("sound") && (text.contains("ding") || text.contains("bell") || text.contains("pop") || text.contains("glitch") || text.contains("thud") || text.contains("whoosh")))
    }

    private fun resolveSfxType(text: String): String {
        return when {
            text.contains("pop") -> "pop"
            text.contains("ding") || text.contains("bell") -> "ding"
            text.contains("glitch") -> "glitch"
            text.contains("thud") || text.contains("bass") -> "thud"
            else -> "whoosh"
        }
    }

    private fun matchesFaceTrackingZoom(text: String): Boolean {
        return text.contains("face tracking") || text.contains("chehre pe zoom") ||
            text.contains("face zoom") || text.contains("smart face")
    }

    private fun matchesStudioVoiceClarity(text: String): Boolean {
        return text.contains("noise") || text.contains("shor") ||
            text.contains("voice clarity") || text.contains("studio audio") ||
            text.contains("clean audio") || text.contains("noise cancellation") ||
            text.contains("audio saaf") || text.contains("voice saaf") ||
            text.contains("studio voice") || text.contains("clear audio")
    }

    private fun matchesDynamicCaptions(text: String): Boolean {
        return text.contains("hormozi") || text.contains("dynamic caption") ||
            text.contains("dynamic captions") || text.contains("mrbeast") ||
            text.contains("bouncy caption") || text.contains("bouncy captions") ||
            text.contains("viral subtitle") || text.contains("animated subtitle") ||
            text.contains("bouncy subtitle")
    }

    private fun matchesGenerateThumbnail(text: String): Boolean {
        return text.contains("thumbnail") || text.contains("cover photo") ||
            text.contains("cover frame") || text.contains("थंबनेल") ||
            text.contains("कवर फोटो")
    }

    private fun resolveThumbnailHeadline(raw: String): String {
        val parts = raw.split(":", limit = 2)
        if (parts.size > 1 && parts[1].trim().isNotBlank()) {
            return parts[1].trim()
        }
        val clean = raw.replace(Regex("(?i)^(?:thumbnail\\s+banao|make\\s+thumbnail|generate\\s+thumbnail|cover\\s+photo\\s+banao|thumbnail)\\s*"), "").trim()
        return if (clean.isNotBlank()) clean else "VIRAL STORY"
    }

    private fun matchesAddSticker(text: String): Boolean {
        return text.contains("sticker") || text.contains("स्टीकर") ||
            text.contains("emoji overlay") || text.contains("b-roll sticker") ||
            text.contains("pip sticker")
    }

    private fun resolveStickerSymbol(text: String): String {
        return when {
            text.contains("fire") || text.contains("aag") -> "🔥"
            text.contains("star") || text.contains("sitara") -> "⭐"
            text.contains("heart") || text.contains("dil") || text.contains("love") -> "❤️"
            text.contains("clap") || text.contains("action") || text.contains("cinema") -> "🎬"
            text.contains("boom") || text.contains("blast") -> "💥"
            else -> "✨"
        }
    }

    private fun matchesPlatformExport(text: String): Boolean {
        return (text.contains("export") && (text.contains("insta") || text.contains("reel") || text.contains("youtube") || text.contains("yt") || text.contains("shorts") || text.contains("whatsapp") || text.contains("tiktok"))) ||
            text.contains("instagram ke liye") || text.contains("whatsapp status")
    }

    private fun resolvePlatform(text: String): String {
        return when {
            text.contains("whatsapp") -> "WhatsApp Status"
            text.contains("youtube") || text.contains("yt") || text.contains("shorts") -> "YouTube Shorts"
            text.contains("tiktok") -> "TikTok"
            else -> "Instagram Reels"
        }
    }

    private fun matchesResetPreview(text: String): Boolean {
        return (text.contains("preview") && (text.contains("blur") || text.contains("reset") || text.contains("fix") || text.contains("saaf") || text.contains("theek") || text.contains("freeze") || text.contains("stuck") || text.contains("black") || text.contains("refresh"))) ||
            text.contains("preview blur") || text.contains("preview theek karo") || text.contains("preview reset") || text.contains("fix preview")
    }

    private fun matchesSpeedRamp(text: String): Boolean {
        return text.contains("speed ramp") || text.contains("curve ramp") ||
            text.contains("velocity") || text.contains("velocity curve") ||
            text.contains("bullet time") || text.contains("slow mo ramp") ||
            text.contains("cinematic ramp") || text.contains("speed curve") ||
            text.contains("रैंप") || text.contains("वेलोसिटी")
    }

    private fun resolveSpeedRampPreset(text: String): String {
        return when {
            text.contains("bullet") -> "bullet_time"
            text.contains("montage") -> "montage"
            text.contains("hero") -> "hero_drop"
            text.contains("flash") -> "flash_cut"
            text.contains("slow") || text.contains("smooth") -> "ease_in"
            else -> "hero_drop"
        }
    }

    private fun matchesSmartJumpCuts(text: String): Boolean {
        return text.contains("jump cut") || text.contains("jump cuts") ||
            text.contains("smart jump") || text.contains("pacing booster") ||
            text.contains("fast pacing") || text.contains("talking head pacer") ||
            text.contains("tight jump") || text.contains("tight cut") ||
            text.contains("जंप कट")
    }

    private fun matchesAutoReframe(text: String): Boolean {
        return text.contains("auto reframe") || text.contains("smart reframe") ||
            text.contains("center subject") || text.contains("reframe 9:16") ||
            text.contains("horizontal to vertical") || text.contains("reframe karo") ||
            text.contains("subject center") || text.contains("ऑटो रीफ्रेम")
    }

    private fun resolveAutoReframeRatio(text: String): String {
        return when {
            text.contains("1:1") -> "1:1"
            text.contains("16:9") -> "16:9"
            else -> "9:16"
        }
    }

    private fun matchesInspectTimelineCritic(text: String): Boolean {
        // English
        if (text.contains("review my video") || text.contains("inspect video") ||
            text.contains("video critic") || text.contains("how is my video") ||
            text.contains("check my timeline") || text.contains("analyze video") ||
            text.contains("audit video") || text.contains("timeline audit") ||
            text.contains("critique video") || text.contains("video feedback") ||
            text.contains("check video") || text.contains("review video") ||
            text.contains("inspect timeline") || text.contains("director review")) {
            return true
        }
        // Hindi / Hinglish
        return text.contains("video check") || text.contains("video kaisi") ||
            text.contains("kya kami hai") || text.contains("kya kami h") ||
            text.contains("video analyze") || text.contains("timeline check") ||
            text.contains("kya sudhar") || text.contains("feedback do") ||
            text.contains("video dekho") || text.contains("video kaisa") ||
            text.contains("कैसी लग रही") || text.contains("वीडियो चेक") ||
            text.contains("कमी बताओ") || text.contains("फीडबैक दो")
    }

    private fun matchesExtractViralHighlight(text: String): Boolean {
        // English
        if (text.contains("viral highlight") || text.contains("viral clip") ||
            text.contains("extract highlight") || text.contains("find highlight") ||
            text.contains("highlight finder") || text.contains("opus clip") ||
            text.contains("viral moment") || text.contains("best part") ||
            text.contains("best moment") || text.contains("best 30") ||
            text.contains("best 60") || text.contains("auto highlight")) {
            return true
        }
        // Hindi / Hinglish
        return text.contains("viral part") || text.contains("viral clip") ||
            text.contains("highlight nikalo") || text.contains("highlight nikal") ||
            text.contains("best part cut") || text.contains("best part nikalo") ||
            text.contains("sabse accha part") || text.contains("viral cut") ||
            text.contains("वायरल पार्ट") || text.contains("हाइलाइट") ||
            text.contains("बेस्ट पार्ट")
    }

    private fun resolveViralHighlightDuration(text: String): Int {
        return if (text.contains("60") || text.contains("minute") || text.contains("मिनट")) 60 else 30
    }

    private fun matchesAutoBrollOverlay(text: String): Boolean {
        // English
        if (text.contains("auto b-roll") || text.contains("auto broll") ||
            text.contains("add b-roll") || text.contains("add broll") ||
            text.contains("b-roll overlay") || text.contains("broll overlay") ||
            text.contains("smart overlay") || text.contains("smart overlays") ||
            text.contains("visual overlay") || text.contains("visual overlays") ||
            text.contains("auto visuals") || text.contains("b-roll") ||
            text.contains("b roll") || text.contains("broll")) {
            return true
        }
        // Hindi / Hinglish
        return text.contains("b roll lagao") || text.contains("broll lagao") ||
            text.contains("b roll dalo") || text.contains("broll dalo") ||
            text.contains("auto overlay") || text.contains("smart visuals") ||
            text.contains("visuals dalo") || text.contains("visuals lagao") ||
            text.contains("बी रोल") || text.contains("स्मार्ट ओवरले")
    }

    private fun tryParseCompound(clean: String): ParsedCommand? {
        val lower = clean.lowercase(Locale.ROOT)

        // Protect text/title/voiceover generation commands from being split
        if (lower.startsWith("title") || lower.startsWith("text") || lower.startsWith("शीर्षक") ||
            lower.startsWith("voiceover") || lower.startsWith("tts") || lower.startsWith("topic to reel") ||
            TITLE_PATTERNS.any { it.matcher(clean).find() }) {
            return null
        }

        // Check if query contains compound conjunctions
        val hasConjunction = lower.contains(" aur ") || lower.contains(" and ") ||
            lower.contains(" fir ") || lower.contains(" phir ") || lower.contains(" then ") ||
            lower.contains(" uske baad ") || lower.contains(" ke baad ") ||
            (lower.contains(",") && !lower.contains("1080p,") && !lower.contains("4k,"))

        if (!hasConjunction) return null

        // Protect known phrases that contain "and" or similar conjunction words
        var masked = clean
        val protectedPhrases = listOf(
            "teal and orange" to "teal__AND__orange",
            "teal & orange" to "teal__AND__orange",
            "black and white" to "black__AND__white",
            "black & white" to "black__AND__white",
            "before and after" to "before__AND__after",
            "before & after" to "before__AND__after",
            "edit and export" to "edit__AND__export",
            "edit & export" to "edit__AND__export",
            "b & w" to "b__AND__w",
            "b&w" to "b__AND__w",
        )

        for ((phrase, mask) in protectedPhrases) {
            masked = masked.replace(Regex("(?i)" + Pattern.quote(phrase)), mask)
        }

        // Split on conjunction regex
        val regex = Regex("(?i)\\s+(?:and|aur|fir|phir|then|uske baad|ke baad)\\s+|\\s*,\\s*")
        val rawParts = masked.split(regex)
            .map { part ->
                var restored = part.trim()
                for ((phrase, mask) in protectedPhrases) {
                    restored = restored.replace(mask, phrase)
                }
                restored
            }
            .filter { it.isNotBlank() }

        if (rawParts.size < 2) return null

        val parsedSteps = mutableListOf<VideoEditIntent>()

        for (part in rawParts) {
            // Strip leading conversational fillers if any: "pehle", "bhai", "please"
            val trimmedPart = part
                .replace(Regex("(?i)^(?:pehle|bhai|bhaiya|please|plz|first|ab|fir|phir)\\s+"), "")
                .trim()
            if (trimmedPart.isBlank()) continue

            val cmd = parseSingle(trimmedPart)
            if (cmd.intent !is VideoEditIntent.Unknown && cmd.intent !is VideoEditIntent.Conversational) {
                parsedSteps.add(cmd.intent)
            }
        }

        if (parsedSteps.size < 2) return null

        val summary = "🚀 AI Compound Pipeline (${parsedSteps.size} actions):\n" +
            parsedSteps.mapIndexed { i, intent ->
                val desc = when (intent) {
                    is VideoEditIntent.RemoveSilence -> "✂️ Cut silence & tightened dead-air"
                    is VideoEditIntent.ApplyColorGrade -> "🎨 Applied ${intent.lutName} color grade"
                    is VideoEditIntent.SetAspectRatio -> "📱 Changed aspect ratio to ${intent.ratio}"
                    is VideoEditIntent.AddMusic -> "🎵 Added ${intent.trackName} background music"
                    is VideoEditIntent.AutoCaptions -> "💬 Generated auto captions"
                    is VideoEditIntent.DynamicCaptions -> "✨ Added ${intent.style} dynamic captions"
                    is VideoEditIntent.DynamicZoom -> "🔍 Added dynamic hook zoom"
                    is VideoEditIntent.SpeedRamp -> "⚡ Applied speed ramp (${intent.curvePreset})"
                    is VideoEditIntent.SmartJumpCuts -> "✂️ Applied smart jump-cuts"
                    is VideoEditIntent.AutoReframe -> "🎯 Auto-reframed to ${intent.targetRatio}"
                    is VideoEditIntent.StudioVoiceClarity -> "🎤 Studio voice clarity applied"
                    is VideoEditIntent.AddTransition -> "🔀 Added ${intent.preset} transition"
                    is VideoEditIntent.AddStickerOverlay -> "🔥 Added sticker overlay"
                    is VideoEditIntent.AutoBrollOverlay -> "🖼️ Added AI B-Roll & Visual Overlays"
                    is VideoEditIntent.ExtractViralHighlight -> "🎞️ Extracted ${intent.targetDurationSec}s viral highlight"
                    is VideoEditIntent.ExportVideo -> "🚀 Exported video (${intent.resolution})"
                    else -> "Action executed"
                }
                "${i + 1}. $desc"
            }.joinToString("\n")

        return ParsedCommand(
            rawText = clean,
            intent = VideoEditIntent.Compound(parsedSteps),
            confidence = 0.98f,
            feedbackMessage = summary,
        )
    }

    private fun matchesReplayLastSeconds(text: String): Boolean {
        return text.contains("replay") || text.contains("rewind") ||
            text.contains("5 second") || text.contains("5s") ||
            text.contains("5 सेक") || text.contains("फिर से चलाओ") ||
            text.contains("dobara chalao") || text.contains("dubara chalao") ||
            text.contains("piche se play") || text.contains("piche se chalao")
    }

    private fun matchesLoopPlayback(text: String): Boolean {
        return text.contains("loop playback") || text.contains("loop play") ||
            text.contains("loop me chalao") || text.contains("loop chalu") ||
            text.contains("repeat playback") || text.contains("a/b loop") ||
            text.contains("loop video") || text.contains("लूप प्ले") ||
            text.contains("लूप में चलाओ") || text == "loop" || text == "लूप"
    }

    private fun matchesNudgeFrame(text: String): Boolean {
        return text.contains("frame aage") || text.contains("frame piche") ||
            text.contains("next frame") || text.contains("prev frame") ||
            text.contains("previous frame") || text.contains("frame forward") ||
            text.contains("frame back") || text.contains("step forward") ||
            text.contains("step back") || text.contains("nudge forward") ||
            text.contains("nudge back") || text.contains("अगला फ्रेम") ||
            text.contains("पिछला फ्रेम") || text.contains("1 frame")
    }

    private fun resolveNudgeDirection(text: String): Boolean {
        return !(text.contains("piche") || text.contains("back") || text.contains("prev") || text.contains("पिछला"))
    }

    private fun matchesJumpToCut(text: String): Boolean {
        return text.contains("cut par jao") || text.contains("cut pe jao") ||
            text.contains("next cut") || text.contains("prev cut") ||
            text.contains("previous cut") || text.contains("next split") ||
            text.contains("prev split") || text.contains("jump next cut") ||
            text.contains("jump prev cut") || text.contains("agle cut") ||
            text.contains("pichle cut") || text.contains("अगले कट") ||
            text.contains("पिछले कट")
    }

    private fun resolveCutJumpDirection(text: String): Boolean {
        return !(text.contains("pichle") || text.contains("prev") || text.contains("back") || text.contains("पिछला"))
    }

    private fun matchesPlaybackPreviewSpeed(text: String): Boolean {
        return (text.contains("play") || text.contains("chalao") || text.contains("preview") || text.contains("playback")) &&
            (text.contains("2x") || text.contains("1.5x") || text.contains("0.5x") || text.contains("fast preview") || text.contains("slow preview") || text.contains("slow mo"))
    }

    private fun resolvePlaybackPreviewSpeed(text: String): Float {
        return when {
            text.contains("2x") -> 2.0f
            text.contains("1.5x") -> 1.5f
            text.contains("0.5x") || text.contains("slow") -> 0.5f
            else -> 2.0f
        }
    }
}
