package com.video.engine

enum class LayerKind {
    CLIP,
    TEXT,
    STICKER,
    AUDIO,
}

data class LayerDescriptor(
    val key: String,
    val kind: LayerKind,
    val id: Int,
    val title: String,
    val subtitle: String,
    val layerIndex: Int,
    val visible: Boolean,
)
