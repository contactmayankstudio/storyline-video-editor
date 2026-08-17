package com.video.engine

import com.video.engine.pro.model.TrackType
import java.util.Locale

object TimelineImportRouter {
    enum class MediaKind {
        VIDEO,
        IMAGE,
        AUDIO,
        TEXT,
        UNKNOWN,
    }

    private val videoExtensions =
        setOf(
            "mp4",
            "mov",
            "avi",
            "mkv",
            "webm",
            "m4v",
            "3gp",
            "3gpp",
            "3g2",
            "3gp2",
            "ts",
            "mts",
            "m2ts",
            "mpeg",
            "mpg",
            "mxf",
            "wmv",
            "flv",
            "hevc",
            "h265",
            "h264",
        )
    private val imageExtensions =
        setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "tif", "tiff", "heic", "heif", "avif")
    private val audioExtensions =
        setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr", "aiff", "wma")

    fun detectMediaKind(path: String, mimeType: String? = null): MediaKind {
        val normalizedMime = mimeType?.lowercase(Locale.US).orEmpty()
        when {
            normalizedMime.startsWith("video/") -> return MediaKind.VIDEO
            normalizedMime.startsWith("image/") -> return MediaKind.IMAGE
            normalizedMime.startsWith("audio/") -> return MediaKind.AUDIO
            normalizedMime.startsWith("text/") -> return MediaKind.TEXT
        }

        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            extension in videoExtensions -> MediaKind.VIDEO
            extension in imageExtensions -> MediaKind.IMAGE
            extension in audioExtensions -> MediaKind.AUDIO
            else -> MediaKind.UNKNOWN
        }
    }

    fun defaultTrackFor(mediaKind: MediaKind): TrackType =
        when (mediaKind) {
            MediaKind.VIDEO -> TrackType.VIDEO
            MediaKind.IMAGE -> TrackType.LAYER
            MediaKind.AUDIO -> TrackType.AUDIO
            MediaKind.TEXT -> TrackType.TEXT
            MediaKind.UNKNOWN -> TrackType.VIDEO
        }

    fun resolveTrack(selectedTrack: TrackType?, mediaKind: MediaKind): TrackType {
        val requested = selectedTrack ?: return defaultTrackFor(mediaKind)
        return when (mediaKind) {
            MediaKind.VIDEO ->
                when (requested) {
                    TrackType.VIDEO,
                    TrackType.OVERLAY,
                    TrackType.LAYER,
                    -> requested
                    else -> TrackType.VIDEO
                }

            MediaKind.IMAGE ->
                when (requested) {
                    TrackType.VIDEO -> TrackType.VIDEO
                    TrackType.OVERLAY -> TrackType.OVERLAY
                    TrackType.LAYER -> TrackType.LAYER
                    else -> TrackType.LAYER
                }

            MediaKind.AUDIO -> TrackType.AUDIO
            MediaKind.TEXT -> TrackType.TEXT
            MediaKind.UNKNOWN -> defaultTrackFor(mediaKind)
        }
    }

    fun importSuccessMessage(trackType: TrackType, mediaKind: MediaKind): String {
        val prefix =
            when (mediaKind) {
                MediaKind.AUDIO -> "Audio"
                MediaKind.IMAGE -> "Image"
                MediaKind.TEXT -> "Text"
                else -> "Clip"
            }
        return "$prefix added to ${trackType.displayName()} Track"
    }
}
