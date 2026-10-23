package com.video.engine.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Snapchat-style multi-color neon doodle drawing canvas.
 * Allows smooth finger sketching on captured photo/video review screen.
 */
class DoodleCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DoodleStroke(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val isNeon: Boolean = true
    )

    private val strokes = mutableListOf<DoodleStroke>()
    private var currentPath: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    var currentColor: Int = Color.parseColor("#FF007F") // Neon Rose default
    var brushWidth: Float = 8f * resources.displayMetrics.density
    var isDrawingEnabled: Boolean = false

    var onStrokeCountChanged: ((count: Int) -> Unit)? = null

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) return false

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val path = Path().apply {
                    moveTo(x, y)
                }
                currentPath = path
                lastX = x
                lastY = y
                strokes.add(DoodleStroke(path, currentColor, brushWidth, isNeon = true))
                onStrokeCountChanged?.invoke(strokes.size)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val path = currentPath ?: return false
                val dx = Math.abs(x - lastX)
                val dy = Math.abs(y - lastY)
                if (dx >= 3f || dy >= 3f) {
                    path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                    lastX = x
                    lastY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.lineTo(x, y)
                currentPath = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderStrokes(canvas)
    }

    /**
     * Renders strokes onto any canvas (e.g. for final bitmap baking / export).
     */
    fun renderStrokes(canvas: Canvas) {
        for (stroke in strokes) {
            if (stroke.isNeon) {
                // Outer vibrant glow
                glowPaint.color = stroke.color
                glowPaint.alpha = 85
                glowPaint.strokeWidth = stroke.strokeWidth * 2.3f
                canvas.drawPath(stroke.path, glowPaint)
            }
            // Inner crisp stroke
            drawPaint.color = stroke.color
            drawPaint.alpha = 255
            drawPaint.strokeWidth = stroke.strokeWidth
            canvas.drawPath(stroke.path, drawPaint)
        }
    }

    fun undo(): Boolean {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            onStrokeCountChanged?.invoke(strokes.size)
            invalidate()
            return true
        }
        return false
    }

    fun clear() {
        strokes.clear()
        currentPath = null
        onStrokeCountChanged?.invoke(0)
        invalidate()
    }

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun getStrokeCount(): Int = strokes.size
}
