package com.video.engine

import com.video.engine.audio.AudioClipStore
import com.video.engine.overlay.OverlayStore
import com.video.engine.overlay.TextOverlay
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.timeline.TimelineManager

class LayerController(
    private val editorStateProvider: () -> EditorState?,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val selectedLayerKeyProvider: () -> String?,
    private val onToggleClipVisibility: (Int, Boolean) -> Unit,
    private val onRemoveClip: (Int) -> Unit,
    private val onRemoveText: (Int) -> Unit,
    private val onRemoveSticker: (Int) -> Unit,
    private val onRemoveAudio: (Int) -> Unit,
    private val onApplyTextState: (TextOverlay) -> Unit,
    private val onApplyStickerState: (StickerClip) -> Unit,
    private val onRecordEditorUndo: () -> Unit,
    private val onRecordTimelineUndo: () -> Unit,
    private val onApplyTimelineShell: () -> Unit,
    private val onTimelineContentChanged: () -> Unit,
    private val onSelectLayer: (String) -> Unit,
) {
    fun buildLayerItem(descriptor: LayerDescriptor): LayerItem {
        val audioClip = if (descriptor.kind == LayerKind.AUDIO) AudioClipStore.get(descriptor.id) else null
        return LayerItem(
            id = descriptor.key,
            title = descriptor.title,
            subtitle = descriptor.subtitle,
            selected = descriptor.key == selectedLayerKeyProvider(),
            visible = descriptor.visible,
            onSelect = { onSelectLayer(descriptor.key) },
            secondaryIcon = when {
                audioClip == null -> null
                audioClip.muted -> android.R.drawable.ic_lock_silent_mode
                else -> android.R.drawable.ic_lock_silent_mode_off
            },
            onSecondaryAction = if (audioClip == null) {
                null
            } else {
                {
                    onRecordEditorUndo()
                    audioClip.muted = !audioClip.muted
                    onTimelineContentChanged()
                }
            },
            onToggleVisibility = {
                when (descriptor.kind) {
                    LayerKind.CLIP -> {
                        onRecordEditorUndo()
                        val timelineManager = timelineManagerProvider()
                        val next = !(timelineManager?.getClipVisibility(descriptor.id) ?: true)
                        timelineManager?.setClipVisibility(descriptor.id, next)
                        onToggleClipVisibility(descriptor.id, next)
                        onTimelineContentChanged()
                    }
                    LayerKind.TEXT -> {
                        onRecordEditorUndo()
                        OverlayStore.get(descriptor.id)?.let { overlay ->
                            overlay.visible = !overlay.visible
                            onApplyTextState(overlay)
                            onTimelineContentChanged()
                        }
                    }
                    LayerKind.STICKER -> {
                        onRecordEditorUndo()
                        StickerClipStore.all().find { it.id == descriptor.id }?.let { sticker ->
                            sticker.visible = !sticker.visible
                            onApplyStickerState(sticker)
                            onTimelineContentChanged()
                        }
                    }
                    LayerKind.AUDIO -> {
                        onRecordEditorUndo()
                        AudioClipStore.get(descriptor.id)?.let { clip ->
                            clip.visible = !clip.visible
                            onTimelineContentChanged()
                        }
                    }
                }
            },
            onMoveUp = {
                onRecordEditorUndo()
                editorStateProvider()?.moveLayerKey(descriptor.key, 1)
                onTimelineContentChanged()
            },
            onMoveDown = {
                onRecordEditorUndo()
                editorStateProvider()?.moveLayerKey(descriptor.key, -1)
                onTimelineContentChanged()
            },
            onDelete = {
                when (descriptor.kind) {
                    LayerKind.CLIP -> {
                        val timelineManager = timelineManagerProvider()
                        onRemoveClip(descriptor.id)
                        timelineManager?.removeClipState(descriptor.id)
                        if (timelineManager?.removeClip(descriptor.id) == true) {
                            onRecordTimelineUndo()
                            onApplyTimelineShell()
                            onTimelineContentChanged()
                        }
                    }
                    LayerKind.TEXT -> {
                        onRemoveText(descriptor.id)
                        OverlayStore.remove(descriptor.id)
                        onTimelineContentChanged()
                    }
                    LayerKind.STICKER -> {
                        onRemoveSticker(descriptor.id)
                        StickerClipStore.remove(descriptor.id)
                        onTimelineContentChanged()
                    }
                    LayerKind.AUDIO -> {
                        onRemoveAudio(descriptor.id)
                        AudioClipStore.remove(descriptor.id)
                        onTimelineContentChanged()
                    }
                }
                editorStateProvider()?.normalizeAllLayerIndices()
            },
        )
    }
}
