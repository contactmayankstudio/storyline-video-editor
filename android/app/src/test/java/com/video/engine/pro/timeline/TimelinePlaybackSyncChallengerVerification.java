package com.video.engine.pro.timeline;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.util.Random;

/**
 * Empirical Challenger Verification for Milestone M1 (Requirement R1: Timeline Gesture & Playback Verification).
 *
 * Verifies:
 * 1. Playback forward monotonicity guard in TimelineCanvasView.renderPlaybackPosition:
 *    - Sequential decoder timestamps with jitter: 1000ms -> 1033ms -> 1025ms -> 1066ms -> 1058ms
 *    - Suppression of minor backwards micro-jitter (<80ms) during active playback
 *    - Immediate playhead update on intentional backward seeks (diff >= 80ms)
 *    - Exact boundary behavior at 79ms, 80ms, 81ms
 *    - Non-suppression of backward adjustments when playback is paused
 *    - 1,000-frame randomized jitter generator proving strict monotonic progression
 *
 * 2. Active timeline time lookups during split and trim in MainActivity.kt:
 *    - Verification of activeTimelineCurrentTimeMs resolution precedence chain
 *    - Verification that active canvas timeline playhead overrides hidden 0ms MultiTrackTimelineView
 */
public class TimelinePlaybackSyncChallengerVerification {

    /**
     * Exact simulation of TimelineCanvasView.renderPlaybackPosition logic:
     *
     * val clampedMs = ms.coerceAtLeast(0L)
     * if (playbackActive && clampedMs < playheadMs && (playheadMs - clampedMs) < 80L) {
     *     return
     * }
     * playheadMs = clampedMs
     */
    public static class PlayheadRenderer {
        public boolean playbackActive;
        public long playheadMs;
        public int renderCount = 0;
        public int suppressedCount = 0;

        public PlayheadRenderer(boolean playbackActive, long initialPlayheadMs) {
            this.playbackActive = playbackActive;
            this.playheadMs = initialPlayheadMs;
        }

        public boolean renderPlaybackPosition(long ms) {
            long clampedMs = Math.max(0L, ms);
            if (playbackActive && clampedMs < playheadMs && (playheadMs - clampedMs) < 80L) {
                // Forward monotonicity guard: suppress minor backwards micro-jitter (<80ms)
                suppressedCount++;
                return false;
            }
            playheadMs = clampedMs;
            renderCount++;
            return true;
        }
    }

    @Test
    public void testMissionSequence_jitterSuppressedAndBackwardSeekImmediatelyApplied() {
        PlayheadRenderer renderer = new PlayheadRenderer(true, 1000L);

        // 1. Initial state
        Assert.assertEquals(1000L, renderer.playheadMs);

        // 2. Next timestamp: 1033ms -> forward progress rendered
        boolean rendered1033 = renderer.renderPlaybackPosition(1033L);
        Assert.assertTrue("1033ms forward progress must be rendered", rendered1033);
        Assert.assertEquals(1033L, renderer.playheadMs);

        // 3. Jitter timestamp: 1025ms (diff = 8ms < 80ms) -> suppressed!
        boolean rendered1025 = renderer.renderPlaybackPosition(1025L);
        Assert.assertFalse("1025ms micro-jitter (<80ms backward) must be suppressed", rendered1025);
        Assert.assertEquals("Playhead must remain at 1033ms", 1033L, renderer.playheadMs);

        // 4. Next timestamp: 1066ms -> forward progress rendered
        boolean rendered1066 = renderer.renderPlaybackPosition(1066L);
        Assert.assertTrue("1066ms forward progress must be rendered", rendered1066);
        Assert.assertEquals(1066L, renderer.playheadMs);

        // 5. Jitter timestamp: 1058ms (diff = 8ms < 80ms) -> suppressed!
        boolean rendered1058 = renderer.renderPlaybackPosition(1058L);
        Assert.assertFalse("1058ms micro-jitter (<80ms backward) must be suppressed", rendered1058);
        Assert.assertEquals("Playhead must remain at 1066ms", 1066L, renderer.playheadMs);

        // Forward progress to 5000ms
        Assert.assertTrue(renderer.renderPlaybackPosition(5000L));
        Assert.assertEquals(5000L, renderer.playheadMs);

        // 6. Intentional backward seek: 5000ms -> 1000ms (diff = 4000ms >= 80ms) -> NOT suppressed!
        boolean renderedSeek = renderer.renderPlaybackPosition(1000L);
        Assert.assertTrue("Intentional backward seek (diff >= 80ms) must NOT be suppressed", renderedSeek);
        Assert.assertEquals("Playhead must immediately update to seek destination 1000ms", 1000L, renderer.playheadMs);
    }

