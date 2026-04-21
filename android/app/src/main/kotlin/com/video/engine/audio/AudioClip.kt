package com.video.engine.audio

data class AudioClip(
    val id: Int,
    val sourcePath: String,
    val displayName: String,
    var startTimeMs: Long,
    var durationMs: Long,
    var gain: Float = 1.0f,
    var layerIndex: Int = 0,
    var visible: Boolean = true,
    var muted: Boolean = false,
    var peakMapPath: String? = null,
    var peakBucketMs: Int = 20,
    var peakLevels: List<Int> = emptyList(),
)

object AudioClipStore {
    private val clips = linkedMapOf<Int, AudioClip>()

    fun all(): List<AudioClip> = clips.values.toList()

    fun get(id: Int): AudioClip? = clips[id]

    fun add(clip: AudioClip) {
        clips[clip.id] = clip
        com.video.engine.NativeBridge.syncAudioClips()
    }

    fun remove(id: Int) {
        clips.remove(id)
        com.video.engine.NativeBridge.syncAudioClips()
    }

    fun splitAt(
        clipId: Int,
        splitTimeMs: Long,
        nextId: Int,
    ): Pair<AudioClip, AudioClip>? {
        val clip = clips[clipId] ?: return null
        val relativeSplit = splitTimeMs - clip.startTimeMs
        if (relativeSplit <= 0L || relativeSplit >= clip.durationMs) {
            return null
        }

        val left = clip.copy(
            durationMs = relativeSplit,
        )
        val right = clip.copy(
            id = nextId,
            startTimeMs = splitTimeMs,
            durationMs = clip.durationMs - relativeSplit,
            layerIndex = clip.layerIndex + 1,
        )
        clips[left.id] = left
        clips[right.id] = right
        return left to right
    }

    fun clear() {
        clips.clear()
    }
}
