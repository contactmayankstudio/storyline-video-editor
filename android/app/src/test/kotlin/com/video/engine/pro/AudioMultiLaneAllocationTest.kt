package com.video.engine.pro

import com.video.engine.pro.model.ClipSegment
import com.video.engine.pro.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMultiLaneAllocationTest {

    private fun withAutoSubTracks(clips: List<ClipSegment>): List<ClipSegment> {
        val sorted = clips.sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.zOrder })
        val laneEndTimes = mutableListOf<Long>()
        return sorted.map { clip ->
            val explicitSub = clip.metadata["subTrack"]?.toIntOrNull()
            val laneIndex = if (explicitSub != null && explicitSub > 0) {
                (explicitSub - 1).coerceIn(0, 3)
            } else {
                var chosen = -1
                for (i in laneEndTimes.indices) {
                    if (clip.startTimeMs >= laneEndTimes[i]) {
                        chosen = i
                        break
                    }
                }
                if (chosen == -1) {
                    chosen = laneEndTimes.size.coerceAtMost(3)
                    if (chosen >= laneEndTimes.size) {
                        laneEndTimes.add(clip.endTimeMs())
                    } else {
                        laneEndTimes[chosen] = clip.endTimeMs()
                    }
                } else {
                    laneEndTimes[chosen] = clip.endTimeMs()
                }
                chosen
            }
            if (laneIndex < laneEndTimes.size) {
                laneEndTimes[laneIndex] = maxOf(laneEndTimes[laneIndex], clip.endTimeMs())
            }
            clip.copy(metadata = clip.metadata + ("subTrack" to (laneIndex + 1).toString()))
        }
    }

    @Test
    fun concurrentMusicAndVoiceover_assignedToSeparateLanes() {
        val music = ClipSegment(
            id = "audio-1",
            sourcePath = "/music.mp3",
            trackType = TrackType.AUDIO,
            startTimeMs = 0L,
            durationMs = 30000L,
            sourceInMs = 0L,
            sourceOutMs = 30000L,
        )

        val voiceover = ClipSegment(
            id = "audio-2",
            sourcePath = "/vo.m4a",
            trackType = TrackType.AUDIO,
            startTimeMs = 5000L,
            durationMs = 8000L,
            sourceInMs = 0L,
            sourceOutMs = 8000L,
        )

        val result = withAutoSubTracks(listOf(music, voiceover))
        assertEquals(2, result.size)
        assertEquals("1", result[0].metadata["subTrack"])
        assertEquals("2", result[1].metadata["subTrack"])
    }

    @Test
    fun sequentialAudioClips_assignedToSameLane() {
        val song1 = ClipSegment(
            id = "audio-1",
            sourcePath = "/song1.mp3",
            trackType = TrackType.AUDIO,
            startTimeMs = 0L,
            durationMs = 10000L,
            sourceInMs = 0L,
            sourceOutMs = 10000L,
        )

        val song2 = ClipSegment(
            id = "audio-2",
            sourcePath = "/song2.mp3",
            trackType = TrackType.AUDIO,
            startTimeMs = 12000L,
            durationMs = 8000L,
            sourceInMs = 0L,
            sourceOutMs = 8000L,
        )

        val result = withAutoSubTracks(listOf(song1, song2))
        assertEquals(2, result.size)
        assertEquals("1", result[0].metadata["subTrack"])
        assertEquals("1", result[1].metadata["subTrack"])
    }
}
