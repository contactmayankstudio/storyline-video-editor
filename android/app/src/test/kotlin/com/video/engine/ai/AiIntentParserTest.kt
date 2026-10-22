package com.video.engine.ai

import org.junit.Assert.assertEquals
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
        assertTrue(playRes.intent is VideoEditIntent.TogglePlayback)

        val pauseRes = AiIntentParser.parse("pause")
        assertTrue(pauseRes.intent is VideoEditIntent.TogglePlayback)

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
        assertTrue(playRes.intent is VideoEditIntent.TogglePlayback)

        val pauseRes = AiIntentParser.parse("रोको")
        assertTrue(pauseRes.intent is VideoEditIntent.TogglePlayback)

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
    fun parse_blankAndUnknown() {
        val emptyRes = AiIntentParser.parse("")
        assertTrue(emptyRes.intent is VideoEditIntent.Unknown)

        val gibberishRes = AiIntentParser.parse("xyzabc foo bar baz")
        assertTrue(gibberishRes.intent is VideoEditIntent.Unknown)
    }
}
