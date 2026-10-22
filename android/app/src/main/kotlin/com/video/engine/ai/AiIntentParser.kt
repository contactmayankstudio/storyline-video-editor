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
    object TogglePlayback : VideoEditIntent()
    object Undo : VideoEditIntent()
    object Redo : VideoEditIntent()
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

        // 0b. Export Video
        if (matchesExportVideo(normalized)) {
            val res = resolveExportResolution(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ExportVideo(res),
                confidence = 0.98f,
                feedbackMessage = "🚀 Video export pipeline started ($res Full HD). Video aapki phone gallery me save ho rahi hai!",
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

        // 15. Playback Controls
        if (matchesPlayback(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.TogglePlayback,
                confidence = 0.90f,
                feedbackMessage = "Toggling playback",
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
            text.contains("quiet part remove") || text.contains("silence kaat")) {
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
        if (text.contains("vintage 70s") || text.contains("vintage") ||
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
            text.contains("noir mono") || text.contains("noir")) {
            return "Retro B&W"
        }
        if (text.contains("golden hour") || text.contains("गोल्डन आवर")) {
            return "Golden Hour"
        }
        if (text.contains("cine matte") || text.contains("cinematic") || text.contains("सिनेमैटिक")) {
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
            text.contains("yahan cut karo") || text.contains("clip split karo") ||
            text.contains("video kato") || text == "kato" || text == "kaato") {
            return true
        }
        return false
    }

    private fun matchesPlayback(text: String): Boolean {
        return text in listOf("play", "pause", "resume", "stop", "toggle playback") ||
            text in listOf("चलाओ", "रोको", "प्ले करो", "पॉज़ करो", "बंद करो") ||
            text in listOf("chalao", "roko", "play karo", "pause karo", "pause kar do")
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

    private fun matchesAutoPilotEdit(text: String): Boolean {
        // English
        if (text.contains("auto pilot") || text.contains("autopilot") ||
            text.contains("auto edit") || text.contains("magic edit") ||
            text.contains("full auto") || text.contains("viral reel edit") ||
            text.contains("auto reel") || text.contains("auto produce") ||
            text.contains("autonomous edit") || text.contains("auto-pilot") ||
            text.contains("smart edit")) {
            return true
        }
        // Hindi (Devanagari)
        if (text.contains("ऑटो एडिट") || text.contains("ऑटोपायलट") ||
            text.contains("ऑटो पायलट") || text.contains("मैजिक एडिट") ||
            text.contains("पूरी वीडियो एडिट") || text.contains("पूरी वीडियो बनाओ") ||
            text.contains("पूरा एडिट करो") || text.contains("सब कुछ एडिट")) {
            return true
        }
        // Hinglish
        if (text.contains("auto edit") || text.contains("autopilot") ||
            text.contains("magic edit") || text.contains("puri video edit") ||
            text.contains("poori video edit") || text.contains("pura video edit") ||
            text.contains("poora video edit") || text.contains("pura edit") ||
            text.contains("poora edit") || text.contains("full video edit") ||
            text.contains("sab edit kar") || text.contains("viral reel bana") ||
            text.contains("reel edit kar") || text.contains("auto-pilot")) {
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
            text.contains("awaaz band") || text.contains("sound off") || text.contains("silent")
    }

    private fun matchesSetVolume(text: String): Boolean {
        return text.contains("volume") || text.contains("sound level") ||
            text.contains("awaaz") || text.contains("वॉल्यूम")
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
}
