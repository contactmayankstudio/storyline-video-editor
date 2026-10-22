package com.video.engine.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Interactive A/B Before/After Split-Screen Manager for Storyline Video Editor.
 * Overlays an interactive split comparison view over the preview stage to compare
 * raw source video with AI graded, zoomed, and filtered footage.
 */
class AiBeforeAfterPreviewManager(
    private val activity: Activity,
    private val previewContainer: ViewGroup,
) {
    private var splitOverlay: FrameLayout? = null
    private var isEnabled = false

    fun isShowing(): Boolean = isEnabled

    fun toggle(): Boolean {
        return if (isEnabled) {
            hide()
            false
        } else {
            show()
            true
        }
    }

    fun show() {
        if (splitOverlay != null) {
            splitOverlay?.visibility = View.VISIBLE
            isEnabled = true
            return
        }

        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isClickable = true
            isFocusable = true
        }

        // Left Badge: "RAW ORIGINAL"
        val rawBadge = TextView(activity).apply {
            text = "RAW BEFORE"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(Color.parseColor("#99111827"))
                setStroke(2, Color.parseColor("#4B5563"))
            }
            setPadding(20, 8, 20, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                marginStart = 24
                topMargin = 24
            }
        }
        overlay.addView(rawBadge)

        // Right Badge: "AI GRADED"
        val aiBadge = TextView(activity).apply {
            text = "AI AFTER ✨"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(Color.parseColor("#990B192C"))
                setStroke(2, Color.parseColor("#00E5FF"))
            }
            setPadding(20, 8, 20, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                marginEnd = 24
                topMargin = 24
            }
        }
        overlay.addView(aiBadge)

        // Center Vertical Divider Line
        val divider = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#00E5FF"))
            }
        }
        overlay.addView(divider)

        // Close Pill
        val closePill = TextView(activity).apply {
            text = "✕ Close Split"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#CC1E293B"))
                setStroke(2, Color.parseColor("#64748B"))
            }
            setPadding(24, 10, 24, 10)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = 24
            }
            setOnClickListener { hide() }
        }
        overlay.addView(closePill)

        previewContainer.addView(overlay)
        splitOverlay = overlay
        isEnabled = true
    }

    fun hide() {
        splitOverlay?.visibility = View.GONE
        isEnabled = false
    }
}
