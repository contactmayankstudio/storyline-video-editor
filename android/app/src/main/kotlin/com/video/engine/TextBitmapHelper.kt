package com.video.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.video.engine.overlay.TextOverlay
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
        fontName: String? = null,
    ): Bitmap {
        if (text.isEmpty()) {
            // Return minimal 1x1 transparent pixels
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        // Setup paint for text rendering
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            typeface = resolveTypeface(fontName, bold, italic)
            isAntiAlias = true
            isDither = true
            textAlign = Paint.Align.LEFT
            setShadowLayer(fontSize * 0.10f, 0f, fontSize * 0.05f, 0x99000000.toInt())
        }

        val lines = text.replace("\r\n", "\n").split('\n').ifEmpty { listOf(" ") }
        val safeLines = lines.map { if (it.isEmpty()) " " else it }
        val fontMetrics = paint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent + fontSize * 0.18f).coerceAtLeast(fontSize * 1.15f)
        val maxLineWidth = safeLines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val horizontalPadding = (fontSize * 0.32f).toInt().coerceAtLeast(16)
        val verticalPadding = (fontSize * 0.28f).toInt().coerceAtLeast(16)
        val bitmapWidth = (maxLineWidth + horizontalPadding * 2).roundToInt().coerceAtLeast(32)
        val bitmapHeight = (lineHeight * safeLines.size + verticalPadding * 2).roundToInt().coerceAtLeast(32)

        // Create bitmap
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clear background
        canvas.drawColor(bgColor, android.graphics.PorterDuff.Mode.CLEAR)

        // Draw multiline text centered line-by-line.
        var baselineY = verticalPadding - fontMetrics.ascent
        safeLines.forEach { line ->
            val lineWidth = paint.measureText(line)
            val xPos = ((bitmapWidth - lineWidth) * 0.5f).coerceAtLeast(horizontalPadding.toFloat())
            canvas.drawText(line, xPos, baselineY, paint)
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
        bold: Boolean = true,
        italic: Boolean = false,
        fontName: String? = null,
    ): Triple<IntArray, Int, Int> {
        val bitmap = createTextBitmap(
            text = text,
            fontSize = fontSize,
            textColor = textColor,
            bold = bold,
            italic = italic,
            fontName = fontName,
        )
        val pixels = bitmapToPixelArray(bitmap)
        return Triple(pixels, bitmap.width, bitmap.height)
    }

    fun createTextPixels(overlay: TextOverlay): Triple<IntArray, Int, Int> {
        return createTextPixels(
            text = overlay.text,
            fontSize = overlay.fontSize,
            textColor = overlay.color,
            bold = overlay.bold,
            italic = overlay.italic,
            fontName = overlay.fontName,
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
            else -> Typeface.SANS_SERIF
        }
        return Typeface.create(base, style)
    }
}
