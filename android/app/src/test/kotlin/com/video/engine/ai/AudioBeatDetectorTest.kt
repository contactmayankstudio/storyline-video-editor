package com.video.engine.ai

import com.video.engine.audio.AudioClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBeatDetectorTest {

    @Test
    fun detectBeatsFromDurationProducesExpectedDensityForSensitivities() {
        val durationMs = 10_000L // 10 seconds

        val fast = AudioBeatDetector.detectBeatsFromDuration(
            durationMs = durationMs,
            sensitivity = AudioBeatDetector.BeatSensitivity.FAST_CUTS,
        )
        val balanced = AudioBeatDetector.detectBeatsFromDuration(
            durationMs = durationMs,
            sensitivity = AudioBeatDetector.BeatSensitivity.BALANCED,
        )
        val smooth = AudioBeatDetector.detectBeatsFromDuration(
            durationMs = durationMs,
            sensitivity = AudioBeatDetector.BeatSensitivity.SMOOTH,
        )

        // Fast cuts should have more beats than balanced, which has more than smooth
        assertTrue("Fast cuts count (${fast.beatTimestampsMs.size}) > balanced (${balanced.beatTimestampsMs.size})",
            fast.beatTimestampsMs.size > balanced.beatTimestampsMs.size)
        assertTrue("Balanced count (${balanced.beatTimestampsMs.size}) > smooth (${smooth.beatTimestampsMs.size})",
            balanced.beatTimestampsMs.size > smooth.beatTimestampsMs.size)

        // All timestamps must be strictly ascending and within bounds
        assertTrue(fast.beatTimestampsMs.zipWithNext().all { (a, b) -> a < b })
        assertTrue(fast.beatTimestampsMs.all { it >= 0L && it <= durationMs })
    }

    @Test
    fun detectBeatsWithPeaksFindsTransientsAboveThreshold() {
        // Create an audio clip with simulated peaks: baseline 20, transient kick at bucket 25 and 60 (at 20ms/bucket -> 500ms, 1200ms)
        val simulatedPeaks = MutableList(100) { 20 }
        simulatedPeaks[25] = 95 // strong kick drop
        simulatedPeaks[26] = 85
        simulatedPeaks[60] = 90 // second drop
        simulatedPeaks[61] = 80

        val clip = AudioClip(
            id = 1,
            sourcePath = "/dummy/music.mp3",
            displayName = "Club Beat",
            startTimeMs = 0L,
            durationMs = 2000L,
            peakBucketMs = 20,
            peakLevels = simulatedPeaks,
        )

        val result = AudioBeatDetector.detectBeats(
            clip = clip,
            sensitivity = AudioBeatDetector.BeatSensitivity.BALANCED,
        )

        assertFalse("Detected beats should not be empty", result.beatTimestampsMs.isEmpty())
        assertTrue("BPM should be positive", result.bpm > 0)

        // Peak at bucket 25 corresponds to ~500ms
        val hasAround500Ms = result.beatTimestampsMs.any { kotlin.math.abs(it - 500L) <= 60L }
        assertTrue("Should detect transient near 500ms", hasAround500Ms)
    }

    @Test
    fun calculateBeatTimestampsReturnsValidGridForBpm() {
        val result = AudioBeatDetector.calculateBeatTimestamps(
            durationMs = 5000L,
            bpm = 120, // 500ms interval
            startOffsetMs = 0L,
        )

        assertEquals(120, result.bpm)
        assertEquals(500L, result.beatIntervalMs)
        assertFalse(result.beatTimestampsMs.isEmpty())
        assertEquals(500L, result.beatTimestampsMs[0])
        assertEquals(1000L, result.beatTimestampsMs[1])
    }
}
