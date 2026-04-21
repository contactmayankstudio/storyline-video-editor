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
    fun showTransitionEditor(outgoingClipId: Int, incomingClipId: Int) {
        val timelineManager = timelineManagerProvider()
        val existingTransition = timelineManager?.getTransitionByOutgoingClip(outgoingClipId)
        val transition = existingTransition ?: run {
            val outgoingClip = timelineManager?.getClips()?.find { it.id == outgoingClipId }
            val startTimeMs = (outgoingClip?.endTimeMs ?: currentTimeMsProvider()) as? Long ?: currentTimeMsProvider()
            Transition(
                type = TransitionType.CROSS,
                durationMs = 300,
                outgoingClipId = outgoingClipId,
                incomingClipId = incomingClipId,
                startTimeMs = startTimeMs,
            )
        }

        previewViewProvider()?.let { previewView ->
            TransitionPanel(activity, previewView, transition) { updatedTransition ->
                if (updatedTransition.type == TransitionType.NONE) {
                    if (timelineManager?.removeTransition(updatedTransition.id) == true) {
                        onRecordTimelineUndo()
                        val result = runCatching {
                            NativeBridge.executeCommand(
                                action = "TRANSITION",
                                params = mapOf(
                                    "mode" to "remove",
                                    "transitionId" to updatedTransition.id,
                                ),
                            )
                        }.getOrNull()
                        if (result?.success != true) {
                            previewView.removeTransition(updatedTransition.id)
                        }
                        setCurrentTransitionId(-1L)
                        Log.d("[TRANSITION]", "removed id=${updatedTransition.id}")
                    }
                } else {
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
                    timelineManager?.upsertTransition(updatedTransition)
                    onRecordTimelineUndo()
                    setCurrentTransitionId(updatedTransition.id)
                    if (result?.success != true) {
                        previewView.updateTransition(updatedTransition)
                    }
                    Log.d("[TRANSITION]", "updated type=${updatedTransition.type} duration=${updatedTransition.durationMs}ms")
                }
                timelineManager?.notifyDatasetChanged()
            }.show()
        }
    }

    fun deleteTransition(transitionId: Long) {
        val result = runCatching {
            NativeBridge.executeCommand(
                action = "TRANSITION",
                params = mapOf(
                    "mode" to "remove",
                    "transitionId" to transitionId,
                ),
            )
        }.getOrNull()
        if (result?.success != true) {
            previewViewProvider()?.removeTransition(transitionId)
        }
        if (timelineManagerProvider()?.removeTransition(transitionId) == true) {
            onRecordTimelineUndo()
            setCurrentTransitionId(-1L)
            Log.d("[TRANSITION]", "deleted id=$transitionId")
        }
    }
}
