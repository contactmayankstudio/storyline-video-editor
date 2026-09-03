package com.video.engine

import com.video.engine.pro.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineImportRouterTest {

    @Test
    fun detectMediaKind_identifiesVideo() {
        assertEquals(TimelineImportRouter.MediaKind.VIDEO, TimelineImportRouter.detectMediaKind("movie.mp4"))
        assertEquals(TimelineImportRouter.MediaKind.VIDEO, TimelineImportRouter.detectMediaKind("clip.mov"))
        assertEquals(TimelineImportRouter.MediaKind.VIDEO, TimelineImportRouter.detectMediaKind("video.mkv"))
        assertEquals(TimelineImportRouter.MediaKind.VIDEO, TimelineImportRouter.detectMediaKind("stream.webm"))
    }

    @Test
    fun detectMediaKind_identifiesImage() {
        assertEquals(TimelineImportRouter.MediaKind.IMAGE, TimelineImportRouter.detectMediaKind("photo.jpg"))
        assertEquals(TimelineImportRouter.MediaKind.IMAGE, TimelineImportRouter.detectMediaKind("image.png"))
        assertEquals(TimelineImportRouter.MediaKind.IMAGE, TimelineImportRouter.detectMediaKind("picture.webp"))
        assertEquals(TimelineImportRouter.MediaKind.IMAGE, TimelineImportRouter.detectMediaKind("shot.heic"))
    }

    @Test
    fun detectMediaKind_identifiesAudio() {
        assertEquals(TimelineImportRouter.MediaKind.AUDIO, TimelineImportRouter.detectMediaKind("song.mp3"))
        assertEquals(TimelineImportRouter.MediaKind.AUDIO, TimelineImportRouter.detectMediaKind("track.wav"))
        assertEquals(TimelineImportRouter.MediaKind.AUDIO, TimelineImportRouter.detectMediaKind("audio.m4a"))
        assertEquals(TimelineImportRouter.MediaKind.AUDIO, TimelineImportRouter.detectMediaKind("music.aac"))
        assertEquals(TimelineImportRouter.MediaKind.AUDIO, TimelineImportRouter.detectMediaKind("sound.flac"))
    }

    @Test
    fun resolveTrack_routesToCorrectTrackTypes() {
        // Video routing
        assertEquals(TrackType.VIDEO, TimelineImportRouter.resolveTrack(null, TimelineImportRouter.MediaKind.VIDEO))
        assertEquals(TrackType.VIDEO, TimelineImportRouter.resolveTrack(TrackType.VIDEO, TimelineImportRouter.MediaKind.VIDEO))

        // Image routing
        assertEquals(TrackType.LAYER, TimelineImportRouter.resolveTrack(null, TimelineImportRouter.MediaKind.IMAGE))
        assertEquals(TrackType.OVERLAY, TimelineImportRouter.resolveTrack(TrackType.OVERLAY, TimelineImportRouter.MediaKind.IMAGE))
        assertEquals(TrackType.LAYER, TimelineImportRouter.resolveTrack(TrackType.LAYER, TimelineImportRouter.MediaKind.IMAGE))

        // Audio routing
        assertEquals(TrackType.AUDIO, TimelineImportRouter.resolveTrack(null, TimelineImportRouter.MediaKind.AUDIO))
        assertEquals(TrackType.AUDIO, TimelineImportRouter.resolveTrack(TrackType.AUDIO, TimelineImportRouter.MediaKind.AUDIO))
        assertEquals(TrackType.AUDIO, TimelineImportRouter.resolveTrack(TrackType.VIDEO, TimelineImportRouter.MediaKind.AUDIO))
    }
}
