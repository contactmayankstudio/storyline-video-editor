package com.video.engine.pro.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class TimelineGesturePlaybackTest {

    @Test
    fun playbackMonotonicity_suppressesMinorBackwardJitterDuringActivePlayback() {
        var playheadMs = 1000L
        val playbackActive = true

        fun shouldRenderPlaybackPosition(nextMs: Long): Boolean {
            val clampedMs = nextMs.coerceAtLeast(0L)
            if (playbackActive && clampedMs < playheadMs && (playheadMs - clampedMs) < 80L) {
                return false // suppressed micro-jitter
            }
            playheadMs = clampedMs
            return true
        }

        // Forward progress should always render
        assertTrue(shouldRenderPlaybackPosition(1016L))
        assertEquals(1016L, playheadMs)

        assertTrue(shouldRenderPlaybackPosition(1033L))
        assertEquals(1033L, playheadMs)

        // Micro-jitter: decoder frame report 1025ms while predicted at 1033ms (< 80ms backward)
        assertFalse(shouldRenderPlaybackPosition(1025L))
        assertEquals(1033L, playheadMs) // preserved, not jittered backward

        // Another micro-jitter: 1030ms (< 80ms backward)
        assertFalse(shouldRenderPlaybackPosition(1030L))
        assertEquals(1033L, playheadMs)

        // Forward continuation
        assertTrue(shouldRenderPlaybackPosition(1050L))
        assertEquals(1050L, playheadMs)

        // Explicit user seek backward (>= 80ms backward jump, e.g. back to 200ms)
        assertTrue(shouldRenderPlaybackPosition(200L))
        assertEquals(200L, playheadMs)
    }

    @Test
    fun trimHandleReach_isClampedOnSmallClipsToPreventCoveringBody() {
        fun computeMaxHandleReach(clipWidth: Float, handleWidthWithMargin: Float): Float {
            return if (clipWidth > 0f) {
                handleWidthWithMargin.coerceAtMost(clipWidth * 0.28f)
            } else 0f
        }

        val handleWidthWithMargin = 18f // 10dp handle + 8dp margin

        // Normal large clip: 200px width
        val largeClipReach = computeMaxHandleReach(200f, handleWidthWithMargin)
        assertEquals(18f, largeClipReach, 0.001f)
        val largeClipBody = 200f - (2 * largeClipReach)
        assertTrue("Clip body should remain at least 164px", largeClipBody >= 164f)

        // Small clip: 30px width (previously 18px + 18px = 36px > 30px completely covered the clip!)
        val smallClipReach = computeMaxHandleReach(30f, handleWidthWithMargin)
        assertEquals(30f * 0.28f, smallClipReach, 0.001f) // clamped to 8.4px
        val smallClipBody = 30f - (2 * smallClipReach)
        assertTrue("Clip body must be preserved in center (> 40% of width)", smallClipBody >= 30f * 0.44f)

        // Very small clip: 15px width
        val tinyClipReach = computeMaxHandleReach(15f, handleWidthWithMargin)
        assertEquals(15f * 0.28f, tinyClipReach, 0.001f)
        val tinyClipBody = 15f - (2 * tinyClipReach)
        assertTrue("Tiny clip must still retain central body", tinyClipBody >= 15f * 0.44f)
    }

    @Test
    fun importChipTap_validatesDeliberateTapOnly() {
        val touchSlop = 24f // typical 8dp on 3x screen
        val tapMaxDist = (touchSlop / 2f).coerceAtMost(12f) // max 4dp on 3x screen = 12px

        fun isValidDeliberateTap(distance: Float, durationMs: Long): Boolean {
            return distance <= tapMaxDist && durationMs in 30L..600L
        }

        // Clean deliberate tap
        assertTrue(isValidDeliberateTap(distance = 2f, durationMs = 120L))
        assertTrue(isValidDeliberateTap(distance = 6f, durationMs = 250L))

        // Swipe starting near chip: distance exceeds tapMaxDist
        assertFalse(isValidDeliberateTap(distance = 15f, durationMs = 100L))
        assertFalse(isValidDeliberateTap(distance = 25f, durationMs = 300L))

        // Accidental micro-tap or touch bounce (< 30ms)
        assertFalse(isValidDeliberateTap(distance = 1f, durationMs = 10L))

        // Long hold on chip (> 600ms)
        assertFalse(isValidDeliberateTap(distance = 1f, durationMs = 800L))
    }

    @Test
    fun clipDragGating_requires400MsHoldBeforeDrag() {
        val longPressTimeoutMs = 400L

        fun canMoveClip(heldDurationMs: Long, movedDistance: Float, touchSlop: Float): Boolean {
            val movedBeforeHold = movedDistance >= touchSlop && heldDurationMs < longPressTimeoutMs
            val moveArmed = heldDurationMs >= longPressTimeoutMs && !movedBeforeHold
            return moveArmed
        }

        val touchSlop = 24f

        // Swipe across clip immediately (moved before 400ms) -> cannot move clip, falls back to scroll
        assertFalse(canMoveClip(heldDurationMs = 100L, movedDistance = 30f, touchSlop = touchSlop))

        // Held for 400ms without moving -> armed, can move clip
        assertTrue(canMoveClip(heldDurationMs = 450L, movedDistance = 5f, touchSlop = touchSlop))
    }
}
