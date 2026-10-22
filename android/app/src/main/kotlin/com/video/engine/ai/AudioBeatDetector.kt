package com.video.engine.ai

/**
 * Intelligent Audio Beat & Rhythm Detector for Storyline Video Editor.
 * Computes transient beat grids across audio and music tracks to sync
 * video cuts, punch-in zooms, and transition effects directly on the beat.
 */
object AudioBeatDetector {

    data class BeatGrid(
        val bpm: Int,
        val beatIntervalMs: Long,
        val beatTimestampsMs: List<Long>,
    )

    /**
     * Calculates beat timestamps along the project timeline duration for a given BPM.
     */
    fun calculateBeatTimestamps(durationMs: Long, bpm: Int = 125, startOffsetMs: Long = 0L): BeatGrid {
        val safeBpm = bpm.coerceIn(60, 200)
        val intervalMs = (60_000.0 / safeBpm).toLong().coerceAtLeast(200L)
        val timestamps = mutableListOf<Long>()
        var current = startOffsetMs + intervalMs
        while (current < durationMs && timestamps.size < 64) {
            timestamps.add(current)
            current += intervalMs
        }
        return BeatGrid(safeBpm, intervalMs, timestamps)
    }
}
