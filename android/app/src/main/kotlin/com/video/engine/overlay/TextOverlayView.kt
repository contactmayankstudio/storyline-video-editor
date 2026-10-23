package com.video.engine.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.abs

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
        isSingleLine = false
        maxLines = 4
        includeFontPadding = false
        alpha = 1f
    }

    private val deleteButton: ImageButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        val pad = 8
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
    }
    private var lastStyleKey: String? = null

    /**
     * Callback when transform changes. Provides normalized center x,y (0..1), scale and rotation.
     */
    var onTransformChanged: ((Float, Float, Float, Float) -> Unit)? = null
    var onTransformCommitted: ((Float, Float, Float, Float) -> Unit)? = null

    /**
     * Callback when delete is requested.
     */
    var onDeleteRequested: (() -> Unit)? = null

    /**
     * Callback when this overlay view is touched/selected.
     */
    var onSelected: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var transformNotifyScheduled = false
    private var pinchStartScale = 1.0f
    private var pinchStartSpan = 1.0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchStartScale = scaleX
            pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
            isDragging = false
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (pinchStartScale * (detector.currentSpan / pinchStartSpan)).coerceIn(0.1f, 10.0f)
            scaleX = newScale
            scaleY = newScale
            syncControlChromeScale()
            clampToParentBounds()
            notifyTransformChanged()
            return true
        }
    })

    // Rotation tracking
    private var rotating = false
    private var initialAngle = 0.0

    init {
        clipChildren = false
        clipToPadding = false
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val btnSize = dp(34)
        val lp = LayoutParams(btnSize, btnSize, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(24)
            rightMargin = dp(4)
        }
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
        if (textView.text.toString() == text) return
        textView.text = text
        requestLayout()
    }

    fun applyStyle(overlay: TextOverlay, currentTimeMs: Long = -1L) {
        val activeWordIndex = if (currentTimeMs >= 0L) overlay.activeWordIndexAt(currentTimeMs) else -1
        val styleKey = listOf(
            overlay.text,
            overlay.color,
            overlay.backgroundColor,
            overlay.strokeColor,
            overlay.strokeWidth,
            overlay.depthColor,
            overlay.depthPx,
            overlay.shadowEnabled,
            overlay.shadowColor,
            overlay.shadowBlur,
            overlay.shadowOffsetX,
            overlay.shadowOffsetY,
            overlay.gradientEnabled,
            overlay.gradientStartColor,
            overlay.gradientEndColor,
            overlay.backgroundPadding,
            overlay.backgroundCornerRadius,
            overlay.fontSize,
            overlay.fontName.orEmpty(),
            overlay.bold,
            overlay.italic,
            overlay.underline,
            overlay.allCaps,
            overlay.captionStyle.orEmpty(),
            overlay.highlightColor,
            overlay.activeWordScale,
            overlay.glowColor,
            activeWordIndex,
        ).joinToString("|")
        if (styleKey == lastStyleKey) return
        lastStyleKey = styleKey
        val displayText = if (overlay.allCaps) {
            overlay.text.uppercase(Locale.getDefault())
        } else {
            overlay.text
        }
        val words = (if (overlay.wordCues.isNotEmpty()) overlay.wordCues.map { it.word } else displayText.trim().split(Regex("\\s+"))).filter { it.isNotBlank() }
        if (words.isNotEmpty() && (overlay.captionStyle != null || overlay.wordCues.isNotEmpty())) {
            val builder = android.text.SpannableStringBuilder()
            words.forEachIndexed { idx, word ->
                val displayWord = if (overlay.allCaps) word.uppercase(Locale.getDefault()) else word
                val start = builder.length
                builder.append(displayWord)
                val end = builder.length

                val isActive = (idx == activeWordIndex)
                val isPast = (idx < activeWordIndex)

                if (isActive) {
                    val wColor = when {
                        overlay.captionStyle == "MRBEAST" && (idx % 2 == 1) -> 0xFFFF2A55.toInt() // Pop Red
                        overlay.captionStyle == "MRBEAST" -> 0xFF00FF66.toInt() // Pop Green
                        overlay.captionStyle == "CYBERPUNK" -> 0xFFFF007F.toInt() // Neon Magenta pop
                        else -> overlay.highlightColor
                    }
                    builder.setSpan(android.text.style.ForegroundColorSpan(wColor), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val bounce = overlay.activeWordScale.coerceIn(1.0f, 1.4f)
                    builder.setSpan(android.text.style.RelativeSizeSpan(bounce), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (overlay.captionStyle == "KARAOKE" && isPast) {
                    builder.setSpan(android.text.style.ForegroundColorSpan(overlay.highlightColor), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (overlay.captionStyle == "KARAOKE") {
                    builder.setSpan(android.text.style.ForegroundColorSpan(Color.argb(128, 255, 255, 255)), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    builder.setSpan(android.text.style.ForegroundColorSpan(if (overlay.gradientEnabled) overlay.gradientStartColor else overlay.color), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                if (idx < words.lastIndex) {
                    builder.append(" ")
                }
            }
            textView.setText(builder, TextView.BufferType.SPANNABLE)
        } else {
            if (textView.text.toString() != displayText) {
                textView.text = displayText
            }
            textView.setTextColor(if (overlay.gradientEnabled) overlay.gradientStartColor else overlay.color)
        }
        textView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (overlay.fontSize * 0.66f).coerceIn(12f, 54f),
        )
        textView.typeface = resolveTypeface(overlay.fontName, overlay.bold, overlay.italic)
        textView.paintFlags =
            if (overlay.underline) {
                textView.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            } else {
                textView.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        if ((overlay.backgroundColor ushr 24) != 0) {
            textView.background = GradientDrawable().apply {
                setColor(overlay.backgroundColor)
                cornerRadius =
                    if (overlay.backgroundCornerRadius > 0f) {
                        overlay.backgroundCornerRadius
                    } else {
                        dp(8).toFloat()
                    }
            }
            val padding = overlay.backgroundPadding.takeIf { it > 0f }?.toInt()?.coerceIn(0, dp(48)) ?: dp(10)
            val verticalPadding = (padding * 0.65f).toInt().coerceAtLeast(dp(4))
            textView.setPadding(padding, verticalPadding, padding, verticalPadding)
        } else {
            textView.background = null
            textView.setPadding(0, 0, 0, 0)
        }
        val effectiveGlow = if (overlay.captionStyle != null && (overlay.glowColor ushr 24) != 0) {
            overlay.glowColor
        } else null
        val shadowColor = when {
            effectiveGlow != null -> effectiveGlow
            (overlay.shadowColor ushr 24) != 0 -> overlay.shadowColor
            isLightColor(overlay.color) -> Color.argb(150, 0, 0, 0)
            else -> Color.argb(190, 255, 255, 255)
        }
        if (effectiveGlow != null) {
            textView.setShadowLayer(16f, 0f, 0f, effectiveGlow)
        } else if (overlay.shadowEnabled && overlay.shadowBlur > 0f) {
            textView.setShadowLayer(
                overlay.shadowBlur.coerceIn(0f, 40f),
                overlay.shadowOffsetX.coerceIn(-40f, 40f),
                overlay.shadowOffsetY.coerceIn(-40f, 40f),
                shadowColor,
            )
        } else {
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        requestLayout()
    }

    fun setMaxTextWidth(maxWidthPx: Int) {
        if (maxWidthPx > 0) {
            textView.maxWidth = maxWidthPx
            requestLayout()
        }
    }

    fun showControls(show: Boolean) {
        deleteButton.visibility = if (show) View.VISIBLE else View.GONE
        background = null
        syncControlChromeScale()
        if (show) {
            deleteButton.bringToFront()
        }
        alpha = 1.0f
    }

    fun syncControlChromeScale() {
        val inverseScale = 1f / abs(scaleX).coerceIn(0.35f, 6f)
        deleteButton.scaleX = inverseScale
        deleteButton.scaleY = inverseScale
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = true
                bringToFront()
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                parent.requestDisallowInterceptTouchEvent(true)
                showControls(true)
                onSelected?.invoke()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    isDragging = false
                    rotating = true
                    initialAngle = angle(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (event.pointerCount <= 2) {
                    rotating = false
                    isDragging = true
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (rotating && event.pointerCount >= 2) {
                    val current = angle(event)
                    val delta = Math.toDegrees(current - initialAngle).toFloat()
                    rotation = rotation + delta
                    initialAngle = current
                    notifyTransformChanged()
                } else if (isDragging && event.pointerCount == 1) {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    translationX += dx
                    translationY += dy
                    clampToParentBounds()
                    lastX = event.x
                    lastY = event.y
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                rotating = false
                postDelayed({ setLayerType(View.LAYER_TYPE_NONE, null) }, 120L)
                parent?.requestDisallowInterceptTouchEvent(false)
                clampToParentBounds()
                notifyTransformChanged(immediate = true)
                dispatchTransformCommitted()
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

    private fun clampToParentBounds() {
        val parentView = parent as? View ?: return
        val parentW = parentView.width
        val parentH = parentView.height
        if (parentW <= 0 || parentH <= 0 || width <= 0 || height <= 0) return

        val scaledWidth = (width * abs(scaleX)).coerceAtLeast(1f)
        val scaledHeight = (height * abs(scaleY)).coerceAtLeast(1f)
        // When overlay is scaled larger than its parent, clamping causes violent
        // snapping — skip bounds enforcement and let the user position freely.
        if (scaledWidth >= parentW || scaledHeight >= parentH) return
        val minCenterX = scaledWidth * 0.5f
        val minCenterY = scaledHeight * 0.5f
        val maxCenterX = parentW.toFloat() - scaledWidth * 0.5f
        val maxCenterY = parentH.toFloat() - scaledHeight * 0.5f
        val clampedCenterX = (x + width * 0.5f).coerceIn(minCenterX, maxCenterX)
        val clampedCenterY = (y + height * 0.5f).coerceIn(minCenterY, maxCenterY)
        val clampedLeft = clampedCenterX - width * 0.5f
        val clampedTop = clampedCenterY - height * 0.5f
        translationX = clampedLeft - left.toFloat()
        translationY = clampedTop - top.toFloat()
    }

    private fun notifyTransformChanged(immediate: Boolean = false) {
        if (immediate) {
            transformNotifyScheduled = false
            dispatchTransformChanged()
            return
        }
        if (transformNotifyScheduled) return
        transformNotifyScheduled = true
        postOnAnimation {
            transformNotifyScheduled = false
            dispatchTransformChanged()
        }
    }

    private fun dispatchTransformChanged() {
        val parentW = (parent as? View)?.width ?: return
        val parentH = (parent as? View)?.height ?: return
        if (parentW == 0 || parentH == 0) return

        val scaledWidth = (width * abs(scaleX)).coerceAtLeast(1f)
        val scaledHeight = (height * abs(scaleY)).coerceAtLeast(1f)
        val minCenterX = scaledWidth * 0.5f
        val minCenterY = scaledHeight * 0.5f
        val maxCenterX = (parentW.toFloat() - scaledWidth * 0.5f).coerceAtLeast(minCenterX)
        val maxCenterY = (parentH.toFloat() - scaledHeight * 0.5f).coerceAtLeast(minCenterY)
        val centerXPx = (x + width * 0.5f).coerceIn(minCenterX, maxCenterX)
        val centerYPx = (y + height * 0.5f).coerceIn(minCenterY, maxCenterY)
        val centerX = centerXPx / parentW.toFloat()
        val centerY = centerYPx / parentH.toFloat()
        onTransformChanged?.invoke(centerX.coerceIn(0f, 1f), centerY.coerceIn(0f, 1f), scaleX, rotation)
    }

    private fun dispatchTransformCommitted() {
        val parentW = (parent as? View)?.width ?: return
        val parentH = (parent as? View)?.height ?: return
        if (parentW == 0 || parentH == 0) return

        val scaledWidth = (width * abs(scaleX)).coerceAtLeast(1f)
        val scaledHeight = (height * abs(scaleY)).coerceAtLeast(1f)
        val minCenterX = scaledWidth * 0.5f
        val minCenterY = scaledHeight * 0.5f
        val maxCenterX = (parentW.toFloat() - scaledWidth * 0.5f).coerceAtLeast(minCenterX)
        val maxCenterY = (parentH.toFloat() - scaledHeight * 0.5f).coerceAtLeast(minCenterY)
        val centerXPx = (x + width * 0.5f).coerceIn(minCenterX, maxCenterX)
        val centerYPx = (y + height * 0.5f).coerceIn(minCenterY, maxCenterY)
        val centerX = centerXPx / parentW.toFloat()
        val centerY = centerYPx / parentH.toFloat()
        onTransformCommitted?.invoke(centerX.coerceIn(0f, 1f), centerY.coerceIn(0f, 1f), scaleX, rotation)
    }

    private fun resolveTypeface(fontName: String?, bold: Boolean, italic: Boolean): Typeface {
        return com.video.engine.TextBitmapHelper.resolveTypeface(fontName, bold, italic, context)
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0
        return (0.299 * red + 0.587 * green + 0.114 * blue) > 0.55
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
