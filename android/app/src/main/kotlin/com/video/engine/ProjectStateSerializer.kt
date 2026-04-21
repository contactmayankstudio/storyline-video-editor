package com.video.engine

import com.video.engine.overlay.OverlayStore
import com.video.engine.overlay.TextOverlay
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.timeline.MultiClipTimeline
import com.video.engine.timeline.TimelineManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class LoadedUiState(
    val nextTextOverlayId: Int,
    val nextStickerId: Int,
)

class ProjectStateSerializer(
    private val timeline: MultiClipTimeline,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val editorStateProvider: () -> EditorState?,
    private val allTextOverlaysProvider: () -> List<TextOverlay>,
    private val nextTextOverlayIdProvider: () -> Int,
    private val nextStickerIdProvider: () -> Int,
    private val onAddOverlayView: (TextOverlay) -> Unit,
    private val onAddStickerOverlayView: (StickerClip) -> Unit,
) {
    fun saveUiState(projectFile: File, projectName: String) {
        val timelineManager = timelineManagerProvider()
        val root = JSONObject()
        root.put("projectName", projectName)

        val clips = JSONArray()
        timeline.getClips().forEach { clip ->
            clips.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("layerIndex", timelineManager?.getClipLayerIndex(clip.id) ?: 0)
                    .put("visible", timelineManager?.getClipVisibility(clip.id) ?: true),
            )
        }
        root.put("clips", clips)

        val texts = JSONArray()
        allTextOverlaysProvider().forEach { overlay ->
            texts.put(
                JSONObject()
                    .put("id", overlay.id)
                    .put("text", overlay.text)
                    .put("startTimeMs", overlay.startTimeMs)
                    .put("endTimeMs", overlay.endTimeMs)
                    .put("x", overlay.x.toDouble())
                    .put("y", overlay.y.toDouble())
                    .put("scale", overlay.scale.toDouble())
                    .put("rotation", overlay.rotation.toDouble())
                    .put("opacity", overlay.opacity.toDouble())
                    .put("color", overlay.color)
                    .put("fontSize", overlay.fontSize.toDouble())
                    .put("layerIndex", overlay.layerIndex)
                    .put("visible", overlay.visible),
            )
        }
        root.put("texts", texts)

        val stickers = JSONArray()
        StickerClipStore.all().forEach { sticker ->
            stickers.put(
                JSONObject()
                    .put("id", sticker.id)
                    .put("type", sticker.type)
                    .put("stickerId", sticker.stickerId)
                    .put("imagePath", sticker.imagePath)
                    .put("startTimeMs", sticker.startTimeMs)
                    .put("durationMs", sticker.durationMs)
                    .put("x", sticker.x.toDouble())
                    .put("y", sticker.y.toDouble())
                    .put("scale", sticker.scale.toDouble())
                    .put("rotation", sticker.rotation.toDouble())
                    .put("layerIndex", sticker.layerIndex)
                    .put("visible", sticker.visible),
            )
        }
        root.put("stickers", stickers)
        root.put("nextTextOverlayId", nextTextOverlayIdProvider())
        root.put("nextStickerId", nextStickerIdProvider())
        root.put(
            "nativeCommandJournal",
            JSONArray().apply {
                NativeBridge.snapshotCommandJournal().forEach { put(it) }
            },
        )
        root.put("nativeCommandTrace", NativeBridge.snapshotNativeCommandTelemetry())

        sidecarFileFor(projectFile).writeText(root.toString(2))
    }

    fun loadUiState(projectFile: File): LoadedUiState {
        val sidecar = sidecarFileFor(projectFile)
        if (!sidecar.exists()) {
            editorStateProvider()?.normalizeAllLayerIndices()
            return LoadedUiState(
                nextTextOverlayId = (allTextOverlaysProvider().maxOfOrNull { it.id } ?: 0) + 1,
                nextStickerId = (StickerClipStore.all().maxOfOrNull { it.id } ?: 0) + 1,
            )
        }

        val timelineManager = timelineManagerProvider()
        val previewView = previewViewProvider()
        val editorState = editorStateProvider()
        val root = JSONObject(sidecar.readText())
        NativeBridge.restoreCommandJournal(
            buildList {
                val journal = root.optJSONArray("nativeCommandJournal") ?: JSONArray()
                for (index in 0 until journal.length()) {
                    val entry = journal.optString(index)
                    if (entry.isNotBlank()) add(entry)
                }
            },
        )
        NativeBridge.clearNativeCommandTelemetry()

        val clipState = root.optJSONArray("clips") ?: JSONArray()
        for (i in 0 until clipState.length()) {
            val item = clipState.optJSONObject(i) ?: continue
            val clipId = item.optInt("id", -1)
            if (clipId <= 0) continue
            timelineManager?.setClipLayerIndex(clipId, item.optInt("layerIndex", i))
            timelineManager?.setClipVisibility(clipId, item.optBoolean("visible", true))
            previewView?.toggleLayerVisibility(clipId, timelineManager?.getClipVisibility(clipId) ?: true)
        }

        val texts = root.optJSONArray("texts") ?: JSONArray()
        for (i in 0 until texts.length()) {
            val item = texts.optJSONObject(i) ?: continue
            val overlay = TextOverlay(
                id = item.optInt("id", -1),
                text = item.optString("text", "Text"),
                startTimeMs = item.optInt("startTimeMs", 0),
                endTimeMs = item.optInt("endTimeMs", 10_000),
                x = item.optDouble("x", 0.5).toFloat(),
                y = item.optDouble("y", 0.5).toFloat(),
                scale = item.optDouble("scale", 1.0).toFloat(),
                rotation = item.optDouble("rotation", 0.0).toFloat(),
                opacity = item.optDouble("opacity", 1.0).toFloat(),
                color = item.optInt("color", 0xFFFFFFFF.toInt()),
                fontSize = item.optDouble("fontSize", 36.0).toFloat(),
                layerIndex = item.optInt("layerIndex", editorState?.nextCompositeLayerIndex() ?: 0),
                visible = item.optBoolean("visible", true),
            )
            OverlayStore.put(overlay)
            onAddOverlayView(overlay)
        }

        val stickers = root.optJSONArray("stickers") ?: JSONArray()
        for (i in 0 until stickers.length()) {
            val item = stickers.optJSONObject(i) ?: continue
            val clip = StickerClip(
                id = item.optInt("id", -1),
                type = item.optString("type", "sticker"),
                stickerId = item.optInt("stickerId", 0),
                imagePath = item.optString("imagePath").takeIf { it.isNotEmpty() && it != "null" },
                startTimeMs = item.optInt("startTimeMs", 0),
                durationMs = item.optInt("durationMs", 3000),
                x = item.optDouble("x", 0.5).toFloat(),
                y = item.optDouble("y", 0.5).toFloat(),
                scale = item.optDouble("scale", 1.0).toFloat(),
                rotation = item.optDouble("rotation", 0.0).toFloat(),
                layerIndex = item.optInt("layerIndex", editorState?.nextCompositeLayerIndex() ?: 0),
                visible = item.optBoolean("visible", true),
            )
            StickerClipStore.add(clip)
            onAddStickerOverlayView(clip)
        }

        editorState?.normalizeAllLayerIndices()
        return LoadedUiState(
            nextTextOverlayId = root.optInt("nextTextOverlayId", (allTextOverlaysProvider().maxOfOrNull { it.id } ?: 0) + 1),
            nextStickerId = root.optInt("nextStickerId", (StickerClipStore.all().maxOfOrNull { it.id } ?: 0) + 1),
        )
    }

    private fun sidecarFileFor(projectFile: File): File {
        return File(projectFile.parentFile, "${projectFile.nameWithoutExtension}.ui.json")
    }
}
