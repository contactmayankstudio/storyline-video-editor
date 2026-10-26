package com.video.engine.speed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class SpeedCurvePoint(
    var progress: Float, // 0.0 to 1.0 (timeline progression through clip)
    var speed: Float     // 0.10x to 6.0x (speed multiplier)
) {
    fun copy(): SpeedCurvePoint = SpeedCurvePoint(progress, speed)
}

data class SpeedCurvePreset(
    val id: String,
    val name: String,
    val description: String,
    val points: List<SpeedCurvePoint>
)

object VelocityPresets {
    // 1. "Montage" (Fast-Slow-Fast beat sync)
    val MONTAGE = SpeedCurvePreset(
        id = "montage",
        name = "⚡ Montage",
        description = "Fast-Slow-Fast beat sync",
        points = listOf(
            SpeedCurvePoint(0.0f, 2.0f),
            SpeedCurvePoint(0.25f, 0.4f),
            SpeedCurvePoint(0.50f, 2.5f),
            SpeedCurvePoint(0.75f, 0.5f),
            SpeedCurvePoint(1.0f, 1.8f)
        )
    )

    // 2. "Hero Flow" (Smooth slow drop at 0.3x)
    val HERO_FLOW = SpeedCurvePreset(
        id = "hero_flow",
        name = "🌊 Hero Flow",
        description = "Smooth slow drop at 0.3x",
        points = listOf(
            SpeedCurvePoint(0.0f, 1.6f),
            SpeedCurvePoint(0.25f, 0.3f),
            SpeedCurvePoint(0.50f, 0.3f),
            SpeedCurvePoint(0.75f, 0.6f),
            SpeedCurvePoint(1.0f, 1.2f)
        )
    )

    // 3. "Bullet Drop" (Slow-mo to 4x punch)
    val BULLET_DROP = SpeedCurvePreset(
        id = "bullet_drop",
        name = "💥 Bullet Drop",
        description = "Slow-mo to 4x punch",
        points = listOf(
            SpeedCurvePoint(0.0f, 0.25f),
            SpeedCurvePoint(0.30f, 0.25f),
            SpeedCurvePoint(0.60f, 1.0f),
            SpeedCurvePoint(0.85f, 3.5f),
            SpeedCurvePoint(1.0f, 4.0f)
        )
    )

    // 4. "Flash Cut" (Rapid 2.5x ramps)
    val FLASH_CUT = SpeedCurvePreset(
        id = "flash_cut",
        name = "⚡ Flash Cut",
        description = "Rapid 2.5x ramps",
        points = listOf(
            SpeedCurvePoint(0.0f, 2.5f),
            SpeedCurvePoint(0.25f, 0.4f),
            SpeedCurvePoint(0.50f, 2.5f),
            SpeedCurvePoint(0.75f, 0.4f),
            SpeedCurvePoint(1.0f, 2.5f)
        )
    )

    // 5. "Smooth Slow-Mo" (0.5x cinematic glide)
    val SMOOTH_SLOWMO = SpeedCurvePreset(
        id = "smooth_slowmo",
        name = "🎬 Smooth Slow-Mo",
        description = "0.5x cinematic glide",
        points = listOf(
            SpeedCurvePoint(0.0f, 1.0f),
            SpeedCurvePoint(0.25f, 0.5f),
            SpeedCurvePoint(0.50f, 0.35f),
            SpeedCurvePoint(0.75f, 0.5f),
            SpeedCurvePoint(1.0f, 1.0f)
        )
    )

    // 6. "Custom Bézier"
    val CUSTOM_BEZIER = SpeedCurvePreset(
        id = "custom_bezier",
        name = "✏️ Custom Bézier",
        description = "Interactive touch curve",
        points = listOf(
            SpeedCurvePoint(0.0f, 1.0f),
            SpeedCurvePoint(0.25f, 1.5f),
            SpeedCurvePoint(0.50f, 0.5f),
            SpeedCurvePoint(0.75f, 2.0f),
            SpeedCurvePoint(1.0f, 1.0f)
        )
    )

