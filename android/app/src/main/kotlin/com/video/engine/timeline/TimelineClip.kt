package com.video.engine.timeline

import android.graphics.Color

/**
 * Data class representing a fake video clip on the timeline.
 *
 * @param id Unique clip identifier
 * @param durationMs Duration in milliseconds
 * @param color Visual color for the clip rectangle
 * @param title Display name for the clip
 */
data class TimelineClip(
    val id: Int,
    val durationMs: Long,
    val color: Int = Color.parseColor("#6200EE"),
    val title: String = "Clip",
    val startTimeMs: Long = 0L
) {
    val endTimeMs: Long
        get() = startTimeMs + durationMs

    /**
     * Get human-readable duration string.
     */
    fun getDurationString(): String {
        val totalSeconds = durationMs / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

/**
 * Create fake timeline clips for demo.
 * Replace with real clips from native video decoder later.
 */
fun createFakeClips(): List<TimelineClip> {
    return listOf(
        TimelineClip(
            id = 1,
            durationMs = 3000,  // 3 seconds
            color = Color.parseColor("#FF6B6B"),  // Red
            title = "Clip 1"
        ),
        TimelineClip(
            id = 2,
            durationMs = 5000,  // 5 seconds
            color = Color.parseColor("#4ECDC4"),  // Teal
            title = "Clip 2"
        ),
        TimelineClip(
            id = 3,
            durationMs = 4000,  // 4 seconds
            color = Color.parseColor("#45B7D1"),  // Blue
            title = "Clip 3"
        ),
        TimelineClip(
            id = 4,
            durationMs = 2500,  // 2.5 seconds
            color = Color.parseColor("#96CEB4"),  // Green
            title = "Clip 4"
        ),
        TimelineClip(
            id = 5,
            durationMs = 3500,  // 3.5 seconds
            color = Color.parseColor("#FFEAA7"),  // Yellow
            title = "Clip 5"
        )
    )
}

/**
 * Calculate total duration of all clips.
 */
fun List<TimelineClip>.getTotalDuration(): Long {
    return this.sumOf { it.durationMs }
}
