package com.video.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

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
        bgColor: Int = 0x00000000               // Transparent
    ): Bitmap {
        if (text.isEmpty()) {
            // Return minimal 1x1 transparent pixels
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        
        // Setup paint for text rendering
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            isDither = true
            // Optional: add text effects
            // setShadowLayer(2f, 0f, 0f, 0xFF000000.toInt())
        }
        
        // Measure text dimensions
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        
        // Calculate bitmap size (add padding for antialiasing)
        val textWidth = bounds.width() + 16
        val textHeight = bounds.height() + 16
        val bitmapWidth = textWidth.coerceAtLeast(32)
        val bitmapHeight = textHeight.coerceAtLeast(32)
        
        // Create bitmap
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Clear background
        canvas.drawColor(bgColor, android.graphics.PorterDuff.Mode.CLEAR)
        
        // Draw text centered
        val xPos = (bitmapWidth - bounds.width()) / 2f
        val yPos = (bitmapHeight / 2f) + (bounds.height() / 2f)
        canvas.drawText(text, xPos, yPos, paint)
        
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
        textColor: Int = 0xFFFFFFFF.toInt()
    ): Triple<IntArray, Int, Int> {
        val bitmap = createTextBitmap(text, fontSize, textColor)
        val pixels = bitmapToPixelArray(bitmap)
        return Triple(pixels, bitmap.width, bitmap.height)
    }
}
