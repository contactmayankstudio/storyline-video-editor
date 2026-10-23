package com.video.engine.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicCaptionsTest {

    @Test
    fun testWordCueInterpolation() {
        val overlay = TextOverlay(
            id = 1,
            text = "Make Money Fast Today",
            startTimeMs = 1000,
            endTimeMs = 3000,
            captionStyle = "HORMOZI",
            highlightColor = 0xFFFFE500.toInt(),
        )

        val cues = overlay.interpolateWordCuesIfNeeded()
        assertEquals(4, cues.size)
        assertEquals("Make", cues[0].word)
        assertEquals("Money", cues[1].word)
        assertEquals("Fast", cues[2].word)
        assertEquals("Today", cues[3].word)

        assertEquals(1000, cues[0].startMs)
        assertEquals(3000, cues[3].endMs)
        assertTrue(cues[0].endMs <= cues[1].startMs)
        assertTrue(cues[1].endMs <= cues[2].startMs)
        assertTrue(cues[2].endMs <= cues[3].startMs)
    }

    @Test
    fun testActiveWordIndexLookup() {
        val overlay = TextOverlay(
            id = 2,
            text = "Welcome to the future",
            startTimeMs = 0,
            endTimeMs = 4000,
            captionStyle = "CYBERPUNK",
        )
        overlay.interpolateWordCuesIfNeeded()

        // At beginning (0ms): first word
        assertEquals(0, overlay.activeWordIndexAt(0))
        // At 2000ms: middle word
        val midIdx = overlay.activeWordIndexAt(2000)
        assertTrue(midIdx in 1..2)
        // At or past 4000ms: last word
        assertEquals(3, overlay.activeWordIndexAt(4000))
        assertEquals(3, overlay.activeWordIndexAt(5000))
    }

    @Test
    fun testMrBeastPopKeywordAlternatingColors() {
        val overlay = TextOverlay(
            id = 3,
            text = "I Gave Away Millions",
            startTimeMs = 500,
            endTimeMs = 2500,
            captionStyle = "MRBEAST",
        )
        val cues = overlay.interpolateWordCuesIfNeeded()
        assertEquals(4, cues.size)

        // MrBeast style alternates neon green and punchy red pop keywords
        assertEquals(0xFF00FF66.toInt(), cues[0].highlightColor) // Green
        assertEquals(0xFFFF2A55.toInt(), cues[1].highlightColor) // Red
        assertEquals(0xFF00FF66.toInt(), cues[2].highlightColor) // Green
        assertEquals(0xFFFF2A55.toInt(), cues[3].highlightColor) // Red
    }

    @Test
    fun testKaraokeBeatStyleAttributes() {
        val overlay = TextOverlay(
            id = 4,
            text = "Sing Along Beat Drop",
            startTimeMs = 2000,
            endTimeMs = 6000,
            captionStyle = "KARAOKE",
            highlightColor = 0xFFFFD700.toInt(),
            activeWordScale = 1.20f,
        )
        val cues = overlay.interpolateWordCuesIfNeeded()
        assertEquals(4, cues.size)
        assertEquals("KARAOKE", overlay.captionStyle)
        assertEquals(1.20f, overlay.activeWordScale, 0.001f)
        assertEquals(0xFFFFD700.toInt(), overlay.highlightColor)
    }

    @Test
    fun testCinemaMinimalistStyleAttributes() {
        val overlay = TextOverlay(
            id = 5,
            text = "A cinematic journey begins",
            startTimeMs = 0,
            endTimeMs = 3500,
            captionStyle = "CINEMA",
            fontName = "poppins-bold",
            backgroundColor = 0x99000000.toInt(),
            backgroundPadding = 16f,
            backgroundCornerRadius = 14f,
            activeWordScale = 1.15f,
        )
        assertEquals("CINEMA", overlay.captionStyle)
        assertEquals("poppins-bold", overlay.fontName)
        assertEquals(16f, overlay.backgroundPadding, 0.001f)
        assertEquals(14f, overlay.backgroundCornerRadius, 0.001f)
        assertEquals(1.15f, overlay.activeWordScale, 0.001f)
    }
}
