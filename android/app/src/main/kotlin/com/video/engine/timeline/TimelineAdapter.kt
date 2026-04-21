package com.video.engine.timeline

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.transition.TransitionStore

class TimelineAdapter(
    private val clips: MutableList<TimelineClip>,
    private var pixelsPerMs: Float = 0.12f,
) : RecyclerView.Adapter<TimelineAdapter.ClipViewHolder>() {
    var onClipClick: ((Int) -> Unit)? = null
    var onTransitionClick: ((Int, Int) -> Unit)? = null
    private var selectedClipId: Int? = null

    inner class ClipViewHolder(private val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        private val column = LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        private val label = TextView(container.context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
        }
        private val transitionChip = TextView(container.context).apply {
            setTextColor(0xFFD9F3FF.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 4)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0x33279EFF)
            }
            visibility = View.GONE
        }

        init {
            column.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            column.addView(
                transitionChip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8 },
            )
            container.addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        }

        fun bind(position: Int, clip: TimelineClip) {
            val widthPx = (clip.durationMs * pixelsPerMs).toInt().coerceAtLeast(140)
            container.layoutParams = RecyclerView.LayoutParams(
                widthPx,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = 12
                marginEnd = 12
            }
            container.background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(clip.color)
                setStroke(
                    if (clip.id == selectedClipId) 6 else 0,
                    if (clip.id == selectedClipId) 0xFFFFD166.toInt() else 0x00000000,
                )
            }
            label.text = "${clip.title}\n${clip.getDurationString()}"
            container.alpha = if (clip.id == selectedClipId) 1.0f else 0.92f
            container.setOnClickListener { onClipClick?.invoke(clip.id) }

            val nextClip = clips.getOrNull(position + 1)
            val transition = TransitionStore.getByOutgoingClip(clip.id).firstOrNull()
            if (nextClip != null) {
                transitionChip.visibility = View.VISIBLE
                transitionChip.text = transition?.let { "${it.type.name.lowercase()} ${it.durationMs}ms" } ?: "+ transition"
                transitionChip.setOnClickListener { onTransitionClick?.invoke(clip.id, nextClip.id) }
            } else {
                transitionChip.visibility = View.GONE
                transitionChip.setOnClickListener(null)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        return ClipViewHolder(FrameLayout(parent.context).apply {
            setPadding(16, 12, 16, 12)
        })
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        holder.bind(position, clips[position])
    }

    override fun getItemCount(): Int = clips.size

    fun setSelectedClipId(clipId: Int?) {
        selectedClipId = clipId
    }
}
