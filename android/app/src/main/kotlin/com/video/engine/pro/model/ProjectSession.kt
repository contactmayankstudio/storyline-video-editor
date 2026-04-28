package com.video.engine.pro.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class TrackType {
    TEXT,
    OVERLAY,
    LAYER,
    VIDEO,
    AUDIO,
    ;

    fun displayName(): String = when (this) {
        TEXT -> "Text"
        OVERLAY -> "Overlay"
        LAYER -> "Layer"
        VIDEO -> "Video"
        AUDIO -> "Audio"
    }

    fun nativeRoleName(): String = when (this) {
        TEXT -> "TEXT"
        OVERLAY -> "OVERLAY"
        LAYER -> "LAYER"
        VIDEO -> "VIDEO"
        AUDIO -> "AUDIO"
    }

    fun isOverlayLike(): Boolean = this == OVERLAY || this == LAYER

    fun usesVisualMediaImport(): Boolean = this == VIDEO || isOverlayLike()

    fun defaultZOrder(clipCount: Int): Int = when (this) {
        TEXT -> 400 + clipCount
        OVERLAY -> 200 + clipCount
        LAYER -> 120 + clipCount
        AUDIO -> 0
        VIDEO -> clipCount - 1
    }

    companion object {
        fun displayOrder(): List<TrackType> = listOf(TEXT, OVERLAY, LAYER, VIDEO, AUDIO)

        fun fromNativeRole(trackTypeRaw: String?, zOrder: Int = 0): TrackType {
            fun inferVisualTrackFromZOrder(): TrackType {
                return when {
                    zOrder >= 200 -> OVERLAY
                    zOrder in 100 until 200 -> LAYER
                    else -> VIDEO
                }
            }
            return when (trackTypeRaw?.uppercase(Locale.US)) {
                "LAYER" -> LAYER
                "OVERLAY" -> if (zOrder in 100 until 200) LAYER else OVERLAY
                "TEXT", "TEXT_STICKER" -> TEXT
                "AUDIO" -> AUDIO
                "VIDEO", "MAINVIDEO", "MAIN_VIDEO", "", null -> inferVisualTrackFromZOrder()
                else -> inferVisualTrackFromZOrder()
            }
        }
    }
}

data class ClipSegment(
    val id: String,
    val sourcePath: String,
    val trackType: TrackType,
    val startTimeMs: Long,
    val durationMs: Long,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val zOrder: Int = 0,
    val isMuted: Boolean = false,
    val isHidden: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun endTimeMs(): Long = startTimeMs + durationMs

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("sourcePath", sourcePath)
            .put("trackType", trackType.name)
            .put("startTimeMs", startTimeMs)
            .put("durationMs", durationMs)
            .put("sourceInMs", sourceInMs)
            .put("sourceOutMs", sourceOutMs)
            .put("zOrder", zOrder)
            .put("isMuted", isMuted)
            .put("isHidden", isHidden)
            .put(
                "metadata",
                JSONObject().apply {
                    metadata.forEach { (key, value) -> put(key, value) }
                },
            )
    }

    companion object {
        fun fromJson(json: JSONObject): ClipSegment {
            val metadataJson = json.optJSONObject("metadata") ?: JSONObject()
            val metadata = buildMap {
                metadataJson.keys().forEach { key ->
                    put(key, metadataJson.optString(key))
                }
            }
            return ClipSegment(
                id = json.getString("id"),
                sourcePath = json.getString("sourcePath"),
                trackType = TrackType.valueOf(json.getString("trackType")),
                startTimeMs = json.getLong("startTimeMs"),
                durationMs = json.getLong("durationMs"),
                sourceInMs = json.optLong("sourceInMs", 0L),
                sourceOutMs = json.optLong("sourceOutMs", json.getLong("durationMs")),
                zOrder = json.optInt("zOrder", 0),
                isMuted = json.optBoolean("isMuted", false),
                isHidden = json.optBoolean("isHidden", false),
                metadata = metadata,
            )
        }
    }
}

data class TrackState(
    val id: String,
    val type: TrackType,
    val clips: List<ClipSegment>,
    val isLocked: Boolean = false,
    val isVisible: Boolean = true,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("type", type.name)
            .put("isLocked", isLocked)
            .put("isVisible", isVisible)
            .put(
                "clips",
                JSONArray().apply {
                    clips.forEach { put(it.toJson()) }
                },
            )
    }

    companion object {
        fun fromJson(json: JSONObject): TrackState {
            val clipsJson = json.optJSONArray("clips") ?: JSONArray()
            val clips = buildList {
                for (index in 0 until clipsJson.length()) {
                    add(ClipSegment.fromJson(clipsJson.getJSONObject(index)))
                }
            }
            return TrackState(
                id = json.getString("id"),
                type = TrackType.valueOf(json.getString("type")),
                clips = clips,
                isLocked = json.optBoolean("isLocked", false),
                isVisible = json.optBoolean("isVisible", true),
            )
        }
    }
}

data class ProjectSession(
    val projectId: String,
    val projectName: String,
    val durationMs: Long,
    val frameRate: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val tracks: List<TrackState>,
    val selectedClipId: String? = null,
    val playheadTimeMs: Long = 0L,
    val zoomPxPerSecond: Float = 120f,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("projectId", projectId)
            .put("projectName", projectName)
            .put("durationMs", durationMs)
            .put("frameRate", frameRate)
            .put("canvasWidth", canvasWidth)
            .put("canvasHeight", canvasHeight)
            .put("selectedClipId", selectedClipId)
            .put("playheadTimeMs", playheadTimeMs)
            .put("zoomPxPerSecond", zoomPxPerSecond.toDouble())
            .put(
                "tracks",
                JSONArray().apply {
                    tracks.forEach { put(it.toJson()) }
                },
            )
    }

    fun toJsonString(indentSpaces: Int = 2): String = toJson().toString(indentSpaces)

    companion object {
        fun fromJson(json: JSONObject): ProjectSession {
            val tracksJson = json.optJSONArray("tracks") ?: JSONArray()
            val tracks = buildList {
                for (index in 0 until tracksJson.length()) {
                    add(TrackState.fromJson(tracksJson.getJSONObject(index)))
                }
            }
            return ProjectSession(
                projectId = json.getString("projectId"),
                projectName = json.getString("projectName"),
                durationMs = json.getLong("durationMs"),
                frameRate = json.optInt("frameRate", 30),
                canvasWidth = json.optInt("canvasWidth", 1920),
                canvasHeight = json.optInt("canvasHeight", 1080),
                tracks = tracks,
                selectedClipId = json.optString("selectedClipId").takeIf { it.isNotEmpty() },
                playheadTimeMs = json.optLong("playheadTimeMs", 0L),
                zoomPxPerSecond = json.optDouble("zoomPxPerSecond", 120.0).toFloat(),
            )
        }

        fun fromJsonString(json: String): ProjectSession {
            return fromJson(JSONObject(json))
        }
    }
}
