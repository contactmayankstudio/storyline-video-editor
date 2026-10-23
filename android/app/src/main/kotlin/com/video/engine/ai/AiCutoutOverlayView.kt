package com.video.engine.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Interactive Overlay View rendering 3D Dynamic Backgrounds, Glowing Neon Silhouette Edges,
 * and Cutout Person directly above the video preview canvas.
 */
class AiCutoutOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cutoutBitmap: Bitmap? = null
    private var alphaMaskBitmap: Bitmap? = null
    private var originalBitmap: Bitmap? = null
    private var params: AiCutoutParams? = null
    private var currentTimeMs: Long = 0L

    private val destRectF = RectF()
    private val srcRect = Rect()

    init {
        // Required for BlurMaskFilter on hardware-accelerated canvas
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setCutoutData(
        cutout: Bitmap?,
        original: Bitmap?,
        cutoutParams: AiCutoutParams?,
        playheadMs: Long = 0L
    ) {
        cutoutBitmap = cutout
        originalBitmap = original
        params = cutoutParams
        currentTimeMs = playheadMs

        alphaMaskBitmap = if (cutout != null && !cutout.isRecycled) {
            runCatching { cutout.extractAlpha() }.getOrNull()
        } else {
            null
        }

        visibility = if (cutoutParams?.enabled == true && cutout != null) VISIBLE else GONE
        invalidate()
    }

    fun updateTime(playheadMs: Long) {
        currentTimeMs = playheadMs
        if (visibility == VISIBLE && params?.enabled == true) {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = params ?: return
        if (!p.enabled) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        destRectF.set(0f, 0f, w, h)

        // 1. Draw 3D Dynamic Background
        if (p.backgroundType != AiCutoutParams.BG_NONE) {
            DynamicBackgroundRenderer.render(
                canvas = canvas,
                width = width,
                height = height,
                backgroundType = p.backgroundType,
                timeMs = currentTimeMs,
                originalFrame = originalBitmap
            )
        }

        val cutout = cutoutBitmap
        val alphaMask = alphaMaskBitmap
        if (cutout != null && !cutout.isRecycled) {
            srcRect.set(0, 0, cutout.width, cutout.height)

            // 2. Draw Glowing Neon Silhouette Edge (with animated beat pulse)
            if (p.hasGlow && alphaMask != null && !alphaMask.isRecycled) {
                val beatPulse = if (p.neonGlowPulse) {
                    1.0f + 0.38f * abs(sin(currentTimeMs / 175.0)).toFloat()
                } else {
                    1.0f
                }
                val glowRadius = (p.glowWidth * beatPulse).coerceIn(4f, 48f)
                val glowAlpha = if (p.neonGlowPulse) {
                    (200 + 55 * sin(currentTimeMs / 120.0)).toInt().coerceIn(160, 255)
                } else {
                    230
                }

                // Outer Radiant Neon Glow
                val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = p.neonGlowColor
                    alpha = glowAlpha
                    maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.SOLID)
                }
                canvas.drawBitmap(alphaMask, srcRect, destRectF, outerGlowPaint)

                // Inner Bright Neon Core
                val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    alpha = (glowAlpha * 0.75f).toInt()
                    maskFilter = BlurMaskFilter(glowRadius * 0.35f, BlurMaskFilter.Blur.SOLID)
                }
                canvas.drawBitmap(alphaMask, srcRect, destRectF, coreGlowPaint)
            }

            // 3. Draw Foreground Person Cutout
            val personPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(cutout, srcRect, destRectF, personPaint)
        }
    }
}
