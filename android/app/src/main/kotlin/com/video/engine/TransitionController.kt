package com.video.engine

import android.app.Activity
import android.util.Log
import com.video.engine.timeline.TimelineManager
import com.video.engine.transition.Transition
import com.video.engine.transition.TransitionPanel
import com.video.engine.transition.TransitionType

class TransitionController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val currentTimeMsProvider: () -> Long,
    private val onRecordTimelineUndo: () -> Unit,
    private val setCurrentTransitionId: (Long) -> Unit,
) {
    private fun focusPreviewOnTransition(transition: Transition, previewView: VideoPreviewView?) {
        val view = previewView ?: return
        val previewTimeMs = maxOf(0L, transition.startTimeMs + (transition.durationMs / 2L))
        runCatching { NativeBridge.seekToTime(view, previewTimeMs) }
        timelineManagerProvider()?.updateDisplayedTime(previewTimeMs)
    }

    private fun buildTransition(
        outgoingClipId: Int,
        incomingClipId: Int,
        defaultType: TransitionType = TransitionType.CROSS,
        defaultDurationMs: Int = 300,
    ): Transition {
        val timelineManager = timelineManagerProvider()
        val existingTransition = timelineManager?.getTransitionByOutgoingClip(outgoingClipId)
        return existingTransition ?: run {
            val outgoingClip = timelineManager?.getClips()?.find { it.id == outgoingClipId }
            val startTimeMs = outgoingClip?.endTimeMs ?: currentTimeMsProvider()
            Transition(
                type = defaultType,
                durationMs = defaultDurationMs,
                outgoingClipId = outgoingClipId,
                incomingClipId = incomingClipId,
                startTimeMs = startTimeMs,
            )
        }
    }

    private fun removeTransitionInternal(updatedTransition: Transition, previewView: VideoPreviewView?): Boolean {
        val timelineManager = timelineManagerProvider()
        if (timelineManager?.removeTransition(updatedTransition.id) != true) {
            return false
        }
        onRecordTimelineUndo()
        runCatching {
            NativeBridge.executeCommand(
                action = "TRANSITION",
                params = mapOf(
                    "mode" to "remove",
                    "transitionId" to updatedTransition.id,
                ),
            )
        }.getOrNull()
        previewView?.removeTransition(updatedTransition.id)
        setCurrentTransitionId(-1L)
        timelineManager?.notifyDatasetChanged()
        Log.d("[TRANSITION]", "removed id=${updatedTransition.id}")
        return true
    }

    private fun upsertTransitionInternal(updatedTransition: Transition, previewView: VideoPreviewView?): Boolean {
        val timelineManager = timelineManagerProvider()
        val isNewTransition = updatedTransition.id <= 0L
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "TRANSITION",
                params = mapOf(
                    "mode" to if (isNewTransition) "add" else "update",
                    "transitionId" to updatedTransition.id,
                    "outgoingClipId" to updatedTransition.outgoingClipId,
                    "incomingClipId" to updatedTransition.incomingClipId,
                    "typeId" to updatedTransition.type.ordinal,
                    "durationMs" to updatedTransition.durationMs,
                    "startTimeMs" to updatedTransition.startTimeMs,
                ),
            )
        }.getOrNull()
        if (isNewTransition && result?.success == true) {
            updatedTransition.id = result.data.optLong("transitionId", updatedTransition.id)
        }
        previewView?.let { view ->
            if (updatedTransition.id > 0L) {
                view.updateTransition(updatedTransition)
            } else {
                val previewId = view.addTransition(updatedTransition)
                if (previewId > 0L) {
                    updatedTransition.id = previewId
                }
            }
        }
        if (isNewTransition && updatedTransition.id <= 0L) {
            Log.w("[TRANSITION]", "failed to resolve transition id for outgoing=${updatedTransition.outgoingClipId}")
            return false
        }
        timelineManager?.upsertTransition(updatedTransition)
        onRecordTimelineUndo()
        setCurrentTransitionId(updatedTransition.id)
        focusPreviewOnTransition(updatedTransition, previewView)
        timelineManager?.notifyDatasetChanged()
        Log.d("[TRANSITION]", "updated type=${updatedTransition.type} duration=${updatedTransition.durationMs}ms")
        return true
    }

    fun showTransitionEditor(outgoingClipId: Int, incomingClipId: Int) {
        val transition = buildTransition(outgoingClipId, incomingClipId)

        previewViewProvider()?.let { previewView ->
            TransitionPanel(activity, previewView, transition) { updatedTransition ->
                if (updatedTransition.type == TransitionType.NONE) {
                    removeTransitionInternal(updatedTransition, previewView)
                } else {
                    upsertTransitionInternal(updatedTransition, previewView)
                }
            }.show()
        }
    }

    fun applyQuickTransition(
        outgoingClipId: Int,
        incomingClipId: Int,
        type: TransitionType,
        durationMs: Int,
    ): Boolean {
        val transition = buildTransition(
            outgoingClipId = outgoingClipId,
            incomingClipId = incomingClipId,
            defaultType = type,
            defaultDurationMs = durationMs,
        ).apply {
            this.type = type
            this.durationMs = durationMs
            this.outgoingClipId = outgoingClipId
            this.incomingClipId = incomingClipId
        }
        return upsertTransitionInternal(transition, previewViewProvider())
    }

    fun removeTransitionByOutgoingClip(outgoingClipId: Int): Boolean {
        val transition = timelineManagerProvider()?.getTransitionByOutgoingClip(outgoingClipId) ?: return false
        return removeTransitionInternal(transition, previewViewProvider())
    }

    fun deleteTransition(transitionId: Long) {
        runCatching {
            NativeBridge.executeCommand(
                action = "TRANSITION",
                params = mapOf(
                    "mode" to "remove",
                    "transitionId" to transitionId,
                ),
            )
        }.getOrNull()
        previewViewProvider()?.removeTransition(transitionId)
        if (timelineManagerProvider()?.removeTransition(transitionId) == true) {
            onRecordTimelineUndo()
            setCurrentTransitionId(-1L)
            Log.d("[TRANSITION]", "deleted id=$transitionId")
        }
    }
}