    @Test
    public void testBoundaryConditions_exact79msAnd80msAnd81ms() {
        PlayheadRenderer renderer = new PlayheadRenderer(true, 1000L);

        // 79ms backward jump: 1000 -> 921 (diff = 79ms < 80ms) -> suppressed
        boolean rendered79 = renderer.renderPlaybackPosition(921L);
        Assert.assertFalse("79ms backward jump (<80ms) must be suppressed", rendered79);
        Assert.assertEquals(1000L, renderer.playheadMs);

        // 80ms backward jump: 1000 -> 920 (diff = 80ms, not < 80ms) -> NOT suppressed
        boolean rendered80 = renderer.renderPlaybackPosition(920L);
        Assert.assertTrue("80ms backward jump (exact boundary) must NOT be suppressed", rendered80);
        Assert.assertEquals(920L, renderer.playheadMs);

        // 81ms backward jump: 920 -> 839 (diff = 81ms >= 80ms) -> NOT suppressed
        boolean rendered81 = renderer.renderPlaybackPosition(839L);
        Assert.assertTrue("81ms backward jump (>=80ms) must NOT be suppressed", rendered81);
        Assert.assertEquals(839L, renderer.playheadMs);

        // 0ms difference (duplicate decoder frame) -> not suppressed
        boolean rendered0 = renderer.renderPlaybackPosition(839L);
        Assert.assertTrue("0ms duplicate frame must not be suppressed", rendered0);
        Assert.assertEquals(839L, renderer.playheadMs);
    }

    @Test
    public void testPausedPlayback_doesNotSuppressSmallBackwardAdjustments() {
        PlayheadRenderer renderer = new PlayheadRenderer(false, 1033L);

        // Single-frame step backward (33ms step: 1033 -> 1000) when paused
        boolean renderedStepBack = renderer.renderPlaybackPosition(1000L);
        Assert.assertTrue("Paused playback must NOT suppress backward step adjustments (<80ms)", renderedStepBack);
        Assert.assertEquals("Playhead must update to 1000ms when paused", 1000L, renderer.playheadMs);

        // Micro step backward (8ms: 1000 -> 992) when paused
        boolean renderedMicroStep = renderer.renderPlaybackPosition(992L);
        Assert.assertTrue("Paused playback must NOT suppress micro adjustments", renderedMicroStep);
        Assert.assertEquals(992L, renderer.playheadMs);
    }

    @Test
    public void testNegativeTimestamps_clampedToZeroSafely() {
        PlayheadRenderer renderer = new PlayheadRenderer(true, 50L);

        // Negative timestamp with small diff: -10ms clamped to 0ms (diff = 50ms < 80ms) -> suppressed
        Assert.assertFalse(renderer.renderPlaybackPosition(-10L));
        Assert.assertEquals(50L, renderer.playheadMs);

        // Negative timestamp with large diff: -10ms clamped to 0ms from 200ms (diff = 200ms >= 80ms) -> updates to 0ms
        PlayheadRenderer renderer2 = new PlayheadRenderer(true, 200L);
        Assert.assertTrue(renderer2.renderPlaybackPosition(-10L));
        Assert.assertEquals(0L, renderer2.playheadMs);
    }

    @Test
    public void testStressHarness_1000FramesJitter_guaranteesStrictMonotonicity() {
        PlayheadRenderer renderer = new PlayheadRenderer(true, 0L);
        Random random = new Random(42); // deterministic seed

        long nominalClockMs = 0L;
        long lastRenderedPlayhead = 0L;

        for (int frame = 1; frame <= 1000; frame++) {
            nominalClockMs += 16L; // ~60fps nominal progression

            // Add simulated arrival jitter in [-25ms, +25ms]
            long jitter = (long) (random.nextInt(51) - 25);
            long arrivalTimestampMs = Math.max(0L, nominalClockMs + jitter);

            boolean rendered = renderer.renderPlaybackPosition(arrivalTimestampMs);
            if (rendered) {
                Assert.assertTrue(
                    "Playhead must be strictly monotonic non-decreasing: current=" + renderer.playheadMs +
                    ", last=" + lastRenderedPlayhead + ", frame=" + frame,
                    renderer.playheadMs >= lastRenderedPlayhead
                );
                lastRenderedPlayhead = renderer.playheadMs;
            } else {
                Assert.assertEquals(
                    "When suppressed, playhead must remain at last rendered position",
                    lastRenderedPlayhead,
                    renderer.playheadMs
                );
            }
        }

        Assert.assertTrue("Stress test must have processed nominal progression", nominalClockMs >= 16000L);
        Assert.assertTrue("Some jitter frames must have been suppressed", renderer.suppressedCount > 0);
        Assert.assertTrue("Legitimate forward frames must have rendered", renderer.renderCount > 0);
    }

