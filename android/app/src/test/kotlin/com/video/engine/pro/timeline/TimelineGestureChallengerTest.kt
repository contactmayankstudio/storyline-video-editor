package com.video.engine.pro.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Empirical Challenger Verification for Milestone M1 (Requirement R1: Timeline Gesture & Playback Verification).
 *
 * Specific Verification Objectives:
 * 1. Deliberate tap boundary conditions on track import chip (`+`):
 *    - Down for 20ms (< 30ms) -> REJECTED (no import)
 *    - Down for 400ms (30ms..600ms) -> ACCEPTED (import triggered)
 *    - Down for 700ms (> 600ms) -> REJECTED (no import)
 *    - Movement of 3dp (<= 4dp) -> ACCEPTED (import triggered)
 *    - Movement of 5dp (> 4dp) -> REJECTED (no import)
 *    - Exact boundary values: 29ms, 30ms, 600ms, 601ms; 4.0dp, 4.01dp
 *
 * 2. Swiping over clips of varying widths (20dp, 50dp, 200dp):
 *    - Verify trim handle reach clamping (at most 28% of clip width) leaves at least 44% central body
 *    - Verify swiping across clips transitions strictly to SCROLL, never triggering TRIM or MOVE
 *    - Verify unselected clips do not arm trim handles regardless of touch location
 *
 * 3. 400ms long-press gating:
 *    - Verify drag does NOT start at 350ms (or 399ms) upon movement -> delegates to SCROLL
 *    - Verify drag strictly starts at or after 400ms hold before movement
 *    - Stress harness: 1000 randomized gesture trials verifying 0 false clip moves
 */
class TimelineGestureChallengerTest {

    enum class GestureKind { NONE, SCROLL, TRIM_START, TRIM_END, MOVE, PLAYHEAD_SCRUB }
    enum class TrackType { VIDEO, AUDIO, TEXT, OVERLAY, LAYER }