    // Baseline Normal 1.0x
    val LINEAR = SpeedCurvePreset(
        id = "linear",
        name = "➖ Normal",
        description = "Constant 1.0x speed",
        points = listOf(
            SpeedCurvePoint(0.0f, 1.0f),
            SpeedCurvePoint(0.25f, 1.0f),
            SpeedCurvePoint(0.50f, 1.0f),
            SpeedCurvePoint(0.75f, 1.0f),
            SpeedCurvePoint(1.0f, 1.0f)
        )
    )

    val allPresets = listOf(
        MONTAGE,
        HERO_FLOW,
        BULLET_DROP,
        FLASH_CUT,
        SMOOTH_SLOWMO,
        CUSTOM_BEZIER,
        LINEAR
    )

    fun findById(id: String?): SpeedCurvePreset {
        if (id.isNullOrBlank()) return MONTAGE
        return when (id.lowercase()) {
            "bullet_time", "bullet", "bullet_drop" -> BULLET_DROP
            "hero_drop", "hero", "hero_flow" -> HERO_FLOW
            "ease_in", "smooth", "smooth_slowmo" -> SMOOTH_SLOWMO
            "flash", "flash_cut" -> FLASH_CUT
            "montage", "hyperlapse" -> MONTAGE
            "custom", "custom_bezier" -> CUSTOM_BEZIER
            "linear", "normal" -> LINEAR
            else -> allPresets.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MONTAGE
        }
    }

    /**
     * Numerically computes the overall effective speed multiplier of a curve.
     * New duration = original duration * (1 / effectiveSpeed).
     */
    fun computeEffectiveSpeed(points: List<SpeedCurvePoint>): Float {
        if (points.isEmpty()) return 1.0f
        val sorted = points.sortedBy { it.progress }
        val samples = 32
        var totalReciprocal = 0.0
        for (i in 0 until samples) {
            val t = (i + 0.5f) / samples
            val s = evaluateSpeedAt(sorted, t).coerceIn(0.1f, 6.0f)
            totalReciprocal += (1.0 / s)
        }
        val avgReciprocal = totalReciprocal / samples
        return (1.0 / avgReciprocal).toFloat().coerceIn(0.1f, 6.0f)
    }

    fun evaluateSpeedAt(points: List<SpeedCurvePoint>, t: Float): Float {
        if (points.isEmpty()) return 1.0f
        if (t <= points.first().progress) return points.first().speed
        if (t >= points.last().progress) return points.last().speed

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            if (t in p0.progress..p1.progress) {
                val span = (p1.progress - p0.progress).coerceAtLeast(0.001f)
                val localT = ((t - p0.progress) / span).coerceIn(0f, 1f)
                // Smooth Hermite S-curve interpolation
                val smoothT = localT * localT * (3f - 2f * localT)
                return p0.speed + (p1.speed - p0.speed) * smoothT
            }
        }
        return points.last().speed
    }
}

/**
 * Interactive touch graph for Storyline Pro Velocity Speed Curves.
 * Allows dragging 5 bezier control handles smoothly between 0.1x and 6.0x with glowing aesthetic.
 */
class VelocitySpeedCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var points: MutableList<SpeedCurvePoint> = VelocityPresets.MONTAGE.points.map { it.copy() }.toMutableList()
        private set

    var currentPresetId: String = VelocityPresets.MONTAGE.id
        private set

    var onCurveChangedListener: ((points: List<SpeedCurvePoint>, effectiveSpeed: Float) -> Unit)? = null

    private var activePointIndex = -1
    private val cardRect = RectF()
    private val curvePath = Path()
    private val fillPath = Path()

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121722")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252E3E")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.2f)
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C2638")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(4f), dpToPx(4f)), 0f)
    }

    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78889B")
        textSize = dpToPx(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val handleOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00F2FE")
    }

    private val handleInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val handleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        color = Color.parseColor("#8000F2FE")
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60B0F17")
        style = Paint.Style.FILL
    }

    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400F2FE")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }

    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun applyPreset(preset: SpeedCurvePreset) {
        currentPresetId = preset.id
        points = preset.points.map { it.copy() }.toMutableList()
        invalidate()
        val effective = VelocityPresets.computeEffectiveSpeed(points)
        onCurveChangedListener?.invoke(points, effective)
    }

    fun setPointsDirectly(newPoints: List<SpeedCurvePoint>, presetId: String = "custom_bezier") {
        currentPresetId = presetId
        points = newPoints.map { it.copy() }.toMutableList()
        invalidate()
        val effective = VelocityPresets.computeEffectiveSpeed(points)
        onCurveChangedListener?.invoke(points, effective)
    }

    fun getEffectiveSpeed(): Float = VelocityPresets.computeEffectiveSpeed(points)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dpToPx(175f).roundToInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padLeft = dpToPx(42f)
        val padRight = dpToPx(18f)
        val padTop = dpToPx(28f)
        val padBottom = dpToPx(24f)

        cardRect.set(dpToPx(4f), dpToPx(4f), w - dpToPx(4f), h - dpToPx(4f))
        val corner = dpToPx(14f)
        canvas.drawRoundRect(cardRect, corner, corner, bgPaint)
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint)

        val graphLeft = padLeft
        val graphRight = w - padRight
        val graphTop = padTop
        val graphBottom = h - padBottom

        // Draw horizontal grid lines & labels
        val gridSpeeds = listOf(6.0f, 4.0f, 2.0f, 1.0f, 0.5f, 0.2f)
        gridSpeeds.forEach { sp ->
            val y = speedToY(sp, graphTop, graphBottom)
            if (sp == 1.0f) {
                canvas.drawLine(graphLeft, y, graphRight, y, baselinePaint)
                canvas.drawText("1.0x", dpToPx(12f), y + dpToPx(3.5f), gridLabelPaint)
            } else {
                canvas.drawLine(graphLeft, y, graphRight, y, gridLinePaint)
                canvas.drawText(String.format(Locale.US, "%.1fx", sp), dpToPx(12f), y + dpToPx(3.5f), gridLabelPaint)
            }
        }

        // Vertical reference guides
        val vSteps = 4
        for (i in 1 until vSteps) {
            val vx = graphLeft + (graphRight - graphLeft) * (i.toFloat() / vSteps)
            canvas.drawLine(vx, graphTop, vx, graphBottom, gridLinePaint)
        }

        if (points.size < 2) return

        // Build smooth Catmull-Rom cubic bezier curve
        curvePath.reset()
        fillPath.reset()

        val screenPoints = points.map { pt ->
            val px = graphLeft + pt.progress * (graphRight - graphLeft)
            val py = speedToY(pt.speed, graphTop, graphBottom)
            Pair(px, py)
        }

        curvePath.moveTo(screenPoints[0].first, screenPoints[0].second)
        fillPath.moveTo(screenPoints[0].first, graphBottom)
        fillPath.lineTo(screenPoints[0].first, screenPoints[0].second)

        for (i in 0 until screenPoints.size - 1) {
            val p0 = if (i > 0) screenPoints[i - 1] else screenPoints[i]
            val p1 = screenPoints[i]
            val p2 = screenPoints[i + 1]
            val p3 = if (i + 2 < screenPoints.size) screenPoints[i + 2] else p2

            // Spline control points
            val c1x = p1.first + (p2.first - p0.first) / 6f
            val c1y = p1.second + (p2.second - p0.second) / 6f
            val c2x = p2.first - (p3.first - p1.first) / 6f
            val c2y = p2.second - (p3.second - p1.second) / 6f

            curvePath.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
            fillPath.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
        }

        fillPath.lineTo(screenPoints.last().first, graphBottom)
        fillPath.close()

        // Gradient under-fill
        fillPaint.shader = LinearGradient(
            0f, graphTop, 0f, graphBottom,
            Color.parseColor("#4400F2FE"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Gradient curve stroke
        curvePaint.shader = LinearGradient(
            graphLeft, 0f, graphRight, 0f,
            intArrayOf(
                Color.parseColor("#00F2FE"),
                Color.parseColor("#4FACFE"),
                Color.parseColor("#FF512F")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(curvePath, curvePaint)

        // Draw handles and floating speed pills
        screenPoints.forEachIndexed { idx, (px, py) ->
            val isActive = (idx == activePointIndex)
            val pt = points[idx]

            // Outer ring & halo
            if (isActive) {
                canvas.drawCircle(px, py, dpToPx(16f), handleHaloPaint)
            }
            canvas.drawCircle(px, py, if (isActive) dpToPx(9f) else dpToPx(7f), handleOuterPaint)
            canvas.drawCircle(px, py, dpToPx(3.5f), handleInnerPaint)

            // Speed bubble above point
            val label = String.format(Locale.US, "%.1fx", pt.speed)
            val pillW = dpToPx(34f)
            val pillH = dpToPx(17f)
            val pillX = px.coerceIn(graphLeft + pillW / 2, graphRight - pillW / 2)
            val pillY = (py - dpToPx(18f)).coerceAtLeast(dpToPx(12f))

            val pillRect = RectF(
                pillX - pillW / 2,
                pillY - pillH / 2,
                pillX + pillW / 2,
                pillY + pillH / 2
            )
            canvas.drawRoundRect(pillRect, dpToPx(6f), dpToPx(6f), pillBgPaint)
            canvas.drawRoundRect(pillRect, dpToPx(6f), dpToPx(6f), pillStrokePaint)
            canvas.drawText(label, pillX, pillY + dpToPx(3.5f), pillTextPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = dpToPx(42f)
        val padRight = dpToPx(18f)
        val padTop = dpToPx(28f)
        val padBottom = dpToPx(24f)
        val graphLeft = padLeft
        val graphRight = w - padRight
        val graphTop = padTop
        val graphBottom = h - padBottom

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Find nearest point within touch radius
                var closestIdx = -1
                var minDist = dpToPx(38f)

                points.forEachIndexed { i, pt ->
                    val px = graphLeft + pt.progress * (graphRight - graphLeft)
                    val py = speedToY(pt.speed, graphTop, graphBottom)
                    val dist = Math.hypot((event.x - px).toDouble(), (event.y - py).toDouble()).toFloat()
                    if (dist < minDist) {
                        minDist = dist
                        closestIdx = i
                    }
                }

                if (closestIdx >= 0) {
                    activePointIndex = closestIdx
                    currentPresetId = VelocityPresets.CUSTOM_BEZIER.id
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointIndex in points.indices) {
                    val pt = points[activePointIndex]
                    // Update speed (Y-axis)
                    val newSpeed = yToSpeed(event.y, graphTop, graphBottom)
                    pt.speed = newSpeed

                    // For interior points, allow dragging along timeline X-axis
                    if (activePointIndex > 0 && activePointIndex < points.size - 1) {
                        val prevX = points[activePointIndex - 1].progress + 0.05f
                        val nextX = points[activePointIndex + 1].progress - 0.05f
                        val newProgress = ((event.x - graphLeft) / (graphRight - graphLeft)).coerceIn(prevX, nextX)
                        pt.progress = newProgress
                    }

                    invalidate()
                    val effective = VelocityPresets.computeEffectiveSpeed(points)
                    onCurveChangedListener?.invoke(points, effective)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointIndex >= 0) {
                    activePointIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    val effective = VelocityPresets.computeEffectiveSpeed(points)
                    onCurveChangedListener?.invoke(points, effective)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun speedToY(speed: Float, top: Float, bottom: Float): Float {
        val midY = top + (bottom - top) * 0.48f // 1.0x baseline sits near vertical middle
        val clamped = speed.coerceIn(0.1f, 6.0f)
        return if (clamped >= 1.0f) {
            // Speeds > 1.0 map upwards from midY to top
            val ratio = (clamped - 1.0f) / 5.0f
            midY - ratio * (midY - top)
        } else {
            // Speeds < 1.0 map downwards from midY to bottom
            val ratio = (1.0f - clamped) / 0.9f
            midY + ratio * (bottom - midY)
        }
    }

    private fun yToSpeed(y: Float, top: Float, bottom: Float): Float {
        val midY = top + (bottom - top) * 0.48f
        return if (y <= midY) {
            val ratio = ((midY - y) / (midY - top)).coerceIn(0f, 1f)
            1.0f + ratio * 5.0f
        } else {
            val ratio = ((y - midY) / (bottom - midY)).coerceIn(0f, 1f)
            1.0f - ratio * 0.9f
        }.coerceIn(0.1f, 6.0f)
    }

    private fun dpToPx(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
