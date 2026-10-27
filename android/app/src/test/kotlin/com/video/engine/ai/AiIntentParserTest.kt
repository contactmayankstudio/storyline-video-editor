package com.video.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiIntentParserTest {

    // --- English Commands ---

    @Test
    fun parse_englishSplitCommands() {
        val cases = listOf(
            "split",
            "cut clip",
            "split clip",
            "split here",
            "trim at playhead",
            "cut video",
            "slice clip",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected SplitClip for '$query', got ${res.intent}", res.intent is VideoEditIntent.SplitClip)
            assertTrue(res.confidence > 0.8f)
        }
    }

    @Test
    fun parse_englishFastBeatCuts() {
        val cases = listOf(
            "fast beat cuts",
            "beat cuts",
            "rhythmic cut",
            "auto cut",
            "punchy cuts",
            "fast cuts",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected FastBeatCuts for '$query', got ${res.intent}", res.intent is VideoEditIntent.FastBeatCuts)
            assertEquals("Punchy", (res.intent as VideoEditIntent.FastBeatCuts).preset)
        }
    }

    @Test
    fun parse_englishRemoveSilence() {
        val cases = listOf(
            "remove silence",
            "cut silence",
            "delete silence",
            "trim silence",
            "mute silence",
            "clean silence",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected RemoveSilence for '$query', got ${res.intent}", res.intent is VideoEditIntent.RemoveSilence)
        }
    }

    @Test
    fun parse_englishDynamicZoom() {
        val pushInCases = listOf(
            "viral hook zoom",
            "dynamic zoom",
            "zoom in",
            "push in",
            "punch in",
            "viral zoom",
        )
        for (query in pushInCases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected DynamicZoom for '$query', got ${res.intent}", res.intent is VideoEditIntent.DynamicZoom)
            assertEquals("push_in", (res.intent as VideoEditIntent.DynamicZoom).preset)
        }

        val pullOutRes = AiIntentParser.parse("pull out zoom")
        assertTrue(pullOutRes.intent is VideoEditIntent.DynamicZoom)
        assertEquals("pull_out", (pullOutRes.intent as VideoEditIntent.DynamicZoom).preset)

        val resetRes = AiIntentParser.parse("zoom reset")
        assertTrue(resetRes.intent is VideoEditIntent.DynamicZoom)
        assertEquals("reset", (resetRes.intent as VideoEditIntent.DynamicZoom).preset)
    }

    @Test
    fun parse_englishColorGradeLuts() {
        val tealOrangeRes = AiIntentParser.parse("apply teal and orange")
        assertTrue(tealOrangeRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Teal & Orange", (tealOrangeRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val moodyRes = AiIntentParser.parse("moody dark")
        assertTrue(moodyRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Moody Dark", (moodyRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val vintageRes = AiIntentParser.parse("apply vintage 70s lut")
        assertTrue(vintageRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Vintage 70s", (vintageRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val cyberpunkRes = AiIntentParser.parse("cyberpunk look")
        assertTrue(cyberpunkRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Cyberpunk", (cyberpunkRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val bwRes = AiIntentParser.parse("black and white")
        assertTrue(bwRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Retro B&W", (bwRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val genericRes = AiIntentParser.parse("color grade")
        assertTrue(genericRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Teal & Orange", (genericRes.intent as VideoEditIntent.ApplyColorGrade).lutName)
    }

    @Test
    fun parse_englishAutoCaptions() {
        val cases = listOf(
            "auto captions",
            "generate subtitles",
            "add captions",
            "create subtitles",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected AutoCaptions for '$query', got ${res.intent}", res.intent is VideoEditIntent.AutoCaptions)
        }
    }

    @Test
    fun parse_englishAddTitle() {
        val res = AiIntentParser.parse("add title My Summer Vacation")
        assertTrue(res.intent is VideoEditIntent.AddTitle)
        assertEquals("My Summer Vacation", (res.intent as VideoEditIntent.AddTitle).text)

        val resQuoted = AiIntentParser.parse("title is \"Epic Drone Shot\"")
        assertTrue(resQuoted.intent is VideoEditIntent.AddTitle)
        assertEquals("Epic Drone Shot", (resQuoted.intent as VideoEditIntent.AddTitle).text)

        val resColon = AiIntentParser.parse("text: Welcome to Delhi")
        assertTrue(resColon.intent is VideoEditIntent.AddTitle)
        assertEquals("Welcome to Delhi", (resColon.intent as VideoEditIntent.AddTitle).text)
    }

    @Test
    fun parse_speedParameter() {
        val res = AiIntentParser.parse("set speed to 2.5x")
        assertTrue(res.intent is VideoEditIntent.SetSpeed)
        assertEquals(2.5f, (res.intent as VideoEditIntent.SetSpeed).speed)
    }

    @Test
    fun parse_englishPlaybackAndHistory() {
        val playRes = AiIntentParser.parse("play")
        assertTrue(playRes.intent is VideoEditIntent.PlayVideo || playRes.intent is VideoEditIntent.TogglePlayback)

        val pauseRes = AiIntentParser.parse("pause")
        assertTrue(pauseRes.intent is VideoEditIntent.PauseVideo || pauseRes.intent is VideoEditIntent.TogglePlayback)

        val undoRes = AiIntentParser.parse("undo that")
        assertTrue(undoRes.intent is VideoEditIntent.Undo)

        val redoRes = AiIntentParser.parse("redo")
        assertTrue(redoRes.intent is VideoEditIntent.Redo)
    }

    // --- Hindi (Devanagari) Commands ---

    @Test
    fun parse_hindiSplitCommands() {
        val cases = listOf(
            "क्लिप काटो",
            "यहाँ से काटो",
            "स्प्लिट करो",
            "कट करो",
            "टुकड़े करो",
            "काट दो",
            "काटो",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected SplitClip for '$query', got ${res.intent}", res.intent is VideoEditIntent.SplitClip)
        }
    }

    @Test
    fun parse_hindiRemoveSilence() {
        val cases = listOf(
            "साइलेंस हटाओ",
            "सन्नाटा हटाओ",
            "खाली जगह काटो",
            "साइलेंस रिमूव करो",
            "सन्नाटा निकालो",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected RemoveSilence for '$query', got ${res.intent}", res.intent is VideoEditIntent.RemoveSilence)
        }
    }

    @Test
    fun parse_hindiFastBeatCuts() {
        val cases = listOf(
            "फास्ट कट",
            "बीट कट",
            "ऑटो कट",
            "तेज़ कट करो",
            "बीट पर काटो",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected FastBeatCuts for '$query', got ${res.intent}", res.intent is VideoEditIntent.FastBeatCuts)
        }
    }

    @Test
    fun parse_hindiDynamicZoom() {
        val cases = listOf(
            "ज़ूम करो",
            "पुश इन करो",
            "वायरल ज़ूम लगाओ",
            "बड़ा करो",
            "ज़ूम इन करो",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected DynamicZoom for '$query', got ${res.intent}", res.intent is VideoEditIntent.DynamicZoom)
        }
    }

    @Test
    fun parse_hindiColorAndCaptions() {
        val lutRes = AiIntentParser.parse("टील एंड ऑरेंज लगाओ")
        assertTrue(lutRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Teal & Orange", (lutRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val lutGeneric = AiIntentParser.parse("रंग बदलो")
        assertTrue(lutGeneric.intent is VideoEditIntent.ApplyColorGrade)

        val captionRes = AiIntentParser.parse("ऑटो कैप्शन बनाओ")
        assertTrue(captionRes.intent is VideoEditIntent.AutoCaptions)

        val titleRes = AiIntentParser.parse("टाइटल लिखो मेरा सफर")
        assertTrue(titleRes.intent is VideoEditIntent.AddTitle)
        assertEquals("मेरा सफर", (titleRes.intent as VideoEditIntent.AddTitle).text)
    }

    @Test
    fun parse_hindiPlaybackAndUndo() {
        val playRes = AiIntentParser.parse("चलाओ")
        assertTrue(playRes.intent is VideoEditIntent.PlayVideo || playRes.intent is VideoEditIntent.TogglePlayback)

        val pauseRes = AiIntentParser.parse("रोको")
        assertTrue(pauseRes.intent is VideoEditIntent.PauseVideo || pauseRes.intent is VideoEditIntent.TogglePlayback)

        val undoRes = AiIntentParser.parse("वापस करो")
        assertTrue(undoRes.intent is VideoEditIntent.Undo)

        val redoRes = AiIntentParser.parse("फिर से करो")
        assertTrue(redoRes.intent is VideoEditIntent.Redo)
    }

    // --- Hinglish (Romanized) Commands ---

    @Test
    fun parse_hinglishCommands() {
        val splitRes = AiIntentParser.parse("clip kato")
        assertTrue(splitRes.intent is VideoEditIntent.SplitClip)

        val splitRes2 = AiIntentParser.parse("yahan se cut karo")
        assertTrue(splitRes2.intent is VideoEditIntent.SplitClip)

        val silenceRes = AiIntentParser.parse("silence hatao")
        assertTrue(silenceRes.intent is VideoEditIntent.RemoveSilence)

        val silenceRes2 = AiIntentParser.parse("quiet part remove karo")
        assertTrue(silenceRes2.intent is VideoEditIntent.RemoveSilence)

        val beatRes = AiIntentParser.parse("beat pe kato")
        assertTrue(beatRes.intent is VideoEditIntent.FastBeatCuts)

        val zoomRes = AiIntentParser.parse("viral zoom lagao")
        assertTrue(zoomRes.intent is VideoEditIntent.DynamicZoom)

        val lutRes = AiIntentParser.parse("teal and orange lagao")
        assertTrue(lutRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Teal & Orange", (lutRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val captionRes = AiIntentParser.parse("auto caption banao")
        assertTrue(captionRes.intent is VideoEditIntent.AutoCaptions)

        val titleRes = AiIntentParser.parse("title dalo Goa Beach Trip")
        assertTrue(titleRes.intent is VideoEditIntent.AddTitle)
        assertEquals("Goa Beach Trip", (titleRes.intent as VideoEditIntent.AddTitle).text)

        val undoRes = AiIntentParser.parse("undo karo")
        assertTrue(undoRes.intent is VideoEditIntent.Undo)
    }

    // --- Edge Cases ---

    @Test
    fun parse_autoPilotEdit() {
        val cases = listOf(
            "auto edit puri video",
            "auto edit",
            "autopilot",
            "magic edit",
            "full auto",
            "viral reel edit",
            "puri video edit karo",
            "ऑटो एडिट",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected AutoPilotEdit for '$query', got ${res.intent}", res.intent is VideoEditIntent.AutoPilotEdit)
            assertEquals("ViralReel", (res.intent as VideoEditIntent.AutoPilotEdit).style)
        }

        // Test dispatcher handling
        var autoPilotCalled = false
        val mockDispatcher = object : AiCommandDispatcher {
            override fun fullAutoPilotEdit(style: String): Boolean {
                autoPilotCalled = true
                return true
            }
            override fun splitClip(): Boolean = true
            override fun fastBeatCuts(preset: String): Boolean = true
            override fun removeSilence(thresholdDb: Float): Boolean = true
            override fun dynamicZoom(preset: String): Boolean = true
            override fun applyColorGrade(lutName: String): Boolean = true
            override fun autoCaptions(): Boolean = true
            override fun addTitle(text: String, preset: String): Boolean = true
            override fun setSpeed(speed: Float): Boolean = true
            override fun togglePlayback(): Boolean = true
            override fun undo(): Boolean = true
            override fun redo(): Boolean = true
        }

        val cmd = AiIntentParser.parse("auto edit puri video")
        val (success, feedback) = mockDispatcher.dispatch(cmd)
        assertTrue(success)
        assertTrue(autoPilotCalled)
        assertTrue(feedback.contains("Auto-Pilot"))
    }

    @Test
    fun parse_ttsVoiceover() {
        val res1 = AiIntentParser.parse("ai voice: Namaste dosto kaise hain aap")
        assertTrue("Expected GenerateTtsVoiceover, got ${res1.intent}", res1.intent is VideoEditIntent.GenerateTtsVoiceover)
        assertEquals("Namaste dosto kaise hain aap", (res1.intent as VideoEditIntent.GenerateTtsVoiceover).text)

        val res2 = AiIntentParser.parse("voiceover: Welcome back to my channel")
        assertTrue(res2.intent is VideoEditIntent.GenerateTtsVoiceover)
        assertEquals("Welcome back to my channel", (res2.intent as VideoEditIntent.GenerateTtsVoiceover).text)
    }

    @Test
    fun parse_autoBeatSync() {
        val cases = listOf(
            "beat drop",
            "beat sync",
            "sync to beat",
            "rhythm sync",
            "music beat",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected AutoBeatSync for '$query', got ${res.intent}", res.intent is VideoEditIntent.AutoBeatSync)
        }
    }

    @Test
    fun parse_beforeAfter() {
        val cases = listOf(
            "before after",
            "split screen compare",
            "split view",
            "pehle aur baad",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected ToggleBeforeAfter for '$query', got ${res.intent}", res.intent is VideoEditIntent.ToggleBeforeAfter)
        }
    }

    @Test
    fun parse_topicToReel() {
        val res1 = AiIntentParser.parse("topic to reel: Top 5 AI tools in 2026")
        assertTrue("Expected TopicToReel, got ${res1.intent}", res1.intent is VideoEditIntent.TopicToReel)
        assertEquals("Top 5 AI tools in 2026", (res1.intent as VideoEditIntent.TopicToReel).topic)

        val res2 = AiIntentParser.parse("make reel on fitness motivation")
        assertTrue(res2.intent is VideoEditIntent.TopicToReel)
        assertEquals("fitness motivation", (res2.intent as VideoEditIntent.TopicToReel).topic)
    }

    @Test
    fun parse_soundEffect() {
        val whooshRes = AiIntentParser.parse("whoosh sound effect dalo")
        assertTrue(whooshRes.intent is VideoEditIntent.AddSoundEffect)
        assertEquals("whoosh", (whooshRes.intent as VideoEditIntent.AddSoundEffect).sfxType)

        val popRes = AiIntentParser.parse("pop sound")
        assertTrue(popRes.intent is VideoEditIntent.AddSoundEffect)
        assertEquals("pop", (popRes.intent as VideoEditIntent.AddSoundEffect).sfxType)

        val dingRes = AiIntentParser.parse("ding bell sound")
        assertTrue(dingRes.intent is VideoEditIntent.AddSoundEffect)
        assertEquals("ding", (dingRes.intent as VideoEditIntent.AddSoundEffect).sfxType)
    }

    @Test
    fun parse_faceTrackingZoom() {
        val cases = listOf(
            "face tracking",
            "face zoom",
            "chehre pe zoom",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected FaceTrackingZoom for '$query', got ${res.intent}", res.intent is VideoEditIntent.FaceTrackingZoom)
        }
    }

    @Test
    fun parse_studioVoiceClarity() {
        val cases = listOf(
            "background noise hatao",
            "studio voice karo",
            "noise cancellation lagao",
            "audio saaf karo",
            "clean audio",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected StudioVoiceClarity for '$query', got ${res.intent}", res.intent is VideoEditIntent.StudioVoiceClarity)
        }
    }

    @Test
    fun parse_dynamicCaptions() {
        val cases = listOf(
            "hormozi style dynamic captions lagao",
            "bouncy captions",
            "mrbeast captions",
            "viral subtitles",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected DynamicCaptions for '$query', got ${res.intent}", res.intent is VideoEditIntent.DynamicCaptions)
        }
    }

    @Test
    fun parse_generateThumbnail() {
        val res1 = AiIntentParser.parse("viral thumbnail banao")
        assertTrue("Expected GenerateThumbnail, got ${res1.intent}", res1.intent is VideoEditIntent.GenerateThumbnail)

        val res2 = AiIntentParser.parse("thumbnail: Top 5 Secret Hacks")
        assertTrue(res2.intent is VideoEditIntent.GenerateThumbnail)
        assertEquals("Top 5 Secret Hacks", (res2.intent as VideoEditIntent.GenerateThumbnail).headline)
    }

    @Test
    fun parse_addStickerOverlay() {
        val fireRes = AiIntentParser.parse("fire sticker lagao")
        assertTrue(fireRes.intent is VideoEditIntent.AddStickerOverlay)
        assertEquals("🔥", (fireRes.intent as VideoEditIntent.AddStickerOverlay).emojiOrSymbol)

        val starRes = AiIntentParser.parse("sitara sticker dalo")
        assertTrue(starRes.intent is VideoEditIntent.AddStickerOverlay)
        assertEquals("⭐", (starRes.intent as VideoEditIntent.AddStickerOverlay).emojiOrSymbol)
    }

    @Test
    fun parse_platformExport() {
        val instaRes = AiIntentParser.parse("export for instagram reel")
        assertTrue(instaRes.intent is VideoEditIntent.PlatformExport)
        assertEquals("Instagram Reels", (instaRes.intent as VideoEditIntent.PlatformExport).platform)

        val ytRes = AiIntentParser.parse("youtube shorts export")
        assertTrue(ytRes.intent is VideoEditIntent.PlatformExport)
        assertEquals("YouTube Shorts", (ytRes.intent as VideoEditIntent.PlatformExport).platform)

        val waRes = AiIntentParser.parse("whatsapp status export")
        assertTrue(waRes.intent is VideoEditIntent.PlatformExport)
        assertEquals("WhatsApp Status", (waRes.intent as VideoEditIntent.PlatformExport).platform)
    }

    @Test
    fun parse_resetPreview() {
        val cases = listOf(
            "preview blur hai theek karo",
            "preview reset karo",
            "fix preview",
            "preview saaf karo",
            "preview freeze ho gaya",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected ResetPreview for '$query', got ${res.intent}", res.intent is VideoEditIntent.ResetPreview)
        }
    }

    @Test
    fun parse_speedRamp() {
        val res1 = AiIntentParser.parse("speed ramp lagao")
        assertTrue("Expected SpeedRamp, got ${res1.intent}", res1.intent is VideoEditIntent.SpeedRamp)

        val res2 = AiIntentParser.parse("bullet time slow mo ramp")
        assertTrue(res2.intent is VideoEditIntent.SpeedRamp)
        assertEquals("bullet_time", (res2.intent as VideoEditIntent.SpeedRamp).curvePreset)
    }

    @Test
    fun parse_smartJumpCuts() {
        val cases = listOf(
            "smart jump cuts lagao",
            "fast pacing booster",
            "talking head pacer",
            "tight jump cuts",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected SmartJumpCuts for '$query', got ${res.intent}", res.intent is VideoEditIntent.SmartJumpCuts)
        }
    }

    @Test
    fun parse_autoReframe() {
        val cases = listOf(
            "auto reframe 9:16",
            "smart reframe",
            "center subject",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected AutoReframe for '$query', got ${res.intent}", res.intent is VideoEditIntent.AutoReframe)
            assertEquals("9:16", (res.intent as VideoEditIntent.AutoReframe).targetRatio)
        }
    }

    @Test
    fun parse_hollywoodFilmGrade() {
        val cyberRes = AiIntentParser.parse("cyberpunk look lagao")
        assertTrue(cyberRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Cyberpunk", (cyberRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val vintageRes = AiIntentParser.parse("vintage 35mm film")
        assertTrue(vintageRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Vintage 70s", (vintageRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val noirRes = AiIntentParser.parse("nolan noir look")
        assertTrue(noirRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Retro B&W", (noirRes.intent as VideoEditIntent.ApplyColorGrade).lutName)

        val goldenRes = AiIntentParser.parse("golden hour grade")
        assertTrue(goldenRes.intent is VideoEditIntent.ApplyColorGrade)
        assertEquals("Golden Hour", (goldenRes.intent as VideoEditIntent.ApplyColorGrade).lutName)
    }

    @Test
    fun parse_compoundPipeline() {
        val res = AiIntentParser.parse("silence kato fir cyberpunk look lagao aur 9:16 karo")
        assertTrue("Expected Compound intent, got ${res.intent}", res.intent is VideoEditIntent.Compound)
        val compound = res.intent as VideoEditIntent.Compound
        assertEquals(3, compound.steps.size)
        assertTrue(compound.steps[0] is VideoEditIntent.RemoveSilence)
        assertTrue(compound.steps[1] is VideoEditIntent.ApplyColorGrade)
        assertEquals("Cyberpunk", (compound.steps[1] as VideoEditIntent.ApplyColorGrade).lutName)
        assertTrue(compound.steps[2] is VideoEditIntent.SetAspectRatio)
        assertEquals("9:16", (compound.steps[2] as VideoEditIntent.SetAspectRatio).ratio)
    }

    @Test
    fun parse_extractViralHighlight() {
        val res1 = AiIntentParser.parse("video se best 30 second viral part nikalo")
        assertTrue("Expected ExtractViralHighlight, got ${res1.intent}", res1.intent is VideoEditIntent.ExtractViralHighlight)
        assertEquals(30, (res1.intent as VideoEditIntent.ExtractViralHighlight).targetDurationSec)

        val res2 = AiIntentParser.parse("extract viral highlight 60 seconds")
        assertTrue(res2.intent is VideoEditIntent.ExtractViralHighlight)
        assertEquals(60, (res2.intent as VideoEditIntent.ExtractViralHighlight).targetDurationSec)
    }

    @Test
    fun parse_autoBrollOverlay() {
        val cases = listOf(
            "auto b-roll aur visuals overlay dalo",
            "smart overlays lagao",
            "add broll overlay",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected AutoBrollOverlay for '$query', got ${res.intent}", res.intent is VideoEditIntent.AutoBrollOverlay)
        }
    }

    @Test
    fun parse_inspectTimelineCritic() {
        val cases = listOf(
            "meri video check karo kya kami hai",
            "review my video",
            "inspect timeline",
            "director review my video",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected InspectTimelineCritic for '$query', got ${res.intent}", res.intent is VideoEditIntent.InspectTimelineCritic)
        }
    }

    @Test
    fun parse_playbackPreviewSpeed() {
        val res1 = AiIntentParser.parse("2x speed par play karo")
        assertTrue("Expected SetPlaybackPreviewSpeed, got ${res1.intent}", res1.intent is VideoEditIntent.SetPlaybackPreviewSpeed)
        assertEquals(2.0f, (res1.intent as VideoEditIntent.SetPlaybackPreviewSpeed).speed)

        val res2 = AiIntentParser.parse("0.5x slow motion preview")
        assertTrue(res2.intent is VideoEditIntent.SetPlaybackPreviewSpeed)
        assertEquals(0.5f, (res2.intent as VideoEditIntent.SetPlaybackPreviewSpeed).speed)
    }

    @Test
    fun parse_toggleLoopPlayback() {
        val cases = listOf(
            "loop playback chalu karo",
            "loop me chalao",
            "repeat playback",
            "a/b loop",
        )
        for (query in cases) {
            val res = AiIntentParser.parse(query)
            assertTrue("Expected ToggleLoopPlayback for '$query', got ${res.intent}", res.intent is VideoEditIntent.ToggleLoopPlayback)
        }
    }

    @Test
    fun parse_nudgeFrame() {
        val fwdRes = AiIntentParser.parse("1 frame aage badhao")
        assertTrue(fwdRes.intent is VideoEditIntent.NudgeFrame)
        assertTrue((fwdRes.intent as VideoEditIntent.NudgeFrame).forward)

        val backRes = AiIntentParser.parse("1 frame piche karo")
        assertTrue(backRes.intent is VideoEditIntent.NudgeFrame)
        assertFalse((backRes.intent as VideoEditIntent.NudgeFrame).forward)
    }

    @Test
    fun parse_jumpToCut() {
        val nextRes = AiIntentParser.parse("agle cut par jao")
        assertTrue(nextRes.intent is VideoEditIntent.JumpToCut)
        assertTrue((nextRes.intent as VideoEditIntent.JumpToCut).forward)

        val prevRes = AiIntentParser.parse("pichle cut par jao")
        assertTrue(prevRes.intent is VideoEditIntent.JumpToCut)
        assertFalse((prevRes.intent as VideoEditIntent.JumpToCut).forward)
    }

    @Test
    fun parse_replayLastSeconds() {
        val res = AiIntentParser.parse("last 5 second play karo")
        assertTrue("Expected ReplayLastSeconds, got ${res.intent}", res.intent is VideoEditIntent.ReplayLastSeconds)
        assertEquals(5, (res.intent as VideoEditIntent.ReplayLastSeconds).seconds)
    }

    @Test
    fun parse_shayariQueries() {
        val queries = listOf(
            "mujhe sayri likh ke do",
            "ek romantic shayari likho",
            "attitude shayari sunao",
            "dard bhari shayari",
            "dosti wali shayari",
            "sayri sunao",
        )
        for (q in queries) {
            val res = AiIntentParser.parse(q)
            assertTrue("Expected Conversational for '$q', got ${res.intent}", res.intent is VideoEditIntent.Conversational)
            val conv = res.intent as VideoEditIntent.Conversational
            assertTrue("Expected AddShayariOverlay executeIntent for '$q'", conv.executeIntent is VideoEditIntent.AddShayariOverlay)
            assertTrue(conv.reply.contains("Shayari"))
        }
    }

    @Test
    fun parse_worldTrendsQueries() {
        val queries = listOf(
            "duniya me kya chal raha",
            "duniya me kya chal raha hai",
            "aaj kal kya chal raha hai",
            "trending kya hai",
            "viral trends batao",
            "market me kya chal raha hai",
        )
        for (q in queries) {
            val res = AiIntentParser.parse(q)
            assertTrue("Expected Conversational for '$q', got ${res.intent}", res.intent is VideoEditIntent.Conversational)
            val conv = res.intent as VideoEditIntent.Conversational
            assertTrue("Expected world trends report in reply for '$q'", conv.reply.contains("Duniya Me Abhi Ye"))
        }
    }

    @Test
    fun parse_appMasterQueries() {
        val queries = listOf(
            "pure app ka master",
            "tum pure app ka master ho",
            "tum kya kar sakte ho",
        )
        for (q in queries) {
            val res = AiIntentParser.parse(q)
            assertTrue("Expected Conversational for '$q', got ${res.intent}", res.intent is VideoEditIntent.Conversational)
            val conv = res.intent as VideoEditIntent.Conversational
            assertTrue("Expected master report in reply for '$q'", conv.reply.contains("Master AI Ask Hoon") || conv.reply.contains("Master AI Copilot Hoon"))
        }
    }

    @Test
    fun parse_appNavigationQueries() {
        val camRes = AiIntentParser.parse("camera kholo")
        assertTrue(camRes.intent is VideoEditIntent.Conversational)
        assertEquals(VideoEditIntent.OpenFunCamera, (camRes.intent as VideoEditIntent.Conversational).executeIntent)

        val statusRes = AiIntentParser.parse("status maker kholo")
        assertTrue(statusRes.intent is VideoEditIntent.Conversational)
        assertEquals(VideoEditIntent.OpenStatusMaker, (statusRes.intent as VideoEditIntent.Conversational).executeIntent)

        val photoRes = AiIntentParser.parse("photo editor kholo")
        assertTrue(photoRes.intent is VideoEditIntent.Conversational)
        assertEquals(VideoEditIntent.OpenPhotoEditor, (photoRes.intent as VideoEditIntent.Conversational).executeIntent)

        val beatRes = AiIntentParser.parse("auto beat cut karo")
        assertTrue(beatRes.intent is VideoEditIntent.Conversational)
        assertEquals(VideoEditIntent.AutoBeatCut, (beatRes.intent as VideoEditIntent.Conversational).executeIntent)

        val chromaRes = AiIntentParser.parse("green screen background hatao")
        assertTrue(chromaRes.intent is VideoEditIntent.Conversational)
        assertEquals(VideoEditIntent.ChromaKey, (chromaRes.intent as VideoEditIntent.Conversational).executeIntent)
    }

    @Test
    fun parse_blankAndUnknown() {
        val emptyRes = AiIntentParser.parse("")
        assertTrue(emptyRes.intent is VideoEditIntent.Unknown)

        val gibberishRes = AiIntentParser.parse("xyzabc foo bar baz")
        assertTrue(gibberishRes.intent is VideoEditIntent.Unknown)
    }
}
