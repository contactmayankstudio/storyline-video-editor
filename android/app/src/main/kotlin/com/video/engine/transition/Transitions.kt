package com.video.engine.transition

import android.app.Activity
import com.video.engine.ModernSheet
import com.video.engine.VideoPreviewView

enum class TransitionType {
    NONE,
    FADE,
    CROSS,
    WIPE,
    SLIDE,
}

data class Transition(
    var id: Long = -1L,
    var type: TransitionType,
    var durationMs: Int,
    var outgoingClipId: Int,
    var incomingClipId: Int,
    var startTimeMs: Long,
)

object TransitionStore {
    private val transitions = linkedMapOf<Long, Transition>()
    private var nextId = 1L

    fun all(): List<Transition> = transitions.values.toList()

    fun get(id: Long): Transition? = transitions[id]

    fun add(transition: Transition) {
        if (transition.id <= 0) transition.id = nextId++
        transitions[transition.id] = transition
    }

    fun remove(id: Long) {
        transitions.remove(id)
    }

    fun getByOutgoingClip(clipId: Int): List<Transition> {
        return transitions.values.filter { it.outgoingClipId == clipId }
    }

    fun removeByClip(clipId: Int) {
        transitions.entries.removeAll { (_, transition) ->
            transition.outgoingClipId == clipId || transition.incomingClipId == clipId
        }
    }

    fun replaceAll(items: List<Transition>) {
        transitions.clear()
        var maxId = 0L
        items.forEach { transition ->
            transitions[transition.id] = transition
            if (transition.id > maxId) maxId = transition.id
        }
        nextId = maxOf(maxId + 1, 1L)
    }
}

class TransitionPanel(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val transition: Transition,
    private val onApply: (Transition) -> Unit,
) {
    fun show() {
        val current = when (transition.type) {
            TransitionType.FADE  -> 1
            TransitionType.CROSS -> 2
            TransitionType.WIPE  -> 3
            TransitionType.SLIDE -> 4
            else -> 0
        }
        ModernSheet.show(activity, "Transition") {
            chips("Type", listOf("None", "Fade", "Cross", "Wipe", "Slide"), current) { i, _ ->
                transition.type = when (i) {
                    1 -> TransitionType.FADE
                    2 -> TransitionType.CROSS
                    3 -> TransitionType.WIPE
                    4 -> TransitionType.SLIDE
                    else -> TransitionType.NONE
                }
                onApply(transition)
            }
            slider("Duration", 100f, 2000f, transition.durationMs.toFloat(), { "${it.toInt()}ms" }) {
                transition.durationMs = it.toInt()
                onApply(transition)
            }
        }
    }
}
