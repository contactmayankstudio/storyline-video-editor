package com.video.engine.transition

import android.app.Activity
import android.widget.Toast
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
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "▶️ Replay Live Preview",
                        isPrimary = true,
                        onClick = { onApply(transition) },
                    ),
                    ModernSheet.CompactAction(
                        title = "✂️ Remove / Cut",
                        isDestructive = true,
                        onClick = { applyPreset(TransitionType.NONE, 0) },
                    ),
                ),
            )
            divider()

            categories(listOf("Popular", "Cinematic", "Fast Action"), selected = 0) { tabIdx, _ ->
                val (chosenType, chosenDuration) = when (tabIdx) {
                    0 -> Pair(TransitionType.CROSS, 450)
                    1 -> Pair(TransitionType.CROSS, 900)
                    2 -> Pair(TransitionType.SLIDE, 320)
                    else -> Pair(TransitionType.CROSS, 450)
                }
                applyPreset(chosenType, chosenDuration)
            }

            section("Transition Presets")
            val popularPresets = listOf(
                Triple("None", TransitionType.NONE, 0),
                Triple("Soft Cross", TransitionType.CROSS, 450),
                Triple("Quick Fade", TransitionType.FADE, 250),
                Triple("Smooth Wipe", TransitionType.WIPE, 480),
                Triple("Push Slide", TransitionType.SLIDE, 400),
                Triple("Film Dissolve", TransitionType.CROSS, 850),
            )
            val selectedPopular = popularPresets.indexOfFirst { (_, type, _) -> type == transition.type }
            val transitionCards = popularPresets.map { (label, type, _) ->
                val gradient = when (type) {
                    TransitionType.NONE -> Pair(android.graphics.Color.parseColor("#1C222C"), android.graphics.Color.parseColor("#252D3A"))
                    TransitionType.FADE -> Pair(android.graphics.Color.parseColor("#111827"), android.graphics.Color.parseColor("#F97316"))
                    TransitionType.CROSS -> Pair(android.graphics.Color.parseColor("#1E3A8A"), android.graphics.Color.parseColor("#3B82F6"))
                    TransitionType.WIPE -> Pair(android.graphics.Color.parseColor("#065F46"), android.graphics.Color.parseColor("#10B981"))
                    TransitionType.SLIDE -> Pair(android.graphics.Color.parseColor("#4C1D95"), android.graphics.Color.parseColor("#8B5CF6"))
                }
                val emoji = when (type) {
                    TransitionType.NONE -> "✂️"
                    TransitionType.FADE -> "🎬"
                    TransitionType.CROSS -> "✨"
                    TransitionType.WIPE -> "🌊"
                    TransitionType.SLIDE -> "➡️"
                }
                ModernSheet.VisualCard(
                    id = label,
                    label = label,
                    emoji = emoji,
                    swatchGradient = gradient,
                )
            }
            visualCardsGrid(
                cards = transitionCards,
                selected = selectedPopular,
                columns = 3,
                dismissOnSelect = false,
            ) { index, _ ->
                val (label, type, durationMs) = popularPresets[index]
                applyPreset(type, if (durationMs > 0) durationMs else transition.durationMs)
                Toast.makeText(activity, "🎬 Transition: $label applied", Toast.LENGTH_SHORT).show()
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
                compactActionRow(
                    listOf(
                        ModernSheet.CompactAction(
                            title = "Apply to All Cuts",
                            isPrimary = true,
                            dismissOnClick = true,
                            onClick = { onApplyToAll.invoke(transition) },
                        ),
                    ),
                )
            }
        }
    }

    private fun applyPreset(type: TransitionType, durationMs: Int) {
        transition.type = type
        transition.durationMs = durationMs.coerceIn(100, 2000)
        onApply(transition)
    }
}
