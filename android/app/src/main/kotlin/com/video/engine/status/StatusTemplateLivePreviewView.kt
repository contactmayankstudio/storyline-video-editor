package com.video.engine.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

class StatusTemplateLivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var template: StatusTemplate? = null
        set(value) {
            field = value
            particlesInitialized = false
            startTime = SystemClock.uptimeMillis()
            ensureSampleSlides()
            postInvalidateOnAnimation()
        }

    var customStatusText: String? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private var isPlaying: Boolean = true
    private var startTime: Long = SystemClock.uptimeMillis()
    private var pausedTime: Long = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val visualizerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val glitchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washPaint = Paint()
    private val pPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#388BFD")
        strokeWidth = 4f
    }
    private val bgPPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 4f
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
    }
    private val customTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val customPillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 15, 18, 25)
    }
    private val customPillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val bgRect = RectF()
    private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val playIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        textAlign = Paint.Align.CENTER
    }

    // 4 Procedurally rendered aesthetic portrait model slides
    private val sampleSlides = mutableListOf<Bitmap>()
    private var customPhotoBitmap: Bitmap? = null

    // Magical Particle Overlays (Love Hearts, Phonk Sparks, Birthday Glitter, Devotional Petals)
    private enum class ParticleTheme {
        LOVE, ATTITUDE_PHONK, BIRTHDAY_SPARKLE, SPIRITUAL_DEVOTIONAL
    }

    private class StatusParticle {
        var x: Float = 0f
        var y: Float = 0f
        var vx: Float = 0f
        var vy: Float = 0f
        var size: Float = 24f
        var alpha: Float = 1f
        var rotation: Float = 0f
        var rotSpeed: Float = 0f
        var symbol: String = ""
        var color: Int = Color.WHITE
        var swayOffset: Float = 0f
        var swaySpeed: Float = 1f
        var life: Float = 0f
        var maxLife: Float = 100f

        fun reset(w: Float, h: Float, theme: ParticleTheme, randomStart: Boolean = false) {
            val rng = java.util.concurrent.ThreadLocalRandom.current()
            life = if (randomStart) rng.nextFloat() * 100f else 0f
            maxLife = 90f + rng.nextFloat() * 70f
            swayOffset = rng.nextFloat() * 6.28f
            swaySpeed = 1.5f + rng.nextFloat() * 2f
            rotation = rng.nextFloat() * 360f
            rotSpeed = -2f + rng.nextFloat() * 4f

            when (theme) {
                ParticleTheme.LOVE -> {
                    x = rng.nextFloat() * w
                    y = if (randomStart) rng.nextFloat() * h else h + (rng.nextFloat() * 30f)
                    vx = -0.3f + rng.nextFloat() * 0.6f
                    vy = -(1.2f + rng.nextFloat() * 1.8f)
                    size = 20f + rng.nextFloat() * 18f
                    symbol = if (rng.nextBoolean()) "❤️" else "💕"
                    color = if (rng.nextBoolean()) Color.parseColor("#FF2D55") else Color.parseColor("#FF69B4")
                }
                ParticleTheme.ATTITUDE_PHONK -> {
                    x = rng.nextFloat() * w
                    y = if (randomStart) rng.nextFloat() * h else h * 0.85f + (rng.nextFloat() * h * 0.2f)
                    vx = -1.2f + rng.nextFloat() * 2.4f
                    vy = -(2.5f + rng.nextFloat() * 4.5f)
                    size = 18f + rng.nextFloat() * 16f
                    symbol = if (rng.nextFloat() < 0.45f) "🔥" else if (rng.nextFloat() < 0.8f) "⚡" else "💥"
                    color = if (rng.nextBoolean()) Color.parseColor("#00F2FE") else Color.parseColor("#FF9500")
                }
                ParticleTheme.BIRTHDAY_SPARKLE -> {
                    x = rng.nextFloat() * w
                    y = rng.nextFloat() * h
                    vx = -0.5f + rng.nextFloat() * 1.0f
                    vy = -0.6f + rng.nextFloat() * 1.2f
                    size = 18f + rng.nextFloat() * 22f
                    symbol = if (rng.nextFloat() < 0.5f) "✨" else "🌟"
                    color = if (rng.nextBoolean()) Color.parseColor("#FFD700") else Color.parseColor("#FFF8DC")
                }
                ParticleTheme.SPIRITUAL_DEVOTIONAL -> {
                    x = rng.nextFloat() * w
                    y = if (randomStart) rng.nextFloat() * h else -30f - (rng.nextFloat() * 30f)
                    vx = -0.8f + rng.nextFloat() * 1.6f
                    vy = 1.0f + rng.nextFloat() * 2.0f
                    size = 20f + rng.nextFloat() * 18f
                    symbol = if (rng.nextFloat() < 0.6f) "🌸" else if (rng.nextFloat() < 0.85f) "🪔" else "✨"
                    color = Color.parseColor("#FF9933")
                }
            }
        }
    }

    private val particles = Array(32) { StatusParticle() }
    private var particlesInitialized = false
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val auraRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    init {
        setOnClickListener {
            isPlaying = !isPlaying
            if (!isPlaying) {
                pausedTime = SystemClock.uptimeMillis()
                try { mediaPlayer?.pause() } catch (_: Exception) {}
            } else {
                startTime += (SystemClock.uptimeMillis() - pausedTime)
                try { mediaPlayer?.start() } catch (_: Exception) {}
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isPlaying = true
        startTime = SystemClock.uptimeMillis()
        ensureSampleSlides()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isPlaying = false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            isPlaying = true
            postInvalidateOnAnimation()
        } else {
            isPlaying = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val density = resources.displayMetrics.density
        val defaultWidth = (225 * density).toInt()
        val defaultHeight = (400 * density).toInt()

        val desiredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> widthSize.coerceAtLeast(defaultWidth)
            else -> defaultWidth
        }

        val desiredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> heightSize.coerceAtLeast(defaultHeight)
            else -> ((desiredWidth * 16f) / 9f).toInt()
        }

        setMeasuredDimension(desiredWidth, desiredHeight)
    }

    fun setCustomPhoto(filePath: String) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)
            var sampleSize = 1
            while (options.outWidth / sampleSize > 1080 || options.outHeight / sampleSize > 1920) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBmp = BitmapFactory.decodeFile(filePath, decodeOptions)
            val bmp = rawBmp?.let { StatusMediaHelper.smartCropToPortrait916(it) }
            if (bmp != null) {
                customPhotoBitmap?.recycle()
                customPhotoBitmap = bmp
                postInvalidateOnAnimation()
            }
        } catch (_: Exception) {}
    }

    private fun ensureSampleSlides() {
        if (sampleSlides.isNotEmpty() && sampleSlides.all { !it.isRecycled }) return
        sampleSlides.forEach {
            try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
        }
        sampleSlides.clear()
        prepareSampleSlides()
    }

    private fun prepareSampleSlides() {
        // Generate 4 high-fashion aesthetic portrait cards with gradients & silhouettes
        val w = 540
        val h = 960

        val themes = listOf(
            Pair("#1A0B2E", "#8A2387"), // Violet Sunset
            Pair("#0F2027", "#2C5364"), // Deep Cyan Oceanic
            Pair("#200122", "#6f0000"), // Crimson Noir
            Pair("#141E30", "#243B55")  // Midnight Royal
        )

        for (theme in themes) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            // Background Gradient
            val bgGradient = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.parseColor(theme.first),
                Color.parseColor(theme.second),
                Shader.TileMode.CLAMP
            )
            val p = Paint().apply { shader = bgGradient }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

            // Stylized Model Silhouette
            val sPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(160, 255, 255, 255)
            }

            // Head & Shoulders Silhouette
            val headRadius = w * 0.16f
            val headCenterY = h * 0.40f
            c.drawCircle(w * 0.5f, headCenterY, headRadius, sPaint)

            val bodyPath = Path()
            bodyPath.moveTo(w * 0.22f, h.toFloat())
            bodyPath.quadTo(w * 0.28f, headCenterY + headRadius * 1.3f, w * 0.40f, headCenterY + headRadius * 1.3f)
            bodyPath.lineTo(w * 0.60f, headCenterY + headRadius * 1.3f)
            bodyPath.quadTo(w * 0.72f, headCenterY + headRadius * 1.3f, w * 0.78f, h.toFloat())
            bodyPath.close()
            c.drawPath(bodyPath, sPaint)

            // Aesthetic Ambient Glow disc
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.5f, headCenterY, w * 0.45f,
                    intArrayOf(Color.argb(100, 255, 220, 180), Color.argb(0, 255, 220, 180)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            c.drawCircle(w * 0.5f, headCenterY, w * 0.45f, glowPaint)

            sampleSlides.add(bmp)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tm = template ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) {
            if (isPlaying) postInvalidateOnAnimation()
            return
        }

        if (customPhotoBitmap == null) {
            ensureSampleSlides()
            if (sampleSlides.isEmpty()) return
        }

        val totalDurationMs = tm.durationMs.coerceAtLeast(6000L)
        val elapsedMs = if (isPlaying) {
            (SystemClock.uptimeMillis() - startTime) % totalDurationMs
        } else {
            (pausedTime - startTime) % totalDurationMs
        }

        val numSlides = if (customPhotoBitmap != null) 4 else sampleSlides.size.coerceAtLeast(1)
        val slideDurationMs = totalDurationMs / numSlides
        val currentSlideIndex = ((elapsedMs / slideDurationMs) % numSlides).toInt()
        val slideProgress = (elapsedMs % slideDurationMs) / slideDurationMs.toFloat()

        // 1. Draw Active Slide with Ken Burns Dynamic Zoom
        val currentBmp = customPhotoBitmap ?: sampleSlides[currentSlideIndex % sampleSlides.size]
        val nextSlideIndex = (currentSlideIndex + 1) % numSlides
        val nextBmp = customPhotoBitmap ?: sampleSlides[nextSlideIndex % sampleSlides.size]

        if (currentBmp.isRecycled || nextBmp.isRecycled) {
            ensureSampleSlides()
            if (isPlaying) postInvalidateOnAnimation()
            return
        }

        // Safe beat pulse calculation from BPM
        val safeBpm = tm.bpm.coerceIn(40, 240)
        val beatIntervalMs = (60_000f / safeBpm).toLong().coerceAtLeast(250L)
        val beatPhase = ((elapsedMs % beatIntervalMs).toFloat() / beatIntervalMs.toFloat()).coerceIn(0f, 1f)
        val beatPulse = (1.0f - beatPhase).coerceIn(0f, 1f)

        // Slot transition
        val currentSlot = tm.slots.getOrNull(currentSlideIndex % tm.slots.size.coerceAtLeast(1))
        val transitionType = currentSlot?.transitionOut ?: "crossfade"

        // Beat shake translation
        var shakeX = 0f
        var shakeY = 0f
        if (beatPhase < 0.20f && transitionType in listOf("beat_drop_zoom", "flash_cut", "rgb_glitch_split", "flash", "glitch")) {
            shakeX = (Math.sin(elapsedMs / 20.0).toFloat() * 12f * beatPulse)
            shakeY = (Math.cos(elapsedMs / 20.0).toFloat() * 8f * beatPulse)
        }

        // 1-Photo Choreographed Ken Burns Camera Movements per slide
        var panOffsetX = 0f
        var panOffsetY = 0f
        val zoom = when (currentSlideIndex % 4) {
            0 -> 1.0f + (slideProgress * 0.18f) + (beatPulse * 0.04f) // Smooth Zoom In
            1 -> {
                panOffsetY = (slideProgress * 15f)
                1.22f - (slideProgress * 0.16f) + (beatPulse * 0.05f) // Zoom Out with drop
            }
            2 -> {
                panOffsetX = (-0.05f + slideProgress * 0.10f) * w // Pan Left to Right
                1.14f + (beatPulse * 0.03f)
            }
            else -> 1.08f + (slideProgress * 0.16f) + (beatPulse * 0.06f) // Beat Punch Zoom
        }

        canvas.save()
        canvas.translate(shakeX + panOffsetX, shakeY + panOffsetY)

        val baseScale = Math.max(w / currentBmp.width.toFloat(), h / currentBmp.height.toFloat()) * zoom
        val scaledW = currentBmp.width.toFloat() * baseScale
        val scaledH = currentBmp.height.toFloat() * baseScale

        matrix.reset()
        matrix.postScale(baseScale, baseScale)
        matrix.postTranslate((w - scaledW) / 2f, (h - scaledH) / 2f)

        // Draw primary photo
        bgPaint.alpha = 255
        canvas.drawBitmap(currentBmp, matrix, bgPaint)

        // 2. Crossfade transition on last 25% of slide duration
        if (slideProgress > 0.75f && customPhotoBitmap == null) {
            val fadeProgress = (slideProgress - 0.75f) / 0.25f
            bgPaint.alpha = (fadeProgress * 255).toInt().coerceIn(0, 255)

            val nextScale = Math.max(w / nextBmp.width.toFloat(), h / nextBmp.height.toFloat()) * (1.0f + fadeProgress * 0.08f)
            val nScaledW = nextBmp.width.toFloat() * nextScale
            val nScaledH = nextBmp.height.toFloat() * nextScale
            matrix.reset()
            matrix.postScale(nextScale, nextScale)
            matrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f)
            canvas.drawBitmap(nextBmp, matrix, bgPaint)
            bgPaint.alpha = 255
        }

        // 3. RGB Glitch Chromatic Aberration on Beat Drops
        if ((transitionType == "rgb_glitch_split" || transitionType == "glitch") && beatPhase < 0.25f) {
            glitchPaint.color = Color.argb((beatPulse * 70).toInt(), 0, 255, 255)
            canvas.drawRect(0f, 0f, w, h, glitchPaint)
        }

        // 4. White Flash Cut at slide start
        if (slideProgress < 0.12f) {
            val flashAlpha = ((1f - slideProgress / 0.12f) * 160).toInt().coerceIn(0, 160)
            flashPaint.color = Color.argb(flashAlpha, 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }

        // 5. Template Gradient Color Wash
        val safeH = h.coerceAtLeast(10f)
        val endColorInt = parseColorSafe(tm.gradientEndHex, Color.MAGENTA)
        val washGradient = LinearGradient(
            0f, 0f, 0f, safeH,
            intArrayOf(
                Color.argb(40, 0, 0, 0),
                Color.argb(30, 0, 0, 0),
                Color.argb(160, Color.red(endColorInt), Color.green(endColorInt), Color.blue(endColorInt))
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        washPaint.shader = washGradient
        canvas.drawRect(0f, 0f, w, h, washPaint)

        canvas.restore()

        // 5.5. Mast-Style Magical Animated Canvas Particle Overlays
        drawMagicalParticles(canvas, w, h, tm, elapsedMs, beatPulse)

        // 6. Kinetic Glowing Lyrical Typography
        drawKineticLyrics(canvas, w, h, tm, elapsedMs, totalDurationMs, beatPulse)

        // 7. Live Neon Audio Visualizer Waveform Equalizer
        drawAudioVisualizer(canvas, w, h, tm, elapsedMs, beatPulse)

        // 8. Progress Time Scrubber Pill at Top
        drawProgressBadge(canvas, w, elapsedMs, totalDurationMs, tm)

        // 9. Pause Overlay if paused
        if (!isPlaying) {
            drawPauseOverlay(canvas, w, h)
        }

        // Continue 60fps loop
        if (isPlaying) {
            postInvalidateOnAnimation()
        }
    }

    private fun parseColorSafe(hex: String, fallback: Int = Color.WHITE): Int {
        return try {
            val clean = hex.trim()
            if (clean.startsWith("0x", ignoreCase = true)) {
                clean.substring(2).toLong(16).toInt()
            } else if (clean.startsWith("#")) {
                Color.parseColor(clean)
            } else {
                Color.parseColor("#$clean")
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun drawKineticLyrics(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        totalDurationMs: Long,
        beatPulse: Float
    ) {
        val overlays = tm.textOverlays
        if (overlays.isNotEmpty()) {
            // Pick lyrics based on timeline
            val overlayIdx = ((elapsedMs * overlays.size) / totalDurationMs).toInt().coerceIn(0, overlays.size - 1)
            val currentOverlay = overlays[overlayIdx]

            val text = currentOverlay.text
            val textSizeSp = (currentOverlay.scale * 20f) * resources.displayMetrics.scaledDensity
            val bounce = beatPulse * 4f

            textPaint.apply {
                color = parseColorSafe(currentOverlay.colorHex, Color.WHITE)
                textSize = textSizeSp + bounce
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(14f, 0f, 0f, parseColorSafe(tm.gradientStartHex, Color.CYAN))
            }

            // If user has custom text at bottom, position template lyrics higher (0.22) so they don't collide
            val basePosYPercent = if (!customStatusText.isNullOrBlank()) 0.22f else currentOverlay.posYPercent
            val yPos = (h * basePosYPercent) - bounce
            val maxTextWidth = w * 0.90f
            if (textPaint.measureText(text) > maxTextWidth) {
                val words = text.split(" ")
                if (words.size > 2) {
                    val mid = words.size / 2
                    val line1 = words.subList(0, mid).joinToString(" ")
                    val line2 = words.subList(mid, words.size).joinToString(" ")
                    canvas.drawText(line1, w / 2f, yPos - (textSizeSp * 0.65f), textPaint)
                    canvas.drawText(line2, w / 2f, yPos + (textSizeSp * 0.65f), textPaint)
                } else {
                    canvas.drawText(text, w / 2f, yPos, textPaint)
                }
            } else {
                canvas.drawText(text, w / 2f, yPos, textPaint)
            }
            textPaint.setShadowLayer(0f, 0f, 0f, 0)
        }

        // Draw custom user name / lyrical text bouncing to the beat with glowing kinetic typography
        val customText = customStatusText
        if (!customText.isNullOrBlank()) {
            val customBounce = beatPulse * 5f
            val customTextSize = (21f * resources.displayMetrics.scaledDensity) + customBounce
            customTextPaint.apply {
                color = Color.parseColor("#FFFDF0")
                textSize = customTextSize
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(16f + (beatPulse * 6f), 0f, 0f, Color.parseColor("#FFD700"))
            }

            val customY = (h * 0.74f) - customBounce
            val measuredW = customTextPaint.measureText(customText)
            val pillPadH = 14f * resources.displayMetrics.density
            val pillPadV = 6f * resources.displayMetrics.density
            val pillRect = RectF(
                (w / 2f) - (measuredW / 2f) - pillPadH,
                customY - customTextSize + (pillPadV * 0.5f),
                (w / 2f) + (measuredW / 2f) + pillPadH,
                customY + (pillPadV * 1.5f)
            )
            canvas.drawRoundRect(pillRect, 18f, 18f, customPillBgPaint)
            canvas.drawRoundRect(pillRect, 18f, 18f, customPillStrokePaint)
            canvas.drawText(customText, w / 2f, customY, customTextPaint)
            customTextPaint.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    private fun drawAudioVisualizer(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float
    ) {
        val numBars = 26
        val barWidth = (w * 0.85f) / numBars
        val startX = w * 0.075f
        val baseY = h * 0.88f

        val neonColor = parseColorSafe(tm.gradientStartHex, Color.CYAN)
        visualizerPaint.apply {
            color = neonColor
            strokeCap = Paint.Cap.ROUND
            strokeWidth = barWidth * 0.65f
            setShadowLayer(8f, 0f, 0f, neonColor)
        }

        for (i in 0 until numBars) {
            val wave = Math.sin((elapsedMs / 140.0) + (i * 0.35)).toFloat()
            val rawH = (Math.abs(wave) * 38f) + (beatPulse * 22f * ((i % 3) + 1))
            val barH = rawH.coerceIn(8f, 75f)

            val bx = startX + (i * barWidth) + (barWidth / 2f)
            canvas.drawLine(bx, baseY, bx, baseY - barH, visualizerPaint)
        }
        visualizerPaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun drawProgressBadge(
        canvas: Canvas,
        w: Float,
        elapsedMs: Long,
        totalDurationMs: Long,
        tm: StatusTemplate
    ) {
        // Thin top progress bar
        val progress = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        canvas.drawLine(16f, 16f, w - 16f, 16f, bgPPaint)
        canvas.drawLine(16f, 16f, 16f + (w - 32f) * progress, 16f, pPaint)

        // Top playing badge
        val curSec = elapsedMs / 1000
        val totSec = totalDurationMs / 1000
        val timeStr = String.format("▶ %s • %02d:%02d / %02d:%02d", tm.emoji, curSec / 60, curSec % 60, totSec / 60, totSec % 60)

        bgRect.set(w / 2f - 140f, 26f, w / 2f + 140f, 58f)
        canvas.drawRoundRect(bgRect, 16f, 16f, pillBgPaint)
        canvas.drawText(timeStr, w / 2f, 48f, badgePaint)
    }

    private fun resolveParticleTheme(tm: StatusTemplate): ParticleTheme {
        val cat = tm.category.lowercase()
        val title = tm.title.lowercase()
        return when {
            cat.contains("love") || title.contains("love") || title.contains("romantic") || tm.emoji in listOf("💖", "❤️", "💕") ->
                ParticleTheme.LOVE
            cat.contains("attitude") || title.contains("phonk") || title.contains("bass") || title.contains("swag") || tm.emoji in listOf("🔥", "⚡", "💥", "😎") ->
                ParticleTheme.ATTITUDE_PHONK
            cat.contains("birthday") || title.contains("birthday") || title.contains("bday") || cat.contains("celebration") || tm.emoji in listOf("🎂", "👑", "🎉") ->
                ParticleTheme.BIRTHDAY_SPARKLE
            cat.contains("bhakti") || cat.contains("devotional") || title.contains("ram") || title.contains("krishna") || title.contains("divine") || tm.emoji in listOf("🪔", "🪶", "🚩", "🙏") ->
                ParticleTheme.SPIRITUAL_DEVOTIONAL
            else -> ParticleTheme.BIRTHDAY_SPARKLE
        }
    }

    private fun drawMagicalParticles(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float
    ) {
        val theme = resolveParticleTheme(tm)
        if (!particlesInitialized) {
            particles.forEach { it.reset(w, h, theme, randomStart = true) }
            particlesInitialized = true
        }

        // Draw Saffron Divine Aura rays for spiritual/devotional templates
        if (theme == ParticleTheme.SPIRITUAL_DEVOTIONAL) {
            val centerX = w * 0.5f
            val centerY = h * 0.15f
            auraRayPaint.color = Color.argb((25 + (beatPulse * 45)).toInt(), 255, 153, 51)
            val numRays = 12
            val rayLen = w * 0.75f
            for (i in 0 until numRays) {
                val angle = ((elapsedMs * 0.015f) + (i * (360f / numRays))) * (Math.PI / 180.0)
                val rx = centerX + (Math.cos(angle) * rayLen).toFloat()
                val ry = centerY + (Math.sin(angle) * rayLen).toFloat()
                canvas.drawLine(centerX, centerY, rx, ry, auraRayPaint)
            }
        }

        // Draw animated particles
        particles.forEach { p ->
            p.life += 1f
            if (p.life >= p.maxLife || p.y < -50f || p.y > h + 60f) {
                p.reset(w, h, theme, randomStart = false)
            }

            val beatBoost = if (beatPulse > 0.5f) (beatPulse - 0.5f) * 2f else 0f
            val currentSway = (Math.sin(p.swayOffset + (elapsedMs / 400.0) * p.swaySpeed).toFloat()) * 1.5f

            p.x += p.vx + currentSway
            p.y += p.vy * (1.0f + beatBoost * 1.2f)
            p.rotation += p.rotSpeed

            val lifeProgress = (p.life / p.maxLife).coerceIn(0f, 1f)
            val alphaProgress = if (lifeProgress < 0.2f) {
                lifeProgress / 0.2f
            } else if (lifeProgress > 0.75f) {
                (1f - lifeProgress) / 0.25f
            } else {
                1f
            }

            val finalAlpha = (alphaProgress * 230).toInt().coerceIn(0, 255)
            particlePaint.textSize = p.size + (beatPulse * 4f)
            particlePaint.alpha = finalAlpha
            particlePaint.setShadowLayer(10f + beatPulse * 8f, 0f, 0f, p.color)

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)
            canvas.drawText(p.symbol, 0f, 0f, particlePaint)
            canvas.restore()
        }
        particlePaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun drawPauseOverlay(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.drawText("▶", w / 2f, h / 2f + 20f, playIconPaint)
    }

    private var onCreateStatusClick: (() -> Unit)? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    fun bindTemplate(tmpl: StatusTemplate, mp: android.media.MediaPlayer? = null) {
        this.template = tmpl
        this.mediaPlayer = mp
        this.particlesInitialized = false
        this.startTime = SystemClock.uptimeMillis()
        this.isPlaying = true
        ensureSampleSlides()
        postInvalidateOnAnimation()
    }

    fun setOnCreateStatusClickListener(listener: () -> Unit) {
        this.onCreateStatusClick = listener
    }

    fun release() {
        isPlaying = false
        sampleSlides.forEach {
            try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
        }
        sampleSlides.clear()
        customPhotoBitmap?.recycle()
        customPhotoBitmap = null
        mediaPlayer = null
    }
}
