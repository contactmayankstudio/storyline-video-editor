package com.video.engine

import com.video.engine.audio.AudioClipStore
import com.video.engine.effects.EffectParams
import com.video.engine.overlay.OverlayStore
import com.video.engine.overlay.TextOverlay
import com.video.engine.pro.model.TrackType
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.timeline.MultiClipTimeline
import com.video.engine.timeline.TimelineManager
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class LoadedUiState(
    val nextTextOverlayId: Int,
    val nextStickerId: Int,
    val nextAudioClipId: Int,
    val selectedAspectRatioIndex: Int?,
    val playheadTimeMs: Long?,
    val timelineZoomPxPerSecond: Float?,
    val trackVisibilityByType: Map<TrackType, Boolean>,
    val trackLockedByType: Map<TrackType, Boolean>,
)

data class NativeClipUiState(
    val id: Int,
    val trackType: TrackType,
    val trackLane: Int,
    val zOrder: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val sourcePath: String,
    val sourceDurationMs: Long = 0L,
    val effectParams: EffectParams = EffectParams(),
    val zoom: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val panXPx: Float = 0f,
    val panYPx: Float = 0f,
    val rotationDeg: Float = 0f,
)

class ProjectStateSerializer(
    private val timeline: MultiClipTimeline,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val editorStateProvider: () -> EditorState?,
    private val allTextOverlaysProvider: () -> List<TextOverlay>,
    private val nextTextOverlayIdProvider: () -> Int,
    private val nextStickerIdProvider: () -> Int,
    private val nextAudioClipIdProvider: () -> Int,
    private val selectedAspectRatioIndexProvider: () -> Int,
    private val playheadTimeMsProvider: () -> Long,
    private val timelineZoomPxPerSecondProvider: () -> Float,
    private val trackVisibilityProvider: () -> Map<TrackType, Boolean>,
    private val trackLockedProvider: () -> Map<TrackType, Boolean>,
    private val onAddOverlayView: (TextOverlay) -> Unit,
    private val onAddStickerOverlayView: (StickerClip) -> Unit,
    private val nativeClipStateProvider: () -> List<NativeClipUiState>,
    private val onRestoreNativeClipState: (List<NativeClipUiState>) -> Unit,
) {
    fun saveUiState(projectFile: File, projectName: String) {
        val timelineManager = timelineManagerProvider()
        val textOverlays = allTextOverlaysProvider()
        val stickers = StickerClipStore.all()
        val nativeClipStates = mergeNativeClipStates(
            primary = nativeClipStateProvider(),
            fallback = parseProjectFileNativeClipStates(projectFile),
        )
        val nativeClipsById = nativeClipStates.associateBy { it.id }
        val root = JSONObject()
        root.put("projectName", projectName)
        root.put("nextAudioClipId", maxOf(nextAudioClipIdProvider(), (AudioClipStore.all().maxOfOrNull { it.id } ?: 0) + 1))
        root.put("selectedAspectRatioIndex", selectedAspectRatioIndexProvider())
        root.put("playheadTimeMs", playheadTimeMsProvider())
        root.put("timelineZoomPxPerSecond", timelineZoomPxPerSecondProvider().toDouble())

        val clips = JSONArray()
        timeline.getClips().forEach { clip ->
            val nativeClip = nativeClipsById[clip.id]
            clips.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("layerIndex", nativeClip?.zOrder ?: timelineManager?.getClipLayerIndex(clip.id) ?: 0)
                    .put("trackType", nativeClip?.trackType?.name.orEmpty())
                    .put("trackLane", nativeClip?.trackLane ?: 0)
                    .put("zOrder", nativeClip?.zOrder ?: timelineManager?.getClipLayerIndex(clip.id) ?: 0)
                    .put("startTimeMs", nativeClip?.startTimeMs ?: 0L)
                    .put("durationMs", nativeClip?.durationMs ?: clip.durationMs)
                    .put("sourceInMs", nativeClip?.sourceInMs ?: 0L)
                    .put("sourceOutMs", nativeClip?.sourceOutMs ?: clip.durationMs)
                    .put("sourceDurationMs", nativeClip?.sourceDurationMs ?: 0L)
                    .put("sourcePath", nativeClip?.sourcePath.orEmpty())
                    .put("brightness", nativeClip?.effectParams?.brightness?.toDouble() ?: 0.0)
                    .put("contrast", nativeClip?.effectParams?.contrast?.toDouble() ?: 1.0)
                    .put("saturation", nativeClip?.effectParams?.saturation?.toDouble() ?: 1.0)
                    .put("visible", timelineManager?.getClipVisibility(clip.id) ?: true)
                    .put("zoom", nativeClip?.zoom?.toDouble() ?: 1.0)
                    .put("scaleX", nativeClip?.scaleX?.toDouble() ?: 1.0)
                    .put("scaleY", nativeClip?.scaleY?.toDouble() ?: 1.0)
                    .put("panXPx", nativeClip?.panXPx?.toDouble() ?: 0.0)
                    .put("panYPx", nativeClip?.panYPx?.toDouble() ?: 0.0)
                    .put("rotationDeg", nativeClip?.rotationDeg?.toDouble() ?: 0.0),
            )
        }
        root.put("clips", clips)
        root.put(
            "nativeClips",
            JSONArray().apply {
                nativeClipStates.forEach { state ->
                    put(
                        JSONObject()
                            .put("id", state.id)
                            .put("trackType", state.trackType.name)
                            .put("trackLane", state.trackLane)
                            .put("zOrder", state.zOrder)
                            .put("startTimeMs", state.startTimeMs)
                            .put("durationMs", state.durationMs)
                            .put("sourceInMs", state.sourceInMs)
                            .put("sourceOutMs", state.sourceOutMs)
                            .put("sourceDurationMs", state.sourceDurationMs)
                            .put("sourcePath", state.sourcePath)
                            .put("brightness", state.effectParams.brightness.toDouble())
                            .put("contrast", state.effectParams.contrast.toDouble())
                            .put("saturation", state.effectParams.saturation.toDouble())
                            .put("zoom", state.zoom.toDouble())
                            .put("scaleX", state.scaleX.toDouble())
                            .put("scaleY", state.scaleY.toDouble())
                            .put("panXPx", state.panXPx.toDouble())
                            .put("panYPx", state.panYPx.toDouble())
                            .put("rotationDeg", state.rotationDeg.toDouble()),
                    )
                }
            },
        )

        val texts = JSONArray()
        textOverlays.forEach { overlay ->
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
                    .put("backgroundColor", overlay.backgroundColor)
                    .put("strokeColor", overlay.strokeColor)
                    .put("strokeWidth", overlay.strokeWidth.toDouble())
                    .put("depthColor", overlay.depthColor)
                    .put("depthPx", overlay.depthPx.toDouble())
                    .put("shadowEnabled", overlay.shadowEnabled)
                    .put("shadowColor", overlay.shadowColor)
                    .put("shadowBlur", overlay.shadowBlur.toDouble())
                    .put("shadowOffsetX", overlay.shadowOffsetX.toDouble())
                    .put("shadowOffsetY", overlay.shadowOffsetY.toDouble())
                    .put("gradientEnabled", overlay.gradientEnabled)
                    .put("gradientStartColor", overlay.gradientStartColor)
                    .put("gradientEndColor", overlay.gradientEndColor)
                    .put("backgroundPadding", overlay.backgroundPadding.toDouble())
                    .put("backgroundCornerRadius", overlay.backgroundCornerRadius.toDouble())
                    .put("fontSize", overlay.fontSize.toDouble())
                    .put("fontName", overlay.fontName)
                    .put("bold", overlay.bold)
                    .put("italic", overlay.italic)
                    .put("underline", overlay.underline)
                    .put("allCaps", overlay.allCaps)
                    .put("layerIndex", overlay.layerIndex)
                    .put("visible", overlay.visible)
                    .put("aiTrackKeyframes", serializeAiPoseKeyframes(overlay.aiTrackKeyframes)),
            )
        }
        root.put("texts", texts)

        val stickersJson = JSONArray()
        stickers.forEach { sticker ->
            stickersJson.put(
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
                    .put("visible", sticker.visible)
                    .put("aiTrackKeyframes", serializeAiPoseKeyframes(sticker.aiTrackKeyframes)),
            )
        }
        root.put("stickers", stickersJson)
        val audioClipsJson = JSONArray()
        AudioClipStore.all().forEach { clip ->
            audioClipsJson.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("sourcePath", clip.sourcePath)
                    .put("displayName", clip.displayName)
                    .put("startTimeMs", clip.startTimeMs)
                    .put("durationMs", clip.durationMs)
                    .put("sourceInMs", clip.sourceInMs)
                    .put("sourceOutMs", clip.sourceOutMs)
                    .put("gain", clip.gain.toDouble())
                    .put("fadeInMs", clip.fadeInMs)
                    .put("fadeOutMs", clip.fadeOutMs)
                    .put("layerIndex", clip.layerIndex)
                    .put("visible", clip.visible)
                    .put("muted", clip.muted)
                    .put("peakMapPath", clip.peakMapPath.orEmpty())
                    .put("peakBucketMs", clip.peakBucketMs)
                    .put("peakLevelsCsv", clip.peakLevelsCsv),
            )
        }
        root.put("audioClips", audioClipsJson)
        root.put("nextTextOverlayId", maxOf(nextTextOverlayIdProvider(), (textOverlays.maxOfOrNull { it.id } ?: 0) + 1))
        root.put("nextStickerId", maxOf(nextStickerIdProvider(), (stickers.maxOfOrNull { it.id } ?: 0) + 1))
        root.put(
            "trackStates",
            JSONArray().apply {
                TrackType.displayOrder().forEach { trackType ->
                    put(
                        JSONObject()
                            .put("type", trackType.name)
                            .put("isVisible", trackVisibilityProvider()[trackType] ?: true)
                            .put("isLocked", trackLockedProvider()[trackType] ?: false),
                    )
                }
            },
        )
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
                nextAudioClipId = (AudioClipStore.all().maxOfOrNull { it.id } ?: 0) + 1,
                selectedAspectRatioIndex = null,
                playheadTimeMs = null,
                timelineZoomPxPerSecond = null,
                trackVisibilityByType = emptyMap(),
                trackLockedByType = emptyMap(),
            )
        }

        val timelineManager = timelineManagerProvider()
        val previewView = previewViewProvider()
        val editorState = editorStateProvider()
        val root = JSONObject(sidecar.readText())
        val trackVisibilityByType = mutableMapOf<TrackType, Boolean>()
        val trackLockedByType = mutableMapOf<TrackType, Boolean>()
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
        val restoredNativeClipState =
            parseNativeClipStates(root.optJSONArray("nativeClips"))
                .ifEmpty { parseNativeClipStates(clipState) }
                .ifEmpty { parseProjectFileNativeClipStates(projectFile) }
        if (restoredNativeClipState.isNotEmpty()) {
            onRestoreNativeClipState(restoredNativeClipState)
        }
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
                backgroundColor = item.optInt("backgroundColor", 0x00000000),
                strokeColor = item.optInt("strokeColor", 0xFF000000.toInt()),
                strokeWidth = item.optDouble("strokeWidth", 0.0).toFloat(),
                depthColor = item.optInt("depthColor", 0x99000000.toInt()),
                depthPx = item.optDouble("depthPx", 0.0).toFloat(),
                shadowEnabled = item.optBoolean("shadowEnabled", true),
                shadowColor = item.optInt("shadowColor", 0x99000000.toInt()),
                shadowBlur = item.optDouble("shadowBlur", 4.0).toFloat(),
                shadowOffsetX = item.optDouble("shadowOffsetX", 0.0).toFloat(),
                shadowOffsetY = item.optDouble("shadowOffsetY", 2.0).toFloat(),
                gradientEnabled = item.optBoolean("gradientEnabled", false),
                gradientStartColor = item.optInt("gradientStartColor", item.optInt("color", 0xFFFFFFFF.toInt())),
                gradientEndColor = item.optInt("gradientEndColor", 0xFF35C7FF.toInt()),
                backgroundPadding = item.optDouble("backgroundPadding", 0.0).toFloat(),
                backgroundCornerRadius = item.optDouble("backgroundCornerRadius", 0.0).toFloat(),
                fontSize = item.optDouble("fontSize", 36.0).toFloat(),
                fontName = item.optString("fontName").takeIf { it.isNotBlank() && it != "null" },
                bold = item.optBoolean("bold", false),
                italic = item.optBoolean("italic", false),
                underline = item.optBoolean("underline", false),
                allCaps = item.optBoolean("allCaps", false),
                layerIndex = item.optInt("layerIndex", editorState?.nextCompositeLayerIndex() ?: 0),
                visible = item.optBoolean("visible", true),
                aiTrackKeyframes = parseAiPoseKeyframes(item.optJSONArray("aiTrackKeyframes")),
            )
            OverlayStore.put(overlay)
            onAddOverlayView(overlay)
            previewView?.let { pv ->
                NativeBridge.addTextOverlay(pv, overlay)
                NativeBridge.updateTextOverlay(pv, overlay)
                pv.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
                NativeBridge.setTextOverlayBitmap(pv, overlay)
            }
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
                aiTrackKeyframes = parseAiPoseKeyframes(item.optJSONArray("aiTrackKeyframes")),
            )
            StickerClipStore.add(clip)
            onAddStickerOverlayView(clip)
        }

        val audioClipsJson = root.optJSONArray("audioClips") ?: JSONArray()
        for (i in 0 until audioClipsJson.length()) {
            val item = audioClipsJson.optJSONObject(i) ?: continue
            val clipId = item.optInt("id", -1)
            val path = item.optString("sourcePath", "")
            if (clipId > 0 && path.isNotBlank()) {
                val csv = item.optString("peakLevelsCsv", "")
                val peaks = if (csv.isNotBlank()) csv.split(',').mapNotNull { it.trim().toIntOrNull() } else emptyList()
                val clip = com.video.engine.audio.AudioClip(
                    id = clipId,
                    sourcePath = path,
                    displayName = item.optString("displayName", "Audio"),
                    startTimeMs = item.optLong("startTimeMs", 0L),
                    durationMs = item.optLong("durationMs", 1000L),
                    sourceInMs = item.optLong("sourceInMs", 0L),
                    sourceOutMs = item.optLong("sourceOutMs", item.optLong("durationMs", 1000L)),
                    gain = item.optDouble("gain", 1.0).toFloat(),
                    fadeInMs = item.optInt("fadeInMs", 0),
                    fadeOutMs = item.optInt("fadeOutMs", 0),
                    layerIndex = item.optInt("layerIndex", 0),
                    visible = item.optBoolean("visible", true),
                    muted = item.optBoolean("muted", false),
                    peakMapPath = item.optString("peakMapPath").takeIf { it.isNotBlank() && it != "null" },
                    peakBucketMs = item.optInt("peakBucketMs", 20),
                    peakLevels = peaks,
                    peakLevelsCsv = csv,
                )
                AudioClipStore.add(clip)
            }
        }
        if (audioClipsJson.length() > 0) {
            NativeBridge.syncAudioClips()
        }

        val trackStates = root.optJSONArray("trackStates") ?: JSONArray()
        for (i in 0 until trackStates.length()) {
            val item = trackStates.optJSONObject(i) ?: continue
            val trackType = runCatching { TrackType.valueOf(item.optString("type")) }.getOrNull() ?: continue
            trackVisibilityByType[trackType] = item.optBoolean("isVisible", true)
            trackLockedByType[trackType] = item.optBoolean("isLocked", false)
        }

        editorState?.normalizeAllLayerIndices()
        val restoredNextTextOverlayId = root.optInt(
            "nextTextOverlayId",
            (allTextOverlaysProvider().maxOfOrNull { it.id } ?: 0) + 1,
        )
        val restoredNextStickerId = root.optInt(
            "nextStickerId",
            (StickerClipStore.all().maxOfOrNull { it.id } ?: 0) + 1,
        )
        val restoredNextAudioClipId = root.optInt(
            "nextAudioClipId",
            (AudioClipStore.all().maxOfOrNull { it.id } ?: 0) + 1,
        )
        return LoadedUiState(
            nextTextOverlayId = maxOf(restoredNextTextOverlayId, (allTextOverlaysProvider().maxOfOrNull { it.id } ?: 0) + 1),
            nextStickerId = maxOf(restoredNextStickerId, (StickerClipStore.all().maxOfOrNull { it.id } ?: 0) + 1),
            nextAudioClipId = maxOf(restoredNextAudioClipId, (AudioClipStore.all().maxOfOrNull { it.id } ?: 0) + 1),
            selectedAspectRatioIndex = root.optInt("selectedAspectRatioIndex", -1).takeIf { it >= 0 },
            playheadTimeMs = root.optLong("playheadTimeMs", -1L).takeIf { it >= 0L },
            timelineZoomPxPerSecond = root.optDouble("timelineZoomPxPerSecond", -1.0)
                .takeIf { it > 0.0 }
                ?.toFloat(),
            trackVisibilityByType = trackVisibilityByType,
            trackLockedByType = trackLockedByType,
        )
    }

    private fun sidecarFileFor(projectFile: File): File {
        return File(projectFile.parentFile, "${projectFile.nameWithoutExtension}.ui.json")
    }

    private fun parseNativeClipStates(array: JSONArray?): List<NativeClipUiState> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseNativeClipState(item)?.let(::add)
            }
        }
    }

    private fun parseProjectFileNativeClipStates(projectFile: File): List<NativeClipUiState> {
        if (!projectFile.isFile || projectFile.length() <= 0L) return emptyList()
        return runCatching {
            parseNativeClipStates(JSONObject(projectFile.readText()).optJSONArray("clips"))
        }.getOrElse {
            emptyList()
        }
    }

    private fun mergeNativeClipStates(
        primary: List<NativeClipUiState>,
        fallback: List<NativeClipUiState>,
    ): List<NativeClipUiState> {
        if (primary.isEmpty()) return fallback
        if (fallback.isEmpty()) return primary
        val primaryIds = primary.mapTo(linkedSetOf()) { it.id }
        return primary + fallback.filterNot { it.id in primaryIds }
    }

    private fun parseNativeClipState(item: JSONObject): NativeClipUiState? {
        val clipId =
            when {
                item.has("id") -> item.optInt("id", -1)
                item.has("clipId") -> item.optInt("clipId", -1)
                else -> -1
            }
        if (clipId <= 0) return null
        val zOrder =
            when {
                item.has("zOrder") -> item.optInt("zOrder", 0)
                item.has("layerIndex") -> item.optInt("layerIndex", 0)
                else -> 0
            }
        val trackTypeRaw = item.optString("trackType", "")
        val sourcePath =
            item.optString("sourcePath")
                .ifBlank { item.optString("originalSourcePath") }
                .ifBlank { item.optString("mediaPath") }
        val normalizedTrackTypeRaw = trackTypeRaw.trim().uppercase(Locale.US)
        val parsedTrackType = runCatching { TrackType.valueOf(normalizedTrackTypeRaw) }.getOrNull()
        val resolvedTrackType =
            when {
                parsedTrackType != null -> parsedTrackType
                normalizedTrackTypeRaw in setOf("MAINVIDEO", "MAIN_VIDEO") -> TrackType.VIDEO
                else -> inferSavedClipTrackType(trackTypeRaw, zOrder, sourcePath)
            }
        val trackType =
            when {
                resolvedTrackType == TrackType.VIDEO && isAudioLikeSourcePath(sourcePath) -> TrackType.AUDIO
                else -> resolvedTrackType
            }
        val durationMs = item.optLong("durationMs", 1L).coerceAtLeast(1L)
        val sourceInMs = item.optLong("sourceInMs", 0L).coerceAtLeast(0L)
        val sourceOutMs =
            item.optLong("sourceOutMs", sourceInMs + durationMs)
                .coerceAtLeast(sourceInMs + 1L)
        return NativeClipUiState(
            id = clipId,
            trackType = trackType,
            trackLane = item.optInt("trackLane", 0).coerceAtLeast(0),
            zOrder = zOrder,
            startTimeMs = item.optLong("startTimeMs", 0L).coerceAtLeast(0L),
            durationMs = durationMs,
            sourceInMs = sourceInMs,
            sourceOutMs = sourceOutMs,
            sourcePath = sourcePath,
            sourceDurationMs = item.optLong("sourceDurationMs", 0L).coerceAtLeast(0L),
            effectParams = EffectParams(
                brightness = item.optDouble("brightness", 0.0).toFloat().coerceIn(-1f, 1f),
                contrast = item.optDouble("contrast", 1.0).toFloat().coerceIn(0f, 2f),
                saturation = item.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
            ),
            zoom = item.optDouble("zoom", 1.0).toFloat().coerceIn(0.1f, 10f),
            scaleX = item.optDouble("scaleX", 1.0).toFloat().coerceIn(0.1f, 10f),
            scaleY = item.optDouble("scaleY", 1.0).toFloat().coerceIn(0.1f, 10f),
            panXPx = item.optDouble("panXPx", 0.0).toFloat(),
            panYPx = item.optDouble("panYPx", 0.0).toFloat(),
            rotationDeg = item.optDouble("rotationDeg", 0.0).toFloat(),
        )
    }

    private fun inferSavedClipTrackType(trackTypeRaw: String?, zOrder: Int, sourcePath: String): TrackType {
        val inferred = TrackType.fromNativeRole(trackTypeRaw, zOrder)
        if (inferred == TrackType.VIDEO && isAudioLikeSourcePath(sourcePath)) {
            return TrackType.AUDIO
        }
        if (inferred == TrackType.VIDEO && isImageLikeSourcePath(sourcePath)) {
            return TrackType.LAYER
        }
        return inferred
    }

    private fun isAudioLikeSourcePath(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in setOf("aac", "amr", "flac", "m4a", "mp3", "ogg", "opus", "wav")
    }

    private fun isImageLikeSourcePath(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp")
    }

    private fun serializeAiPoseKeyframes(keyframes: List<AiPoseKeyframe>): JSONArray {
        return JSONArray().apply {
            keyframes.forEach { keyframe ->
                put(
                    JSONObject()
                        .put("timeMs", keyframe.timeMs)
                        .put("x", keyframe.x.toDouble())
                        .put("y", keyframe.y.toDouble())
                        .put("scale", keyframe.scale.toDouble())
                        .put("rotation", keyframe.rotation.toDouble())
                        .put("opacity", keyframe.opacity.toDouble()),
                )
            }
        }
    }

    private fun parseAiPoseKeyframes(array: JSONArray?): List<AiPoseKeyframe> {
        if (array == null) return emptyList()
        val parsed = mutableListOf<AiPoseKeyframe>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parsed += AiPoseKeyframe(
                timeMs = item.optLong("timeMs", 0L).coerceAtLeast(0L),
                x = item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                y = item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                scale = item.optDouble("scale", 1.0).toFloat().coerceIn(0.1f, 10f),
                rotation = item.optDouble("rotation", 0.0).toFloat(),
                opacity = item.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            )
        }
        return parsed.sortedBy { it.timeMs }
    }
}
