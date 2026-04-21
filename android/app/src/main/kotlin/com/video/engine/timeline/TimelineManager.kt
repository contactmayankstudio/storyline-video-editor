package com.video.engine.timeline

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.transition.Transition
import com.video.engine.transition.TransitionStore

class TimelineManager(
    private val recyclerView: RecyclerView?,
    private val timeDisplay: TextView?,
) {
    interface OnScrubListener {
        fun onScrub(timelineMs: Long)
    }

    private var scrubListener: OnScrubListener? = null
    private var transitionListener: ((Int, Int) -> Unit)? = null
    private var selectionListener: ((Int?) -> Unit)? = null
    private val clips = mutableListOf<TimelineClip>()
    private val adapter = TimelineAdapter(clips)
    private var selectedClipId: Int? = null
    private val clipLayerIndices = mutableMapOf<Int, Int>()
    private val clipVisibility = mutableMapOf<Int, Boolean>()
    private val undoStack = ArrayDeque<TimelineSnapshot>()
    private val redoStack = ArrayDeque<TimelineSnapshot>()

    private data class TimelineSnapshot(
        val clips: List<TimelineClip>,
        val selectedClipId: Int?,
        val transitions: List<Transition>,
        val clipLayerIndices: Map<Int, Int>,
        val clipVisibility: Map<Int, Boolean>,
    )

    init {
        recyclerView?.let { rv ->
            rv.layoutManager =
                LinearLayoutManager(rv.context, LinearLayoutManager.HORIZONTAL, false)
        }
        recyclerView?.adapter = adapter
        adapter.onClipClick = { clipId ->
            selectClip(clipId)
        }
        adapter.onTransitionClick = { outgoing, incoming ->
            transitionListener?.invoke(outgoing, incoming)
        }
        recyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx == 0) return
                dispatchScrub((recyclerView.computeHorizontalScrollOffset() / 0.12f).toLong())
            }
        })
    }

    fun setScrubListener(listener: OnScrubListener) {
        scrubListener = listener
    }

    fun setTransitionListener(listener: (Int, Int) -> Unit) {
        transitionListener = listener
    }

    fun setSelectionListener(listener: (Int?) -> Unit) {
        selectionListener = listener
    }

    fun getClips(): List<TimelineClip> = clips

    fun getTotalDurationMs(): Long = clips.sumOf { it.durationMs }

    fun getSelectedClipId(): Int? = selectedClipId

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun getTransitionByOutgoingClip(clipId: Int): Transition? {
        return TransitionStore.getByOutgoingClip(clipId).firstOrNull()
    }

    fun getClipLayerIndex(clipId: Int): Int = clipLayerIndices[clipId] ?: 0

    fun getClipVisibility(clipId: Int): Boolean = clipVisibility[clipId] ?: true

    fun setClipLayerIndex(clipId: Int, index: Int) {
        clipLayerIndices[clipId] = index
    }

    fun setClipVisibility(clipId: Int, visible: Boolean) {
        clipVisibility[clipId] = visible
    }

    fun removeClipState(clipId: Int) {
        clipLayerIndices.remove(clipId)
        clipVisibility.remove(clipId)
    }

    fun notifyDatasetChanged() {
        adapter.setSelectedClipId(selectedClipId)
        if (recyclerView != null) {
            adapter.notifyDataSetChanged()
        }
        updateTimeDisplay(getTotalDurationMs())
    }

    fun syncClips(
        newClips: List<TimelineClip>,
        recordHistory: Boolean = false,
        clearHistory: Boolean = false,
    ) {
        if (clearHistory) {
            undoStack.clear()
            redoStack.clear()
        } else if (recordHistory) {
            pushUndoSnapshot()
        }
        clips.clear()
        clips.addAll(newClips)
        val validIds = clips.map { it.id }.toSet()
        clipLayerIndices.keys.retainAll(validIds)
        clipVisibility.keys.retainAll(validIds)
        clips.forEachIndexed { index, clip ->
            clipLayerIndices.putIfAbsent(clip.id, index)
            clipVisibility.putIfAbsent(clip.id, true)
        }
        selectedClipId =
            when {
                clips.isEmpty() -> null
                selectedClipId != null && clips.any { it.id == selectedClipId } -> selectedClipId
                else -> null
            }
        notifyDatasetChanged()
        dispatchSelection()
    }

    fun selectClip(clipId: Int?) {
        val normalized = clipId?.takeIf { id -> clips.any { it.id == id } }
        if (selectedClipId == normalized) return
        selectedClipId = normalized
        adapter.setSelectedClipId(normalized)
        adapter.notifyDataSetChanged()
        dispatchSelection()
    }

    fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(createSnapshot())
        restoreSnapshot(snapshot)
        return true
    }

    fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(createSnapshot())
        restoreSnapshot(snapshot)
        return true
    }

    fun removeClip(clipId: Int): Boolean {
        if (clips.none { it.id == clipId }) return false
        pushUndoSnapshot()
        clips.removeAll { it.id == clipId }
        removeClipState(clipId)
        TransitionStore.removeByClip(clipId)
        if (selectedClipId == clipId) {
            selectedClipId = clips.firstOrNull()?.id
        }
        notifyDatasetChanged()
        dispatchSelection()
        return true
    }

    fun upsertTransition(transition: Transition) {
        pushUndoSnapshot()
        TransitionStore.add(transition)
        notifyDatasetChanged()
    }

    fun removeTransition(transitionId: Long): Boolean {
        val existing = TransitionStore.get(transitionId) ?: return false
        pushUndoSnapshot()
        TransitionStore.remove(existing.id)
        notifyDatasetChanged()
        return true
    }

    fun dispatchScrub(timelineMs: Long) {
        updateTimeDisplay(timelineMs)
        scrubListener?.onScrub(timelineMs)
    }

    fun updateDisplayedTime(timelineMs: Long) {
        updateTimeDisplay(timelineMs)
    }

    private fun updateTimeDisplay(timelineMs: Long) {
        timeDisplay
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.text = formatTime(timelineMs)
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun dispatchSelection() {
        selectionListener?.invoke(selectedClipId)
    }

    private fun createSnapshot(): TimelineSnapshot {
        return TimelineSnapshot(
            clips = clips.map { it.copy() },
            selectedClipId = selectedClipId,
            transitions = TransitionStore.all().map { it.copy() },
            clipLayerIndices = clipLayerIndices.toMap(),
            clipVisibility = clipVisibility.toMap(),
        )
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(createSnapshot())
        while (undoStack.size > 40) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    private fun restoreSnapshot(snapshot: TimelineSnapshot) {
        clips.clear()
        clips.addAll(snapshot.clips.map { it.copy() })
        TransitionStore.replaceAll(snapshot.transitions.map { it.copy() })
        clipLayerIndices.clear()
        clipLayerIndices.putAll(snapshot.clipLayerIndices)
        clipVisibility.clear()
        clipVisibility.putAll(snapshot.clipVisibility)
        selectedClipId =
            snapshot.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
                ?: clips.firstOrNull()?.id
        notifyDatasetChanged()
        dispatchSelection()
    }
}
