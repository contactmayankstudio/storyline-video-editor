package com.video.engine

import com.video.engine.audio.AudioClip
import com.video.engine.overlay.TextOverlay
import com.video.engine.stickers.StickerClip
import com.video.engine.timeline.TimelineManager

class EditorState(
    private val timelineManagerProvider: () -> TimelineManager?,
    private val overlaysProvider: () -> List<TextOverlay>,
    private val stickersProvider: () -> List<StickerClip>,
    private val audioClipsProvider: () -> List<AudioClip>,
    private val onMoveClipLayer: (Int, Int) -> Unit,
    private val onApplyTextState: (TextOverlay) -> Unit,
    private val onApplyStickerState: (StickerClip) -> Unit,
    private val onRefreshOverlayStack: () -> Unit,
) {
    private data class EditorLayerSnapshot(
        val clipLayerIndices: Map<Int, Int>,
        val clipVisibility: Map<Int, Boolean>,
        val textStates: Map<Int, Pair<Int, Boolean>>,
        val stickerStates: Map<Int, Pair<Int, Boolean>>,
        val audioStates: Map<Int, Pair<Int, Boolean>>,
    )

    private data class LayerSlot(
        val key: String,
        val index: Int,
    )

    private val undoStack = ArrayDeque<EditorLayerSnapshot>()
    private val redoStack = ArrayDeque<EditorLayerSnapshot>()

    fun buildLayerDescriptors(): List<LayerDescriptor> {
        val timelineManager = timelineManagerProvider()
        val descriptors = mutableListOf<LayerDescriptor>()
        timelineManager?.getClips()?.forEach { clip ->
            descriptors += LayerDescriptor(
                key = "clip-${clip.id}",
                kind = LayerKind.CLIP,
                id = clip.id,
                title = "Clip ${clip.id}",
                subtitle = "Layer ${timelineManager.getClipLayerIndex(clip.id)} • ${clip.getDurationString()}",
                layerIndex = timelineManager.getClipLayerIndex(clip.id),
                visible = timelineManager.getClipVisibility(clip.id),
            )
        }
        overlaysProvider().forEach { overlay ->
            descriptors += LayerDescriptor(
                key = "text-${overlay.id}",
                kind = LayerKind.TEXT,
                id = overlay.id,
                title = "Text: ${overlay.text}",
                subtitle = "Layer ${overlay.layerIndex} • at ${overlay.startTimeMs}ms",
                layerIndex = overlay.layerIndex,
                visible = overlay.visible,
            )
        }
        stickersProvider().forEach { sticker ->
            descriptors += LayerDescriptor(
                key = "sticker-${sticker.id}",
                kind = LayerKind.STICKER,
                id = sticker.id,
                title = if (sticker.type == "sticker") "Sticker ${sticker.stickerId}" else "Image Sticker",
                subtitle = "Layer ${sticker.layerIndex} • at ${sticker.startTimeMs}ms",
                layerIndex = sticker.layerIndex,
                visible = sticker.visible,
            )
        }
        audioClipsProvider().forEach { audioClip ->
            descriptors += LayerDescriptor(
                key = "audio-${audioClip.id}",
                kind = LayerKind.AUDIO,
                id = audioClip.id,
                title = "Audio: ${audioClip.displayName}",
                subtitle = "Layer ${audioClip.layerIndex} • at ${audioClip.startTimeMs}ms • ${if (audioClip.muted) "Muted" else "Audible"}",
                layerIndex = audioClip.layerIndex,
                visible = audioClip.visible,
            )
        }
        return descriptors
    }

    fun nextCompositeLayerIndex(): Int {
        return buildLayerSlots().maxOfOrNull { it.index }?.plus(1) ?: 0
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun recordLayerSnapshot() {
        undoStack.addLast(createSnapshot())
        while (undoStack.size > 40) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(createSnapshot())
        restoreSnapshot(snapshot)
        return true
    }

    fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(createSnapshot())
        restoreSnapshot(snapshot)
        return true
    }

    fun moveLayerKey(key: String, direction: Int) {
        val ordered = buildLayerSlots()
            .sortedWith(compareBy<LayerSlot> { it.index }.thenBy { it.key })
            .toMutableList()
        val currentIndex = ordered.indexOfFirst { it.key == key }
        if (currentIndex == -1) return
        val targetIndex = (currentIndex + direction).coerceIn(0, ordered.lastIndex)
        if (currentIndex == targetIndex) return
        val item = ordered.removeAt(currentIndex)
        ordered.add(targetIndex, item)
        applyNormalizedOrder(ordered.map { it.key })
    }

    fun normalizeAllLayerIndices() {
        val orderedKeys = buildLayerSlots()
            .sortedWith(compareBy<LayerSlot> { it.index }.thenBy { it.key })
            .map { it.key }
        applyNormalizedOrder(orderedKeys)
    }

    private fun buildLayerSlots(): List<LayerSlot> {
        return buildLayerDescriptors().map { LayerSlot(it.key, it.layerIndex) }
    }

    private fun applyNormalizedOrder(orderedKeys: List<String>) {
        val timelineManager = timelineManagerProvider()
        orderedKeys.forEachIndexed { index, key ->
            when {
                key.startsWith("clip-") -> {
                    val clipId = key.removePrefix("clip-").toIntOrNull() ?: return@forEachIndexed
                    timelineManager?.setClipLayerIndex(clipId, index)
                    onMoveClipLayer(clipId, index)
                }
                key.startsWith("text-") -> {
                    val overlayId = key.removePrefix("text-").toIntOrNull() ?: return@forEachIndexed
                    overlaysProvider().find { it.id == overlayId }?.layerIndex = index
                }
                key.startsWith("sticker-") -> {
                    val stickerId = key.removePrefix("sticker-").toIntOrNull() ?: return@forEachIndexed
                    stickersProvider().find { it.id == stickerId }?.layerIndex = index
                }
                key.startsWith("audio-") -> {
                    val audioId = key.removePrefix("audio-").toIntOrNull() ?: return@forEachIndexed
                    audioClipsProvider().find { it.id == audioId }?.layerIndex = index
                }
            }
        }
        overlaysProvider().forEach(onApplyTextState)
        stickersProvider().forEach(onApplyStickerState)
        onRefreshOverlayStack()
    }

    private fun createSnapshot(): EditorLayerSnapshot {
        val timelineManager = timelineManagerProvider()
        val clipLayerIndices =
            timelineManager?.getClips()?.associate { it.id to timelineManager.getClipLayerIndex(it.id) }.orEmpty()
        val clipVisibility =
            timelineManager?.getClips()?.associate { it.id to timelineManager.getClipVisibility(it.id) }.orEmpty()
        val textStates = overlaysProvider().associate { it.id to (it.layerIndex to it.visible) }
        val stickerStates = stickersProvider().associate { it.id to (it.layerIndex to it.visible) }
        val audioStates = audioClipsProvider().associate { it.id to (it.layerIndex to it.visible) }
        return EditorLayerSnapshot(
            clipLayerIndices = clipLayerIndices,
            clipVisibility = clipVisibility,
            textStates = textStates,
            stickerStates = stickerStates,
            audioStates = audioStates,
        )
    }

    private fun restoreSnapshot(snapshot: EditorLayerSnapshot) {
        val timelineManager = timelineManagerProvider()
        timelineManager?.getClips()?.forEach { clip ->
            snapshot.clipLayerIndices[clip.id]?.let { timelineManager.setClipLayerIndex(clip.id, it) }
            snapshot.clipVisibility[clip.id]?.let { timelineManager.setClipVisibility(clip.id, it) }
        }
        overlaysProvider().forEach { overlay ->
            snapshot.textStates[overlay.id]?.let { (layerIndex, visible) ->
                overlay.layerIndex = layerIndex
                overlay.visible = visible
            }
        }
        stickersProvider().forEach { sticker ->
            snapshot.stickerStates[sticker.id]?.let { (layerIndex, visible) ->
                sticker.layerIndex = layerIndex
                sticker.visible = visible
            }
        }
        audioClipsProvider().forEach { audioClip ->
            snapshot.audioStates[audioClip.id]?.let { (layerIndex, visible) ->
                audioClip.layerIndex = layerIndex
                audioClip.visible = visible
            }
        }
        overlaysProvider().forEach(onApplyTextState)
        stickersProvider().forEach(onApplyStickerState)
        onRefreshOverlayStack()
    }
}
