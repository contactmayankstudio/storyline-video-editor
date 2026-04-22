package com.video.engine.audio

data class AudioGainKeyframe(
    val timeMs: Long,
    val gain: Float,
)

data class AudioClip(
    val id: Int,
    val sourcePath: String,
    val displayName: String,
    var startTimeMs: Long,
    var durationMs: Long,
    var gain: Float = 1.0f,
    var fadeInMs: Int = 0,
    var fadeOutMs: Int = 0,
    var layerIndex: Int = 0,
    var visible: Boolean = true,
    var muted: Boolean = false,
    var peakMapPath: String? = null,
    var peakBucketMs: Int = 20,
    var peakLevels: List<Int> = emptyList(),
    var gainKeyframes: List<AudioGainKeyframe> = emptyList(),
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
            gainKeyframes = clip.gainKeyframes
                .filter { it.timeMs < relativeSplit }
                .sortedBy { it.timeMs },
        )
        val splitGain = sampleGainAt(clip.gainKeyframes, relativeSplit)
        val rightKeyframes = mutableListOf(AudioGainKeyframe(timeMs = 0L, gain = splitGain))
        clip.gainKeyframes.forEach { keyframe ->
            if (keyframe.timeMs >= relativeSplit) {
                rightKeyframes += AudioGainKeyframe(
                    timeMs = (keyframe.timeMs - relativeSplit).coerceAtLeast(0L),
                    gain = keyframe.gain,
                )
            }
        }
        val right = clip.copy(
            id = nextId,
            startTimeMs = splitTimeMs,
            durationMs = clip.durationMs - relativeSplit,
            layerIndex = clip.layerIndex + 1,
            gainKeyframes = rightKeyframes
                .sortedBy { it.timeMs }
                .distinctBy { it.timeMs },
        )
        clips[left.id] = left
        clips[right.id] = right
        return left to right
    }

    fun clear() {
        clips.clear()
    }

    private fun sampleGainAt(keyframes: List<AudioGainKeyframe>, timeMs: Long): Float {
        if (keyframes.isEmpty()) return 1.0f
        val normalized = keyframes.sortedBy { it.timeMs }
        val clampedTimeMs = timeMs.coerceAtLeast(0L)
        if (clampedTimeMs <= normalized.first().timeMs) {
            return normalized.first().gain.coerceIn(0f, 2f)
        }
        if (clampedTimeMs >= normalized.last().timeMs) {
            return normalized.last().gain.coerceIn(0f, 2f)
        }
        for (index in 1 until normalized.size) {
            val left = normalized[index - 1]
            val right = normalized[index]
            if (clampedTimeMs > right.timeMs) continue
            val span = (right.timeMs - left.timeMs).coerceAtLeast(1L)
            val progress = (clampedTimeMs - left.timeMs).toFloat() / span.toFloat()
            return (left.gain + ((right.gain - left.gain) * progress)).coerceIn(0f, 2f)
        }
        return 1.0f
    }
}
