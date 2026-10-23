package com.video.engine.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance 3D and dynamic visual background generator for AI Cutouts.
 * Renders Cyberpunk Grid, Vintage Golden Hour, Neon Tunnel, and Bokeh Blur backgrounds.
 */
object DynamicBackgroundRenderer {

    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        backgroundType: String,
        timeMs: Long = 0L,
        originalFrame: Bitmap? = null,
    ) {
        val w = width.toFloat().coerceAtLeast(10f)
        val h = height.toFloat().coerceAtLeast(10f)

        when (backgroundType) {
            AiCutoutParams.BG_CYBERPUNK_GRID -> renderCyberpunkGrid(canvas, w, h, timeMs)
            AiCutoutParams.BG_GOLDEN_HOUR -> renderGoldenHour(canvas, w, h, timeMs)
            AiCutoutParams.BG_NEON_TUNNEL -> renderNeonTunnel(canvas, w, h, timeMs)
            AiCutoutParams.BG_BLUR_BOKEH -> renderBlurBokeh(canvas, w, h, timeMs, originalFrame)
            else -> {
                // Transparent / None
            }
        }
    }

    private fun renderCyberpunkGrid(canvas: Canvas, w: Float, h: Float, timeMs: Long) {
        val cx = w * 0.5f
        val horizonY = h * 0.55f

        // 1. Sky Gradient (Deep Obsidian Indigo to Neon Violet)
        val skyPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, horizonY,
                Color.parseColor("#080114"),
                Color.parseColor("#26004C"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, horizonY, skyPaint)

        // 2. Horizon Sun with Scanlines
        val sunRadius = w * 0.24f
        val sunCenterY = horizonY - sunRadius * 0.25f
        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx, sunCenterY - sunRadius, cx, sunCenterY + sunRadius,
                Color.parseColor("#FF007F"),
                Color.parseColor("#FFE600"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, sunCenterY, sunRadius, sunPaint)

        // Sun horizontal black scanlines
        val scanlinePaint = Paint().apply {
            color = Color.parseColor("#080114")
            strokeWidth = 3f
        }
        for (i in 0..7) {
            val y = sunCenterY + (i * (sunRadius * 0.14f))
            canvas.drawLine(cx - sunRadius, y, cx + sunRadius, y, scanlinePaint)
        }

        // 3. Perspective Floor Grid
        val floorPaint = Paint().apply {
            shader = LinearGradient(
                0f, horizonY, 0f, h,
                Color.parseColor("#12002A"),
                Color.parseColor("#04000C"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, horizonY, w, h, floorPaint)

        // Radiating Perspective Lines
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F2FE")
            strokeWidth = 2.2f
            alpha = 180
        }
        val numVLines = 14
        for (i in -numVLines..numVLines) {
            val targetX = cx + (i.toFloat() / numVLines) * (w * 1.4f)
            canvas.drawLine(cx, horizonY, targetX, h, gridLinePaint)
        }

        // Horizontal Grid Lines with 3D Depth Spacing (animated motion forward)
        val numHLines = 12
        val scrollSpeed = (timeMs * 0.0006f) % (1f / numHLines)
        for (i in 1..numHLines) {
            val progress = ((i.toFloat() / numHLines) + scrollSpeed) % 1.0f
            val curve = progress * progress
            val y = horizonY + (h - horizonY) * curve
            gridLinePaint.alpha = (curve * 220).toInt().coerceIn(30, 240)
            canvas.drawLine(0f, y, w, y, gridLinePaint)
        }

        // Horizon Neon Glow Line
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF007F")
            strokeWidth = 4f
            alpha = 220
        }
        canvas.drawLine(0f, horizonY, w, horizonY, glowPaint)
    }

    private fun renderGoldenHour(canvas: Canvas, w: Float, h: Float, timeMs: Long) {
        val cx = w * 0.5f
        val cy = h * 0.65f
        val radius = maxOf(w, h) * 0.85f

        // Rich Warm Sunset Sky
        val sunsetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.parseColor("#FFFDF0"),
                    Color.parseColor("#FFB703"),
                    Color.parseColor("#FB8500"),
                    Color.parseColor("#C1121F"),
                    Color.parseColor("#1A0933")
                ),
                floatArrayOf(0.0f, 0.18f, 0.42f, 0.70f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, sunsetPaint)

        // Radiating Sun Flares / Beams
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFD166")
            style = Paint.Style.FILL
        }
        val rayPath = Path()
        val numRays = 10
        val baseAngle = (timeMs * 0.0002f) % (Math.PI * 2)
        for (i in 0 until numRays) {
            val a1 = baseAngle + (i * Math.PI * 2 / numRays)
            val a2 = a1 + (Math.PI / numRays * 0.45)
            rayPath.reset()
            rayPath.moveTo(cx, cy)
            rayPath.lineTo((cx + cos(a1) * radius).toFloat(), (cy + sin(a1) * radius).toFloat())
            rayPath.lineTo((cx + cos(a2) * radius).toFloat(), (cy + sin(a2) * radius).toFloat())
            rayPath.close()
            canvas.drawPath(rayPath, rayPaint)
        }
    }

    private fun renderNeonTunnel(canvas: Canvas, w: Float, h: Float, timeMs: Long) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxRadius = maxOf(w, h) * 0.75f

        // Deep Space Background
        canvas.drawColor(Color.parseColor("#03030A"))

        // Corner Rails
        val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6600F2FE")
            strokeWidth = 2.0f
        }
        canvas.drawLine(0f, 0f, cx, cy, railPaint)
        canvas.drawLine(w, 0f, cx, cy, railPaint)
        canvas.drawLine(w, h, cx, cy, railPaint)
        canvas.drawLine(0f, h, cx, cy, railPaint)

        // 3D Concentric Tunnel Rings moving outward
        val ringCount = 8
        val loopSpeed = (timeMs * 0.0008f) % (1f / ringCount)
        val colors = intArrayOf(
            Color.parseColor("#00F2FE"), // Cyan
            Color.parseColor("#9D4EDD"), // Violet
            Color.parseColor("#FF007F"), // Pink
            Color.parseColor("#FFD700")  // Gold
        )

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        for (i in 1..ringCount) {
            val rawP = ((i.toFloat() / ringCount) + loopSpeed) % 1.0f
            val p = rawP * rawP // 3D perspective acceleration
            val rWidth = w * p * 0.95f
            val rHeight = h * p * 0.95f
            val ringColor = colors[i % colors.size]

            ringPaint.color = ringColor
            ringPaint.strokeWidth = (p * 5f).coerceIn(1.5f, 6.0f)
            ringPaint.alpha = (p * 255).toInt().coerceIn(30, 255)

            val left = cx - rWidth * 0.5f
            val top = cy - rHeight * 0.5f
            val right = cx + rWidth * 0.5f
            val bottom = cy + rHeight * 0.5f
            val radius = 16f * p

            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, ringPaint)
        }
    }

    private fun renderBlurBokeh(canvas: Canvas, w: Float, h: Float, timeMs: Long, originalFrame: Bitmap?) {
        if (originalFrame != null && !originalFrame.isRecycled) {
            // High-speed stack blur simulation via downscale + upscale
            val smallW = 32
            val smallH = 32
            val small = Bitmap.createScaledBitmap(originalFrame, smallW, smallH, true)
            val destRect = Rect(0, 0, w.toInt(), h.toInt())
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(small, null, destRect, paint)
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w, h,
                    Color.parseColor("#141E30"),
                    Color.parseColor("#243B55"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }

        // Vignette Darken
        val vignette = Paint().apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.5f, maxOf(w, h) * 0.7f,
                Color.TRANSPARENT,
                Color.parseColor("#88000000"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, vignette)

        // Floating Bokeh Lights
        val bokehPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val numBokeh = 9
        for (i in 0 until numBokeh) {
            val drift = (timeMs * 0.0004f + i * 0.7f)
            val bx = ((sin(drift) * 0.4f + 0.5f) * w).coerceIn(0f, w)
            val by = (((drift * 0.6f + i * 0.3f) % 1.0f) * h)
            val br = (w * 0.08f) + (i % 3) * (w * 0.04f)

            val bColor = when (i % 3) {
                0 -> Color.parseColor("#3300F2FE")
                1 -> Color.parseColor("#33FF007F")
                else -> Color.parseColor("#33FFD700")
            }
            bokehPaint.color = bColor
            canvas.drawCircle(bx, by, br, bokehPaint)
        }
    }
}
