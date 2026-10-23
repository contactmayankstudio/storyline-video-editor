package com.video.engine.overlay

import com.video.engine.AiPoseKeyframe

/**
 * Word-level cue for animated dynamic captions (Hormozi, MrBeast, Cyberpunk, etc.).
 */
data class WordCue(
    val word: String,
    val startMs: Int,
    val endMs: Int,
    val highlightColor: Int? = null,
)

data class TextOverlay(
    var id: Int = -1,
    var text: String = "Text",
    var startTimeMs: Int = 0,
    var endTimeMs: Int = 10_000,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var scale: Float = 1.0f,
    var rotation: Float = 0.0f,
    var opacity: Float = 1.0f,
    var color: Int = 0xFFFFFFFF.toInt(),
    var backgroundColor: Int = 0x00000000,
    var strokeColor: Int = 0xFF000000.toInt(),
    var strokeWidth: Float = 0.0f,
    var depthColor: Int = 0x99000000.toInt(),
    var depthPx: Float = 0.0f,
    var shadowEnabled: Boolean = true,
    var shadowColor: Int = 0x99000000.toInt(),
    var shadowBlur: Float = 4.0f,
    var shadowOffsetX: Float = 0.0f,
    var shadowOffsetY: Float = 2.0f,
    var gradientEnabled: Boolean = false,
    var gradientStartColor: Int = 0xFFFFFFFF.toInt(),
    var gradientEndColor: Int = 0xFF35C7FF.toInt(),
    var backgroundPadding: Float = 0.0f,
    var backgroundCornerRadius: Float = 0.0f,
    var fontSize: Float = 36f,
    var fontName: String? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var allCaps: Boolean = false,
    var layerIndex: Int = 0,
    var visible: Boolean = true,
    var aiTrackKeyframes: List<AiPoseKeyframe> = emptyList(),
    var wordCues: List<WordCue> = emptyList(),
    var captionStyle: String? = null, // "HORMOZI", "MRBEAST", "CYBERPUNK", "CINEMA", "KARAOKE"
    var highlightColor: Int = 0xFFFFE500.toInt(),
    var activeWordScale: Float = 1.25f,
    var glowColor: Int = 0xFFFFE500.toInt(),
) {
    /**
     * Compute or interpolate word cues if not already populated.
     * Divides text duration proportionally across words based on character length.
     */
    fun interpolateWordCuesIfNeeded(): List<WordCue> {
        if (wordCues.isNotEmpty()) return wordCues
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val duration = (endTimeMs - startTimeMs).coerceAtLeast(100)
        val totalChars = words.sumOf { it.length }.coerceAtLeast(1)
        var currentOffset = 0
        val cues = mutableListOf<WordCue>()
        words.forEachIndexed { index, word ->
            val wordDuration = (duration * word.length) / totalChars
            val wordStart = startTimeMs + currentOffset
            val wordEnd = if (index == words.lastIndex) endTimeMs else (wordStart + wordDuration)
            currentOffset += wordDuration
            val popColor = when {
                captionStyle == "MRBEAST" && (index % 2 == 1) -> 0xFFFF2A55.toInt() // Red pop keyword
                captionStyle == "MRBEAST" -> 0xFF00FF66.toInt() // Green keyword
                else -> highlightColor
            }
            cues.add(WordCue(word = word, startMs = wordStart, endMs = wordEnd, highlightColor = popColor))
        }
        wordCues = cues
        return cues
    }

    /**
     * Return index of active word at given timeline timeMs.
     */
    fun activeWordIndexAt(timeMs: Long): Int {
        val cues = if (wordCues.isEmpty()) interpolateWordCuesIfNeeded() else wordCues
        if (cues.isEmpty()) return -1
        val idx = cues.indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }
        if (idx != -1) return idx
        if (timeMs >= cues.last().endMs) return cues.lastIndex
        if (timeMs <= cues.first().startMs) return 0
        return -1
    }
}

/**
 * Simple in-memory overlay store for Java/Kotlin side state.
 */
object OverlayStore {
    private val map = mutableMapOf<Int, TextOverlay>()
    private var nextLocalId = 1

    fun generateId(): Int = nextLocalId++

    fun put(overlay: TextOverlay) {
        if (overlay.id <= 0) overlay.id = generateId()
        map[overlay.id] = overlay
    }

    fun remove(id: Int) {
        map.remove(id)
    }

    fun get(id: Int): TextOverlay? = map[id]

    fun all(): List<TextOverlay> = map.values.toList()

    fun clear() {
        map.clear()
    }
}