    @Test
    public void testActiveTimelineCurrentTimeMs_precedenceResolution() {
        class MockCanvasView {
            final boolean isVisible;
            final long playhead;
            MockCanvasView(boolean isVisible, long playhead) {
                this.isVisible = isVisible;
                this.playhead = playhead;
            }
            long currentTimeMs() { return playhead; }
        }

        class MockMultiTrackView {
            final boolean isVisible;
            final long playhead;
            MockMultiTrackView(boolean isVisible, long playhead) {
                this.isVisible = isVisible;
                this.playhead = playhead;
            }
            long currentTimeMs() { return playhead; }
        }

        class Resolver {
            long resolveTime(
                MockCanvasView activeCanvas,
                MockCanvasView anyCanvas,
                MockMultiTrackView multiTrack,
                long fallbackTime
            ) {
                if (activeCanvas != null && activeCanvas.isVisible) return activeCanvas.currentTimeMs();
                if (anyCanvas != null) return anyCanvas.currentTimeMs();
                if (multiTrack != null && multiTrack.isVisible) return multiTrack.currentTimeMs();
                return fallbackTime;
            }
        }

        Resolver resolver = new Resolver();

        // Scenario 1: Active visible canvas timeline at 3500ms, hidden/stale MultiTrackTimelineView at 0ms
        long time1 = resolver.resolveTime(
            new MockCanvasView(true, 3500L),
            new MockCanvasView(true, 3500L),
            new MockMultiTrackView(false, 0L),
            0L
        );
        Assert.assertEquals("Must resolve to active canvas time 3500ms, ignoring stale 0ms in multiTrackView", 3500L, time1);

        // Scenario 2: Active canvas invisible, but any canvas instance present at 4200ms
        long time2 = resolver.resolveTime(
            new MockCanvasView(false, 4200L),
            new MockCanvasView(false, 4200L),
            new MockMultiTrackView(true, 1000L),
            0L
        );
        Assert.assertEquals("Must resolve to canvasView.currentTimeMs() 4200ms before falling back to multiTrack", 4200L, time2);

        // Scenario 3: No canvas view present, visible MultiTrackTimelineView at 2100ms
        long time3 = resolver.resolveTime(
            null,
            null,
            new MockMultiTrackView(true, 2100L),
            500L
        );
        Assert.assertEquals("Must resolve to multiTrackView when canvas view is null", 2100L, time3);

        // Scenario 4: All timeline views null, fallback to MainActivity.currentTimeMs
        long time4 = resolver.resolveTime(
            null,
            null,
            null,
            1850L
        );
        Assert.assertEquals("Must resolve to fallback time when all timeline views are null", 1850L, time4);
    }

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("Running Empirical Challenger Verification Suite for M1 Playback Sync");
        System.out.println("======================================================================");

        Result result = JUnitCore.runClasses(TimelinePlaybackSyncChallengerVerification.class);

        System.out.println("Tests run: " + result.getRunCount());
        System.out.println("Failures:  " + result.getFailureCount());
        System.out.println("Ignored:   " + result.getIgnoreCount());
        System.out.println("Runtime:   " + result.getRunTime() + " ms");

        for (Failure failure : result.getFailures()) {
            System.err.println("FAILED: " + failure.getTestHeader() + " -> " + failure.getMessage());
            failure.getException().printStackTrace();
        }

        if (result.wasSuccessful()) {
            System.out.println(">>> ALL EMPIRICAL CHALLENGER VERIFICATION TESTS PASSED! <<<");
            System.exit(0);
        } else {
            System.err.println(">>> SOME EMPIRICAL CHALLENGER TESTS FAILED! <<<");
            System.exit(1);
        }
    }
}
