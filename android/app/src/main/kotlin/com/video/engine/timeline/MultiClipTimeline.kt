package com.video.engine.timeline

import android.util.Log

class MultiClipTimeline {
    companion object {
        private const val TAG = "[TimelineUI]"
    }

    private val clips = mutableListOf<TimelineClip>()

    fun addClip(clipId: Int, durationMs: Long, title: String = "Clip", startTimeMs: Long = 0L) {
        clips.add(TimelineClip(id = clipId, durationMs = durationMs, title = title, startTimeMs = startTimeMs))
        Log.d(TAG, "clip added id=$clipId")
    }

    fun removeClip(clipId: Int) {
        clips.removeAll { it.id == clipId }
        Log.d(TAG, "clip removed id=$clipId")
    }

    fun syncFromEngine(previewView: com.video.engine.VideoPreviewView?) {
        previewView ?: return
        val clipIds = previewView.getClipIds()
        clips.clear()
        for (id in clipIds) {
            clips.add(TimelineClip(id = id, durationMs = previewView.getClipDuration(id)))
        }
    }

    fun getClips(): List<TimelineClip> = clips

    fun getTotalDuration(): Long = clips.sumOf { it.durationMs }

    fun clear() {
        clips.clear()
    }
}
