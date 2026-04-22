package com.video.engine.pro.model

object ProjectSessionMutator {

    private fun normalizeVideoMagnetic(clips: List<ClipSegment>): List<ClipSegment> {
        var cursorMs = 0L
        return clips
            .sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.id })
            .map { clip ->
                val updated = clip.copy(startTimeMs = cursorMs.coerceAtLeast(0L))
                cursorMs = updated.endTimeMs()
                updated
            }
    }

    private fun repackOverlayLanes(clips: List<ClipSegment>, zOrderBase: Int = 200): List<ClipSegment> {
        val laneEndTimes = mutableListOf<Long>()
        return clips
            .sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.zOrder }.thenBy { it.id })
            .map { clip ->
                val lane = laneEndTimes.indexOfFirst { clip.startTimeMs >= it }
                    .let { existing ->
                        if (existing >= 0) existing else laneEndTimes.size.also { laneEndTimes += 0L }
                    }
                laneEndTimes[lane] = clip.endTimeMs()
                clip.copy(
                    zOrder = zOrderBase + lane,
                    metadata = clip.metadata + ("subTrack" to (lane + 1).toString()),
                )
            }
    }

    private fun normalizeTextTopLayer(clips: List<ClipSegment>): List<ClipSegment> {
        return clips
            .sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.zOrder }.thenBy { it.id })
            .mapIndexed { index, clip ->
                val z = if (clip.zOrder >= 400) clip.zOrder else 400 + index
                clip.copy(zOrder = z)
            }
    }

    private fun applyProfessionalTrackPolicies(tracks: List<TrackState>): List<TrackState> {
        return tracks.map { track ->
            when (track.type) {
                TrackType.VIDEO -> track.copy(clips = normalizeVideoMagnetic(track.clips))
                TrackType.OVERLAY -> track.copy(clips = repackOverlayLanes(track.clips, zOrderBase = 200))
                TrackType.LAYER -> track.copy(clips = repackOverlayLanes(track.clips, zOrderBase = 120))
                TrackType.TEXT -> track.copy(clips = normalizeTextTopLayer(repackOverlayLanes(track.clips, zOrderBase = 400)))
                TrackType.AUDIO -> track.copy(
                    clips = track.clips.sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.id }),
                )
            }
        }
    }

    private fun withNormalizedTracks(session: ProjectSession, tracks: List<TrackState>): ProjectSession {
        val normalizedTracks = applyProfessionalTrackPolicies(tracks)
        val maxDuration = normalizedTracks
            .flatMap { it.clips }
            .maxOfOrNull { it.endTimeMs() }
            ?: 0L
        return session.copy(
            tracks = normalizedTracks,
            durationMs = maxOf(session.durationMs, maxDuration),
        )
    }

    fun moveClip(session: ProjectSession, clipId: String, newTimeMs: Long): ProjectSession {
        val updatedTracks = session.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                        clip.copy(startTimeMs = newTimeMs.coerceAtLeast(0L))
                    } else {
                        clip
                    }
                },
            )
        }
        return withNormalizedTracks(
            session,
            updatedTracks,
        )
    }

    fun addClip(session: ProjectSession, trackType: TrackType, clip: ClipSegment): ProjectSession {
        val clipWithTrackDefaults = when (trackType) {
            TrackType.TEXT -> clip.copy(zOrder = maxOf(clip.zOrder, 400))
            TrackType.OVERLAY -> clip.copy(zOrder = maxOf(clip.zOrder, 200))
            TrackType.LAYER -> clip.copy(zOrder = maxOf(clip.zOrder, 120))
            else -> clip
        }
        val updated = session.copy(
            tracks = session.tracks.map { track ->
                if (track.type == trackType) track.copy(clips = track.clips + clipWithTrackDefaults) else track
            },
            selectedClipId = clipWithTrackDefaults.id,
            durationMs = maxOf(session.durationMs, clipWithTrackDefaults.endTimeMs()),
        )
        return withNormalizedTracks(updated, updated.tracks)
    }

    fun setTrackVisibility(session: ProjectSession, trackType: TrackType, isVisible: Boolean): ProjectSession {
        return session.copy(
            tracks = session.tracks.map { track ->
                if (track.type == trackType) track.copy(isVisible = isVisible) else track
            },
        )
    }

    fun setTrackLocked(session: ProjectSession, trackType: TrackType, isLocked: Boolean): ProjectSession {
        return session.copy(
            tracks = session.tracks.map { track ->
                if (track.type == trackType) track.copy(isLocked = isLocked) else track
            },
        )
    }

    fun removeClip(session: ProjectSession, clipId: String): ProjectSession {
        val updatedTracks = session.tracks.map { track ->
            track.copy(clips = track.clips.filterNot { it.id == clipId })
        }
        val nextSelectedId = updatedTracks
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull()
            ?.id
        val nextDurationMs = updatedTracks
            .flatMap { it.clips }
            .maxOfOrNull { it.endTimeMs() }
            ?: 0L
        val updated = session.copy(
            tracks = updatedTracks,
            selectedClipId = nextSelectedId,
            durationMs = nextDurationMs,
            playheadTimeMs = session.playheadTimeMs.coerceAtMost(nextDurationMs),
        )
        return withNormalizedTracks(updated, updatedTracks)
    }

    fun duplicateClip(session: ProjectSession, clipId: String): ProjectSession {
        val sourceClip = session.tracks
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
            ?: return session

        val duplicatedClip = sourceClip.copy(
            id = "${sourceClip.trackType.name.lowercase()}_${System.currentTimeMillis()}",
            startTimeMs = sourceClip.endTimeMs() + 100L,
        )
        return addClip(session, sourceClip.trackType, duplicatedClip)
    }

    fun nudgeClip(session: ProjectSession, clipId: String, deltaMs: Long): ProjectSession {
        val currentClip = session.tracks
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
            ?: return session
        return moveClip(session, clipId, currentClip.startTimeMs + deltaMs)
    }

    fun updateClipTiming(
        session: ProjectSession,
        clipId: String,
        newStartTimeMs: Long,
        newDurationMs: Long,
        newSourceInMs: Long,
        newSourceOutMs: Long,
    ): ProjectSession {
        val clampedStartMs = newStartTimeMs.coerceAtLeast(0L)
        val clampedDurationMs = newDurationMs.coerceAtLeast(1L)
        val clampedSourceInMs = newSourceInMs.coerceAtLeast(0L)
        val clampedSourceOutMs = maxOf(newSourceOutMs, clampedSourceInMs + 1L)
        val updatedTracks = session.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                        clip.copy(
                            startTimeMs = clampedStartMs,
                            durationMs = clampedDurationMs,
                            sourceInMs = clampedSourceInMs,
                            sourceOutMs = clampedSourceOutMs,
                        )
                    } else {
                        clip
                    }
                },
            )
        }
        return withNormalizedTracks(session, updatedTracks)
    }
}
