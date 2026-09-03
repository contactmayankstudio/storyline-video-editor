package com.video.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioClipStoreTest {

    @Before
    fun setUp() {
        AudioClipStore.clear()
    }

    @Test
    fun addAndRetrieveAudioClips() {
        val musicClip = AudioClip(
            id = 1,
            sourcePath = "/storage/emulated/0/Music/background.mp3",
            displayName = "Background Music",
            startTimeMs = 0L,
            durationMs = 30000L,
            gain = 0.8f,
            layerIndex = 0,
        )

        val voiceoverClip = AudioClip(
            id = 2,
            sourcePath = "/data/user/0/com.video.engine/files/voiceovers/vo_12345.m4a",
            displayName = "Voiceover",
            startTimeMs = 4000L,
            durationMs = 8000L,
            gain = 1.2f,
            layerIndex = 1,
        )

        AudioClipStore.add(musicClip)
        AudioClipStore.add(voiceoverClip)

        val allClips = AudioClipStore.all()
        assertEquals("Both music and voiceover must coexist in AudioClipStore", 2, allClips.size)

        val retrievedMusic = AudioClipStore.get(1)
        assertNotNull(retrievedMusic)
        assertEquals("Background Music", retrievedMusic?.displayName)
        assertEquals(0L, retrievedMusic?.startTimeMs)
        assertEquals(30000L, retrievedMusic?.durationMs)
        assertEquals(0, retrievedMusic?.layerIndex)

        val retrievedVo = AudioClipStore.get(2)
        assertNotNull(retrievedVo)
        assertEquals("Voiceover", retrievedVo?.displayName)
        assertEquals(4000L, retrievedVo?.startTimeMs)
        assertEquals(8000L, retrievedVo?.durationMs)
        assertEquals(1, retrievedVo?.layerIndex)
    }

    @Test
    fun removeAudioClip_preservesOtherClips() {
        val musicClip = AudioClip(
            id = 1,
            sourcePath = "/path/music.mp3",
            displayName = "Music",
            startTimeMs = 0L,
            durationMs = 15000L,
        )
        val voClip = AudioClip(
            id = 2,
            sourcePath = "/path/vo.m4a",
            displayName = "Voiceover",
            startTimeMs = 2000L,
            durationMs = 5000L,
        )

        AudioClipStore.add(musicClip)
        AudioClipStore.add(voClip)

        AudioClipStore.remove(1)

        val remaining = AudioClipStore.all()
        assertEquals(1, remaining.size)
        assertEquals(2, remaining[0].id)
        assertEquals("Voiceover", remaining[0].displayName)
    }
}
