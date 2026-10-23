package com.video.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCutoutTest {

    @Test
    fun defaultParams_areInitializedCorrectly() {
        val params = AiCutoutParams(clipId = 42)
        assertEquals(42, params.clipId)
        assertFalse(params.enabled)
        assertEquals(AiCutoutParams.COLOR_NEON_CYAN, params.neonGlowColor)
        assertTrue(params.neonGlowPulse)
        assertEquals(14.0f, params.glowWidth, 0.001f)
        assertEquals(AiCutoutParams.BG_NONE, params.backgroundType)
        assertTrue(params.hasGlow)
    }

    @Test
    fun colorConstants_matchSpecifications() {
        assertEquals(0xFF00F2FE.toInt(), AiCutoutParams.COLOR_NEON_CYAN)
        assertEquals(0xFFFF007F.toInt(), AiCutoutParams.COLOR_HOT_PINK)
        assertEquals(0xFFFFD700.toInt(), AiCutoutParams.COLOR_GOLD_FLAME)
        assertEquals(0, AiCutoutParams.COLOR_NONE)

        val noGlowParams = AiCutoutParams(neonGlowColor = AiCutoutParams.COLOR_NONE)
        assertFalse(noGlowParams.hasGlow)
    }

    @Test
    fun backgroundTypeConstants_matchSpecifications() {
        assertEquals("NONE", AiCutoutParams.BG_NONE)
        assertEquals("CYBERPUNK_GRID", AiCutoutParams.BG_CYBERPUNK_GRID)
        assertEquals("GOLDEN_HOUR", AiCutoutParams.BG_GOLDEN_HOUR)
        assertEquals("NEON_TUNNEL", AiCutoutParams.BG_NEON_TUNNEL)
        assertEquals("BLUR_BOKEH", AiCutoutParams.BG_BLUR_BOKEH)
    }

    @Test
    fun parse_aiCutoutEnglishCommands() {
        val queries = listOf(
            "ai cutout",
            "remove background",
            "background remove",
            "neon glow outline",
            "cutout person",
            "smart cutout",
            "silhouette glow"
        )
        for (q in queries) {
            val res = AiIntentParser.parse(q)
            assertTrue("Expected AiCutout for '$q', got ${res.intent}", res.intent is VideoEditIntent.AiCutout)
            assertTrue(res.confidence >= 0.95f)
        }
    }

    @Test
    fun parse_aiCutoutHindiAndHinglishCommands() {
        val queries = listOf(
            "background hatao",
            "bg hatao",
            "कटआउट",
            "बैकग्राउंड हटाओ",
            "नियॉन आउटलाइन",
            "बैकग्राउंड रिमूव"
        )
        for (q in queries) {
            val res = AiIntentParser.parse(q)
            assertTrue("Expected AiCutout for '$q', got ${res.intent}", res.intent is VideoEditIntent.AiCutout)
            assertTrue(res.confidence >= 0.95f)
        }
    }

    @Test
    fun parse_aiCutoutColorAndBackgroundResolution() {
        val res1 = AiIntentParser.parse("ai cutout hot pink neon glow golden background")
        assertTrue("Expected AiCutout, got ${res1.intent}", res1.intent is VideoEditIntent.AiCutout)
        val intent1 = res1.intent as VideoEditIntent.AiCutout
        assertEquals("pink", intent1.glowColor)
        assertEquals("golden", intent1.background)

        val res2 = AiIntentParser.parse("ai cutout gold flame glow neon tunnel")
        assertTrue(res2.intent is VideoEditIntent.AiCutout)
        val intent2 = res2.intent as VideoEditIntent.AiCutout
        assertEquals("gold", intent2.glowColor)
        assertEquals("tunnel", intent2.background)

        val res3 = AiIntentParser.parse("remove background blur bokeh")
        assertTrue(res3.intent is VideoEditIntent.AiCutout)
        val intent3 = res3.intent as VideoEditIntent.AiCutout
        assertEquals("cyan", intent3.glowColor)
        assertEquals("blur", intent3.background)
    }

    @Test
    fun dispatcher_handlesAiCutoutIntent() {
        var cutoutGlow = ""
        var cutoutBg = ""
        var cutoutCalled = false

        val dispatcher = object : AiCommandDispatcher {
            override fun aiCutout(glowColor: String, background: String): Boolean {
                cutoutCalled = true
                cutoutGlow = glowColor
                cutoutBg = background
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

        val parsed = AiIntentParser.parse("cutout with pink glow and cyber grid")
        val (success, feedback) = dispatcher.dispatch(parsed)

        assertTrue(success)
        assertTrue(cutoutCalled)
        assertEquals("pink", cutoutGlow)
        assertEquals("grid", cutoutBg)
        assertTrue(feedback.contains("AI Smart Cutout"))
    }
}
