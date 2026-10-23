package com.video.engine.pip

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

enum class PipLayoutPreset(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val zoom: Float,
    val scaleX: Float,
    val scaleY: Float,
    val panXNorm: Float, // -0.5 to 0.5 relative to screen width
    val panYNorm: Float, // -0.5 to 0.5 relative to screen height
) {
    FLOATING_BOTTOM_RIGHT("pip_br", "Floating Corner", "Bottom-right reaction box", "↘️", 1.0f, 0.38f, 0.38f, 0.28f, 0.28f),
    FLOATING_TOP_RIGHT("pip_tr", "Top-Right Corner", "Top-right facecam box", "↗️", 1.0f, 0.38f, 0.38f, 0.28f, -0.28f),
    FLOATING_BOTTOM_LEFT("pip_bl", "Bottom-Left Corner", "Bottom-left stream overlay", "↙️", 1.0f, 0.38f, 0.38f, -0.28f, 0.28f),
    DUET_SPLIT_HORIZONTAL("duet_lr", "Duet Left / Right", "50:50 side-by-side split screen", "👥", 1.0f, 0.50f, 1.00f, 0.25f, 0.00f),
    DUET_SPLIT_VERTICAL("duet_tb", "Duet Top / Bottom", "50:50 vertical reaction split", "↕️", 1.0f, 1.00f, 0.50f, 0.00f, 0.25f),
    CIRCLE_REACTION("pip_circle", "Reaction Bubble", "Compact 32% circle reaction", "🔘", 1.0f, 0.32f, 0.32f, 0.30f, 0.30f),
    FULLSCREEN_RESET("fullscreen", "Reset Fullscreen", "Full 100% overlay fit", "⛶", 1.0f, 1.00f, 1.00f, 0.00f, 0.00f),
}

object PipLayoutPresets {

    fun showPipLayoutSheet(
        context: Context,
        onSelectPreset: (preset: PipLayoutPreset) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10131A"))
                cornerRadii = floatArrayOf(
                    24f * density, 24f * density,
                    24f * density, 24f * density,
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1f), Color.parseColor("#1E2430"))
            }
            setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12f))
        }
        val title = TextView(context).apply {
            text = "📱 PIP & Duet Split Presets"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#8E99A8"))
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        val subtitle = TextView(context).apply {
            text = "Select 1-tap reaction layout or split-screen duet"
            textSize = 12f
            setTextColor(Color.parseColor("#7D8898"))
            setPadding(0, 0, 0, dp(12f))
        }
        root.addView(subtitle)

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320f)
            )
        }

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = PipLayoutPreset.values().size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        dp(56f)
                    ).apply { setMargins(0, dp(4f), 0, dp(4f)) }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#181D26"))
                        cornerRadius = 14f * density
                        setStroke(dp(1f), Color.parseColor("#262E3D"))
                    }
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                }
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val preset = PipLayoutPreset.values()[position]
                val layout = holder.itemView as LinearLayout
                layout.removeAllViews()

                val emojiView = TextView(layout.context).apply {
                    text = preset.icon
                    textSize = 22f
                    setPadding(0, 0, dp(12f), 0)
                }
                layout.addView(emojiView)

                val textContainer = LinearLayout(layout.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameView = TextView(layout.context).apply {
                    text = preset.title
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                val descView = TextView(layout.context).apply {
                    text = preset.description
                    textSize = 11f
                    setTextColor(Color.parseColor("#8E99A8"))
                }
                textContainer.addView(nameView)
                textContainer.addView(descView)
                layout.addView(textContainer)

                val applyBtn = TextView(layout.context).apply {
                    text = "APPLY"
                    textSize = 12f
                    setTextColor(Color.parseColor("#00E5FF"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0F2833"))
                        cornerRadius = 10f * density
                        setStroke(dp(1f), Color.parseColor("#00E5FF"))
                    }
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                }
                layout.addView(applyBtn)

                layout.setOnClickListener {
                    dialog.dismiss()
                    onSelectPreset(preset)
                }
            }
        }
        root.addView(recyclerView)

        dialog.setContentView(root)
        dialog.show()
    }
}