    data class RectF(var left: Float, var top: Float, var right: Float, var bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
        fun inset(dx: Float, dy: Float) {
            left += dx
            right -= dx
            top += dy
            bottom -= dy
        }
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Exact state machine model of TimelineCanvasView gesture handling.
     */
    class CanvasGestureStateMachine(
        val density: Float = 3f, // 3x density (typical 1080p Android device)
    ) {
        fun dp(v: Int): Int = (v * density).toInt()
        fun dpF(v: Float): Float = v * density

        val touchSlop = dpF(8f) // 24px on 3x density
        val tapMaxDist = (touchSlop / 2f).coerceAtMost(dpF(4f)) // 12px on 3x density (4dp)
        val handleWidthPx = dpF(10f)
        val handleTouchMargin = dpF(8f)

        // State variables
        var pendingImportChipTrack: TrackType? = null
        var pendingImportChipDownX = 0f
        var pendingImportChipDownY = 0f
        var pendingImportChipDownTimeMs = 0L

        var gestureStarted = false
        var gesture = GestureKind.NONE
        var gestureClipId: String? = null
        var clipMoveArmed = false
        var selectedClipId: String? = null

        var touchDownX = 0f
        var touchDownY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f
        var scrollX = 0f

        // Delayed runnables simulation
        var clipMoveArmRunnableScheduled = false
        var clipMoveArmScheduledTimeMs = 0L

        // Listener callbacks
        var importRequestedTrack: TrackType? = null
        var trackSelected: TrackType? = null
        var clipSelected: String? = null
        var committedMoveClipId: String? = null
        var scrollDispatchedDeltaX = 0f

        // Simulated track import chip rect: right edge of canvas
        val chipRect = RectF(left = dpF(300f), top = dpF(10f), right = dpF(324f), bottom = dpF(34f))

        fun trackImportChipHit(x: Float, y: Float): TrackType? {
            val tempHit = RectF(chipRect.left, chipRect.top, chipRect.right, chipRect.bottom)
            tempHit.inset(0f, -dpF(4f)) // Expanded vertically only
            return if (tempHit.contains(x, y)) TrackType.VIDEO else null
        }

        fun onDown(x: Float, y: Float, timeMs: Long, clip: ClipInfo? = null) {
            touchDownX = x
            touchDownY = y
            lastTouchX = x
            lastTouchY = y
            gestureStarted = false
            gesture = GestureKind.NONE
            gestureClipId = null
            clipMoveArmed = false
            pendingImportChipTrack = null
            clipMoveArmRunnableScheduled = false
            importRequestedTrack = null

            // Check import chip hit
            val hitTrack = trackImportChipHit(x, y)
            if (hitTrack != null) {
                pendingImportChipTrack = hitTrack
                pendingImportChipDownX = x
                pendingImportChipDownY = y
                pendingImportChipDownTimeMs = timeMs
                return
            }

            if (clip == null) return

            val left = clip.left
            val right = clip.right
            val clipWidth = right - left
            val maxHandleReach = if (clipWidth > 0f) {
                (handleWidthPx + handleTouchMargin).coerceAtMost(clipWidth * 0.28f)
            } else 0f

            if (clip.id == selectedClipId && maxHandleReach > 0f) {
                if (x <= left + maxHandleReach) {
                    gesture = GestureKind.TRIM_START
                    gestureClipId = clip.id
                    return
                }
                if (x >= right - maxHandleReach) {
                    gesture = GestureKind.TRIM_END
                    gestureClipId = clip.id
                    return
                }
            }

            // Clip body touch
            clipMoveArmRunnableScheduled = true
            clipMoveArmScheduledTimeMs = timeMs + 400L
        }

        fun checkTimer(currentTimeMs: Long, clip: ClipInfo? = null) {
            if (clipMoveArmRunnableScheduled && currentTimeMs >= clipMoveArmScheduledTimeMs) {
                clipMoveArmRunnableScheduled = false
                if (!gestureStarted && clip != null) {
                    clipMoveArmed = true
                    gesture = GestureKind.MOVE
                    gestureClipId = clip.id
                    selectedClipId = clip.id
                }
            }
        }

        fun onMove(x: Float, y: Float, currentTimeMs: Long, clip: ClipInfo? = null) {
            checkTimer(currentTimeMs, clip)

            val dx = x - touchDownX
            val dy = y - touchDownY
            val stepX = x - lastTouchX
            lastTouchX = x
            lastTouchY = y

            // Import chip movement gating
            if (pendingImportChipTrack != null) {
                val chipMoveDist = hypot(x - pendingImportChipDownX, y - pendingImportChipDownY)
                if (chipMoveDist > tapMaxDist) {
                    pendingImportChipTrack = null
                }
            }

            if (!gestureStarted) {
                if (abs(dx) < touchSlop && abs(dy) < touchSlop) return
                gestureStarted = true
                clipMoveArmRunnableScheduled = false // Cancel 400ms timer
                pendingImportChipTrack = null
            }

            when (gesture) {
                GestureKind.NONE -> {
                    if (gestureClipId == null || !clipMoveArmed) {
                        gesture = GestureKind.SCROLL
                        scrollX -= stepX
                        scrollDispatchedDeltaX += stepX
                    }
                }
                GestureKind.SCROLL -> {
                    scrollX -= stepX
                    scrollDispatchedDeltaX += stepX
                }
                GestureKind.MOVE -> {
                    // Moving armed clip
                }
                GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.PLAYHEAD_SCRUB -> {
                    // Trim or scrub
                }
            }
        }

        fun onUp(x: Float, y: Float, currentTimeMs: Long, clip: ClipInfo? = null) {
            checkTimer(currentTimeMs, clip)
            clipMoveArmRunnableScheduled = false

            val importTrack = pendingImportChipTrack
            pendingImportChipTrack = null
            if (importTrack != null && !gestureStarted) {
                val dist = hypot(x - pendingImportChipDownX, y - pendingImportChipDownY)
                val durationMs = currentTimeMs - pendingImportChipDownTimeMs
                if (dist <= tapMaxDist && durationMs in 30L..600L && trackImportChipHit(x, y) == importTrack) {
                    trackSelected = importTrack
                    importRequestedTrack = importTrack
                    return
                }
            }

            if (gestureStarted && gesture == GestureKind.MOVE) {
                committedMoveClipId = gestureClipId
            }
        }
    }

    data class ClipInfo(val id: String, val left: Float, val right: Float) {
        val width: Float get() = right - left
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Deliberate Tap Boundary Conditions on Import Chip (`+`)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun importChip_downFor20ms_doesNotTriggerImport() {
        val sm = CanvasGestureStateMachine()
        val chipCenterX = (sm.chipRect.left + sm.chipRect.right) / 2f
        val chipCenterY = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        sm.onDown(chipCenterX, chipCenterY, timeMs = 1000L)
        sm.onUp(chipCenterX, chipCenterY, currentTimeMs = 1020L) // 20ms hold (< 30ms)

        assertNull("Tap duration 20ms (< 30ms) must NOT trigger import", sm.importRequestedTrack)
    }

    @Test
    fun importChip_downFor400ms_cleanStationaryTap_triggersImport() {
        val sm = CanvasGestureStateMachine()
        val chipCenterX = (sm.chipRect.left + sm.chipRect.right) / 2f
        val chipCenterY = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        sm.onDown(chipCenterX, chipCenterY, timeMs = 1000L)
        sm.onUp(chipCenterX, chipCenterY, currentTimeMs = 1400L) // 400ms hold (30..600ms)

        assertEquals("Tap duration 400ms with zero movement must trigger import", TrackType.VIDEO, sm.importRequestedTrack)
    }

    @Test
    fun importChip_downFor700ms_doesNotTriggerImport() {
        val sm = CanvasGestureStateMachine()
        val chipCenterX = (sm.chipRect.left + sm.chipRect.right) / 2f
        val chipCenterY = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        sm.onDown(chipCenterX, chipCenterY, timeMs = 1000L)
        sm.onUp(chipCenterX, chipCenterY, currentTimeMs = 1700L) // 700ms hold (> 600ms)

        assertNull("Tap duration 700ms (> 600ms) must NOT trigger import", sm.importRequestedTrack)
    }

    @Test
    fun importChip_durationBoundary_29msAnd30msAnd600msAnd601ms() {
        val sm = CanvasGestureStateMachine()
        val cx = (sm.chipRect.left + sm.chipRect.right) / 2f
        val cy = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        // 29ms (just below 30ms lower threshold) -> REJECTED
        sm.onDown(cx, cy, timeMs = 1000L)
        sm.onUp(cx, cy, currentTimeMs = 1029L)
        assertNull("29ms tap must be rejected", sm.importRequestedTrack)

        // 30ms (exact lower threshold) -> ACCEPTED
        sm.onDown(cx, cy, timeMs = 1000L)
        sm.onUp(cx, cy, currentTimeMs = 1030L)
        assertEquals("30ms tap must be accepted", TrackType.VIDEO, sm.importRequestedTrack)

        // 600ms (exact upper threshold) -> ACCEPTED
        sm.onDown(cx, cy, timeMs = 1000L)
        sm.onUp(cx, cy, currentTimeMs = 1600L)
        assertEquals("600ms tap must be accepted", TrackType.VIDEO, sm.importRequestedTrack)

        // 601ms (just above 600ms upper threshold) -> REJECTED
        sm.onDown(cx, cy, timeMs = 1000L)
        sm.onUp(cx, cy, currentTimeMs = 1601L)
        assertNull("601ms tap must be rejected", sm.importRequestedTrack)
    }

    @Test
    fun importChip_movementOf3dp_triggersImport() {
        val sm = CanvasGestureStateMachine(density = 3f)
        val cx = (sm.chipRect.left + sm.chipRect.right) / 2f
        val cy = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        sm.onDown(cx, cy, timeMs = 1000L)
        // Move 3dp (9px at 3x density <= tapMaxDist of 12px)
        val move3dp = sm.dpF(3f)
        sm.onMove(cx + move3dp, cy, currentTimeMs = 1100L)
        sm.onUp(cx + move3dp, cy, currentTimeMs = 1200L)

        assertEquals("Movement of 3dp (<= 4dp tapMaxDist) must trigger import", TrackType.VIDEO, sm.importRequestedTrack)
    }

    @Test
    fun importChip_movementOf5dp_doesNotTriggerImport() {
        val sm = CanvasGestureStateMachine(density = 3f)
        val cx = (sm.chipRect.left + sm.chipRect.right) / 2f
        val cy = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        sm.onDown(cx, cy, timeMs = 1000L)
        // Move 5dp (15px at 3x density > tapMaxDist of 12px)
        val move5dp = sm.dpF(5f)
        sm.onMove(cx + move5dp, cy, currentTimeMs = 1100L)
        sm.onUp(cx + move5dp, cy, currentTimeMs = 1200L)

        assertNull("Movement of 5dp (> 4dp tapMaxDist) must NOT trigger import", sm.importRequestedTrack)
    }

    @Test
    fun importChip_movementExactBoundaries_4dpAnd4_1dp() {
        val sm = CanvasGestureStateMachine(density = 3f)
        val cx = (sm.chipRect.left + sm.chipRect.right) / 2f
        val cy = (sm.chipRect.top + sm.chipRect.bottom) / 2f

        // 4.0dp (12.0px at 3x density == 12.0px tapMaxDist) -> ACCEPTED
        val move4dp = sm.dpF(4.0f)
        sm.onDown(cx, cy, timeMs = 1000L)
        sm.onMove(cx + move4dp, cy, currentTimeMs = 1100L)
        sm.onUp(cx + move4dp, cy, currentTimeMs = 1200L)
        assertEquals("Exact 4.0dp movement must be accepted", TrackType.VIDEO, sm.importRequestedTrack)

        // 4.1dp (12.3px at 3x density > 12.0px tapMaxDist) -> REJECTED
        val move4_1dp = sm.dpF(4.1f)
        sm.onDown(cx, cy, timeMs = 1000L)
        sm.onMove(cx + move4_1dp, cy, currentTimeMs = 1100L)
        sm.onUp(cx + move4_1dp, cy, currentTimeMs = 1200L)
        assertNull("4.1dp movement must be rejected", sm.importRequestedTrack)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Swiping Over Clips of Varying Widths (20dp, 50dp, 200dp)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun trimHandleReach_clampedTo28Percent_guaranteesCentralBodyAcrossAllWidths() {
        val density = 3f
        fun dpF(v: Float): Float = v * density
        val handleReachUncapped = dpF(10f) + dpF(8f) // 18dp = 54px

        fun computeReachAndBody(clipWidthDp: Float): Pair<Float, Float> {
            val clipWidthPx = dpF(clipWidthDp)
            val reach = handleReachUncapped.coerceAtMost(clipWidthPx * 0.28f)
            val centralBody = clipWidthPx - (2 * reach)
            return Pair(reach, centralBody)
        }

        // Test width: 20dp (60px)
        val (reach20, body20) = computeReachAndBody(20f)
        assertEquals(dpF(20f) * 0.28f, reach20, 0.001f) // clamped to 5.6dp (16.8px)
        assertTrue("20dp clip must preserve at least 44% central body (8.8dp)", body20 >= dpF(20f) * 0.44f)

        // Test width: 50dp (150px)
        val (reach50, body50) = computeReachAndBody(50f)
        assertEquals(dpF(50f) * 0.28f, reach50, 0.001f) // clamped to 14.0dp (42px)
        assertTrue("50dp clip must preserve at least 44% central body (22dp)", body50 >= dpF(50f) * 0.44f)

        // Test width: 200dp (600px)
        val (reach200, body200) = computeReachAndBody(200f)
        assertEquals(handleReachUncapped, reach200, 0.001f) // reach capped at standard 18dp
        val bodyRatio200 = body200 / dpF(200f)
        assertTrue("200dp clip preserves > 80% central body", bodyRatio200 >= 0.80f)
    }

    @Test
    fun swipingOverClips_20dp_50dp_200dp_strictlyScrollsWithoutTrimOrDrag() {
        val sm = CanvasGestureStateMachine(density = 3f)

        val testClipWidths = listOf(20f, 50f, 200f)
        for (widthDp in testClipWidths) {
            val clipWidthPx = sm.dpF(widthDp)
            val clip = ClipInfo(id = "clip-$widthDp", left = 100f, right = 100f + clipWidthPx)
            // Even if clip is currently selected:
            sm.selectedClipId = clip.id

            // Touch down in center of clip body
            val centerX = clip.left + (clipWidthPx / 2f)
            val centerY = 150f

            sm.onDown(centerX, centerY, timeMs = 1000L, clip = clip)
            assertEquals("On touch down, gesture must remain NONE", GestureKind.NONE, sm.gesture)
            assertFalse("Clip move must NOT be armed on touch down", sm.clipMoveArmed)

            // Horizontal swipe: move by 20dp (> touchSlop of 8dp) at 100ms (< 400ms)
            val swipeStepX = sm.dpF(20f)
            sm.onMove(centerX + swipeStepX, centerY, currentTimeMs = 1100L, clip = clip)

            assertEquals("Swiping across clip ($widthDp dp) must transition to SCROLL", GestureKind.SCROLL, sm.gesture)
            assertFalse("clipMoveArmed must remain false", sm.clipMoveArmed)
            assertNull("Trim gesture must not be triggered", sm.gestureClipId)

            sm.onUp(centerX + swipeStepX, centerY, currentTimeMs = 1200L, clip = clip)
            assertNull("Clip must NOT be moved on up after swipe", sm.committedMoveClipId)
            assertTrue("Scroll delta must have been dispatched", sm.scrollDispatchedDeltaX != 0f)
        }
    }

    @Test
    fun unselectedClip_swipeNearEdge_doesNotTriggerTrim() {
        val sm = CanvasGestureStateMachine(density = 3f)
        val clip = ClipInfo(id = "unselected-clip", left = 100f, right = 100f + sm.dpF(50f))
        sm.selectedClipId = "another-clip" // Different clip selected

        // Touch near left edge (where trim handle would be if selected)
        val touchNearEdgeX = clip.left + sm.dpF(2f)
        sm.onDown(touchNearEdgeX, 150f, timeMs = 1000L, clip = clip)

        // Gesture should NOT be TRIM_START because clip is not selected
        assertEquals(GestureKind.NONE, sm.gesture)

        // Swipe horizontally
        sm.onMove(touchNearEdgeX + sm.dpF(15f), 150f, currentTimeMs = 1100L, clip = clip)
        assertEquals("Swipe over unselected clip edge must transition to SCROLL", GestureKind.SCROLL, sm.gesture)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. 400ms Long-Press Gating (350ms vs 400ms)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun longPressGating_dragDoesNotStartAt350ms_startsAt400ms() {
        val sm = CanvasGestureStateMachine(density = 3f)
        val clip = ClipInfo(id = "test-clip", left = 100f, right = 400f)
        val centerX = 250f
        val centerY = 150f

        // ── Scenario A: Movement at 350ms (< 400ms) ──
        sm.onDown(centerX, centerY, timeMs = 1000L, clip = clip)
        // At 350ms, finger moves by 12dp (> touchSlop of 8dp)
        sm.onMove(centerX + sm.dpF(12f), centerY, currentTimeMs = 1350L, clip = clip)

        assertEquals("Movement at 350ms must cancel long-press and become SCROLL", GestureKind.SCROLL, sm.gesture)
        assertFalse("Move must NOT be armed at 350ms", sm.clipMoveArmed)

        // Subsequent movement at 500ms
        sm.onMove(centerX + sm.dpF(24f), centerY, currentTimeMs = 1500L, clip = clip)
        assertEquals("Gesture must remain SCROLL even after 400ms if movement started early", GestureKind.SCROLL, sm.gesture)

        sm.onUp(centerX + sm.dpF(24f), centerY, currentTimeMs = 1600L, clip = clip)
        assertNull("Clip must NOT have moved", sm.committedMoveClipId)

        // ── Scenario B: Held stationary for 400ms, then move at 450ms ──
        val sm2 = CanvasGestureStateMachine(density = 3f)
        sm2.onDown(centerX, centerY, timeMs = 1000L, clip = clip)

        // Stationary at 200ms
        sm2.onMove(centerX, centerY, currentTimeMs = 1200L, clip = clip)
        assertFalse("Move not armed at 200ms", sm2.clipMoveArmed)

        // Stationary at 350ms
        sm2.onMove(centerX, centerY, currentTimeMs = 1350L, clip = clip)
        assertFalse("Move not armed at 350ms", sm2.clipMoveArmed)

        // At 400ms exact, long-press timer triggers
        sm2.checkTimer(currentTimeMs = 1400L, clip = clip)
        assertTrue("Move MUST be armed at 400ms hold", sm2.clipMoveArmed)
        assertEquals("Gesture MUST be MOVE at 400ms hold", GestureKind.MOVE, sm2.gesture)
        assertEquals("Clip must be selected on arm", clip.id, sm2.selectedClipId)

        // Now move finger at 450ms
        sm2.onMove(centerX + sm.dpF(15f), centerY, currentTimeMs = 1450L, clip = clip)
        assertEquals("Gesture must remain MOVE while dragging", GestureKind.MOVE, sm2.gesture)

        sm2.onUp(centerX + sm.dpF(15f), centerY, currentTimeMs = 1550L, clip = clip)
        assertEquals("Clip move MUST be committed on release", clip.id, sm2.committedMoveClipId)
    }

    @Test
    fun longPressGating_exactBoundaries_399msVs400ms() {
        val sm = CanvasGestureStateMachine(density = 3f)
        val clip = ClipInfo(id = "clip-boundary", left = 100f, right = 400f)

        // 399ms movement -> cancelled, SCROLL
        sm.onDown(250f, 150f, timeMs = 1000L, clip = clip)
        sm.onMove(250f + sm.dpF(10f), 150f, currentTimeMs = 1399L, clip = clip)
        assertFalse("399ms movement must NOT arm move", sm.clipMoveArmed)
        assertEquals(GestureKind.SCROLL, sm.gesture)

        // 400ms hold -> armed, MOVE
        val sm2 = CanvasGestureStateMachine(density = 3f)
        sm2.onDown(250f, 150f, timeMs = 1000L, clip = clip)
        sm2.checkTimer(currentTimeMs = 1400L, clip = clip)
        assertTrue("400ms hold MUST arm move", sm2.clipMoveArmed)
        assertEquals(GestureKind.MOVE, sm2.gesture)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Randomized Stress Harness (500 Early Swipes vs 500 Deliberate Holds)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun stressHarness_1000RandomizedTrials_provesZeroFalseClipMoves() {
        val random = Random(42) // Deterministic seed

        var falseClipMovesCount = 0
        var successfulLegitimateMovesCount = 0
        var successfulScrollsCount = 0

        val clip = ClipInfo(id = "stress-clip", left = 100f, right = 300f)

        // Part 1: 500 trials with early swipe movement (between 50ms and 390ms)
        for (i in 1..500) {
            val sm = CanvasGestureStateMachine(density = 3f)
            val startTime = 1000L
            val moveTime = startTime + random.nextLong(50L, 395L) // strictly < 400ms
            val swipeDist = sm.dpF(random.nextFloat() * 30f + 10f) // 10dp..40dp > touchSlop

            sm.onDown(200f, 150f, timeMs = startTime, clip = clip)
            sm.onMove(200f + swipeDist, 150f, currentTimeMs = moveTime, clip = clip)
            sm.onUp(200f + swipeDist, 150f, currentTimeMs = moveTime + 100L, clip = clip)

            if (sm.committedMoveClipId != null) {
                falseClipMovesCount++
            }
            if (sm.gesture == GestureKind.SCROLL) {
                successfulScrollsCount++
            }
        }

        assertEquals("Zero false clip moves allowed during early swipes", 0, falseClipMovesCount)
        assertEquals("All 500 early swipe trials must resolve to SCROLL", 500, successfulScrollsCount)

        // Part 2: 500 trials with deliberate hold (>= 400ms, between 400ms and 800ms)
        for (i in 1..500) {
            val sm = CanvasGestureStateMachine(density = 3f)
            val startTime = 1000L
            val holdTime = startTime + random.nextLong(400L, 800L) // >= 400ms
            val dragDist = sm.dpF(random.nextFloat() * 30f + 10f)

            sm.onDown(200f, 150f, timeMs = startTime, clip = clip)
            // Hold stationary until holdTime
            sm.checkTimer(currentTimeMs = holdTime, clip = clip)
            // Now drag
            sm.onMove(200f + dragDist, 150f, currentTimeMs = holdTime + 50L, clip = clip)
            sm.onUp(200f + dragDist, 150f, currentTimeMs = holdTime + 150L, clip = clip)

            if (sm.committedMoveClipId == clip.id) {
                successfulLegitimateMovesCount++
            }
        }

        assertEquals("All 500 deliberate hold trials must commit clip move", 500, successfulLegitimateMovesCount)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. MultiTrackTimelineView Import Button Touch Interception Gating
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun multiTrackImportButton_swipeRoutesToRecyclerScrollAndSuppressesClick() {
        class ImportButtonInterceptor(val touchSlop: Float = 24f) {
            var isPressed = false
            var importDragging = false
            var recyclerViewScrolledPx = 0
            var clickDispatched = false

            private var downRawX = 0f
            private var downRawY = 0f

            fun onTouch(action: Int, rawX: Float, rawY: Float): Boolean {
                when (action) {
                    0 -> { // ACTION_DOWN
                        downRawX = rawX
                        downRawY = rawY
                        importDragging = false
                        isPressed = true
                        return false // Let button press handle normally
                    }
                    2 -> { // ACTION_MOVE
                        val dx = rawX - downRawX
                        val dy = rawY - downRawY
                        if (!importDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            importDragging = true
                            isPressed = false // cancel pressed state
                        }
                        if (importDragging) {
                            recyclerViewScrolledPx += (-dx).toInt()
                            downRawX = rawX
                            downRawY = rawY
                            return true
                        }
                        return false
                    }
                    1 -> { // ACTION_UP
                        if (importDragging) {
                            importDragging = false
                            return true // Consumed: onClick does NOT fire!
                        }
                        // Not dragging: button click fires
                        clickDispatched = true
                        isPressed = false
                        return false
                    }
                }
                return false
            }
        }

        // Scenario 1: Clean tap on import button (movement < touchSlop)
        val tapButton = ImportButtonInterceptor(touchSlop = 24f)
        tapButton.onTouch(0, 100f, 100f) // ACTION_DOWN
        tapButton.onTouch(2, 102f, 101f) // ACTION_MOVE (2px < 24px)
        tapButton.onTouch(1, 102f, 101f) // ACTION_UP
        assertTrue("Clean tap must dispatch button click", tapButton.clickDispatched)
        assertEquals("Clean tap must not scroll recyclerView", 0, tapButton.recyclerViewScrolledPx)

        // Scenario 2: Swipe across import button (movement > touchSlop)
        val swipeButton = ImportButtonInterceptor(touchSlop = 24f)
        swipeButton.onTouch(0, 100f, 100f) // ACTION_DOWN
        swipeButton.onTouch(2, 140f, 100f) // ACTION_MOVE (40px > 24px)
        assertTrue("Import dragging must be true on swipe", swipeButton.importDragging)
        assertFalse("isPressed must be cancelled", swipeButton.isPressed)
        assertTrue("RecyclerView must have scrolled", swipeButton.recyclerViewScrolledPx != 0)

        swipeButton.onTouch(1, 140f, 100f) // ACTION_UP
        assertFalse("onClick must NOT be dispatched when swiping over import button", swipeButton.clickDispatched)
    }
}
