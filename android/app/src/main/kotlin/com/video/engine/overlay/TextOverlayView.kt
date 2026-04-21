package com.video.engine.overlay

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import kotlin.math.atan2

/**
 * Interactive UI element placed above the preview for editing text overlays.
 * Supports: drag (one finger), pinch scale, rotate (two fingers), delete button.
 */
class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "[TextOverlayView]"
    }

    private val textView: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 24f
        setShadowLayer(4f, 0f, 2f, Color.argb(120, 0, 0, 0))
        gravity = Gravity.CENTER
    }

    private val deleteButton: ImageButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        val pad = 8
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
    }

    /**
     * Callback when transform changes. Provides normalized center x,y (0..1), scale and rotation.
     */
    var onTransformChanged: ((Float, Float, Float, Float) -> Unit)? = null

    /**
     * Callback when delete is requested.
     */
    var onDeleteRequested: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val s = detector.scaleFactor
            scaleX *= s
            scaleY *= s
            // Notify during scale for live preview
            notifyTransformChanged()
            return true
        }
    })

    // Rotation tracking
    private var rotating = false
    private var initialAngle = 0.0

    init {
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val btnSize = 64
        val lp = LayoutParams(btnSize, btnSize, Gravity.END or Gravity.TOP)
        addView(deleteButton, lp)

        // Visual bounding
        setPadding(20, 20, 20, 20)
        setBackgroundResource(android.R.color.transparent)

        deleteButton.setOnClickListener {
            Log.d(TAG, "delete pressed")
            onDeleteRequested?.invoke()
        }
    }

    fun setText(text: String) {
        textView.text = text
    }

    fun showControls(show: Boolean) {
        deleteButton.visibility = if (show) View.VISIBLE else View.GONE
        // Add light bounding via background when selected
        alpha = if (show) 1.0f else 0.95f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = true
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                parent.requestDisallowInterceptTouchEvent(true)
                showControls(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    rotating = true
                    initialAngle = angle(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x
                lastY = event.y
                if (event.pointerCount <= 2) {
                    rotating = false
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (rotating && event.pointerCount >= 2) {
                    val current = angle(event)
                    val delta = Math.toDegrees(current - initialAngle).toFloat()
                    rotation = rotation + delta
                    initialAngle = current
                    // Live notify during rotation
                    notifyTransformChanged()
                } else if (isDragging) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    translationX += dx
                    translationY += dy
                    lastX = event.x
                    lastY = event.y
                    // Live notify during drag
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                rotating = false
                setLayerType(View.LAYER_TYPE_NONE, null)
                parent.requestDisallowInterceptTouchEvent(false)
                // Notify final transform
                notifyTransformChanged()
            }
        }

        return true
    }

    private fun angle(event: MotionEvent): Double {
        if (event.pointerCount >= 2) {
            val dx = event.getX(1) - event.getX(0)
            val dy = event.getY(1) - event.getY(0)
            return atan2(dy.toDouble(), dx.toDouble())
        }
        return 0.0
    }

    private fun notifyTransformChanged() {
        val parentW = (parent as? View)?.width ?: return
        val parentH = (parent as? View)?.height ?: return
        if (parentW == 0 || parentH == 0) return

        val centerX = (x + width * 0.5f) / parentW.toFloat()
        val centerY = (y + height * 0.5f) / parentH.toFloat()
        onTransformChanged?.invoke(centerX.coerceIn(0f,1f), centerY.coerceIn(0f,1f), scaleX, rotation)
    }
}
