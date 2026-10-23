package com.video.engine.status

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.ThreadLocalRandom

/**
 * High-performance Canvas Confetti celebration view.
 * Renders fluttering colorful ribbons, golden stars, and celebratory sparkles.
 */
class ConfettiCelebrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class ConfettiPiece {
        var x: Float = 0f
        var y: Float = 0f
        var vx: Float = 0f
        var vy: Float = 0f
        var width: Float = 12f
        var height: Float = 20f
        var rotation: Float = 0f
        var rotSpeed: Float = 0f
        var color: Int = Color.YELLOW
        var isStar: Boolean = false
        var isCircle: Boolean = false
        var life: Float = 0f
        var maxLife: Float = 120f

        fun reset(w: Float, h: Float, burstFromCenter: Boolean = true) {
            val rng = ThreadLocalRandom.current()
            life = 0f
            maxLife = 90f + rng.nextFloat() * 60f
            rotation = rng.nextFloat() * 360f
            rotSpeed = -8f + rng.nextFloat() * 16f

            val colors = intArrayOf(
                Color.parseColor("#FFD700"), // Gold
                Color.parseColor("#00F2FE"), // Neon Cyan
                Color.parseColor("#FF007F"), // Hot Pink
                Color.parseColor("#25D366"), // WhatsApp Green
                Color.parseColor("#D946EF"), // Magenta
                Color.parseColor("#FF9500"), // Orange
                Color.parseColor("#388BFD")  // Royal Blue
            )
            color = colors[rng.nextInt(colors.size)]
            val type = rng.nextInt(3)
            isStar = type == 0
            isCircle = type == 1

            if (burstFromCenter) {
                x = (w * 0.45f) + (rng.nextFloat() * w * 0.1f)
                y = (h * 0.35f) + (rng.nextFloat() * h * 0.1f)
                val angle = rng.nextFloat() * 6.28f
                val speed = 6f + rng.nextFloat() * 14f
                vx = (Math.cos(angle.toDouble()).toFloat() * speed)
                vy = (Math.sin(angle.toDouble()).toFloat() * speed) - 4f
            } else {
                x = rng.nextFloat() * w
                y = -20f - rng.nextFloat() * 80f
                vx = -2f + rng.nextFloat() * 4f
                vy = 4f + rng.nextFloat() * 8f
            }
            width = 10f + rng.nextFloat() * 14f
            height = 14f + rng.nextFloat() * 16f
        }
    }

    private val pieces = Array(70) { ConfettiPiece() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var isAnimating = false
    private var startAnimTime: Long = 0L

    fun startCelebration() {
        isAnimating = true
        startAnimTime = SystemClock.uptimeMillis()
        val w = width.toFloat().coerceAtLeast(400f)
        val h = height.toFloat().coerceAtLeast(600f)
        pieces.forEach { it.reset(w, h, burstFromCenter = true) }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isAnimating && w > 0 && h > 0) {
            pieces.forEach { it.reset(w.toFloat(), h.toFloat(), burstFromCenter = false) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnimating) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val elapsed = SystemClock.uptimeMillis() - startAnimTime
        var anyActive = false

        pieces.forEach { p ->
            p.life += 1f
            if (p.life < p.maxLife && p.y < h + 50f) {
                anyActive = true
                // Apply gravity and air drag
                p.vy += 0.35f
                p.vx *= 0.98f
                p.x += p.vx
                p.y += p.vy
                p.rotation += p.rotSpeed

                val lifeFrac = (p.life / p.maxLife).coerceIn(0f, 1f)
                val alpha = if (lifeFrac > 0.7f) ((1f - lifeFrac) / 0.3f) else 1f

                paint.color = p.color
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.rotate(p.rotation)

                if (p.isStar) {
                    paint.textSize = p.width * 1.5f
                    canvas.drawText("★", -p.width * 0.75f, p.width * 0.75f, paint)
                } else if (p.isCircle) {
                    canvas.drawCircle(0f, 0f, p.width / 2f, paint)
                } else {
                    rect.set(-p.width / 2f, -p.height / 2f, p.width / 2f, p.height / 2f)
                    canvas.drawRoundRect(rect, 4f, 4f, paint)
                }
                canvas.restore()
            }
        }

        if (anyActive || elapsed < 6000L) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }
}
