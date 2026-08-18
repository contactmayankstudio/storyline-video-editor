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
        nextId = maxOf(nextId, transition.id + 1)
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
    private val onApplyToAll: ((Transition) -> Unit)? = null,
    private val onApply: (Transition) -> Unit,
) {
    private fun typeIndex(type: TransitionType): Int = when (type) {
        TransitionType.FADE -> 1
        TransitionType.CROSS -> 2
        TransitionType.WIPE -> 3
        TransitionType.SLIDE -> 4
        else -> 0
    }

    private fun typeForIndex(index: Int): TransitionType = when (index) {
        1 -> TransitionType.FADE
        2 -> TransitionType.CROSS
        3 -> TransitionType.WIPE
        4 -> TransitionType.SLIDE
        else -> TransitionType.NONE
    }

    fun show() {
        val initialType = transition.type
        val initialDuration = transition.durationMs

        ModernSheet.showModal(
            context = activity,
            title = "Video Transition",
            showClose = true,
            showApply = true,
            onApply = {
                onApply(transition)
            },
            onCancel = {
                transition.type = initialType
                transition.durationMs = initialDuration
                onApply(transition)
            },
        ) {
            tabs(listOf("Featured", "3D", "Shape", "Motion", "Glitch", "Shake"), selected = 0) { tabIdx ->
                val chosenType = when (tabIdx) {
                    1 -> TransitionType.SLIDE
                    2 -> TransitionType.WIPE
                    3 -> TransitionType.CROSS
                    4 -> TransitionType.FADE
                    5 -> TransitionType.SLIDE
                    else -> TransitionType.CROSS
                }
                applyPreset(chosenType, transition.durationMs)
            }

            section("Transition Presets")
            val popularPresets = listOf(
                Triple("None", TransitionType.NONE, 0),
                Triple("Soft Cross", TransitionType.CROSS, 450),
                Triple("Quick Fade", TransitionType.FADE, 220),
                Triple("Smooth Wipe", TransitionType.WIPE, 500),
                Triple("Push Slide", TransitionType.SLIDE, 420),
                Triple("Dissolve", TransitionType.CROSS, 900),
            )
            val selectedPopular = popularPresets.indexOfFirst { (_, type, _) -> type == transition.type }
            selectableTileGrid(
                label = "Styles",
                options = popularPresets.map { it.first },
                selected = selectedPopular,
                columns = 3,
                dismissOnSelect = false,
            ) { index, _ ->
                val (_, type, durationMs) = popularPresets[index]
                applyPreset(type, if (durationMs > 0) durationMs else transition.durationMs)
            }

            divider()
            section("Duration")
            sliderWithBubble(
                label = "Transition Duration",
                min = 0.2f,
                max = 2.0f,
                value = (transition.durationMs / 1000f).coerceIn(0.2f, 2.0f),
                unit = "s",
                format = { "%.1f".format(it) },
            ) { sec ->
                transition.durationMs = (sec * 1000).toInt()
                onApply(transition)
            }

            if (onApplyToAll != null) {
                divider()
                chips(
                    label = "Batch Actions",
                    options = listOf("Apply to All Cuts"),
                    selected = -1,
                    dismissOnSelect = true,
                ) { _, _ ->
                    onApplyToAll.invoke(transition)
                }
            }
        }
    }

    private fun applyPreset(type: TransitionType, durationMs: Int) {
        transition.type = type
        transition.durationMs = durationMs.coerceIn(100, 2000)
        onApply(transition)
    }
}
