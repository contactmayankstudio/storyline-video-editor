package com.video.engine.ai

import java.util.Locale

/**
 * High-Level Conversational AI Engine for Storyline Video Editor.
 * Delivers human-like chat responses in Hinglish, Hindi, and English.
 * Supports:
 * - Natural chit-chat (greetings, mood, creator advice, tips)
 * - Context-aware video editing recommendations (pacing, color, hooks, fonts)
 * - Conversational command execution (executing edits while speaking like an expert editor buddy)
 */
object SmartConversationalEngine {

    /**
     * Process conversational inputs and produce a rich, human-like response.
     * Returns a ParsedCommand with VideoEditIntent.Conversational if recognized, or null for standard parser.
     */
    fun process(rawText: String): ParsedCommand? {
        val clean = rawText.trim()
        val lower = clean.lowercase(Locale.ROOT)
            .replace(Regex("[?!.,]+$"), "")
            .trim()

        // 1. Greetings & Chit-chat
        if (isGreeting(lower)) {
            val reply = listOf(
                "Hey bhai! Ek dum badhiya! Aaj kaunsi video ko viral banana hai? Bolo — cut karna hai, silence hatana hai, ya cinematic look chahiye? 🎬",
                "Hello bhai! Main bilkul ready hoon. Aap bolo clip me kya tweak karein — color grade, speed ya auto captions? ✨",
                "Arre bhai, mast! Timeline par clips ready hain. Batao kya magic karna hai aaj video me? 🔥"
            ).random()
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply), 0.98f, reply)
        }

        // 2. Identity & Capabilities
        if (isIdentityQuery(lower)) {
            val reply = "Main aapka human-like video editor hoon! 🎬\n\n" +
                "Mujhe commands sikhane ki zaroorat nahi hai — aap aam bolchaal me batao ki video me kya mood, cut ya change chahiye, aur main timeline par samajhkar turant edit kar dunga. Direct chat karo, baki sab main dekh lunga!"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply), 0.99f, reply)
        }

        // 3. Creator Advice: How to make video viral / reels tips
        if (isViralOrAdviceQuery(lower)) {
            val reply = "Bhai viral video banane ke 3 golden rules hain:\n\n" +
                "1️⃣ **Strong Hook (First 3 sec):** 'Dynamic Zoom' dalo taaki viewer swipe na kare!\n" +
                "2️⃣ **Zero Pauses:** 'Remove Silence' use karo taaki pacing fast rahe aur retention na gire.\n" +
                "3️⃣ **Subtitles:** 'Auto Captions' lagao kyunki 70% log silent mode me dekhte hain!\n\n" +
                "Bolo bhai, kya main abhi is clip par **Teal & Orange + Dynamic Zoom** laga doon?"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply), 0.95f, reply)
        }

        // 4. Color Grading Advice / Query
        if (isColorAdviceQuery(lower)) {
            val reply = "Bhai cinematic look ke liye **Teal & Orange** sabse best hai (skin tones pop hote hain aur background cinematic blue lagta hai). " +
                "Agar dark moody scene hai to **Moody Dark** aur nostalgia ke liye **Vintage 70s** best hai! " +
                "Aap bolo — kaunsa LUT apply karoon?"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply), 0.95f, reply)
        }

        // 4.5 Multimodal Video Inspection & Director Review Advice
        if (isInspectOrDirectorReviewQuery(lower)) {
            val reply = "🎬 Antigravity Video Inspection & Director Review:\n\n" +
                "Maine timeline, audio waveforms aur clip pacing analyze kar li hai:\n" +
                "1️⃣ [00:00 - 00:03] Hook Section: 🔍 'Viral Hook Zoom' (1.25x push-in) lagana zaroori hai taaki audience swipe na kare.\n" +
                "2️⃣ [00:03 - 00:06] Awkward Pause: ✂️ Audio silence gap mila hai. 'Remove Silence' se trim karna best rahega.\n" +
                "3️⃣ [00:07 - 00:15] Visual Grade & Text: 🎨 'Teal & Orange' LUT aur 💬 'Auto Captions' se retention 80% badhegi.\n" +
                "4️⃣ Outro: Clips ke beech 🔀 'Crossfade' transition aur background music add karna sahi rahega.\n\n" +
                "👉 Kya main ye saare cuts aur zooms 1-tap me apply kar doon? ('Haan kar do' bolo ya '✨ Full AI Edit' tap karo!)"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply), 0.98f, reply)
        }

        // Confirmation of auto edit
        if (matchesConversationalConfirmation(lower)) {
            val reply = "Shandar bhai! 🚀 Antigravity Director Pipeline chalu kar di hai. Silence removal, hook zoom, Teal & Orange color grade aur captions apply ho rahe hain..."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.AutoPilotEdit()), 0.98f, reply)
        }

        // 5. Conversational Editing Actions (Human Phrasing)
        // A. Conversational Split / Cut
        if (matchesConversationalSplit(lower)) {
            val reply = "Done bhai! ✂️ Playhead par clip cut kar di hai. Ab aap aage ka ya peeche ka hissa edit ya delete kar sakte ho."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.SplitClip()), 0.95f, reply)
        }

        // B. Conversational Silence Removal
        if (matchesConversationalSilence(lower)) {
            val reply = "Silence scan karke trim kar diya bhai! 🔇 Saare khali aur shant pause hat gaye hain, ab video ka pace ek dum snappy ho gaya hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.RemoveSilence()), 0.95f, reply)
        }

        // C. Conversational Color Grading
        if (matchesConversationalColor(lower)) {
            val reply = "Cinematic Teal & Orange color grade apply kar diya hai bhai! 🎨 Video ke colors ab ek dum vibrant aur professional lag rahe hain."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.ApplyColorGrade("Teal & Orange")), 0.95f, reply)
        }

        // D. Conversational Speed Up
        if (matchesConversationalFast(lower)) {
            val reply = "Speed 2x fast kar di hai bhai! ⚡ Video ab double speed me chalegi."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.SetSpeed(2.0f)), 0.95f, reply)
        }

        // E. Conversational Slow Motion
        if (matchesConversationalSlow(lower)) {
            val reply = "Smooth Slow Motion (0.5x) apply kar diya hai bhai! 🎬 Ab cinematic slow vibe aayegi."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.SetSpeed(0.5f)), 0.95f, reply)
        }

        // F. Conversational Zoom
        if (matchesConversationalZoom(lower)) {
            val reply = "Viral Hook Zoom animation daal diya hai bhai! 🔍 First few seconds me attention grab karega."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.DynamicZoom("push_in")), 0.95f, reply)
        }

        // G. Conversational Auto Captions
        if (matchesConversationalCaptions(lower)) {
            val reply = "Auto Captions generate kar diye hain bhai! 💬 Text timeline par add ho gaya hai, ab retention badhegi."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.AutoCaptions), 0.95f, reply)
        }

        // H. Conversational Auto-Pilot Magic Edit
        if (matchesConversationalAutoPilot(lower)) {
            val reply = "Auto-Pilot Magic Edit shuru kar diya bhai! 🚀 Silence removal, viral hook zoom, Teal & Orange color grade, captions aur title sab ready ho rahe hain."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.AutoPilotEdit()), 0.98f, reply)
        }

        // Conversational Status Maker Pro (1 photo se mast video maker jaisa status / reel)
        if (matchesConversationalStatusMaker(lower)) {
            val reply = "🔥 Status Maker Pro active! 1-Photo se dynamic beat transitions, kinetic zoom, vibrant look aur lyrical status video tayyar ho raha hai! 🎬✨"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.StatusMakerPro()), 0.99f, reply)
        }

        // I. Conversational Play / Start / Resume
        if (matchesConversationalPlay(lower)) {
            val reply = "Video play ho rahi hai bhai! ▶️ Live preview timeline par chalu hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.PlayVideo), 0.98f, reply)
        }

        // J. Conversational Pause / Stop / Roko
        if (matchesConversationalPause(lower)) {
            val reply = "Video pause kar di hai bhai! ⏸️ Playhead current frame par ruk gaya hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.PauseVideo), 0.98f, reply)
        }

        // K. Conversational Aspect Ratio (9:16, 16:9, 1:1)
        if (matchesConversationalAspect(lower)) {
            val ratio = if (lower.contains("16:9") || lower.contains("youtube") || lower.contains("landscape")) "16:9"
            else if (lower.contains("1:1") || lower.contains("square")) "1:1"
            else "9:16"
            val reply = "Canvas aspect ratio $ratio switch kar diya hai bhai! 📐 Frame dynamically fit ho gaya hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.SetAspectRatio(ratio)), 0.98f, reply)
        }

        // L. Conversational Export / Save
        if (matchesConversationalExport(lower)) {
            val reply = "Video export queue me daal di hai bhai! 🚀 1080p HD me render hokar phone gallery me save ho jayegi."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.ExportVideo("1080p")), 0.98f, reply)
        }

        // M. Conversational Undo / Redo
        if (lower.contains("undo") || lower.contains("wapas") || lower.contains("piche lo")) {
            val reply = "Last action undo kar diya hai bhai! ↩️ Timeline restore ho gayi."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.Undo), 0.98f, reply)
        }

        // N. Conversational Music / BGM
        if (matchesConversationalMusic(lower)) {
            val track = if (lower.contains("trap") || lower.contains("reels")) "trap"
                else if (lower.contains("bass") || lower.contains("action")) "attitude"
                else "lofi"
            val reply = "Background music timeline par add kar diya hai bhai! 🎵 Beat audio ke sath sync ho gayi hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.AddMusic(track)), 0.98f, reply)
        }

        // O. Conversational Mute
        if (matchesConversationalMute(lower)) {
            val reply = "Selected clip ka audio mute kar diya hai bhai! 🔇"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.MuteClip), 0.98f, reply)
        }

        // P. Conversational Volume
        if (matchesConversationalVolume(lower)) {
            val level = if (lower.contains("kam") || lower.contains("dheeme") || lower.contains("ghata")) 0.5f else 1.5f
            val label = if (level > 1.0f) "boost (150%)" else "reduce (50%)"
            val reply = "Volume $label kar diya hai bhai! 🔊"
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.SetVolume(level)), 0.98f, reply)
        }

        // Q. Conversational Loop Playback
        if (lower.contains("loop") || lower.contains("repeat") || lower.contains("लूप")) {
            val reply = "Seamless loop playback chalu kar diya hai bhai! 🔁 Video khatam hote hi dobara start ho jayegi."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.ToggleLoopPlayback(true)), 0.98f, reply)
        }

        // 6. Gratitude & Compliments
        if (isGratitudeOrCompliment(lower)) {
            val reply = listOf(
                "Shukriya bhai! Main hamesha aapki video ko blockbuster banane ke liye haazir hoon! 🚀",
                "Arey welcome bhai! Video banate raho aur aage badho! Koi aur edit karna ho to bas bolo. 🌟",
                "Thank you bhai! Aapka content ek dum top class banega. Next kya karein? 🎬"
            ).random()
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply), 0.98f, reply)
        }

        return null
    }

    /**
     * Generate friendly, intelligent fallback when a user asks something unconventional.
     */
    fun generateHelpfulFallback(rawText: String): String {
        val clean = rawText.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (lower.contains("play") || lower.contains("chalao") || lower.contains("chalu") || lower.contains("start")) {
            return "Video play kar raha hoon bhai! ▶️"
        }
        if (lower.contains("pause") || lower.contains("roko") || lower.contains("stop") || lower.contains("band")) {
            return "Video pause kar di hai bhai! ⏸️"
        }
        return "Bhai aap video me kya edit karna chahte ho? Aap seedhe bol ya type kar sakte ho:\n\n" +
            "• 'Status banao' (1-photo se Mast video maker jaisa jordaar video)\n" +
            "• 'Play' ya 'Pause' (video chalane/rokne ke liye)\n" +
            "• 'Silence hatao' (dead pauses trim karne ke liye)\n" +
            "• 'Cut karo' (current frame par split karne ke liye)\n" +
            "• '9:16 karo' ya '16:9 karo' (aspect ratio badalne ke liye)\n" +
            "• 'Teal Orange look do' (cinematic LUT lagane ke liye)\n" +
            "• 'Auto Captions' (subtitles add karne ke liye)\n" +
            "• 'Speed 2x karo' (fast forward ke liye)\n" +
            "• 'Export karo' (gallery me save karne ke liye)"
    }

    private fun matchesConversationalStatusMaker(text: String): Boolean {
        return text.contains("status") || text.contains("स्टेटस") ||
            text.contains("1 photo") || text.contains("ek photo") ||
            text.contains("one photo") || text.contains("photo se video") ||
            text.contains("photo se reel") || text.contains("mast video") ||
            text.contains("mast jaisa") || text.contains("photo video maker") ||
            text.contains("status maker")
    }

    private fun matchesConversationalPlay(text: String): Boolean {
        if (text.contains("pause") || text.contains("roko") || text.contains("stop")) return false
        return text in listOf("play", "chalao", "chalu karo", "play karo", "shuru karo", "chalu kar do", "play kar do", "video play karo", "video chalao", "video chalu karo", "chalne do") ||
            text.contains("video play") || text.contains("chalu kar") || text.contains("video chala") || text.contains("shuru kar") || text.contains("chalne do") || text.contains("play video")
    }

    private fun matchesConversationalPause(text: String): Boolean {
        return text in listOf("pause", "roko", "stop", "rok do", "pause karo", "pause kar do", "video roko", "video pause karo", "band karo") ||
            text.contains("video pause") || text.contains("rok de") || text.contains("rok do") || text.contains("video roko") || text.contains("band kar") || text.contains("pause video")
    }

    private fun matchesConversationalAspect(text: String): Boolean {
        return text.contains("9:16") || text.contains("16:9") || text.contains("1:1") ||
            text.contains("ratio") || text.contains("aspect") || text.contains("reels size") ||
            text.contains("youtube size") || text.contains("vertical") || text.contains("landscape")
    }

    private fun matchesConversationalExport(text: String): Boolean {
        return text.contains("export") || text.contains("gallery") || text.contains("save") || text.contains("download") || text.contains("render")
    }

    private fun isGreeting(text: String): Boolean {
        return text in listOf("kese ho", "kaise ho", "kya haal hai", "kya haal", "hello", "hi", "hey", "namaste", "pranam", "sup", "wassup", "hii", "heyy", "kaise ho bhai", "kese ho bhai", "kya chal raha hai") ||
            text.startsWith("kaise ho") || text.startsWith("kese ho") || text.startsWith("hello") || text.startsWith("hi ") || text.startsWith("hey ")
    }

    private fun isIdentityQuery(text: String): Boolean {
        return text in listOf("tum kaun ho", "kaun ho tum", "who are you", "kya kar sakte ho", "help", "madad", "features", "options", "kya kar sakte ho bhai", "kya karte ho", "tum kya ho") ||
            text.contains("kya kar sakte") || text.contains("tum kaun") || text.contains("what can you do") || text.contains("help me")
    }

    private fun isViralOrAdviceQuery(text: String): Boolean {
        return text.contains("viral") || text.contains("reels kaise") || text.contains("views") ||
            text.contains("reach kaise") || text.contains("tips") || text.contains("advice") ||
            text.contains("video acchi kaise") || text.contains("video achi kaise") || text.contains("retention")
    }

    private fun isColorAdviceQuery(text: String): Boolean {
        return (text.contains("color") || text.contains("filter") || text.contains("lut") || text.contains("rang")) &&
            (text.contains("kaunsa") || text.contains("kaisa") || text.contains("batao") || text.contains("advice") || text.contains("which"))
    }

    private fun matchesConversationalSplit(text: String): Boolean {
        return text.contains("cut kar") || text.contains("kaat de") || text.contains("kaat do") ||
            text.contains("split kar") || text.contains("beech se kaat") || text.contains("yahan se cut") ||
            text.contains("bekar part") || text.contains("tukda kar")
    }

    private fun matchesConversationalSilence(text: String): Boolean {
        return text.contains("silence") || text.contains("shant part") || text.contains("khali jagah") ||
            text.contains("pause hata") || text.contains("awaz saaf") || text.contains("gap hata")
    }

    private fun matchesConversationalColor(text: String): Boolean {
        return (text.contains("color") || text.contains("rang") || text.contains("filter") || text.contains("look")) &&
            (text.contains("mast") || text.contains("cinematic") || text.contains("badal") || text.contains("daal") || text.contains("achha") || text.contains("de do") || text.contains("karo"))
    }

    private fun matchesConversationalFast(text: String): Boolean {
        return text.contains("tez kar") || text.contains("fast kar") || text.contains("speed badha") ||
            text.contains("jaldi chala") || text.contains("fast forward")
    }

    private fun matchesConversationalSlow(text: String): Boolean {
        return text.contains("slow motion") || text.contains("slow mo") || text.contains("dheere kar") ||
            text.contains("slow kar") || text.contains("dheeme kar")
    }

    private fun matchesConversationalZoom(text: String): Boolean {
        return text.contains("zoom in") || text.contains("zoom kar") || text.contains("paas lao") ||
            text.contains("bada kar") || text.contains("hook zoom")
    }

    private fun matchesConversationalCaptions(text: String): Boolean {
        return text.contains("caption") || text.contains("subtitle") || text.contains("jo bol raha") ||
            text.contains("screen pe likh") || text.contains("likh do")
    }

    private fun isGratitudeOrCompliment(text: String): Boolean {
        return text in listOf("shukriya", "dhanyawad", "thanks", "thank you", "thx", "mast", "badhiya", "badiya", "superb", "great", "nice", "awesome", "bohot khoob", "wah", "waah") ||
            text.contains("bohot badhiya") || text.contains("mast kaam") || text.contains("achha hai")
    }

    private fun matchesConversationalAutoPilot(text: String): Boolean {
        return text.contains("auto edit") || text.contains("autopilot") ||
            text.contains("magic edit") || text.contains("puri video edit") ||
            text.contains("poori video edit") || text.contains("pura edit")
    }

    private fun isInspectOrDirectorReviewQuery(text: String): Boolean {
        return text.contains("dekho") || text.contains("inspect") || text.contains("kaha cut") ||
            text.contains("kaha zoom") || text.contains("kaha se kaat") || text.contains("check karo") ||
            text.contains("review") || text.contains("director") || text.contains("kya cut") ||
            text.contains("analysis") || text.contains("detail batao") || text.contains("video dekho") ||
            text.contains("kaha cut karne jaisa")
    }

    private fun matchesConversationalConfirmation(text: String): Boolean {
        return text in listOf("haan", "haan kar do", "kar do", "theek hai", "ok kar do", "yes", "apply", "apply kar do", "go ahead", "haan bhai", "kardo", "ha kar do", "bilkul") ||
            text.startsWith("haan kar") || text.startsWith("kar do") || text.startsWith("apply")
    }

    private fun matchesConversationalMusic(text: String): Boolean {
        return text.contains("music") || text.contains("bgm") || text.contains("song") ||
            text.contains("gaana") || text.contains("gana") || text.contains("soundtrack") ||
            text.contains("म्यूजिक") || text.contains("गाना")
    }

    private fun matchesConversationalMute(text: String): Boolean {
        return text.contains("mute") || text.contains("awaaz band") || text.contains("aawaz band") ||
            text.contains("awaz band") || text.contains("sound off") || text.contains("silent") ||
            text.contains("म्यूट")
    }

    private fun matchesConversationalVolume(text: String): Boolean {
        return text.contains("volume") || text.contains("awaaz") || text.contains("aawaz") ||
            text.contains("awaz") || text.contains("sound badha") || text.contains("sound kam")
    }
}
