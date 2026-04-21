package com.video.engine.overlay

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
    var fontSize: Float = 36f,
    var fontName: String? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var layerIndex: Int = 0,
    var visible: Boolean = true,
)

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
