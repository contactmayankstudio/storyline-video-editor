package com.video.engine

import android.app.Activity
import android.util.Log
import android.widget.Toast
import com.video.engine.timeline.TimelineManager
import com.video.engine.transition.Transition
import com.video.engine.transition.TransitionPanel
import com.video.engine.transition.TransitionStore
import com.video.engine.transition.TransitionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransitionController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val currentTimeMsProvider: () -> Long,
    private val onRecordTimelineUndo: () -> Unit,
    private val setCurrentTransitionId: (Long) -> Unit,
    private val resolveClipTiming: (Int) -> Pair<Long, Long>? = { null },
    private val onFocusPreviewTime: (Long) -> Unit = {},
    private val onTransitionChanged: () -> Unit = {},
    private val onPreviewTransitionPlayback: (Transition) -> Unit = {},
) {
    private fun resolveTransitionStartTimeMs(outgoingClipId: Int, fallbackTimeMs: Long = currentTimeMsProvider()): Long {
        val timing = resolveClipTiming(outgoingClipId)
        val clipStartMs = timing?.first?.coerceAtLeast(0L)
        val clipDurationMs = timing?.second?.coerceAtLeast(1L)
        return if (clipStartMs != null && clipDurationMs != null) {
            clipStartMs + clipDurationMs
        } else {
            fallbackTimeMs.coerceAtLeast(0L)
        }
    }

    private fun resolveCenteredTransitionStartTimeMs(
        outgoingClipId: Int,
        durationMs: Int,
        fallbackTimeMs: Long = currentTimeMsProvider(),
    ): Long {
        val cutTimeMs = resolveTransitionStartTimeMs(outgoingClipId, fallbackTimeMs)
        val halfDurationMs = durationMs.coerceIn(100, 2_000).toLong() / 2L
        return (cutTimeMs - halfDurationMs).coerceAtLeast(0L)
    }

    private fun focusPreviewOnTransition(transition: Transition, previewView: VideoPreviewView?) {
        val view = previewView ?: return
        val previewTimeMs = maxOf(0L, transition.startTimeMs + (transition.durationMs / 2L))
        val focusedViaEditor = runCatching { onFocusPreviewTime(previewTimeMs) }.isSuccess
        if (!focusedViaEditor) {
            runCatching { NativeBridge.seekToTime(view, previewTimeMs) }
        }
        timelineManagerProvider()?.updateDisplayedTime(previewTimeMs)
    }

    private fun buildTransition(
        outgoingClipId: Int,
        incomingClipId: Int,
        defaultType: TransitionType = TransitionType.CROSS,
        defaultDurationMs: Int = 450,
    ): Transition {
        val timelineManager = timelineManagerProvider()
        val existingTransition = timelineManager?.getTransitionByOutgoingClip(outgoingClipId)
        return existingTransition ?: run {
            val startTimeMs = resolveTransitionStartTimeMs(outgoingClipId, currentTimeMsProvider())
            Transition(
                type = defaultType,
                durationMs = defaultDurationMs,
                outgoingClipId = outgoingClipId,
                incomingClipId = incomingClipId,
                startTimeMs = startTimeMs,
            )
        }
    }

    private fun removeTransitionInternal(updatedTransition: Transition, previewView: VideoPreviewView?) {
        val timelineManager = timelineManagerProvider()
        if (timelineManager?.removeTransition(updatedTransition.id) != true) {
            return
        }
        onRecordTimelineUndo()
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    NativeBridge.executeCommand(
                        action = "TRANSITION",
                        params = mapOf(
                            "mode" to "remove",
                            "transitionId" to updatedTransition.id,
                        ),
                    )
                }.getOrNull()
            }
            previewView?.removeTransition(updatedTransition.id)
            setCurrentTransitionId(-1L)
            timelineManager?.notifyDatasetChanged()
            onTransitionChanged()
            Log.d("[TRANSITION]", "removed id=${updatedTransition.id}")
        }
    }

    private fun upsertTransitionInternal(updatedTransition: Transition, previewView: VideoPreviewView?) {
        val timelineManager = timelineManagerProvider()
        val isNewTransition = updatedTransition.id <= 0L
        updatedTransition.durationMs = updatedTransition.durationMs.coerceIn(100, 2_000)
        updatedTransition.startTimeMs = resolveCenteredTransitionStartTimeMs(
            outgoingClipId = updatedTransition.outgoingClipId,
            durationMs = updatedTransition.durationMs,
            fallbackTimeMs = updatedTransition.startTimeMs,
        )
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
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
            }
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
                return@launch
            }
            timelineManager?.upsertTransition(updatedTransition)
            onRecordTimelineUndo()
            setCurrentTransitionId(updatedTransition.id)
            timelineManager?.notifyDatasetChanged()
            onTransitionChanged()
            if (updatedTransition.type == TransitionType.NONE) {
                focusPreviewOnTransition(updatedTransition, previewView)
            } else {
                onPreviewTransitionPlayback(updatedTransition)
            }
            Log.d("[TRANSITION]", "updated type=${updatedTransition.type} duration=${updatedTransition.durationMs}ms")
        }
    }

    fun showTransitionEditor(outgoingClipId: Int, incomingClipId: Int) {
        val transition = buildTransition(outgoingClipId, incomingClipId)

        previewViewProvider()?.let { previewView ->
            TransitionPanel(
                activity = activity,
                previewView = previewView,
                transition = transition,
                onApplyToAll = { batchTransition ->
                    applyTransitionToAllCuts(batchTransition)
                },
            ) { updatedTransition ->
                if (updatedTransition.type == TransitionType.NONE) {
                    removeTransitionInternal(updatedTransition, previewView)
                } else {
                    upsertTransitionInternal(updatedTransition, previewView)
                }
            }.show()
        }
    }

    private fun applyTransitionToAllCuts(sourceTransition: Transition) {
        val previewView = previewViewProvider()
        val allTransitions = TransitionStore.all()
        allTransitions.forEach { trans ->
            trans.type = sourceTransition.type
            trans.durationMs = sourceTransition.durationMs
            upsertTransitionInternal(trans, previewView)
        }
        Toast.makeText(activity, "Transition applied to all cuts", Toast.LENGTH_SHORT).show()
    }

    fun applyQuickTransition(
        outgoingClipId: Int,
        incomingClipId: Int,
        type: TransitionType,
        durationMs: Int,
    ) {
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
        upsertTransitionInternal(transition, previewViewProvider())
    }

    fun removeTransitionByOutgoingClip(outgoingClipId: Int) {
        val transition = timelineManagerProvider()?.getTransitionByOutgoingClip(outgoingClipId) ?: return
        removeTransitionInternal(transition, previewViewProvider())
    }

    fun deleteTransition(transitionId: Long) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    NativeBridge.executeCommand(
                        action = "TRANSITION",
                        params = mapOf(
                            "mode" to "remove",
                            "transitionId" to transitionId,
                        ),
                    )
                }.getOrNull()
            }
            previewViewProvider()?.removeTransition(transitionId)
            if (timelineManagerProvider()?.removeTransition(transitionId) == true) {
                onRecordTimelineUndo()
                setCurrentTransitionId(-1L)
                onTransitionChanged()
                Log.d("[TRANSITION]", "deleted id=$transitionId")
            }
        }
    }
}
