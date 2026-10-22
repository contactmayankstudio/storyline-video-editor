package com.video.engine.camera

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Snapchat-style pro camera shutter button.
 * Features:
 * - 360° gradient circular progress ring using SweepGradient rotated -90° (12 o'clock start)
 * - Outer track circle and inner shape morphing (white circle to red rounded square)
 * - Mode handling: PHOTO (single tap capture with bounce, hold to record) vs VIDEO (tap to toggle)
 * - Drag-to-zoom touch tracking: dragging upward while holding shutter maps deltaY to zoom ratio
 */
class CameraShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { PHOTO, VIDEO }

    var mode: Mode = Mode.PHOTO
        set(value) {
            field = value
            invalidate()
        }

    var isRecording: Boolean = false
        private set

    var maxRecordingDurationMs: Long = 15_000L
    private var recordingStartTime: Long = 0L

    // Progress [0..1]
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    // Callbacks
    var onPhotoCapture: (() -> Unit)? = null
    var onRecordStart: (() -> Unit)? = null
    var onRecordStop: (() -> Unit)? = null
    var onDragZoom: ((zoomFraction: Float) -> Unit)? = null

    // Touch tracking for Drag-to-Zoom
    private var initialTouchY: Float = 0f
    private var isDraggingZoom: Boolean = false
    private val maxDragDistancePx: Float = 320f * resources.displayMetrics.density

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * resources.displayMetrics.density
        color = Color.argb(80, 255, 255, 255)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.0f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringBounds = RectF()
    private val innerBounds = RectF()

    // Morphing factor: 0f = white circle, 1f = red rounded square
    private var morphProgress = 0f
    private var morphAnimator: ValueAnimator? = null

    private val holdToRecordRunnable = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startRecording()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = SystemClock.uptimeMillis() - recordingStartTime
            progress = (elapsed.toFloat() / maxRecordingDurationMs).coerceIn(0f, 1f)
            if (progress >= 1.0f) {
                stopRecording()
            } else {
                postOnAnimation(this)
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = progressPaint.strokeWidth
        val padding = stroke / 2f + 2f * resources.displayMetrics.density
        ringBounds.set(padding, padding, w - padding, h - padding)

        // Gradient: Vibrant Snapchat/Storyline Sunset to Neon Rose
        val colors = intArrayOf(
            Color.parseColor("#FF007F"), // Neon Rose
            Color.parseColor("#FF5E3A"), // Coral Orange
            Color.parseColor("#FFD200"), // Sunlight Gold
            Color.parseColor("#00F2FE"), // Cyan Electric
            Color.parseColor("#FF007F")  // Loop back
        )
        val positions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)
        val sweep = SweepGradient(w / 2f, h / 2f, colors, positions)
        val matrix = Matrix().apply { postRotate(-90f, w / 2f, h / 2f) }
        sweep.setLocalMatrix(matrix)
        progressPaint.shader = sweep
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // 1. Draw Outer Track Ring
        canvas.drawOval(ringBounds, trackPaint)

        // 2. Draw 360° Gradient Radial Progress Ring
        if (isRecording && progress > 0f) {
            val sweepAngle = progress * 360f
            canvas.drawArc(ringBounds, -90f, sweepAngle, false, progressPaint)
        }

        // 3. Draw Inner Morphing Shutter Shape (Circle -> Red Rounded Square)
        val defaultSize = width * 0.72f
        val recordingSize = width * 0.44f
        val currentSize = defaultSize - morphProgress * (defaultSize - recordingSize)

        innerBounds.set(
            cx - currentSize / 2f,
            cy - currentSize / 2f,
            cx + currentSize / 2f,
            cy + currentSize / 2f
        )

        val cornerRadius = (currentSize / 2f) * (1f - morphProgress) + (8f * resources.displayMetrics.density * morphProgress)
        // Smoothly interpolate color: White (#FFFFFF) to Red (#FF3B30)
        val r = 255
        val g = (255 * (1f - morphProgress) + 59 * morphProgress).toInt().coerceIn(0, 255)
        val b = (255 * (1f - morphProgress) + 48 * morphProgress).toInt().coerceIn(0, 255)
        innerPaint.color = Color.rgb(r, g, b)

        canvas.drawRoundRect(innerBounds, cornerRadius, cornerRadius, innerPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchY = event.rawY
                isDraggingZoom = false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                if (mode == Mode.VIDEO) {
                    if (!isRecording) {
                        startRecording()
                    } else {
                        stopRecording()
                    }
                } else {
                    // Photo mode: start hold timer for hold-to-record
                    postDelayed(holdToRecordRunnable, 280L)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRecording) {
                    val deltaY = initialTouchY - event.rawY
                    if (deltaY > 15f) {
                        isDraggingZoom = true
                        val fraction = (deltaY / maxDragDistancePx).coerceIn(0f, 1f)
                        onDragZoom?.invoke(fraction)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdToRecordRunnable)
                if (mode == Mode.PHOTO) {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        // Quick Tap: Take photo with bounce animation
                        animateTapBounce()
                        onPhotoCapture?.invoke()
                    }
                }
                if (isDraggingZoom) {
                    isDraggingZoom = false
                    onDragZoom?.invoke(0f)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        recordingStartTime = SystemClock.uptimeMillis()
        progress = 0f
        animateMorph(1f)
        onRecordStart?.invoke()
        startProgressLoop()
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        progress = 0f
        removeCallbacks(progressRunnable)
        animateMorph(0f)
        onRecordStop?.invoke()
    }

    private fun startProgressLoop() {
        removeCallbacks(progressRunnable)
        postOnAnimation(progressRunnable)
    }

    private fun animateMorph(target: Float) {
        morphAnimator?.cancel()
        morphAnimator = ValueAnimator.ofFloat(morphProgress, target).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                morphProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateTapBounce() {
        animate().scaleX(0.88f).scaleY(0.88f).setDuration(80L).withEndAction {
            animate().scaleX(1.0f).scaleY(1.0f).setDuration(140L).setInterpolator(OvershootInterpolator(2.0f)).start()
        }.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(holdToRecordRunnable)
        removeCallbacks(progressRunnable)
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
