package com.video.engine.overlay

import com.video.engine.AiPoseKeyframe

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
