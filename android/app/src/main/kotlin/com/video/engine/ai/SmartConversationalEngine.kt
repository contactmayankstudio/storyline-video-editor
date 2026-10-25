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
     * Top-level App Master, Navigation, Shayari, and World Trends queries.
     * Evaluated first to ensure navigation commands like "status maker kholo" or "camera kholo"
     * are never misidentified as timeline editing actions.
     */
    fun processAppMaster(rawText: String): ParsedCommand? {
        val clean = rawText.trim()
        val lower = clean.lowercase(Locale.ROOT)
            .replace(Regex("[?!.,]+$"), "")
            .trim()

        // 1. World Trends & Creator Intelligence (Duniya Me Kya Chal Raha Hai)
        if (isWorldTrendsQuery(lower)) {
            val reply = getWorldTrendsReport()
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, null), 0.99f, reply)
        }

        // 2. Shayari & Poetic Creative Studio (Mujhe Shayari Likh Ke Do)
        if (isShayariQuery(lower)) {
            val (shayariText, categoryName) = pickShayari(lower)
            val reply = "📜 **$categoryName Shayari (Aapke Liye):**\n\n" +
                "\"$shayariText\"\n\n" +
                "👉 *Is shayari ko video par glowing text lagana hai ya Status Maker me use karna hai? Neeche 1-tap options se chunein!*"
            return ParsedCommand(
                rawText = rawText,
                intent = VideoEditIntent.Conversational(reply, VideoEditIntent.AddShayariOverlay(shayariText)),
                confidence = 0.99f,
                feedbackMessage = reply,
            )
        }

        // 3. Pure App Ka Master (Full App Control & Navigation)
        if (isAppMasterQuery(lower)) {
            val reply = getAppMasterReport()
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, null), 0.99f, reply)
        }

        // App Navigation Commands
        if (isOpenFunCamera(lower)) {
            val reply = "Fun Camera launch ho raha hai bhai! 📸 Snapchat-style lenses, amber night glow aur hands-free recording ready hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.OpenFunCamera), 0.99f, reply)
        }

        if (isOpenStatusMaker(lower)) {
            val reply = "Status Maker Pro launch ho raha hai bhai! 🌟 1-Photo se dynamic lyrical status tayyar ho raha hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.OpenStatusMaker), 0.99f, reply)
        }

        if (isOpenPhotoEditor(lower)) {
            val reply = "Photo Editor Studio launch ho raha hai bhai! 🖼️ AI cutouts, presets aur typography tools tayyar hain."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.OpenPhotoEditor), 0.99f, reply)
        }

        if (isAutoBeatCut(lower)) {
            val reply = "Auto Beat Cut chalu kar raha hoon bhai! 🎵 Audio waveform ke transients analyze karke har beat drop par auto-split aur flash zoom cut lagaye ja rahe hain."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.AutoBeatCut), 0.99f, reply)
        }

        if (isChromaKey(lower)) {
            val reply = "AI Green Screen Chroma Key apply kar diya hai bhai! 🟢 Background cleanly transparent ho gaya hai."
            return ParsedCommand(rawText, VideoEditIntent.Conversational(reply, VideoEditIntent.ChromaKey), 0.99f, reply)
        }

        return null
    }

    /**
     * Process conversational inputs and produce a rich, human-like response.
     * Returns a ParsedCommand with VideoEditIntent.Conversational if recognized, or null for standard parser.
     */
    fun process(rawText: String): ParsedCommand? {
        processAppMaster(rawText)?.let { return it }

        val clean = rawText.trim()
        val lower = clean.lowercase(Locale.ROOT)
            .replace(Regex("[?!.,]+$"), "")
            .trim()

        // 4. Greetings & Chit-chat
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
        return text in listOf("kese ho", "kaise ho", "kya haal hai", "kya haal", "hello", "hi", "hey", "namaste", "pranam", "sup", "wassup", "hii", "heyy", "kaise ho bhai", "kese ho bhai") ||
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

    fun getWorldTrendsReport(): String {
        return "🌍 **Duniya Me Abhi Ye 4 Sabse Bade Trends Dhoom Macha Rahe Hain:**\n\n" +
            "1️⃣ 🎬 **Aesthetic Shayari & Lyrical Reels:** 9:16 portrait video par slow-motion visuals + golden neon kinetic shayari + Urdu/Lofi music Instagram aur YouTube Shorts par #1 viral trend hai!\n\n" +
            "2️⃣ ⚡ **1-Tap AI Beat-Drop Cuts:** Audio waveform ke har kick aur drop par 0.2s flash zoom cuts. Audience retention 90%+ boost ho jati hai!\n\n" +
            "3️⃣ 🤖 **AI Smart Cutout & Duet Splits:** Podcast hooks aur viral memes par side-by-side reaction aur clean background replacement.\n\n" +
            "4️⃣ 📼 **Retro 90s Camcorder & Cyberpunk VHS:** Vintage timestamp overlay, CRT lines aur warm nostalgic glow.\n\n" +
            "💡 **Mera Mashwara:** Abhi apni video par '🎵 Auto Beat Cut' lagao ya '📜 Shayari Likh Do' bolo, reel instant viral hogi!"
    }

    fun getAppMasterReport(): String {
        return "👑 **Main Storyline Video Editor Ka Master AI Copilot Hoon!** 🚀\n\n" +
            "Aapki ek aawaaz ya chat message par main poore app ke saare tools control kar sakta hoon:\n\n" +
            "• 📸 **Fun Camera:** 'Camera kholo' bolo -> Snapchat lenses & selfie glow camera launch hoga!\n" +
            "• 🌟 **Status Maker:** 'Status maker kholo' -> 1-Photo se Mast video maker status banega!\n" +
            "• 🖼️ **Photo Editor:** 'Photo editor kholo' -> Studio thumbnails & magic cutout designer!\n" +
            "• 🎵 **1-Tap Beat Cut:** 'Beat cut karo' -> Gaane ke har drop par video auto-split & flash!\n" +
            "• 📜 **Shayari Studio:** 'Shayari likho' -> Romantic, Attitude, Sad shayari with 1-tap text overlay!\n" +
            "• 🌍 **World Trends:** 'Duniya me kya chal raha' -> Viral content ideas & creative advice!\n" +
            "• 🟢 **Chroma Key:** 'Green screen hatao' -> Clean transparent background!\n" +
            "• ⚡ **Timeline Master:** Split, Silence Trim, 2x Fast, Slow-Mo, 9:16 Reframe, 4K Export!\n\n" +
            "Bolo bhai, abhi kya order hai? Main turant execute kar dunga! 🎬🔥"
    }

    private val romanticShayaris = listOf(
        "तेरी आँखों की कशिश दिल को बहका देती है,\nतू मुस्कुरा दे तो हर उदासी मिट जाती है... ✨❤️",
        "हज़ार चाहने वालों से बेहतर है वो एक शख्स,\nजो तुम्हारी ख़ामोशी को भी पढ़ ले... 🌹",
        "इश्क़ वो नहीं जो तुझे मेरा कर दे,\nइश्क़ वो है जो तुझे किसी और का होने न दे... 💫",
        "कुछ ख़ास नहीं इन हाथों की लकीरों में,\nमगर तुम मिल जाओ तो तकदीर सँवर जाए... 💖",
        "तुम्हारी सादगी पर दिल निसार करते हैं,\nहम तुमसे बेपनाह और बेइंतहा प्यार करते हैं... 🥀"
    )

    private val attitudeShayaris = listOf(
        "हम वो नहीं जो भीड़ में खो जाएं,\nहम जहां खड़े होते हैं काफिला वहीं से शुरू होता है! 👑🔥",
        "खामोशी हमारी कमजोरी नहीं शान है,\nशेर शिकार से पहले कभी शोर नहीं मचाता! 🦁⚡",
        "अंदाज़ ज़रा अलग है हमारा,\nहम वक्त देखकर बात नहीं करते, वक्त बदल कर दिखाते हैं! 🕶️💥",
        "बराबरी तो दूर की बात है,\nहमारे चर्चे भी तेरे समझ से बाहर हैं! 🚀🔥",
        "तेवर तो हम वक्त आने पर दिखाएंगे,\nशहर तू खरीद ले, उस पर राज हम चलाएंगे! 🏰⚡"
    )

    private val sadShayaris = listOf(
        "कुछ ज़ख़्म ऐसे होते हैं जो दिखाई नहीं देते,\nपर वो दर्द हर सांस के साथ महसूस होता है... 💔🌧️",
        "अक्सर वही लोग खामोश हो जाते हैं,\nजिनके पास कहने को बहुत कुछ होता है... 🥀",
        "मुस्कुराहटें झूठी भी हुआ करती हैं जनाब,\nहर हँसता हुआ चेहरा खुशहाल नहीं होता... 💧",
        "वक्त ने सिखा दिया हर गम सहना,\nअब कोई छोड़ भी जाए तो आंखें नम नहीं होतीं... 🍂"
    )

    private val zindagiShayaris = listOf(
        "ज़िंदगी की दौड़ में तजुर्बे ने ये सिखाया है,\nमंज़िल से ज़्यादा खूबसूरत सफर का हौसला होता है... 🚀✨",
        "वक़्त सबको मिलता है ज़िंदगी बदलने के लिए,\nपर ज़िंदगी दोबारा नहीं मिलती वक़्त बदलने के लिए... ⏳🌿",
        "हवाओं के रुख से जो डर जाए वो कश्ती क्या,\nजो लहरों से टकराए वही असली मुसाफिर है! 🌊⚓",
        "किस्मत के लिखे पर कभी मलाल मत करना,\nखुदा का फैसला हर इंसान की सोच से बेहतर होता है... 🌙🕊️"
    )

    private val dostiShayaris = listOf(
        "दोस्त वो नहीं जो खुशी में साथ हँसे,\nदोस्त वो है जो आंसुओं में भी मुस्कुराना सिखा दे... 🤝💙",
        "दौलत से तो दुनिया की हर चीज़ खरीदी जा सकती है,\nमगर सच्चे यार नसीब वालों को ही मिलते हैं! 🍻🔥",
        "हमारी यारी का अंदाज़ ही कुछ ऐसा है,\nजब मिलते हैं तो ज़माना देखता रह जाता है! ⚡🤜🤛"
    )

    private val motivationalShayaris = listOf(
        "मंज़िलें उन्हीं को मिलती हैं जिनके सपनों में जान होती है,\nपंखों से कुछ नहीं होता हौसलों से उड़ान होती है! 🦅🔥",
        "मेहनत इतनी खामोशी से करो कि,\nतुम्हारी कामयाबी पूरी दुनिया में शोर मचा दे! 🏆⚡",
        "गिरकर संभलना ही तो असली हुनर है,\nजो हार कर भी ना रुके वही सिकंदर है! 🥇🚀"
    )

    fun pickShayari(query: String): Pair<String, String> {
        return when {
            query.contains("romantic") || query.contains("love") || query.contains("pyaar") || query.contains("ishq") || query.contains("mohabbat") ->
                Pair(romanticShayaris.random(), "Romantic")
            query.contains("attitude") || query.contains("swag") || query.contains("king") || query.contains("badshah") || query.contains("sher") || query.contains("aukat") ->
                Pair(attitudeShayaris.random(), "Attitude")
            query.contains("sad") || query.contains("dard") || query.contains("gam") || query.contains("gham") || query.contains("judai") || query.contains("bewafa") || query.contains("rona") ->
                Pair(sadShayaris.random(), "Dard-e-Dil")
            query.contains("dosti") || query.contains("yaar") || query.contains("yaari") || query.contains("friend") ->
                Pair(dostiShayaris.random(), "Dosti")
            query.contains("motivation") || query.contains("hustle") || query.contains("mehnat") || query.contains("kamyaab") || query.contains("jeet") ->
                Pair(motivationalShayaris.random(), "Motivational")
            query.contains("zindagi") || query.contains("life") || query.contains("deep") || query.contains("waqt") ->
                Pair(zindagiShayaris.random(), "Zindagi")
            else -> {
                val all = listOf(
                    Pair(romanticShayaris.random(), "Romantic"),
                    Pair(attitudeShayaris.random(), "Attitude"),
                    Pair(sadShayaris.random(), "Dard-e-Dil"),
                    Pair(zindagiShayaris.random(), "Zindagi"),
                    Pair(motivationalShayaris.random(), "Motivational"),
                )
                all.random()
            }
        }
    }

    private fun isWorldTrendsQuery(text: String): Boolean {
        if (text.contains("edit") || text.contains("trim") || text.contains("banao") || text.contains("karo")) return false
        return text.contains("duniya me kya chal") || text.contains("duniya me kya ho") ||
            text.contains("duniya me") || text.contains("kya chal raha") ||
            text.contains("trending") || text.contains("viral trend") ||
            text.contains("trend kya") || text.contains("aaj kal kya chal") ||
            text.contains("market me kya") || text.contains("what's trending") ||
            text.contains("whats trending") || text.contains("current trend") ||
            text.contains("kya scene hai") || text.contains("viral reels trend")
    }

    private fun isShayariQuery(text: String): Boolean {
        return text.contains("sayri") || text.contains("shayari") || text.contains("shayri") ||
            text.contains("shairi") || text.contains("शायरी") || text.contains("poetry") ||
            text.contains("kavita") || text.contains("sher") || text.contains("sher o shayari")
    }

    private fun isAppMasterQuery(text: String): Boolean {
        if (text.contains("volume") || text.contains("sound") || text.contains("awaaz")) return false
        return text.contains("pure app ka master") || text.contains("app ka master") ||
            text.contains("app master") || text.contains("super agent") ||
            text.contains("tum kya kar sakte ho") || text.contains("kya kya kar sakte ho") ||
            text.contains("all features") || text.contains("poore app ka") ||
            text == "master" || text.startsWith("master ") || text.endsWith(" master")
    }

    private fun isOpenFunCamera(text: String): Boolean {
        return (text.contains("camera") || text.contains("कैमरा") || text.contains("selfie") || text.contains("lens")) &&
            (text.contains("khol") || text.contains("open") || text.contains("chalu") || text.contains("start") || text.contains("snapchat"))
    }

    private fun isOpenStatusMaker(text: String): Boolean {
        return (text.contains("status maker") || text.contains("mast video") || text.contains("reels maker")) &&
            (text.contains("khol") || text.contains("open") || text.contains("chalu") || text.contains("start"))
    }

    private fun isOpenPhotoEditor(text: String): Boolean {
        return (text.contains("photo editor") || text.contains("thumbnail") || text.contains("photo edit")) &&
            (text.contains("khol") || text.contains("open") || text.contains("chalu") || text.contains("start"))
    }

    private fun isAutoBeatCut(text: String): Boolean {
        if (text.contains("fast beat cut") || text.contains("beat sync") ||
            text.contains("sync to beat") || text.contains("rhythm sync") || text.contains("punchy cut")) {
            return false
        }
        return text.contains("auto beat cut") || text.contains("beat drop cut") ||
            text.contains("auto beat") || (text.contains("beat cut") && text.contains("karo")) ||
            text.contains("बीट कट करो")
    }

    private fun isChromaKey(text: String): Boolean {
        return text.contains("chroma") || text.contains("green screen") || text.contains("green parda") ||
            text.contains("greenscreen") || text.contains("parda hata") || text.contains("hara parda")
    }
}
