package com.video.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.video.engine.overlay.TextOverlay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Helper for creating text bitmaps for GPU text overlay rendering.
 * 
 * Strategy:
 * - Render text to bitmap using Android Canvas (Kotlin Paint.drawText)
 * - Upload ARGB pixels to native GPU texture
 * - GPU shader samples texture and applies color + opacity
 * 
 * Why this works:
 * - Fast: no rasterization on C++/GPU side (that's slow and memory-heavy)
 * - Flexible: any font, size, style available on Android
 * - Efficient: bitmap generated once per text overlay, cached on GPU
 */
object TextBitmapHelper {
    
    private const val TAG = "[TextBitmapHelper]"
    
    /**
     * Create a bitmap with text rendered using Android Canvas.
     * Text is anti-aliased and centered horizontally.
     * 
     * @param text Text content to render
     * @param fontSize Font size in pixels
     * @param textColor ARGB color (0xAARRGGBB) for rendering
     * @param bgColor Background color (typically 0x00000000 = transparent black)
     * @return Bitmap with rendered text (ARGB_8888 format)
     */
    fun createTextBitmap(
        text: String,
        fontSize: Float = 36f,
        textColor: Int = 0xFFFFFFFF.toInt(),  // White
        bgColor: Int = 0x00000000,              // Transparent
        bold: Boolean = true,
        italic: Boolean = false,
        underline: Boolean = false,
        allCaps: Boolean = false,
        fontName: String? = null,
        strokeColor: Int = 0xFF000000.toInt(),
        strokeWidth: Float = 0.0f,
        depthColor: Int = 0x99000000.toInt(),
        depthPx: Float = 0.0f,
        shadowEnabled: Boolean = true,
        shadowColor: Int = 0x99000000.toInt(),
        shadowBlur: Float = 4.0f,
        shadowOffsetX: Float = 0.0f,
        shadowOffsetY: Float = 2.0f,
        gradientEnabled: Boolean = false,
        gradientStartColor: Int = textColor,
        gradientEndColor: Int = textColor,
        backgroundPadding: Float = 0.0f,
        backgroundCornerRadius: Float = 0.0f,
    ): Bitmap {
        val renderText = if (allCaps) {
            text.uppercase(Locale.getDefault())
        } else {
            text
        }
        if (renderText.isEmpty()) {
            // Return minimal 1x1 transparent pixels
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val effectiveShadowColor =
            if (shadowColor ushr 24 == 0) {
                if (isLightColor(textColor)) 0x99000000.toInt() else 0xB3FFFFFF.toInt()
            } else {
                shadowColor
            }
        val effectiveShadowBlur =
            if (shadowEnabled) shadowBlur.coerceIn(0f, fontSize * 0.45f) else 0f

        // Setup paint for text rendering
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            typeface = resolveTypeface(fontName, bold, italic)
            isUnderlineText = underline
            isAntiAlias = true
            isDither = true
            textAlign = Paint.Align.LEFT
            if (effectiveShadowBlur > 0.1f) {
                setShadowLayer(
                    effectiveShadowBlur,
                    shadowOffsetX.coerceIn(-fontSize, fontSize),
                    shadowOffsetY.coerceIn(-fontSize, fontSize),
                    effectiveShadowColor,
                )
            }
        }

        val lines = renderText.replace("\r\n", "\n").split('\n').ifEmpty { listOf(" ") }
        val safeLines = lines.map { if (it.isEmpty()) " " else it }
        val strokePx = strokeWidth.coerceIn(0f, fontSize * 0.35f)
        val depthOffsetPx = depthPx.coerceIn(0f, fontSize * 0.55f)
        val fontMetrics = paint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent + fontSize * 0.18f).coerceAtLeast(fontSize * 1.15f)
        val maxLineWidth = safeLines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val hasBackground = ((bgColor ushr 24) and 0xFF) > 0
        val shadowPad = if (effectiveShadowBlur > 0.1f) {
            effectiveShadowBlur + maxOf(kotlin.math.abs(shadowOffsetX), kotlin.math.abs(shadowOffsetY))
        } else {
            0f
        }
        val effectPadding = (strokePx + depthOffsetPx + shadowPad + fontSize * 0.14f).roundToInt().coerceAtLeast(0)
        val explicitBackgroundPadding = backgroundPadding.takeIf { it > 0f }?.coerceIn(0f, fontSize * 2.4f)
        val horizontalPadding =
            (explicitBackgroundPadding ?: (fontSize * if (hasBackground) 0.48f else 0.32f))
                .toInt()
                .coerceAtLeast(16) + effectPadding
        val verticalPadding =
            (explicitBackgroundPadding ?: (fontSize * if (hasBackground) 0.36f else 0.28f))
                .toInt()
                .coerceAtLeast(16) + effectPadding
        val bitmapWidth = (maxLineWidth + horizontalPadding * 2 + depthOffsetPx).roundToInt().coerceAtLeast(32)
        val bitmapHeight = (lineHeight * safeLines.size + verticalPadding * 2 + depthOffsetPx).roundToInt().coerceAtLeast(32)

        // Create bitmap
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clear background
        canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR)
        if (hasBackground) {
            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                style = Paint.Style.FILL
            }
            val inset = (fontSize * 0.06f).coerceAtLeast(2f)
            val radius = backgroundCornerRadius.takeIf { it > 0f }?.coerceIn(0f, fontSize * 1.4f)
                ?: (fontSize * 0.30f).coerceIn(8f, 28f)
            canvas.drawRoundRect(
                RectF(inset, inset, bitmapWidth - inset, bitmapHeight - inset),
                radius,
                radius,
                backgroundPaint,
            )
        }

        val strokePaint = Paint(paint).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            this.strokeWidth = strokePx
            setShadowLayer(0f, 0f, 0f, 0)
        }
        val depthPaint = Paint(paint).apply {
            color = depthColor
            style = Paint.Style.FILL
            setShadowLayer(0f, 0f, 0f, 0)
        }
        val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }

        // Draw multiline text centered line-by-line.
        var baselineY = verticalPadding - fontMetrics.ascent
        safeLines.forEach { line ->
            val lineWidth = paint.measureText(line)
            val xPos = ((bitmapWidth - lineWidth) * 0.5f).coerceAtLeast(horizontalPadding.toFloat())
            if (depthOffsetPx > 0.1f) {
                val steps = depthOffsetPx.roundToInt().coerceIn(1, 24)
                for (step in steps downTo 1) {
                    val offset = step.toFloat()
                    canvas.drawText(line, xPos + offset, baselineY + offset, depthPaint)
                }
            }
            if (strokePx > 0.1f) {
                canvas.drawText(line, xPos, baselineY, strokePaint)
            }
            if (gradientEnabled) {
                fillPaint.shader = LinearGradient(
                    xPos,
                    baselineY + fontMetrics.ascent,
                    xPos + lineWidth.coerceAtLeast(1f),
                    baselineY,
                    gradientStartColor,
                    gradientEndColor,
                    Shader.TileMode.CLAMP,
                )
            } else {
                fillPaint.shader = null
            }
            canvas.drawText(line, xPos, baselineY, fillPaint)
            fillPaint.shader = null
            baselineY += lineHeight
        }

        android.util.Log.d(TAG, "Created bitmap: text='$text' size=${bitmapWidth}x${bitmapHeight} color=$textColor")

        return bitmap
    }
    
    /**
     * Convert Bitmap to IntArray (ARGB pixels in row-major order).
     * Used for uploading to native GL texture via JNI.
     * 
     * @param bitmap Bitmap in ARGB_8888 format
     * @return IntArray of ARGB pixel values
     */
    fun bitmapToPixelArray(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }
    
    /**
     * Create text bitmap and return pixels ready for GPU upload.
     * Convenience method combining createTextBitmap + bitmapToPixelArray.
     * 
     * @return Pair of (IntArray pixels, Pair of width x height)
     */
    fun createTextPixels(
        text: String,
        fontSize: Float = 36f,
        textColor: Int = 0xFFFFFFFF.toInt(),
        backgroundColor: Int = 0x00000000,
        bold: Boolean = true,
        italic: Boolean = false,
        underline: Boolean = false,
        allCaps: Boolean = false,
        fontName: String? = null,
        strokeColor: Int = 0xFF000000.toInt(),
        strokeWidth: Float = 0.0f,
        depthColor: Int = 0x99000000.toInt(),
        depthPx: Float = 0.0f,
        shadowEnabled: Boolean = true,
        shadowColor: Int = 0x99000000.toInt(),
        shadowBlur: Float = 4.0f,
        shadowOffsetX: Float = 0.0f,
        shadowOffsetY: Float = 2.0f,
        gradientEnabled: Boolean = false,
        gradientStartColor: Int = textColor,
        gradientEndColor: Int = textColor,
        backgroundPadding: Float = 0.0f,
        backgroundCornerRadius: Float = 0.0f,
    ): Triple<IntArray, Int, Int> {
        val bitmap = createTextBitmap(
            text = text,
            fontSize = fontSize,
            textColor = textColor,
            bgColor = backgroundColor,
            bold = bold,
            italic = italic,
            underline = underline,
            allCaps = allCaps,
            fontName = fontName,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            depthColor = depthColor,
            depthPx = depthPx,
            shadowEnabled = shadowEnabled,
            shadowColor = shadowColor,
            shadowBlur = shadowBlur,
            shadowOffsetX = shadowOffsetX,
            shadowOffsetY = shadowOffsetY,
            gradientEnabled = gradientEnabled,
            gradientStartColor = gradientStartColor,
            gradientEndColor = gradientEndColor,
            backgroundPadding = backgroundPadding,
            backgroundCornerRadius = backgroundCornerRadius,
        )
        val pixels = bitmapToPixelArray(bitmap)
        return Triple(pixels, bitmap.width, bitmap.height)
    }

    fun createTextPixels(overlay: TextOverlay): Triple<IntArray, Int, Int> {
        return createTextPixels(
            text = overlay.text,
            fontSize = overlay.fontSize,
            textColor = overlay.color,
            backgroundColor = overlay.backgroundColor,
            bold = overlay.bold,
            italic = overlay.italic,
            underline = overlay.underline,
            allCaps = overlay.allCaps,
            fontName = overlay.fontName,
            strokeColor = overlay.strokeColor,
            strokeWidth = overlay.strokeWidth,
            depthColor = overlay.depthColor,
            depthPx = overlay.depthPx,
            shadowEnabled = overlay.shadowEnabled,
            shadowColor = overlay.shadowColor,
            shadowBlur = overlay.shadowBlur,
            shadowOffsetX = overlay.shadowOffsetX,
            shadowOffsetY = overlay.shadowOffsetY,
            gradientEnabled = overlay.gradientEnabled,
            gradientStartColor = overlay.gradientStartColor,
            gradientEndColor = overlay.gradientEndColor,
            backgroundPadding = overlay.backgroundPadding,
            backgroundCornerRadius = overlay.backgroundCornerRadius,
        )
    }

    private fun resolveTypeface(fontName: String?, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val base = when (fontName?.trim()?.lowercase()) {
            "serif" -> Typeface.SERIF
            "mono", "monospace" -> Typeface.MONOSPACE
            "condensed", "sans-serif-condensed" -> Typeface.create("sans-serif-condensed", style)
            "medium", "sans-serif-medium" -> Typeface.create("sans-serif-medium", style)
            else -> Typeface.SANS_SERIF
        }
        return if (base === Typeface.SANS_SERIF || base === Typeface.SERIF || base === Typeface.MONOSPACE) {
            Typeface.create(base, style)
        } else {
            base
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = ((color shr 16) and 0xFF) / 255.0
        val green = ((color shr 8) and 0xFF) / 255.0
        val blue = (color and 0xFF) / 255.0
        return (0.299 * red + 0.587 * green + 0.114 * blue) > 0.55
    }
}
