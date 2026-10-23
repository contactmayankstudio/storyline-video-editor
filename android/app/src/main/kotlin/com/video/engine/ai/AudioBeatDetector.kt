package com.video.engine.ai

import com.video.engine.audio.AudioClip
import kotlin.math.max

/**
 * Intelligent Audio Beat & Rhythm Detector for Storyline Video Editor.
 * Computes transient beat grids across audio and music tracks to sync
 * video cuts, punch-in zooms, and flash transitions directly on the beat.
 */
object AudioBeatDetector {

    enum class BeatSensitivity(
        val label: String,
        val minIntervalMs: Long,
        val thresholdRatio: Float,
        val defaultIntervalMs: Long,
    ) {
        FAST_CUTS("Every Kick (Fast Cuts)", 280L, 1.20f, 350L),
        BALANCED("Balanced Groove", 500L, 1.50f, 550L),
        SMOOTH("Major Drops Only (Smooth)", 1000L, 1.90f, 1100L),
    }

    data class BeatGrid(
        val bpm: Int,
        val beatIntervalMs: Long,
        val beatTimestampsMs: List<Long>,
    )

    /**
     * Detects beat timestamps from an [AudioClip] using peak transients if available,
     * or a dynamic tempo grid.
     */
    fun detectBeats(
        clip: AudioClip,
        sensitivity: BeatSensitivity = BeatSensitivity.BALANCED,
        maxBeats: Int = 48,
    ): BeatGrid {
        val durationMs = clip.durationMs.coerceAtLeast(1000L)
        val startTimeMs = clip.startTimeMs.coerceAtLeast(0L)
        val peaks = clip.peakLevels

        if (peaks.isNotEmpty() && peaks.size >= 10) {
            val bucketMs = clip.peakBucketMs.coerceIn(10, 50)
            val detected = detectFromPeaks(
                peaks = peaks,
                bucketMs = bucketMs,
                startTimeMs = startTimeMs,
                durationMs = durationMs,
                sensitivity = sensitivity,
                maxBeats = maxBeats,
            )
            if (detected.isNotEmpty()) {
                val avgInterval = if (detected.size > 1) {
                    (detected.last() - detected.first()) / (detected.size - 1)
                } else sensitivity.defaultIntervalMs
                val bpm = (60_000.0 / avgInterval.coerceIn(250L, 2000L)).toInt()
                return BeatGrid(bpm = bpm, beatIntervalMs = avgInterval, beatTimestampsMs = detected)
            }
        }

        return detectBeatsFromDuration(
            durationMs = durationMs,
            sensitivity = sensitivity,
            startOffsetMs = startTimeMs,
            maxBeats = maxBeats,
        )
    }

    private fun detectFromPeaks(
        peaks: List<Int>,
        bucketMs: Int,
        startTimeMs: Long,
        durationMs: Long,
        sensitivity: BeatSensitivity,
        maxBeats: Int,
    ): List<Long> {
        val timestamps = mutableListOf<Long>()
        val windowSize = max(5, (400 / bucketMs)) // ~400ms window
        var lastBeatMs = -sensitivity.minIntervalMs
        val edgeGuardMs = 300L
        val minLevelThreshold = 18

        for (i in 2 until (peaks.size - 2)) {
            val currentVal = peaks[i]
            if (currentVal < minLevelThreshold) continue

            // Local maximum check
            val isPeak = currentVal >= peaks[i - 1] &&
                currentVal >= peaks[i - 2] &&
                currentVal >= peaks[i + 1] &&
                currentVal >= peaks[i + 2]
            if (!isPeak) continue

            // Compute local baseline average
            val startIdx = max(0, i - windowSize)
            val endIdx = minOf(peaks.size - 1, i + windowSize)
            var sum = 0
            var count = 0
            for (w in startIdx..endIdx) {
                sum += peaks[w]
                count++
            }
            val localAvg = if (count > 0) sum.toFloat() / count else 1.0f

            if (currentVal >= localAvg * sensitivity.thresholdRatio) {
                val beatTimeMs = startTimeMs + (i.toLong() * bucketMs)
                if (beatTimeMs - lastBeatMs >= sensitivity.minIntervalMs &&
                    beatTimeMs >= (startTimeMs + edgeGuardMs) &&
                    beatTimeMs <= (startTimeMs + durationMs - edgeGuardMs)
                ) {
                    timestamps.add(beatTimeMs)
                    lastBeatMs = beatTimeMs
                    if (timestamps.size >= maxBeats) break
                }
            }
        }
        return timestamps
    }

    /**
     * Calculates beat timestamps along the project timeline duration for a given BPM or sensitivity.
     */
    fun calculateBeatTimestamps(
        durationMs: Long,
        bpm: Int = 125,
        startOffsetMs: Long = 0L,
    ): BeatGrid {
        val safeBpm = bpm.coerceIn(60, 200)
        val intervalMs = (60_000.0 / safeBpm).toLong().coerceAtLeast(200L)
        val timestamps = mutableListOf<Long>()
        var current = startOffsetMs + intervalMs
        while (current < (startOffsetMs + durationMs) && timestamps.size < 64) {
            timestamps.add(current)
            current += intervalMs
        }
        return BeatGrid(safeBpm, intervalMs, timestamps)
    }

    /**
     * Generates a beat grid based on sensitivity interval when audio waveform is not yet indexed.
     */
    fun detectBeatsFromDuration(
        durationMs: Long,
        sensitivity: BeatSensitivity,
        startOffsetMs: Long = 0L,
        maxBeats: Int = 48,
    ): BeatGrid {
        val intervalMs = sensitivity.defaultIntervalMs
        val bpm = (60_000.0 / intervalMs).toInt()
        val timestamps = mutableListOf<Long>()
        var current = startOffsetMs + intervalMs
        val edgeGuardMs = 300L
        val maxTime = startOffsetMs + durationMs - edgeGuardMs

        while (current <= maxTime && timestamps.size < maxBeats) {
            timestamps.add(current)
            current += intervalMs
        }
        return BeatGrid(bpm = bpm, beatIntervalMs = intervalMs, beatTimestampsMs = timestamps)
    }
}
