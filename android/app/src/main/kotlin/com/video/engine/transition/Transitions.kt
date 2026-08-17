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
        ModernSheet.show(activity, "Transition") {
            var updateTypeSelection: (Int) -> Unit = {}
            var updatePopularSelection: (Int) -> Unit = {}
            fun applyAndSync(type: TransitionType, durationMs: Int) {
                applyPreset(type, durationMs)
                updateTypeSelection(typeIndex(type))
            }
            val popularPresets = listOf(
                Triple("Soft Cross", TransitionType.CROSS, 450),
                Triple("Quick Fade", TransitionType.FADE, 220),
                Triple("Smooth Wipe", TransitionType.WIPE, 500),
                Triple("Push Slide", TransitionType.SLIDE, 420),
                Triple("Long Dissolve", TransitionType.CROSS, 900),
                Triple("Clean Cut", TransitionType.NONE, transition.durationMs.coerceIn(100, 2000)),
            )
            val selectedPopular = popularPresets.indexOfFirst { (_, type, _) -> type == transition.type }
            updatePopularSelection = selectableTileGrid(
                "Popular",
                popularPresets.map { it.first },
                selected = selectedPopular,
                columns = 3,
                dismissOnSelect = false,
            ) { index, _ ->
                val (_, type, durationMs) = popularPresets[index]
                applyAndSync(type, durationMs)
                updatePopularSelection(index)
            }
            updateTypeSelection = selectableChips(
                "Type",
                listOf("None", "Fade", "Cross", "Wipe", "Slide"),
                typeIndex(transition.type),
                dismissOnSelect = false,
            ) { i, _ ->
                applyPreset(typeForIndex(i), transition.durationMs.coerceIn(100, 2000))
                updatePopularSelection(-1)
            }
            chips(
                "Timing",
                listOf("Fast 180", "Short 250", "Smooth 450", "Slow 700", "Cinematic 1000", "Max 1500"),
                selected = -1,
                dismissOnSelect = false,
            ) { _, option ->
                val durationMs = option.substringAfterLast(' ').toIntOrNull() ?: transition.durationMs
                val type = transition.type.takeIf { it != TransitionType.NONE } ?: TransitionType.CROSS
                applyPreset(type, durationMs)
                updateTypeSelection(typeIndex(type))
                updatePopularSelection(-1)
            }
            slider("Duration", 100f, 2000f, transition.durationMs.toFloat(), { "${it.toInt()}ms" }) {
                transition.durationMs = it.toInt()
                onApply(transition)
            }
        }
    }

    private fun applyPreset(type: TransitionType, durationMs: Int) {
        transition.type = type
        transition.durationMs = durationMs.coerceIn(100, 2000)
        onApply(transition)
    }
}
