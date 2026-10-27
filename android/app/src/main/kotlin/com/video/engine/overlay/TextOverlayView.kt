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
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Interactive UI element placed above the preview for editing text overlays.
 * Supports:
 * - 4-corner controls (TL: Delete ✕, TR: Width/Stretch ↔, BL: Reset ↺, BR: Scale/Zoom & Rotate ⤢)
 * - 1-finger drag & drop translation across the canvas
 * - 1-finger corner handle scaling (bada) & widening (choda)
 * - 2-finger pinch zoom and rotation
 */
class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "[TextOverlayView]"
    }

    private enum class TouchMode {
        NONE,
        BODY_DRAG,
        STRETCH_WIDTH,
        SCALE_ROTATE,
        PINCH_MULTI
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

    // Bounding selection box
    private val selectionBorder = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1.5f), Color.parseColor("#388BFD"))
            cornerRadius = dp(6f).toFloat()
        }
        visibility = View.GONE
    }

    // 4 Corner Control Handles
    // Top-Left: Delete
    private val tlDeleteButton = createHandleButton("✕", Color.parseColor("#D90429"), Color.WHITE).apply {
        setOnClickListener { onDeleteRequested?.invoke() }
    }

    // Top-Right: Width / Stretch (Choda)
    private val trStretchButton = createHandleButton("↔", Color.parseColor("#1B2838"), Color.parseColor("#388BFD"))

    // Bottom-Left: Reset / Snap
    private val blResetButton = createHandleButton("↺", Color.parseColor("#1B2838"), Color.parseColor("#388BFD")).apply {
        setOnClickListener {
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
            syncControlChromeScale()
            notifyTransformChanged(immediate = true)
            dispatchTransformCommitted()
        }
    }

    // Bottom-Right: Scale / Zoom (Bada) & Rotate
    private val brScaleRotateButton = createHandleButton("⤢", Color.parseColor("#388BFD"), Color.WHITE)

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

    // Touch tracking state
    private var activeTouchMode = TouchMode.NONE
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var startRawX = 0f
    private var startRawY = 0f
    private var startMaxWidth = 0
    private var startScale = 1.0f
    private var startRotation = 0f
    private var startDist = 1.0f
    private var startAngle = 0.0
    private var centerScreenX = 0f
    private var centerScreenY = 0f
    private var transformNotifyScheduled = false

    private var pinchStartScale = 1.0f
    private var pinchStartSpan = 1.0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchStartScale = scaleX
            pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
            activeTouchMode = TouchMode.PINCH_MULTI
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

    // Multi-touch rotation tracking
    private var initialMultiAngle = 0.0

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true

        // Padding around view so corner buttons sit right on the bounding box edges
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundResource(android.R.color.transparent)

        // 1. Selection Border
        val borderLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        }
        addView(selectionBorder, borderLp)

        // 2. Text Content View
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 3. Four Corner Handles
        val handleSize = dp(28)
        addView(tlDeleteButton, LayoutParams(handleSize, handleSize, Gravity.START or Gravity.TOP))
        addView(trStretchButton, LayoutParams(handleSize, handleSize, Gravity.END or Gravity.TOP))
        addView(blResetButton, LayoutParams(handleSize, handleSize, Gravity.START or Gravity.BOTTOM))
        addView(brScaleRotateButton, LayoutParams(handleSize, handleSize, Gravity.END or Gravity.BOTTOM))
    }

    private fun createHandleButton(symbol: String, bgColor: Int, borderColor: Int): TextView {
        return TextView(context).apply {
            text = symbol
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
                setStroke(dp(1.5f), borderColor)
            }
            visibility = View.GONE
            elevation = dp(6f).toFloat()
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
        val vis = if (show) View.VISIBLE else View.GONE
        selectionBorder.visibility = vis
        tlDeleteButton.visibility = vis
        trStretchButton.visibility = vis
        blResetButton.visibility = vis
        brScaleRotateButton.visibility = vis

        if (show) {
            selectionBorder.bringToFront()
            textView.bringToFront()
            tlDeleteButton.bringToFront()
            trStretchButton.bringToFront()
            blResetButton.bringToFront()
            brScaleRotateButton.bringToFront()
            syncControlChromeScale()
        }
        alpha = 1.0f
    }

    fun syncControlChromeScale() {
        val inverseScale = 1f / abs(scaleX).coerceIn(0.25f, 8f)
        tlDeleteButton.scaleX = inverseScale
        tlDeleteButton.scaleY = inverseScale
        trStretchButton.scaleX = inverseScale
        trStretchButton.scaleY = inverseScale
        blResetButton.scaleX = inverseScale
        blResetButton.scaleY = inverseScale
        brScaleRotateButton.scaleX = inverseScale
        brScaleRotateButton.scaleY = inverseScale
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept all touches on TextOverlayView so child views (like textView) don't swallow them
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                startRawX = event.rawX
                startRawY = event.rawY

                bringToFront()
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                parent?.requestDisallowInterceptTouchEvent(true)
                showControls(true)
                onSelected?.invoke()

                // Check hit against the 4 corner handles
                when {
                    isNearHandle(tlDeleteButton, event.x, event.y) -> {
                        activeTouchMode = TouchMode.NONE
                        onDeleteRequested?.invoke()
                        return true
                    }
                    isNearHandle(blResetButton, event.x, event.y) -> {
                        activeTouchMode = TouchMode.NONE
                        rotation = 0f
                        scaleX = 1f
                        scaleY = 1f
                        syncControlChromeScale()
                        notifyTransformChanged(immediate = true)
                        dispatchTransformCommitted()
                        return true
                    }
                    isNearHandle(trStretchButton, event.x, event.y) -> {
                        activeTouchMode = TouchMode.STRETCH_WIDTH
                        startMaxWidth = if (textView.maxWidth in 1..99999) textView.maxWidth else textView.width
                    }
                    isNearHandle(brScaleRotateButton, event.x, event.y) -> {
                        activeTouchMode = TouchMode.SCALE_ROTATE
                        computeCenterScreen()
                        startDist = hypot((event.rawX - centerScreenX).toDouble(), (event.rawY - centerScreenY).toDouble()).toFloat().coerceAtLeast(10f)
                        startScale = scaleX
                        startAngle = atan2((event.rawY - centerScreenY).toDouble(), (event.rawX - centerScreenX).toDouble())
                        startRotation = rotation
                    }
                    else -> {
                        activeTouchMode = TouchMode.BODY_DRAG
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    activeTouchMode = TouchMode.PINCH_MULTI
                    initialMultiAngle = angle(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (event.pointerCount <= 2) {
                    activeTouchMode = TouchMode.BODY_DRAG
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeTouchMode) {
                    TouchMode.PINCH_MULTI -> {
                        if (event.pointerCount >= 2) {
                            val currentAngle = angle(event)
                            val delta = Math.toDegrees(currentAngle - initialMultiAngle).toFloat()
                            rotation += delta
                            initialMultiAngle = currentAngle
                            notifyTransformChanged()
                        }
                    }
                    TouchMode.BODY_DRAG -> {
                        if (event.pointerCount == 1) {
                            val dx = event.rawX - lastRawX
                            val dy = event.rawY - lastRawY
                            translationX += dx
                            translationY += dy
                            clampToParentBounds()
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                            notifyTransformChanged()
                        }
                    }
                    TouchMode.SCALE_ROTATE -> {
                        val currDist = hypot((event.rawX - centerScreenX).toDouble(), (event.rawY - centerScreenY).toDouble()).toFloat()
                        val factor = (currDist / startDist).coerceIn(0.15f, 8.0f)
                        val newScale = (startScale * factor).coerceIn(0.15f, 8.0f)
                        scaleX = newScale
                        scaleY = newScale

                        val currAngle = atan2((event.rawY - centerScreenY).toDouble(), (event.rawX - centerScreenX).toDouble())
                        val deltaDeg = Math.toDegrees(currAngle - startAngle).toFloat()
                        rotation = startRotation + deltaDeg

                        syncControlChromeScale()
                        clampToParentBounds()
                        notifyTransformChanged()
                    }
                    TouchMode.STRETCH_WIDTH -> {
                        val dx = (event.rawX - startRawX) / abs(scaleX).coerceAtLeast(0.1f)
                        val newW = (startMaxWidth + (dx * 2).toInt()).coerceIn(dp(60), dp(1200))
                        textView.maxWidth = newW
                        textView.requestLayout()
                        notifyTransformChanged()
                    }
                    TouchMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeTouchMode = TouchMode.NONE
                postDelayed({ setLayerType(View.LAYER_TYPE_NONE, null) }, 120L)
                parent?.requestDisallowInterceptTouchEvent(false)
                clampToParentBounds()
                notifyTransformChanged(immediate = true)
                dispatchTransformCommitted()
            }
        }
        return true
    }

    private fun isNearHandle(view: View, touchX: Float, touchY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val slopPx = dp(40)
        val handleCenterX = view.left + (view.width * 0.5f)
        val handleCenterY = view.top + (view.height * 0.5f)
        return hypot((touchX - handleCenterX).toDouble(), (touchY - handleCenterY).toDouble()) <= slopPx
    }

    private fun computeCenterScreen() {
        val parentView = parent as? View ?: this
        val loc = IntArray(2)
        parentView.getLocationOnScreen(loc)
        centerScreenX = loc[0] + x + (width * 0.5f)
        centerScreenY = loc[1] + y + (height * 0.5f)
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

        val marginPx = dp(24).toFloat()
        val minCenterX = marginPx
        val maxCenterX = (parentW.toFloat() - marginPx).coerceAtLeast(minCenterX)
        val minCenterY = marginPx
        val maxCenterY = (parentH.toFloat() - marginPx).coerceAtLeast(minCenterY)

        val currentCenterX = x + width * 0.5f
        val currentCenterY = y + height * 0.5f
        val clampedCenterX = currentCenterX.coerceIn(minCenterX, maxCenterX)
        val clampedCenterY = currentCenterY.coerceIn(minCenterY, maxCenterY)

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

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
