package com.video.engine.status

import com.video.engine.ai.AiCommandDispatcher
import com.video.engine.ai.AiIntentParser
import com.video.engine.ai.VideoEditIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTemplateCommunityTest {

    @Test
    fun builtInTemplates_containViralStorylinePresets() {
        val all = StatusTemplateRepository.allTemplates
        assertTrue(all.isNotEmpty())

        val storylineZoom = StatusTemplateRepository.getById("tpl_storyline_3d_zoom_pro")
        assertNotNull(storylineZoom)
        assertEquals("Storyline 3D Zoom Pro 🔥", storylineZoom?.title)
        assertEquals(130, storylineZoom?.bpm)
        assertFalse(storylineZoom?.isUserCreated ?: true)

        val velocity = StatusTemplateRepository.getById("tpl_storyline_velocity_flow")
        assertNotNull(velocity)
        assertEquals("Storyline Velocity Speed-Ramp ⚡", velocity?.title)

        val phonk = StatusTemplateRepository.getById("tpl_storyline_sigma_phonk")
        assertNotNull(phonk)
        assertEquals("Storyline Sigma Phonk Bass Drop 💥", phonk?.title)
    }

    @Test
    fun userTemplate_createsAndIdentifiesCorrectly() {
        val userTemplate = StatusTemplate(
            id = "tpl_user_test_123",
            title = "My Viral Summer Reel 🌴",
            category = "My Templates",
            emoji = "🌟",
            durationMs = 15000L,
            requiredPhotoCount = 3,
            bpm = 125,
            description = "Community template exported from editor.",
            gradientStartHex = "#FF0844",
            gradientEndHex = "#FFB199",
            audioAssetPath = "music_bgm/attitude_bass_drop.m4a",
            lutFilter = "",
            slots = listOf(
                StatusSlot(1, 5000L, "crossfade"),
                StatusSlot(2, 5000L, "flash"),
                StatusSlot(3, 5000L, "zoom_in")
            ),
            textOverlays = listOf(
                StatusTextOverlay("SUMMER VIBES 🌴", 0L, 5000L)
            ),
            templateFilePath = "/sdcard/templates/tpl_user_test_123.vne",
            isUserCreated = true
        )

        assertEquals("tpl_user_test_123", userTemplate.id)
        assertTrue(userTemplate.isUserCreated)
        assertEquals("/sdcard/templates/tpl_user_test_123.vne", userTemplate.templateFilePath)
        assertEquals(3, userTemplate.slots.size)
    }

    @Test
    fun parse_saveAsTemplateCommands() {
        val queries = listOf(
            "save as template",
            "export as template",
            "save template",
            "export template",
            "template banao",
            "reusable template",
            "टेम्पलेट बनाओ"
        )
        for (q in queries) {
            val res = AiIntentParser.parse(q)
            assertTrue("Expected SaveAsTemplate for '$q', got ${res.intent}", res.intent is VideoEditIntent.SaveAsTemplate)
            assertTrue(res.confidence >= 0.95f)
        }
    }

    @Test
    fun parse_saveAsTemplateWithTitle() {
        val res = AiIntentParser.parse("save as template: Sunset Glow Beat Drop")
        assertTrue(res.intent is VideoEditIntent.SaveAsTemplate)
        val intent = res.intent as VideoEditIntent.SaveAsTemplate
        assertEquals("Sunset Glow Beat Drop", intent.title)
    }

    @Test
    fun dispatcher_handlesSaveAsTemplate() {
        var templateSaved = false
        var savedTitle = ""

        val dispatcher = object : AiCommandDispatcher {
            override fun saveAsTemplate(title: String, category: String): Boolean {
                templateSaved = true
                savedTitle = title
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

        val cmd = AiIntentParser.parse("save as template: Phonk Energy")
        val (success, feedback) = dispatcher.dispatch(cmd)

        assertTrue(success)
        assertTrue(templateSaved)
        assertEquals("Phonk Energy", savedTitle)
        assertTrue(feedback.contains("Reusable Status/Reels Template"))
    }
}
