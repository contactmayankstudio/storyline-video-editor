package com.video.engine.ai

import java.util.Locale
import java.util.regex.Pattern

/**
 * Sealed class hierarchy defining supported video editing actions triggered by AI Copilot.
 */
sealed class VideoEditIntent {
    data class AutoPilotEdit(val style: String = "ViralReel") : VideoEditIntent()
    data class SplitClip(val atPlayhead: Boolean = true) : VideoEditIntent()
    data class FastBeatCuts(val preset: String = "Punchy") : VideoEditIntent()
    data class RemoveSilence(val thresholdDb: Float = -30f) : VideoEditIntent()
    data class DynamicZoom(val preset: String = "push_in") : VideoEditIntent()
    data class ApplyColorGrade(val lutName: String) : VideoEditIntent()
    object AutoCaptions : VideoEditIntent()
    data class AddTitle(val text: String, val preset: String = "Title") : VideoEditIntent()
    data class SetSpeed(val speed: Float) : VideoEditIntent()
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
        // English
        Pattern.compile("^(?:add\\s+title|insert\\s+title|put\\s+title|new\\s+title|title)(?:\\s+is|\\s+named|\\s+called|\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:add\\s+text|insert\\s+text|put\\s+text|text)(?:\\s+is|\\s+named|\\s+called|\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        // Hindi (Devanagari)
        Pattern.compile("^(?:टाइटल\\s+लिखो|टाइटल\\s+डालो|शीर्षक\\s+लिखो|शीर्षक\\s+डालो|टाइटल)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:टेक्स्ट\\s+लिखो|टेक्स्ट\\s+डालो|टेक्स्ट)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        // Hinglish
        Pattern.compile("^(?:title\\s+dalo|title\\s+likho|title\\s+lagao)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:text\\s+likho|text\\s+dalo|text\\s+lagao)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:add\\s+caption|caption\\s+likho|caption\\s+dalo)(?:\\s*:)?\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
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

        // 0. Auto-Pilot Magic Edit Check (Higher priority autonomous editing)
        if (matchesAutoPilotEdit(normalized)) {
            val style = resolveAutoPilotStyle(normalized)
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoPilotEdit(style),
                confidence = 0.98f,
                feedbackMessage = "✨ Auto-Pilot Magic Edit complete! Silence removed, dynamic hook zoom, Teal & Orange color grade, auto captions, and title added.",
            )
        }

        // 0b. Smart Conversational Engine Check (Human chit-chat, advice, Hinglish commands)
        SmartConversationalEngine.process(clean)?.let { return it }

        // 1. Check for Add Title / Text with parameter extraction
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

        // 2. Check for Speed parameter
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

        // 3. Remove Silence
        if (matchesRemoveSilence(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.RemoveSilence(),
                confidence = 0.92f,
                feedbackMessage = "Scanning and removing silent intervals from audio",
            )
        }

        // 4. Fast Beat Cuts / Rhythmic Cuts
        if (matchesFastBeatCuts(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.FastBeatCuts("Punchy"),
                confidence = 0.94f,
                feedbackMessage = "Applying fast rhythmic beat cuts to clip",
            )
        }

        // 5. Dynamic Zoom / Viral Hook Zoom
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

        // 6. Color Grade / LUT Presets
        val matchedLut = matchColorGradeLut(normalized)
        if (matchedLut != null) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.ApplyColorGrade(matchedLut),
                confidence = 0.95f,
                feedbackMessage = "Applying $matchedLut color grade",
            )
        }

        // 7. Auto Captions / Subtitles
        if (matchesAutoCaptions(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.AutoCaptions,
                confidence = 0.95f,
                feedbackMessage = "Generating AI captions and subtitles",
            )
        }

        // 8. Split / Cut Clip
        if (matchesSplitClip(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.SplitClip(),
                confidence = 0.92f,
                feedbackMessage = "Splitting clip at playhead",
            )
        }

        // 9. Playback Controls
        if (matchesPlayback(normalized)) {
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.TogglePlayback,
                confidence = 0.90f,
                feedbackMessage = "Toggling playback",
            )
        }

        // 10. Undo / Redo
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

        // Fallback: Smart Conversational Guidance
        val helpfulReply = SmartConversationalEngine.generateHelpfulFallback(clean)
        return ParsedCommand(
            rawText = rawText,
            intent = VideoEditIntent.Conversational(helpfulReply),
            confidence = 0.85f,
            feedbackMessage = helpfulReply,
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
            text.contains("fast cuts") || text.contains("fast cut") ||
            text.contains("beat sync")) {
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
}
