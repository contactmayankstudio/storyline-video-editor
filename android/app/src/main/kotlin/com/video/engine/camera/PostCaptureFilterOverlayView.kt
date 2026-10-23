package com.video.engine.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapchat-style Post-Capture Geofilters and Time/Date Stamp Overlays.
 * Cycles via Left/Right swipes on the review screen.
 */
enum class PostCaptureGeofilter(val displayName: String, val emoji: String) {
    CLEAN("Original", "✨"),
    CLOCK("Clock Badge", "⏰"),
    DATE("Date Stamp", "📅"),
    BW_NOIR("B&W Noir", "🖤"),
    WARM_SUNSET("Golden Sunset", "🌅"),
    CYBERPUNK("Cyberpunk Neon", "🌆")
}

class PostCaptureFilterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentFilter: PostCaptureGeofilter = PostCaptureGeofilter.CLEAN
        set(value) {
            field = value
            invalidate()
        }

    // Time & Date formatters
    private val clockTimeFormat = SimpleDateFormat("hh:mm", Locale.US)
    private val clockAmPmFormat = SimpleDateFormat("a", Locale.US)
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private val dayFormat = SimpleDateFormat("EEEE", Locale.US)

    // Paints
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66000000")
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#55FFFFFF")
    }

    private val noirColorFilter by lazy {
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            // Boost contrast for authentic B&W noir
            val scale = 1.15f
            val translate = -18f
            postConcat(ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        ColorMatrixColorFilter(matrix)
    }

    fun nextFilter(): PostCaptureGeofilter {
        val values = PostCaptureGeofilter.values()
        val nextIdx = (currentFilter.ordinal + 1) % values.size
        currentFilter = values[nextIdx]
        return currentFilter
    }

    fun prevFilter(): PostCaptureGeofilter {
        val values = PostCaptureGeofilter.values()
        val prevIdx = if (currentFilter.ordinal <= 0) values.size - 1 else currentFilter.ordinal - 1
        currentFilter = values[prevIdx]
        return currentFilter
    }

    fun getFilterNameWithEmoji(): String {
        return when (currentFilter) {
            PostCaptureGeofilter.CLEAN -> "✨ Original"
            PostCaptureGeofilter.CLOCK -> "⏰ " + getFormattedTime()
            PostCaptureGeofilter.DATE -> "📅 " + getFormattedDate()
            PostCaptureGeofilter.BW_NOIR -> "🖤 B&W Noir"
            PostCaptureGeofilter.WARM_SUNSET -> "🌅 Golden Sunset"
            PostCaptureGeofilter.CYBERPUNK -> "🌆 Cyberpunk Neon"
        }
    }

    fun getFormattedTime(): String {
        return clockTimeFormat.format(Date()) + " " + clockAmPmFormat.format(Date()).uppercase(Locale.US)
    }

    fun getFormattedDate(): String {
        return dateFormat.format(Date()).uppercase(Locale.US)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGeofilter(canvas, width.toFloat(), height.toFloat())
    }

    /**
     * Renders the current geofilter effect onto any canvas.
     */
    fun drawGeofilter(canvas: Canvas, w: Float, h: Float) {
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density

        when (currentFilter) {
            PostCaptureGeofilter.CLEAN -> {
                // No overlay
            }
            PostCaptureGeofilter.CLOCK -> {
                drawClockBadge(canvas, w, h, density)
            }
            PostCaptureGeofilter.DATE -> {
                drawDateBadge(canvas, w, h, density)
            }
            PostCaptureGeofilter.BW_NOIR -> {
                drawBwNoirOverlay(canvas, w, h)
            }
            PostCaptureGeofilter.WARM_SUNSET -> {
                drawWarmSunsetOverlay(canvas, w, h)
            }
            PostCaptureGeofilter.CYBERPUNK -> {
                drawCyberpunkOverlay(canvas, w, h)
            }
        }
    }

    private fun drawClockBadge(canvas: Canvas, w: Float, h: Float, density: Float) {
        val now = Date()
        val timeStr = clockTimeFormat.format(now)
        val amPmStr = clockAmPmFormat.format(now).uppercase(Locale.US)
        val dayStr = dayFormat.format(now).uppercase(Locale.US) + " • STORYLINE"

        val cx = w / 2f
        val cy = h * 0.28f

        val badgeWidth = 230f * density
        val badgeHeight = 94f * density
        val rect = RectF(cx - badgeWidth / 2f, cy - badgeHeight / 2f, cx + badgeWidth / 2f, cy + badgeHeight / 2f)

        // Translucent glass pill
        canvas.drawRoundRect(rect, 22f * density, 22f * density, badgeBgPaint)
        canvas.drawRoundRect(rect, 22f * density, 22f * density, badgeBorderPaint)

        // Main clock digits
        textPaint.textSize = 44f * density
        textPaint.setShadowLayer(8f * density, 0f, 2f * density, Color.argb(120, 0, 0, 0))
        canvas.drawText("$timeStr $amPmStr", cx, cy + 4f * density, textPaint)

        // Subtitle (Day & Tagline)
        subTextPaint.textSize = 11.5f * density
        subTextPaint.letterSpacing = 0.15f
        canvas.drawText(dayStr, cx, cy + 28f * density, subTextPaint)
    }

    private fun drawDateBadge(canvas: Canvas, w: Float, h: Float, density: Float) {
        val now = Date()
        val dateStr = dateFormat.format(now).uppercase(Locale.US)
        val dayStr = dayFormat.format(now).uppercase(Locale.US)

        val cx = w / 2f
        val cy = h * 0.28f

        val badgeWidth = 220f * density
        val badgeHeight = 84f * density
        val rect = RectF(cx - badgeWidth / 2f, cy - badgeHeight / 2f, cx + badgeWidth / 2f, cy + badgeHeight / 2f)

        // Translucent glass badge with accent border
        canvas.drawRoundRect(rect, 18f * density, 18f * density, badgeBgPaint)
        canvas.drawRoundRect(rect, 18f * density, 18f * density, badgeBorderPaint)

        // Date String (e.g. 23 OCT 2026)
        textPaint.textSize = 28f * density
        textPaint.setShadowLayer(6f * density, 0f, 2f * density, Color.argb(120, 0, 0, 0))
        canvas.drawText(dateStr, cx, cy + 4f * density, textPaint)

        // Day of Week
        subTextPaint.textSize = 12f * density
        subTextPaint.letterSpacing = 0.2f
        canvas.drawText("✦ $dayStr ✦", cx, cy + 24f * density, subTextPaint)
    }

    private fun drawBwNoirOverlay(canvas: Canvas, w: Float, h: Float) {
        // High contrast monochrome tint
        overlayPaint.shader = null
        overlayPaint.colorFilter = null
        overlayPaint.color = Color.argb(80, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Noir Vignette
        val vignette = RadialGradient(
            w / 2f, h / 2f, Math.max(w, h) * 0.7f,
            intArrayOf(Color.TRANSPARENT, Color.argb(130, 0, 0, 0)),
            floatArrayOf(0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        overlayPaint.shader = vignette
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        overlayPaint.shader = null
    }

    private fun drawWarmSunsetOverlay(canvas: Canvas, w: Float, h: Float) {
        // Warm golden hour sunset amber gradient
        val sunsetGradient = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(35, 255, 120, 0),   // Soft golden sky
                Color.argb(55, 255, 160, 20),  // Warm amber mid
                Color.argb(85, 220, 50, 40)    // Deep sunset orange-rose bottom
            ),
            floatArrayOf(0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        overlayPaint.shader = sunsetGradient
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        overlayPaint.shader = null
    }

    private fun drawCyberpunkOverlay(canvas: Canvas, w: Float, h: Float) {
        // Cyberpunk dual-tone gradient: Electric cyan top-left to Hot magenta bottom-right
        val cyberGradient = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.argb(60, 0, 242, 254),  // Neon Electric Cyan
                Color.argb(25, 120, 0, 255),  // Deep Violet
                Color.argb(65, 255, 0, 128)   // Vibrant Neon Magenta
            ),
            floatArrayOf(0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
        overlayPaint.shader = cyberGradient
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        overlayPaint.shader = null
    }

    fun getColorFilterForBase(): ColorMatrixColorFilter? {
        return if (currentFilter == PostCaptureGeofilter.BW_NOIR) {
            noirColorFilter
        } else {
            null
        }
    }
}
