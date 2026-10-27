package com.video.engine.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.util.Log
import com.video.engine.export.CanvasVideoEncoder
import java.io.File

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

    var customTexts: StatusCustomTexts = StatusCustomTexts()
        set(value) {
            field = value
            if (value.quoteText != null) {
                customStatusText = value.quoteText
            }
            postInvalidateOnAnimation()
        }

    var customStatusText: String? = null
        set(value) {
            field = value
            if (value != null && customTexts.quoteText == null) {
                customTexts.quoteText = value
            }
            postInvalidateOnAnimation()
        }

    var onTextClickListener: (() -> Unit)? = null

    // Touch gesture adjustment for text: Position (Norm X/Y) & Scale
    var customTextNormX: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.10f, 0.90f)
            postInvalidateOnAnimation()
        }
    var customTextNormY: Float = 0.74f
        set(value) {
            field = value.coerceIn(0.12f, 0.88f)
            postInvalidateOnAnimation()
        }
    var customTextScaleFactor: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.55f, 2.8f)
            postInvalidateOnAnimation()
        }

    /**
     * When true, live video is actively playing in real-time beneath this view via a hardware TextureView.
     * In this mode, renderFrameAt skips solid slide bitmap rendering so real motion video shines through!
     */
    var hasActiveLiveVideo: Boolean = false
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    private var isDraggingText = false
    private var isTouchInteractingWithText = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val textBoundingRect = RectF()
    private val textHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val textHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
    }
    private val textTouchHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE500")
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val textTouchHintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
    }
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            customTextScaleFactor = (customTextScaleFactor * detector.scaleFactor).coerceIn(0.55f, 2.8f)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            postInvalidateOnAnimation()
            return true
        }
    })

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
    private val transMatrix = Matrix()
    private val transNextMatrix = Matrix()
    private val transPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val transNextPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val transFlarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transHeartPath = Path()
    private val transShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transSpeedStreakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 3f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    // Vinyl Disc & Floating Music Notes
    private val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vinylOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vinylGroovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val vinylLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vinylHolePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    // Interactive Tap-to-Play/Pause Feedback Badge
    private var tapFeedbackAlpha = 0f
    private var tapFeedbackScale = 1.0f
    private var tapFeedbackText = "▶"
    private var touchDownTime = 0L
    private val tapFeedbackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tapFeedbackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val tapFeedbackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // Scrubber dot paint
    private val scrubberDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Distinct Unique Theme Graphic Paints ---
    // Polaroid & 35mm Vintage Film
    private val polaroidFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FBFBFC") }
    private val polaroidShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 0, 0, 0) }
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(195, 235, 230, 210) }
    private val recTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = 24f
    }
    private val recDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF1744") }
    private val polaroidSignaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22242A")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // Spotify Music Player Glassmorphic Card
    private val spotifyCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 18, 20, 28) }
    private val spotifyCardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(90, 255, 255, 255)
    }
    private val spotifyTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val spotifyArtistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        typeface = Typeface.DEFAULT_BOLD
    }
    private val spotifyProgressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val spotifyProgressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1DB954")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val spotifyControlsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    // Royal Attitude & Sigma King
    private val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        textSize = 52f
        textAlign = Paint.Align.CENTER
    }
    private val attitudeGoldBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFD700")
    }
    private val attitudeBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 15, 15, 20)
    }
    private val fireSparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cyberpunk Neon HUD
    private val hudBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#00F2FE")
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        typeface = Typeface.MONOSPACE
        textSize = 20f
    }
    private val scanlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(22, 0, 0, 0)
        strokeWidth = 1.5f
    }

    // Birthday & Celebration
    private val confettiColors = intArrayOf(
        Color.parseColor("#FF1744"),
        Color.parseColor("#FFD700"),
        Color.parseColor("#00F2FE"),
        Color.parseColor("#E040FB"),
        Color.parseColor("#76FF03"),
        Color.parseColor("#FF9100")
    )
    private val confettiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val birthdayBannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 215, 0)
    }
    private val birthdayBannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#221200")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // Divine Bhakti Sunburst Halo
    private val divineSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9933")
        textSize = 54f
        textAlign = Paint.Align.CENTER
    }

    // 4 Procedurally rendered aesthetic portrait model slides
    private val sampleSlides = mutableListOf<Bitmap>()
    private var customPhotoBitmap: Bitmap? = null

    // Magical Particle Overlays (Love Hearts, Phonk Sparks, Birthday Glitter, Devotional Petals, Rain, Snow, Vintage, Embers)
    private enum class ParticleTheme {
        LOVE, ATTITUDE_PHONK, BIRTHDAY_SPARKLE, SPIRITUAL_DEVOTIONAL, RAIN, SNOW, VINTAGE_FILM, GOLDEN_EMBERS
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
                ParticleTheme.RAIN -> {
                    x = rng.nextFloat() * w
                    y = if (randomStart) rng.nextFloat() * h else -40f
                    vx = -1.2f + rng.nextFloat() * 0.4f
                    vy = 14f + rng.nextFloat() * 10f
                    size = 15f + rng.nextFloat() * 8f
                    symbol = "💧"
                    color = Color.parseColor("#80D8FF")
                }
                ParticleTheme.SNOW -> {
                    x = rng.nextFloat() * w
                    y = if (randomStart) rng.nextFloat() * h else -25f
                    vx = -0.5f + rng.nextFloat() * 1.0f
                    vy = 1.5f + rng.nextFloat() * 2.2f
                    size = 16f + rng.nextFloat() * 12f
                    symbol = if (rng.nextBoolean()) "❄️" else "⚪"
                    color = Color.WHITE
                }
                ParticleTheme.VINTAGE_FILM -> {
                    x = rng.nextFloat() * w
                    y = rng.nextFloat() * h
                    vx = -0.3f + rng.nextFloat() * 0.6f
                    vy = -0.3f + rng.nextFloat() * 0.6f
                    size = 12f + rng.nextFloat() * 10f
                    symbol = if (rng.nextFloat() < 0.4f) "🎞️" else "•"
                    color = Color.parseColor("#E0D6C3")
                }
                ParticleTheme.GOLDEN_EMBERS -> {
                    x = rng.nextFloat() * w
                    y = if (randomStart) rng.nextFloat() * h else h + 20f
                    vx = -0.9f + rng.nextFloat() * 1.8f
                    vy = -(2.5f + rng.nextFloat() * 3.5f)
                    size = 16f + rng.nextFloat() * 14f
                    symbol = if (rng.nextBoolean()) "🔥" else "✨"
                    color = Color.parseColor("#FFD700")
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

    var customVideoPath: String? = null

    fun setCustomPhoto(filePath: String?) {
        if (filePath.isNullOrBlank() || !java.io.File(filePath).exists()) {
            customPhotoBitmap?.recycle()
            customPhotoBitmap = null
            customVideoPath = null
            sampleSlides.clear()
            prepareSampleSlides()
            postInvalidateOnAnimation()
            return
        }
        val file = File(filePath)
        if (StatusMediaHelper.isVideoFile(filePath)) {
            customVideoPath = filePath
            loadVideoSampleSlides(file)
            return
        }
        customVideoPath = null
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

    private fun loadVideoSampleSlides(videoFile: File) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 15000L
            val durationUs = durationMs * 1000L

            sampleSlides.forEach { if (!it.isRecycled) it.recycle() }
            sampleSlides.clear()

            // Extract 4 keyframe samples across the video duration
            val frameCount = 4
            for (i in 0 until frameCount) {
                val timeUs = (durationUs * i) / frameCount
                val rawBmp = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                if (rawBmp != null) {
                    val cropped = StatusMediaHelper.smartCropToPortrait916(rawBmp)
                    sampleSlides.add(cropped)
                }
            }

            customPhotoBitmap = sampleSlides.firstOrNull()
            postInvalidateOnAnimation()
        } catch (e: Exception) {
            Log.e("StatusLivePreview", "Error loading video sample slides: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun ensureSampleSlides() {
        if (sampleSlides.isNotEmpty() && sampleSlides.all { !it.isRecycled }) return
        sampleSlides.clear()
        prepareSampleSlides()
    }

    private fun prepareSampleSlides() {
        // Load high-fashion portrait human models (female, male, couple, festive)
        val tm = template
        val preferredAsset = tm?.let { StatusSampleModelCache.getModelAssetForTemplate(it) }
            ?: StatusSampleModelCache.MODEL_FEMALE_STYLISH

        val modelPaths = mutableListOf(preferredAsset)
        for (path in StatusSampleModelCache.ALL_MODEL_ASSET_PATHS) {
            if (!modelPaths.contains(path)) {
                modelPaths.add(path)
            }
        }

        val w = 540
        val h = 960

        for (path in modelPaths.take(4)) {
            val bmp = StatusSampleModelCache.getModelBitmap(context, path, w, h)
            if (bmp != null && !bmp.isRecycled) {
                sampleSlides.add(bmp)
            }
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

        renderFrameAt(canvas, w, h, elapsedMs, isExport = false)

        // Continue 60fps loop
        if (isPlaying) {
            postInvalidateOnAnimation()
        }
    }

    fun renderFrameAt(
        canvas: Canvas,
        w: Float,
        h: Float,
        elapsedMs: Long,
        isExport: Boolean = false,
        motionFrameBitmap: Bitmap? = null
    ) {
        val tm = template ?: return
        val totalDurationMs = tm.durationMs.coerceAtLeast(6000L)
        val numSlides = if (customPhotoBitmap != null || motionFrameBitmap != null) 4 else sampleSlides.size.coerceAtLeast(1)
        val slideDurationMs = totalDurationMs / numSlides
        val currentSlideIndex = ((elapsedMs / slideDurationMs) % numSlides).toInt()
        val slideProgress = (elapsedMs % slideDurationMs) / slideDurationMs.toFloat()

        // 1. Draw Active Slide with Ken Burns Dynamic Zoom
        val currentBmp = motionFrameBitmap ?: (customPhotoBitmap ?: sampleSlides[currentSlideIndex % sampleSlides.size])
        val nextSlideIndex = (currentSlideIndex + 1) % numSlides
        val nextBmp = motionFrameBitmap ?: (customPhotoBitmap ?: sampleSlides[nextSlideIndex % sampleSlides.size])

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

        // Template motion style choreography
        val motionStyle = tm.getMotionStyle()

        // Slot transition
        val currentSlot = tm.slots.getOrNull(currentSlideIndex % tm.slots.size.coerceAtLeast(1))
        val transitionType = currentSlot?.transitionOut ?: "crossfade"

        // Beat shake translation, Dutch tilt roll, and Ken Burns camera choreography tailored per Motion Style
        var shakeX = 0f
        var shakeY = 0f
        var rollDeg = 0f
        var panOffsetX = 0f
        var panOffsetY = 0f
        var zoom = 1.0f

        when (motionStyle) {
            TemplateMotionStyle.THREE_D_ZOOM -> {
                // Punch zoom with dynamic 3D camera roll sway
                zoom = when (currentSlideIndex % 4) {
                    0 -> 1.00f + (slideProgress * 0.45f) + (beatPulse * 0.08f)
                    1 -> 1.48f - (slideProgress * 0.38f) + (beatPulse * 0.07f)
                    2 -> 1.08f + (slideProgress * 0.42f) + (beatPulse * 0.09f)
                    else -> 1.45f - (slideProgress * 0.32f) + (beatPulse * 0.08f)
                }
                rollDeg = when (currentSlideIndex % 4) {
                    0 -> slideProgress * 3.0f
                    1 -> 3.0f - (slideProgress * 5.5f)
                    2 -> -2.5f + (slideProgress * 5.0f)
                    else -> 2.5f - (slideProgress * 4.0f)
                }
                if (beatPhase < 0.20f) {
                    shakeX = (Math.sin(elapsedMs / 18.0).toFloat() * 10f * beatPulse)
                    shakeY = (Math.cos(elapsedMs / 18.0).toFloat() * 8f * beatPulse)
                }
            }
            TemplateMotionStyle.VELOCITY_RAMP -> {
                // Non-linear acceleration velocity curve into whip transition
                val rampProgress = Math.pow(slideProgress.toDouble(), 2.4).toFloat()
                zoom = when (currentSlideIndex % 2) {
                    0 -> 1.04f + (rampProgress * 0.38f) + (beatPulse * 0.06f)
                    else -> 1.38f - (rampProgress * 0.32f) + (beatPulse * 0.06f)
                }
                panOffsetX = when (currentSlideIndex % 2) {
                    0 -> rampProgress * (w * 0.08f)
                    else -> (1f - rampProgress) * -(w * 0.08f)
                }
                if (beatPhase < 0.15f) {
                    shakeX = (Math.sin(elapsedMs / 14.0).toFloat() * 8f * beatPulse)
                }
            }
            TemplateMotionStyle.PHONK_ATTITUDE -> {
                // Aggressive Dutch angle roll & heavy bass slam
                zoom = when (currentSlideIndex % 4) {
                    0 -> 1.12f + (slideProgress * 0.24f) + (beatPulse * 0.12f)
                    1 -> 1.38f - (slideProgress * 0.28f) + (beatPulse * 0.14f)
                    2 -> 1.06f + (slideProgress * 0.36f) + (beatPulse * 0.12f)
                    else -> 1.20f + (slideProgress * 0.30f) + (beatPulse * 0.15f)
                }
                rollDeg = when (currentSlideIndex % 4) {
                    0 -> -4.5f + (slideProgress * 8.0f)
                    1 -> 3.5f - (slideProgress * 7.5f)
                    2 -> -4.0f + (slideProgress * 8.5f)
                    else -> (Math.sin(elapsedMs / 100.0).toFloat() * 2.5f)
                }
                if (beatPhase < 0.30f) {
                    shakeX = (Math.sin(elapsedMs / 15.0).toFloat() * 16f * beatPulse)
                    shakeY = (Math.cos(elapsedMs / 15.0).toFloat() * 12f * beatPulse)
                }
            }
            TemplateMotionStyle.ROMANTIC_LOVE -> {
                // Dreamy slow Ken Burns floating, zero harsh shake
                zoom = when (currentSlideIndex % 4) {
                    0 -> 1.02f + (slideProgress * 0.08f) + (beatPulse * 0.02f)
                    1 -> 1.08f + (slideProgress * 0.06f)
                    2 -> 1.12f - (slideProgress * 0.07f)
                    else -> 1.05f + (slideProgress * 0.09f)
                }
                panOffsetX = (Math.sin(slideProgress * Math.PI).toFloat() * 16f) * (if (currentSlideIndex % 2 == 0) 1f else -1f)
                panOffsetY = (Math.cos(slideProgress * Math.PI).toFloat() * 10f)
                rollDeg = (Math.sin(slideProgress * Math.PI).toFloat() * 0.8f) * (if (currentSlideIndex % 2 == 0) 1f else -1f)
            }
            TemplateMotionStyle.CELEBRATION_POP -> {
                // Upbeat celebratory bounce with pop zoom
                val bouncePhase = Math.sin(slideProgress * Math.PI).toFloat()
                zoom = 1.00f + (bouncePhase * 0.22f) + (beatPulse * 0.07f)
                shakeY = -Math.abs(Math.sin(beatPhase * Math.PI).toFloat()) * 12f * beatPulse
                rollDeg = when (currentSlideIndex % 2) {
                    0 -> -2.0f + (slideProgress * 4.0f)
                    else -> 2.0f - (slideProgress * 4.0f)
                }
            }
            TemplateMotionStyle.EMOTIONAL_PULLBACK -> {
                // Melancholic slow pull-back zoom with subtle drift
                zoom = when (currentSlideIndex % 2) {
                    0 -> 1.28f - (slideProgress * 0.24f)
                    else -> 1.06f + (slideProgress * 0.10f)
                }
                panOffsetY = (slideProgress * 12f)
            }
            TemplateMotionStyle.SPIRITUAL_AURA -> {
                // Sacred upward floating pan towards divine light
                panOffsetY = (1.0f - slideProgress) * 40f
                zoom = 1.04f + (slideProgress * 0.12f) + (beatPulse * 0.03f)
            }
            TemplateMotionStyle.NEON_CYBERPUNK -> {
                zoom = 1.10f + (slideProgress * 0.32f) + (beatPulse * 0.10f)
                rollDeg = when (currentSlideIndex % 3) {
                    0 -> slideProgress * 2.5f
                    1 -> -2.5f + (slideProgress * 4.5f)
                    else -> 1.5f - (slideProgress * 3.0f)
                }
                if (beatPhase < 0.25f) {
                    shakeX = (Math.sin(elapsedMs / 12.0).toFloat() * 14f * beatPulse)
                    shakeY = (Math.cos(elapsedMs / 12.0).toFloat() * 10f * beatPulse)
                }
            }
            TemplateMotionStyle.FLASH_BEAT_CUTS -> {
                // Fast beat cuts with rhythmic punch zooms
                zoom = 1.06f + (slideProgress * 0.16f) + (beatPulse * 0.14f)
                if (beatPhase < 0.20f) {
                    shakeX = (Math.sin(elapsedMs / 16.0).toFloat() * 12f * beatPulse)
                }
            }
            TemplateMotionStyle.RETRO_VINTAGE -> {
                // Nostalgic warm 8mm handheld wobble
                zoom = 1.06f + (slideProgress * 0.08f)
                shakeX = (Math.sin(elapsedMs / 55.0).toFloat() * 4f)
                shakeY = (Math.cos(elapsedMs / 45.0).toFloat() * 4f)
                rollDeg = (Math.sin(elapsedMs / 120.0).toFloat() * 0.7f)
            }
        }

        canvas.save()
        canvas.translate(shakeX + panOffsetX, shakeY + panOffsetY)
        if (Math.abs(rollDeg) > 0.01f) {
            canvas.rotate(rollDeg, w / 2f, h / 2f)
        }

        if (hasActiveLiveVideo && !isExport) {
            // Live continuous motion video is actively rendering underneath via hardware TextureView!
            // Skip solid photo rendering so the moving video shines through at 60 FPS!
        } else {
            val photoRatio = currentBmp.width.toFloat() / currentBmp.height.toFloat()
            val isNonPortrait = (customPhotoBitmap != null || motionFrameBitmap != null) && Math.abs(photoRatio - 9f / 16f) > 0.04f

            if (isNonPortrait) {
                // 1. Ambient blurred / cover background to fill 9:16 canvas seamlessly
                val bgScale = Math.max(w / currentBmp.width.toFloat(), h / currentBmp.height.toFloat())
                val bgW = currentBmp.width.toFloat() * bgScale
                val bgH = currentBmp.height.toFloat() * bgScale
                matrix.reset()
                matrix.postScale(bgScale, bgScale)
                matrix.postTranslate((w - bgW) / 2f, (h - bgH) / 2f)
                bgPaint.alpha = 135
                canvas.drawBitmap(currentBmp, matrix, bgPaint)

                // Scrim
                canvas.drawRect(0f, 0f, w, h, dimPaint)

                // 2. Full original photo centered with fitCenter so NOTHING is cut off!
                val fgScale = Math.min(w / currentBmp.width.toFloat(), h / currentBmp.height.toFloat()) * zoom
                val fgW = currentBmp.width.toFloat() * fgScale
                val fgH = currentBmp.height.toFloat() * fgScale
                matrix.reset()
                matrix.postScale(fgScale, fgScale)
                matrix.postTranslate((w - fgW) / 2f, (h - fgH) / 2f)
                bgPaint.alpha = 255
                canvas.drawBitmap(currentBmp, matrix, bgPaint)
            } else {
                val baseScale = Math.max(w / currentBmp.width.toFloat(), h / currentBmp.height.toFloat()) * zoom
                val scaledW = currentBmp.width.toFloat() * baseScale
                val scaledH = currentBmp.height.toFloat() * baseScale

                matrix.reset()
                matrix.postScale(baseScale, baseScale)
                matrix.postTranslate((w - scaledW) / 2f, (h - scaledH) / 2f)

                // Draw primary photo
                bgPaint.alpha = 255
                canvas.drawBitmap(currentBmp, matrix, bgPaint)
            }

            // 2. High-Energy Cinematic Transitions (3D Cube Flip, Film Strip Roll, Glitch Slice, Zoom Burst, Spotify Slide, Heart Iris, Whip Pan, Punch Zoom, Flash Cut, Push Slide, Crossfade)
            if (motionFrameBitmap == null && slideProgress > 0.70f) {
                val rawProgress = (slideProgress - 0.70f) / 0.30f
                val t = (rawProgress * rawProgress * (3f - 2f * rawProgress)).coerceIn(0f, 1f)
                val designTheme = tm.getDesignTheme()

                val effectiveTransition = when {
                    transitionType in listOf("cube_3d_flip", "3d_flip", "cube") -> "cube_3d_flip"
                    transitionType in listOf("film_strip_roll", "film_roll") -> "film_strip_roll"
                    transitionType in listOf("neon_glitch_slice") -> "neon_glitch_slice"
                    transitionType in listOf("zoom_burst_spin") -> "zoom_burst_spin"
                    transitionType in listOf("spotify_card_slide") -> "spotify_card_slide"
                    transitionType in listOf("whip_right", "whip_left", "whip", "whip_pan") -> transitionType
                    transitionType in listOf("zoom_in", "zoom", "punch_zoom") -> "zoom_in"
                    transitionType in listOf("glitch", "rgb_glitch_split") -> "glitch"
                    transitionType in listOf("flash", "light_leak") -> "flash"
                    transitionType in listOf("slide", "slide_up", "push_up") -> "slide"
                    transitionType in listOf("heart_iris", "heart_burst_wipe", "circle_wipe", "iris") -> "heart_burst_wipe"
                    // Design theme signature transitions
                    designTheme == StatusDesignTheme.VINTAGE_POLAROID_FILM -> if (currentSlideIndex % 2 == 0) "film_strip_roll" else "flash"
                    designTheme == StatusDesignTheme.ROMANTIC_LOVE_LOCKET -> if (currentSlideIndex % 2 == 0) "heart_burst_wipe" else "zoom_in"
                    designTheme == StatusDesignTheme.ROYAL_ATTITUDE_KING -> if (currentSlideIndex % 2 == 0) "cube_3d_flip" else "glitch"
                    designTheme == StatusDesignTheme.SPOTIFY_MUSIC_PLAYER -> if (currentSlideIndex % 2 == 0) "spotify_card_slide" else "whip_right"
                    designTheme == StatusDesignTheme.CYBERPUNK_GLITCH_HUD -> if (currentSlideIndex % 2 == 0) "neon_glitch_slice" else "glitch"
                    designTheme == StatusDesignTheme.BIRTHDAY_CONFETTI -> if (currentSlideIndex % 2 == 0) "zoom_burst_spin" else "heart_burst_wipe"
                    designTheme == StatusDesignTheme.DIVINE_BHAKTI_AURA -> "crossfade"
                    motionStyle == TemplateMotionStyle.VELOCITY_RAMP -> if (currentSlideIndex % 2 == 0) "whip_right" else "whip_left"
                    motionStyle == TemplateMotionStyle.THREE_D_ZOOM -> "zoom_in"
                    motionStyle == TemplateMotionStyle.PHONK_ATTITUDE || motionStyle == TemplateMotionStyle.NEON_CYBERPUNK -> "glitch"
                    motionStyle == TemplateMotionStyle.FLASH_BEAT_CUTS -> "flash"
                    motionStyle == TemplateMotionStyle.ROMANTIC_LOVE -> "heart_burst_wipe"
                    else -> "crossfade"
                }

                renderCinematicTransition(
                    canvas = canvas,
                    w = w,
                    h = h,
                    transition = effectiveTransition,
                    t = t,
                    currentBmp = currentBmp,
                    nextBmp = nextBmp,
                    elapsedMs = elapsedMs
                )
            }
        }

        // 3. RGB Glitch Chromatic Aberration on Beat Drops (enhanced for PHONK & NEON)
        val isGlitchStyle = motionStyle == TemplateMotionStyle.PHONK_ATTITUDE ||
                            motionStyle == TemplateMotionStyle.NEON_CYBERPUNK ||
                            transitionType in listOf("rgb_glitch_split", "glitch")
        if (isGlitchStyle && beatPhase < 0.25f) {
            glitchPaint.color = Color.argb((beatPulse * 75).toInt(), 0, 255, 255)
            canvas.drawRect(0f, 0f, w, h, glitchPaint)
        }

        // 4. White Flash Cut at slide start (enhanced for FLASH_BEAT_CUTS & PHONK)
        val flashThreshold = if (motionStyle == TemplateMotionStyle.FLASH_BEAT_CUTS) 0.16f else 0.12f
        if (slideProgress < flashThreshold) {
            val flashAlpha = ((1f - slideProgress / flashThreshold) * 175).toInt().coerceIn(0, 175)
            flashPaint.color = Color.argb(flashAlpha, 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }

        // 5. Template Gradient Color Wash & Atmospheric Tone
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

        // Motion style atmospheric mood tint
        when (motionStyle) {
            TemplateMotionStyle.EMOTIONAL_PULLBACK -> {
                // Moody dark blue-slate vignette
                flashPaint.color = Color.argb(45, 20, 25, 45)
                canvas.drawRect(0f, 0f, w, h, flashPaint)
            }
            TemplateMotionStyle.PHONK_ATTITUDE -> {
                // High contrast dark edge tint
                flashPaint.color = Color.argb(35, 10, 0, 20)
                canvas.drawRect(0f, 0f, w, h, flashPaint)
            }
            TemplateMotionStyle.RETRO_VINTAGE -> {
                // Warm amber sepia tone
                flashPaint.color = Color.argb(40, 180, 130, 60)
                canvas.drawRect(0f, 0f, w, h, flashPaint)
            }
            TemplateMotionStyle.SPIRITUAL_AURA -> {
                // Warm golden devotional glow
                flashPaint.color = Color.argb(30, 255, 180, 50)
                canvas.drawRect(0f, 0f, w, h, flashPaint)
            }
            else -> {}
        }

        canvas.restore()

        // 5.5. Mast-Style Magical Animated Canvas Particle Overlays
        drawMagicalParticles(canvas, w, h, tm, elapsedMs, beatPulse)

        // 6. Distinct Handcrafted Unique Theme Visual Layout
        drawUniqueThemeLayout(canvas, w, h, tm, elapsedMs, safeBpm, beatPulse, totalDurationMs)

        // 7. Kinetic Glowing Lyrical Typography & Customizable Texts
        drawKineticLyrics(canvas, w, h, tm, elapsedMs, totalDurationMs, beatPulse)

        if (!isExport) {
            // 8. Progress Time Scrubber Pill at Top
            drawProgressBadge(canvas, w, elapsedMs, totalDurationMs, tm)

            // 8.5. Interactive Tap-to-Play/Pause Feedback
            drawTapFeedbackBadge(canvas, w, h)

            // 9. Pause Overlay if paused
            if (!isPlaying && tapFeedbackAlpha <= 0f) {
                drawPauseOverlay(canvas, w, h)
            }
        }
    }

    /**
     * Export the active status template with Ken Burns photo animation,
     * visualizer bars, spinning vinyl disc, floating notes, magical particles,
     * glowing lyrics typography, and synchronized music directly into MP4.
     */
    fun exportToVideo(
        outputPath: String,
        width: Int = 720,
        height: Int = 1280,
        fps: Int = 30,
        bitrateMbps: Int = 6,
        customAudioPath: String? = null,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val tm = template ?: return false
        val totalDurationMs = tm.durationMs.coerceAtLeast(6000L)
        val totalFrames = ((totalDurationMs / 1000f) * fps).toInt().coerceAtLeast(30)
        val outputFile = File(outputPath).also { it.parentFile?.mkdirs() }

        ensureSampleSlides()

        val encoder = CanvasVideoEncoder(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrateMbps * 1_000_000,
            outputFile = outputFile
        )

        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val frameCanvas = Canvas(frameBitmap)

        val videoRetriever = if (!customVideoPath.isNullOrBlank() && File(customVideoPath!!).exists()) {
            runCatching {
                android.media.MediaMetadataRetriever().apply { setDataSource(customVideoPath) }
            }.getOrNull()
        } else null

        val videoDurationUs = if (videoRetriever != null) {
            val durStr = runCatching { videoRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) }.getOrNull()
            (durStr?.toLongOrNull() ?: totalDurationMs) * 1000L
        } else totalDurationMs * 1000L

        return try {
            for (frame in 0 until totalFrames) {
                val timeMs = (frame * 1000L) / fps
                val ptsNano = (frame * 1_000_000_000L) / fps

                var motionFrame: Bitmap? = null
                if (videoRetriever != null && videoDurationUs > 0) {
                    val frameTimeUs = (timeMs * 1000L) % videoDurationUs
                    val rawFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        runCatching {
                            videoRetriever.getScaledFrameAtTime(
                                frameTimeUs,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                                width,
                                height
                            )
                        }.getOrNull() ?: videoRetriever.getFrameAtTime(frameTimeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                    } else {
                        videoRetriever.getFrameAtTime(frameTimeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                    }
                    if (rawFrame != null) {
                        motionFrame = if (rawFrame.width == width && rawFrame.height == height) {
                            rawFrame
                        } else {
                            StatusMediaHelper.smartCropToPortrait916(rawFrame).also {
                                if (it != rawFrame && !rawFrame.isRecycled) rawFrame.recycle()
                            }
                        }
                    }
                }

                renderFrameAt(frameCanvas, width.toFloat(), height.toFloat(), timeMs, isExport = true, motionFrameBitmap = motionFrame)
                encoder.encodeFrame(frameBitmap, ptsNano)
                motionFrame?.let { if (!it.isRecycled && it != customPhotoBitmap) it.recycle() }

                val progress = (frame * 85 / totalFrames)
                onProgress(progress)
            }

            onProgress(88)

            val audioFile = if (!customAudioPath.isNullOrBlank() && File(customAudioPath).exists()) {
                File(customAudioPath)
            } else {
                StatusMediaHelper.extractAudioAssetToLocal(context, tm.audioAssetPath)
            }

            val success = encoder.finish(audioFile, maxDurationUs = totalDurationMs * 1000L)
            onProgress(100)
            success
        } catch (e: Exception) {
            Log.e("StatusLivePreview", "exportToVideo error", e)
            false
        } finally {
            runCatching { videoRetriever?.release() }
            frameBitmap.recycle()
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

    private fun renderCinematicTransition(
        canvas: Canvas,
        w: Float,
        h: Float,
        transition: String,
        t: Float,
        currentBmp: Bitmap,
        nextBmp: Bitmap,
        elapsedMs: Long
    ) {
        val nextScale = Math.max(w / nextBmp.width.toFloat(), h / nextBmp.height.toFloat())
        val nScaledW = nextBmp.width.toFloat() * nextScale
        val nScaledH = nextBmp.height.toFloat() * nextScale

        when (transition) {
            "cube_3d_flip", "3d_flip", "cube" -> {
                // Outgoing perspective cube fold
                canvas.save()
                val scaleOut = 1.0f - (t * 0.22f)
                canvas.scale(scaleOut, scaleOut, w / 2f, h / 2f)
                canvas.translate(-t * w * 0.45f, 0f)
                transPaint.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(currentBmp, matrix, transPaint)
                canvas.restore()

                // Incoming slide and un-fold
                canvas.save()
                val enterX = (1f - t) * (w * 0.55f)
                val scaleIn = 0.85f + (t * 0.15f)
                canvas.scale(scaleIn, scaleIn, w / 2f, h / 2f)
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f + enterX, (h - nScaledH) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)
                canvas.restore()

                if (t in 0.15f..0.85f) {
                    val edgeA = ((1f - Math.abs(t - 0.5f) * 2f) * 120).toInt().coerceIn(0, 120)
                    flashPaint.color = Color.argb(edgeA, 0, 0, 0)
                    canvas.drawRect(0f, 0f, w, h, flashPaint)
                }
            }
            "film_strip_roll", "film_roll" -> {
                // Vertical 35mm film strip slide with sprocket holes
                val rollY = -(1f - t) * h
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f - rollY)
                transNextPaint.alpha = 255
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                // Black film separator bar
                val barY = (h - rollY).coerceIn(0f, h)
                flashPaint.color = Color.BLACK
                canvas.drawRect(0f, barY - 18f, w, barY + 18f, flashPaint)

                val numHoles = 10
                val holeH = h / numHoles
                flashPaint.color = Color.WHITE
                for (i in 0 until numHoles) {
                    val hy = (i * holeH + (t * holeH)) % h
                    canvas.drawRoundRect(RectF(8f, hy, 24f, hy + 20f), 4f, 4f, flashPaint)
                    canvas.drawRoundRect(RectF(w - 24f, hy, w - 8f, hy + 20f), 4f, 4f, flashPaint)
                }
            }
            "neon_glitch_slice" -> {
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                val slices = 7
                val sliceH = h / slices
                for (s in 0 until slices) {
                    val shift = (Math.sin((elapsedMs + s * 90) / 15.0).toFloat() * 32f * (1f - Math.abs(t - 0.5f) * 2f))
                    val top = s * sliceH
                    if (Math.abs(shift) > 3f) {
                        canvas.save()
                        canvas.clipRect(0f, top, w, top + sliceH)
                        glitchPaint.color = if (s % 2 == 0) Color.argb(80, 0, 242, 254) else Color.argb(80, 255, 0, 127)
                        canvas.drawRect(0f, top, w, top + sliceH, glitchPaint)
                        canvas.restore()
                    }
                }
            }
            "zoom_burst_spin" -> {
                val rot = (1f - t) * 18f
                val zScale = nextScale * (1.45f - (t * 0.45f))
                canvas.save()
                canvas.rotate(rot, w / 2f, h / 2f)
                transNextMatrix.reset()
                transNextMatrix.postScale(zScale, zScale)
                transNextMatrix.postTranslate((w - (nextBmp.width * zScale)) / 2f, (h - (nextBmp.height * zScale)) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)
                canvas.restore()

                if (t in 0.35f..0.75f) {
                    val fl = ((1f - Math.abs(t - 0.55f) / 0.2f) * 160).toInt().coerceIn(0, 160)
                    flashPaint.color = Color.argb(fl, 255, 255, 255)
                    canvas.drawRect(0f, 0f, w, h, flashPaint)
                }
            }
            "spotify_card_slide" -> {
                val enterX = (1f - t) * w
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f + enterX, (h - nScaledH) / 2f)
                transNextPaint.alpha = 255
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                val shadowW = 32f
                val shadowGrad = LinearGradient(
                    enterX - shadowW, 0f, enterX, 0f,
                    Color.TRANSPARENT, Color.argb(160, 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
                transShadowPaint.shader = shadowGrad
                canvas.drawRect(enterX - shadowW, 0f, enterX, h, transShadowPaint)
            }
            "whip_right", "whip_pan", "whip" -> {
                val enterX = (1f - t) * w
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f + enterX, (h - nScaledH) / 2f)
                transNextPaint.alpha = 255
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                if (t in 0.15f..0.85f) {
                    val streakY1 = h * 0.28f + ((elapsedMs % 200) / 200f) * h * 0.4f
                    val streakY2 = h * 0.72f - ((elapsedMs % 150) / 150f) * h * 0.3f
                    canvas.drawLine(enterX - 60f, streakY1, enterX + 120f, streakY1, transSpeedStreakPaint)
                    canvas.drawLine(enterX - 100f, streakY2, enterX + 80f, streakY2, transSpeedStreakPaint)
                }
            }
            "whip_left" -> {
                val enterX = -(1f - t) * w
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f + enterX, (h - nScaledH) / 2f)
                transNextPaint.alpha = 255
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)
            }
            "zoom_in", "punch_zoom" -> {
                val zoomFactor = 1.35f - (t * 0.35f)
                val curNextScale = nextScale * zoomFactor
                val curScaledW = nextBmp.width.toFloat() * curNextScale
                val curScaledH = nextBmp.height.toFloat() * curNextScale

                transNextMatrix.reset()
                transNextMatrix.postScale(curNextScale, curNextScale)
                transNextMatrix.postTranslate((w - curScaledW) / 2f, (h - curScaledH) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                if (t in 0.4f..0.8f) {
                    val flashA = ((1f - Math.abs(t - 0.6f) / 0.2f) * 110).toInt().coerceIn(0, 110)
                    flashPaint.color = Color.argb(flashA, 255, 255, 255)
                    canvas.drawRect(0f, 0f, w, h, flashPaint)
                }
            }
            "glitch", "rgb_glitch_split" -> {
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                val bandCount = 5
                val bandH = h / bandCount
                for (b in 0 until bandCount) {
                    val shift = (Math.sin((elapsedMs + b * 60) / 20.0).toFloat() * 24f * (1f - Math.abs(t - 0.5f) * 2f))
                    if (Math.abs(shift) > 4f) {
                        val bandTop = b * bandH
                        canvas.save()
                        canvas.clipRect(0f, bandTop, w, bandTop + bandH)
                        glitchPaint.color = if (b % 2 == 0) Color.argb(60, 0, 255, 255) else Color.argb(60, 255, 0, 80)
                        canvas.drawRect(0f, bandTop, w, bandTop + bandH, glitchPaint)
                        canvas.restore()
                    }
                }
            }
            "flash", "light_leak" -> {
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale * (1f + (1f - t) * 0.06f), nextScale * (1f + (1f - t) * 0.06f))
                transNextMatrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                val flareIntensity = (1f - Math.abs(t - 0.5f) * 2f).coerceIn(0f, 1f)
                if (flareIntensity > 0.05f) {
                    val flareRadius = Math.max(w, h) * (0.4f + flareIntensity * 0.6f)
                    val flareGradient = RadialGradient(
                        w * 0.5f, h * 0.4f,
                        flareRadius.coerceAtLeast(10f),
                        intArrayOf(
                            Color.argb((flareIntensity * 220).toInt(), 255, 255, 255),
                            Color.argb((flareIntensity * 160).toInt(), 255, 215, 0),
                            Color.argb((flareIntensity * 80).toInt(), 255, 65, 108),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.25f, 0.6f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    transFlarePaint.shader = flareGradient
                    canvas.drawRect(0f, 0f, w, h, transFlarePaint)
                }
            }
            "heart_burst_wipe", "heart_iris" -> {
                val heartSize = Math.max(w, h) * t * 1.5f
                val cx = w / 2f
                val cy = h / 2f

                transHeartPath.reset()
                val s = heartSize / 100f
                transHeartPath.moveTo(cx, cy + 30f * s)
                transHeartPath.cubicTo(cx - 50f * s, cy - 20f * s, cx - 50f * s, cy - 60f * s, cx, cy - 35f * s)
                transHeartPath.cubicTo(cx + 50f * s, cy - 60f * s, cx + 50f * s, cy - 20f * s, cx, cy + 30f * s)
                transHeartPath.close()

                canvas.save()
                canvas.clipPath(transHeartPath)

                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f)
                transNextPaint.alpha = 255
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)
                canvas.restore()

                strokePaint.reset()
                strokePaint.isAntiAlias = true
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = 5f
                strokePaint.color = Color.argb((220 * (1f - t)).toInt().coerceIn(0, 220), 255, 65, 108)
                canvas.drawPath(transHeartPath, strokePaint)
            }
            "slide", "slide_up", "push_up" -> {
                val enterY = (1f - t) * h
                transNextMatrix.reset()
                transNextMatrix.postScale(nextScale, nextScale)
                transNextMatrix.postTranslate((w - nScaledW) / 2f, (h - nScaledH) / 2f + enterY)
                transNextPaint.alpha = 255
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)

                val shadowH = 24f
                val shadowGrad = LinearGradient(
                    0f, enterY - shadowH, 0f, enterY,
                    Color.TRANSPARENT, Color.argb(140, 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
                transShadowPaint.shader = shadowGrad
                canvas.drawRect(0f, enterY - shadowH, w, enterY, transShadowPaint)
            }
            else -> {
                val curNextScale = nextScale * (1.0f + (1f - t) * 0.08f)
                val curScaledW = nextBmp.width.toFloat() * curNextScale
                val curScaledH = nextBmp.height.toFloat() * curNextScale

                transNextMatrix.reset()
                transNextMatrix.postScale(curNextScale, curNextScale)
                transNextMatrix.postTranslate((w - curScaledW) / 2f, (h - curScaledH) / 2f)
                transNextPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(nextBmp, transNextMatrix, transNextPaint)
            }
        }
    }

    private fun drawUniqueThemeLayout(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        bpm: Int,
        beatPulse: Float,
        totalDurationMs: Long
    ) {
        val theme = tm.getDesignTheme()
        val density = resources.displayMetrics.density

        when (theme) {
            StatusDesignTheme.VINTAGE_POLAROID_FILM -> {
                drawPolaroidFilmTheme(canvas, w, h, tm, elapsedMs, beatPulse, density)
            }
            StatusDesignTheme.SPOTIFY_MUSIC_PLAYER -> {
                drawSpotifyPlayerTheme(canvas, w, h, tm, elapsedMs, totalDurationMs, beatPulse, density)
            }
            StatusDesignTheme.ROMANTIC_LOVE_LOCKET -> {
                drawRomanticLoveTheme(canvas, w, h, tm, elapsedMs, beatPulse, density)
            }
            StatusDesignTheme.ROYAL_ATTITUDE_KING -> {
                drawRoyalAttitudeTheme(canvas, w, h, tm, elapsedMs, beatPulse, density)
            }
            StatusDesignTheme.CYBERPUNK_GLITCH_HUD -> {
                drawCyberpunkHudTheme(canvas, w, h, tm, elapsedMs, beatPulse, density)
            }
            StatusDesignTheme.BIRTHDAY_CONFETTI -> {
                drawBirthdayConfettiTheme(canvas, w, h, tm, elapsedMs, beatPulse, density)
            }
            StatusDesignTheme.DIVINE_BHAKTI_AURA -> {
                drawDivineBhaktiTheme(canvas, w, h, tm, elapsedMs, beatPulse, density)
            }
            StatusDesignTheme.NEON_BEAT_BASS -> {
                drawNeonBeatBassTheme(canvas, w, h, tm, elapsedMs, bpm, beatPulse, density)
            }
        }
    }

    private fun drawPolaroidFilmTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        // 1. Top Camcorder HUD with blinking recording dot
        val isRecBlinking = (elapsedMs % 1000) < 650
        if (isRecBlinking) {
            recDotPaint.color = Color.parseColor("#FF1744")
            canvas.drawCircle(26f * density, 34f * density, 5.5f * density, recDotPaint)
        }
        val curSec = (elapsedMs / 1000) % 60
        val curMin = (elapsedMs / 60000) % 60
        recTextPaint.textSize = 12f * density
        canvas.drawText("REC ● ${String.format(java.util.Locale.US, "%02d:%02d", curMin, curSec)}", 38f * density, 38f * density, recTextPaint)
        canvas.drawText("OCT 26, 2026", w - (115f * density), 38f * density, recTextPaint)

        // 2. Bottom Polaroid Tape banner with signature and date
        val bannerY = h * 0.81f
        val bannerH = 46f * density
        val bannerRect = RectF(w * 0.07f, bannerY, w * 0.93f, bannerY + bannerH)
        canvas.drawRoundRect(bannerRect, 10f * density, 10f * density, tapePaint)

        val signature = customTexts.userName ?: customTexts.titleText ?: "✨ Vintage Memories ✨"
        polaroidSignaturePaint.textSize = 13.5f * density
        canvas.drawText(signature, w / 2f, bannerY + (bannerH * 0.62f), polaroidSignaturePaint)

        // 3. Subtle floating film dust specks
        for (i in 0 until 6) {
            val sx = (w * 0.1f) + ((i * 123 + elapsedMs / 8) % (w * 0.8f).toInt())
            val sy = (h * 0.15f) + ((i * 197 + elapsedMs / 6) % (h * 0.65f).toInt())
            polaroidShadowPaint.alpha = 45
            canvas.drawCircle(sx, sy, (1.5f + (i % 2)) * density, polaroidShadowPaint)
        }
    }

    private fun drawSpotifyPlayerTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        totalDurationMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        val cardX = w * 0.05f
        val cardY = h * 0.70f
        val cardW = w * 0.90f
        val cardH = 96f * density
        val cardRect = RectF(cardX, cardY, cardX + cardW, cardY + cardH)
        canvas.drawRoundRect(cardRect, 18f * density, 18f * density, spotifyCardBgPaint)
        canvas.drawRoundRect(cardRect, 18f * density, 18f * density, spotifyCardStrokePaint)

        // Mini album thumbnail on left
        val thumbSize = 58f * density
        val thumbX = cardX + 12f * density
        val thumbY = cardY + 12f * density
        val thumbRect = RectF(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize)
        val curBmp = customPhotoBitmap ?: sampleSlides.firstOrNull()
        if (curBmp != null && !curBmp.isRecycled) {
            canvas.save()
            val thumbPath = Path().apply { addRoundRect(thumbRect, 10f * density, 10f * density, Path.Direction.CW) }
            canvas.clipPath(thumbPath)
            val sc = Math.max(thumbSize / curBmp.width.toFloat(), thumbSize / curBmp.height.toFloat())
            matrix.reset()
            matrix.postScale(sc, sc)
            matrix.postTranslate(thumbX, thumbY)
            canvas.drawBitmap(curBmp, matrix, bgPaint)
            canvas.restore()
        }

        // Track Title & Artist
        val textLeft = thumbX + thumbSize + 12f * density
        val title = (customTexts.titleText ?: tm.title).take(22)
        val artist = (customTexts.userName ?: "Storyline Beats 🎵").take(26)
        spotifyTitlePaint.textSize = 13.5f * density
        canvas.drawText(title, textLeft, thumbY + 18f * density, spotifyTitlePaint)
        spotifyArtistPaint.textSize = 11.5f * density
        canvas.drawText(artist, textLeft, thumbY + 36f * density, spotifyArtistPaint)

        // Scrubber bar inside card
        val barY = cardY + cardH - 18f * density
        val barLeft = textLeft
        val barRight = cardX + cardW - 14f * density
        val prog = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        canvas.drawLine(barLeft, barY, barRight, barY, spotifyProgressBgPaint)
        val curHeadX = barLeft + (barRight - barLeft) * prog
        canvas.drawLine(barLeft, barY, curHeadX, barY, spotifyProgressFillPaint)
        scrubberDotPaint.color = Color.WHITE
        canvas.drawCircle(curHeadX, barY, 4f * density, scrubberDotPaint)

        // Player icon controls
        spotifyControlsPaint.textSize = 16f * density
        val iconY = thumbY + 38f * density
        val heartX = cardX + cardW - 24f * density
        canvas.drawText("❤️", heartX, iconY, spotifyControlsPaint)
    }

    private fun drawRomanticLoveTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        // Soft romantic glow banner with couple name
        val bannerY = h * 0.77f
        val bannerH = 44f * density
        val bannerW = w * 0.86f
        val bRect = RectF(w / 2f - bannerW / 2f, bannerY, w / 2f + bannerW / 2f, bannerY + bannerH)
        spotifyCardBgPaint.color = Color.argb(195, 35, 12, 24)
        spotifyCardStrokePaint.color = Color.parseColor("#88FF4081")
        canvas.drawRoundRect(bRect, 14f * density, 14f * density, spotifyCardBgPaint)
        canvas.drawRoundRect(bRect, 14f * density, 14f * density, spotifyCardStrokePaint)

        val coupleName = customTexts.userName ?: "Rahul ❤️ Priya"
        customTextPaint.apply {
            color = Color.parseColor("#FFF0F5")
            textSize = 14.5f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(14f * density, 0f, 0f, Color.parseColor("#FF4081"))
        }
        canvas.drawText("💍 $coupleName 💖", w / 2f, bannerY + 28f * density, customTextPaint)
        customTextPaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun drawRoyalAttitudeTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        // 1. Floating 3D golden crown icon perched over center photo
        val crownY = (h * 0.19f) - (beatPulse * 8f * density)
        crownPaint.textSize = 42f * density
        crownPaint.setShadowLayer(16f * density, 0f, 0f, Color.parseColor("#FFD700"))
        canvas.drawText("👑", w / 2f, crownY, crownPaint)
        crownPaint.setShadowLayer(0f, 0f, 0f, 0)

        // 2. Rising fiery sparks from bottom
        for (i in 0 until 8) {
            val cycle = 2200L
            val el = (elapsedMs + i * 260L) % cycle
            val p = el.toFloat() / cycle.toFloat()
            val ex = (w * 0.15f) + ((i * 83) % (w * 0.70f).toInt()) + (Math.sin((p * 6.28) + i).toFloat() * 12f * density)
            val ey = h - (p * h * 0.50f)
            val alpha = ((1f - p) * 220).toInt().coerceIn(0, 255)
            fireSparkPaint.color = if (i % 2 == 0) Color.argb(alpha, 255, 165, 0) else Color.argb(alpha, 255, 69, 0)
            canvas.drawCircle(ex, ey, (2.5f + (i % 3) * 2f) * density, fireSparkPaint)
        }

        // 3. Gold attitude luxury badge
        val badgeY = h * 0.78f
        val badgeH = 46f * density
        val badgeW = w * 0.84f
        val bRect = RectF(w / 2f - badgeW / 2f, badgeY, w / 2f + badgeW / 2f, badgeY + badgeH)
        canvas.drawRoundRect(bRect, 12f * density, 12f * density, attitudeBadgeFillPaint)
        canvas.drawRoundRect(bRect, 12f * density, 12f * density, attitudeGoldBadgePaint)

        val royalName = (customTexts.userName ?: "ROYAL ATTITUDE 👑").uppercase()
        crownPaint.textSize = 14f * density
        crownPaint.color = Color.parseColor("#FFD700")
        canvas.drawText(royalName, w / 2f, badgeY + 28f * density, crownPaint)
    }

    private fun drawCyberpunkHudTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        val bSize = 22f * density
        val pad = 14f * density
        // Top-left bracket
        canvas.drawLine(pad, pad, pad + bSize, pad, hudBracketPaint)
        canvas.drawLine(pad, pad, pad, pad + bSize, hudBracketPaint)
        // Top-right bracket
        canvas.drawLine(w - pad - bSize, pad, w - pad, pad, hudBracketPaint)
        canvas.drawLine(w - pad, pad, w - pad, pad + bSize, hudBracketPaint)
        // Bottom-left bracket
        canvas.drawLine(pad, h - pad, pad + bSize, h - pad, hudBracketPaint)
        canvas.drawLine(pad, h - pad - bSize, pad, h - pad, hudBracketPaint)
        // Bottom-right bracket
        canvas.drawLine(w - pad - bSize, h - pad, w - pad, h - pad, hudBracketPaint)
        canvas.drawLine(w - pad, h - pad - bSize, w - pad, h - pad, hudBracketPaint)

        // Status readout
        hudTextPaint.textSize = 10f * density
        canvas.drawText("[SYS_ONLINE // ${tm.bpm} BPM // 4K 60FPS]", pad + 8f * density, pad + 24f * density, hudTextPaint)
    }

    private fun drawBirthdayConfettiTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        // Raining confetti flakes
        val numConfetti = 20
        for (i in 0 until numConfetti) {
            val cycle = 2600L
            val el = (elapsedMs + i * 130L) % cycle
            val prog = el.toFloat() / cycle.toFloat()
            val cx = ((i * 49) % w.toInt()).toFloat() + (Math.sin((prog * 6.28) + i).toFloat() * 16f * density)
            val cy = (prog * (h + 60f * density)) - 30f * density
            val rot = (el * 0.22f * (if (i % 2 == 0) 1f else -1f))

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rot)
            confettiPaint.color = confettiColors[i % confettiColors.size]
            val fW = (6f + (i % 3) * 2.5f) * density
            val fH = (4f + (i % 2) * 2f) * density
            canvas.drawRoundRect(RectF(-fW / 2f, -fH / 2f, fW / 2f, fH / 2f), 2f, 2f, confettiPaint)
            canvas.restore()
        }

        // Festive banner
        val bannerY = h * 0.77f
        val bH = 46f * density
        val bW = w * 0.88f
        val bRect = RectF(w / 2f - bW / 2f, bannerY, w / 2f + bW / 2f, bannerY + bH)
        canvas.drawRoundRect(bRect, 14f * density, 14f * density, birthdayBannerBgPaint)
        val bdayName = customTexts.userName ?: "SPECIAL ONE"
        birthdayBannerTextPaint.textSize = 13.5f * density
        canvas.drawText("🎉 HAPPY BIRTHDAY $bdayName 🎂", w / 2f, bannerY + 28f * density, birthdayBannerTextPaint)
    }

    private fun drawDivineBhaktiTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        beatPulse: Float,
        density: Float
    ) {
        val symbol = if (tm.title.lowercase().contains("krishna")) "🪶" else if (tm.title.lowercase().contains("ram")) "🚩" else "🔱"
        val symY = h * 0.18f
        divineSymbolPaint.textSize = 42f * density
        divineSymbolPaint.setShadowLayer(14f * density, 0f, 0f, Color.parseColor("#FF9933"))
        canvas.drawText(symbol, w / 2f, symY, divineSymbolPaint)
        divineSymbolPaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun drawNeonBeatBassTheme(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        bpm: Int,
        beatPulse: Float,
        density: Float
    ) {
        // Flashing neon frame
        val frameStroke = (2f + beatPulse * 3f) * density
        visualizerPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = frameStroke
            color = parseColorSafe(tm.gradientStartHex, Color.CYAN)
            setShadowLayer(10f * density, 0f, 0f, color)
        }
        val fPad = 8f * density
        canvas.drawRoundRect(RectF(fPad, fPad, w - fPad, h - fPad), 16f * density, 16f * density, visualizerPaint)
        visualizerPaint.style = Paint.Style.FILL
        visualizerPaint.setShadowLayer(0f, 0f, 0f, 0)

        // Equalizer & Vinyl disc
        drawAudioVisualizer(canvas, w, h, tm, elapsedMs, beatPulse)
        drawVinylDiscAndNotes(canvas, w, h, tm, elapsedMs, bpm, beatPulse)
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
        val hideDefaults = customTexts.hideDefaultOverlays
        val overlays = tm.textOverlays

        if (!hideDefaults && overlays.isNotEmpty()) {
            val overlayIdx = ((elapsedMs * overlays.size) / totalDurationMs).toInt().coerceIn(0, overlays.size - 1)
            val currentOverlay = overlays[overlayIdx]

            // If user provided custom title, use it! Otherwise use template overlay text
            val text = customTexts.titleText ?: currentOverlay.text
            val textSizeSp = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                currentOverlay.scale * 20f,
                resources.displayMetrics
            )
            val bounce = beatPulse * 4f

            val textColor = if (customTexts.textColorHex != null) {
                parseColorSafe(customTexts.textColorHex!!, Color.WHITE)
            } else {
                parseColorSafe(currentOverlay.colorHex, Color.WHITE)
            }
            val glowColor = if (customTexts.glowColorHex != null) {
                parseColorSafe(customTexts.glowColorHex!!, Color.CYAN)
            } else {
                parseColorSafe(tm.gradientStartHex, Color.CYAN)
            }

            val chosenTypeface = if (!customTexts.fontName.isNullOrBlank()) {
                com.video.engine.TextBitmapHelper.resolveTypeface(customTexts.fontName, bold = true, italic = false, context = context)
            } else {
                Typeface.DEFAULT_BOLD
            }

            textPaint.apply {
                color = textColor
                textSize = textSizeSp + bounce
                typeface = chosenTypeface
                textAlign = Paint.Align.CENTER
                setShadowLayer(14f, 0f, 0f, glowColor)
            }

            val hasCustomSub = !customTexts.quoteText.isNullOrBlank() || !customTexts.userName.isNullOrBlank() || !customStatusText.isNullOrBlank()
            val basePosYPercent = if (hasCustomSub) 0.20f else currentOverlay.posYPercent
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
        val customQuote = customTexts.quoteText ?: customStatusText
        val customName = customTexts.userName
        val customTagline = customTexts.taglineText
        val hasCustomContent = !customQuote.isNullOrBlank() || !customName.isNullOrBlank() || !customTagline.isNullOrBlank()

        if (hasCustomContent) {
            val customBounce = beatPulse * 4f
            val baseSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                18.5f,
                resources.displayMetrics
            ) * customTextScaleFactor
            val customTextSize = baseSize + customBounce

            val textColor = if (customTexts.textColorHex != null) {
                parseColorSafe(customTexts.textColorHex!!, Color.parseColor("#FFFDF0"))
            } else {
                Color.parseColor("#FFFDF0")
            }
            val glowColor = if (customTexts.glowColorHex != null) {
                parseColorSafe(customTexts.glowColorHex!!, Color.parseColor("#FFD700"))
            } else {
                Color.parseColor("#FFD700")
            }

            val customTf = if (!customTexts.fontName.isNullOrBlank()) {
                com.video.engine.TextBitmapHelper.resolveTypeface(customTexts.fontName, bold = true, italic = false, context = context)
            } else {
                // Default to Kalam for quotes/shayari if available, else bold
                com.video.engine.TextBitmapHelper.resolveTypeface("kalam", bold = true, italic = false, context = context)
            }

            customTextPaint.apply {
                color = textColor
                textSize = customTextSize
                typeface = customTf
                textAlign = Paint.Align.CENTER
                setShadowLayer(16f + (beatPulse * 6f), 0f, 0f, glowColor)
            }

            // Word wrap lines dynamically so text NEVER cuts off horizontally
            val maxLineWidth = (w * 0.82f)
            val lines = mutableListOf<String>()

            if (!customName.isNullOrBlank() && tm.getDesignTheme() == StatusDesignTheme.NEON_BEAT_BASS) {
                lines.add("👑 $customName 👑")
            }

            if (!customQuote.isNullOrBlank()) {
                val words = customQuote.split(" ")
                var currentLine = StringBuilder()
                for (word in words) {
                    val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (customTextPaint.measureText(candidate) <= maxLineWidth) {
                        currentLine = StringBuilder(candidate)
                    } else {
                        if (currentLine.isNotEmpty()) {
                            lines.add(currentLine.toString())
                        }
                        currentLine = StringBuilder(word)
                    }
                }
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
            }

            if (!customTagline.isNullOrBlank()) {
                lines.add("✨ $customTagline")
            }

            val lineHeight = customTextSize * 1.32f
            val totalTextHeight = lines.size * lineHeight
            var maxMeasuredWidth = 0f
            for (line in lines) {
                val lw = customTextPaint.measureText(line)
                if (lw > maxMeasuredWidth) maxMeasuredWidth = lw
            }

            val centerX = w * customTextNormX
            val centerY = (h * customTextNormY) - customBounce
            val pillPadH = 16f * resources.displayMetrics.density
            val pillPadV = 10f * resources.displayMetrics.density
            val pillRect = RectF(
                centerX - (maxMeasuredWidth / 2f) - pillPadH,
                centerY - (totalTextHeight / 2f) - pillPadV,
                centerX + (maxMeasuredWidth / 2f) + pillPadH,
                centerY + (totalTextHeight / 2f) + pillPadV
            )
            textBoundingRect.set(pillRect)

            canvas.drawRoundRect(pillRect, 18f, 18f, customPillBgPaint)
            canvas.drawRoundRect(pillRect, 18f, 18f, customPillStrokePaint)

            var lineY = centerY - (totalTextHeight / 2f) + (customTextSize * 0.95f)
            for (line in lines) {
                canvas.drawText(line, centerX, lineY, customTextPaint)
                lineY += lineHeight
            }
            customTextPaint.setShadowLayer(0f, 0f, 0f, 0)

            // If user is interacting / dragging, draw dashed bounding box & helper hint
            if (isTouchInteractingWithText) {
                canvas.drawRoundRect(pillRect, 18f, 18f, textHandlePaint)
                val dotR = 6f * resources.displayMetrics.density
                canvas.drawCircle(pillRect.left, pillRect.top, dotR, textHandleFillPaint)
                canvas.drawCircle(pillRect.right, pillRect.top, dotR, textHandleFillPaint)
                canvas.drawCircle(pillRect.left, pillRect.bottom, dotR, textHandleFillPaint)
                canvas.drawCircle(pillRect.right, pillRect.bottom, dotR, textHandleFillPaint)

                val hintText = "✏️ Drag to Move • Pinch to Scale • Tap to Edit"
                val hintW = textTouchHintPaint.measureText(hintText) + 24f
                val hintH = 34f
                val hintRect = RectF(centerX - hintW / 2f, pillRect.top - hintH - 12f, centerX + hintW / 2f, pillRect.top - 12f)
                canvas.drawRoundRect(hintRect, 10f, 10f, textTouchHintBgPaint)
                canvas.drawText(hintText, centerX, hintRect.centerY() + 8f, textTouchHintPaint)
            }
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
        val numBars = 22
        val availableW = w * 0.70f // Leave room on the right for spinning vinyl disc
        val barWidth = availableW / numBars
        val startX = w * 0.06f
        val baseY = h * 0.89f

        val neonStart = parseColorSafe(tm.gradientStartHex, Color.CYAN)
        val neonEnd = parseColorSafe(tm.gradientEndHex, Color.MAGENTA)

        visualizerPaint.apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = barWidth * 0.60f
            setShadowLayer(10f + (beatPulse * 6f), 0f, 0f, neonStart)
        }

        for (i in 0 until numBars) {
            val normI = i.toFloat() / numBars.toFloat()
            val wave1 = Math.sin((elapsedMs / 130.0) + (i * 0.42)).toFloat()
            val wave2 = Math.cos((elapsedMs / 210.0) - (i * 0.28)).toFloat()
            val combined = (Math.abs(wave1) * 0.65f) + (Math.abs(wave2) * 0.35f)

            val freqMultiplier = when (i % 4) {
                0 -> 1.35f
                1 -> 0.85f
                2 -> 1.25f
                else -> 0.95f
            }
            val rawH = (combined * 36f * freqMultiplier) + (beatPulse * 28f * ((i % 3) + 1) * 0.45f)
            val barH = rawH.coerceIn(8f, 70f)

            val r = (Color.red(neonStart) * (1f - normI) + Color.red(neonEnd) * normI).toInt()
            val g = (Color.green(neonStart) * (1f - normI) + Color.green(neonEnd) * normI).toInt()
            val b = (Color.blue(neonStart) * (1f - normI) + Color.blue(neonEnd) * normI).toInt()
            visualizerPaint.color = Color.rgb(r, g, b)

            val bx = startX + (i * barWidth) + (barWidth / 2f)
            canvas.drawLine(bx, baseY, bx, baseY - barH, visualizerPaint)
        }
        visualizerPaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun drawVinylDiscAndNotes(
        canvas: Canvas,
        w: Float,
        h: Float,
        tm: StatusTemplate,
        elapsedMs: Long,
        bpm: Int,
        beatPulse: Float
    ) {
        val density = resources.displayMetrics.density
        val discR = 24f * density
        val discCx = w - (30f * density)
        val discCy = h - (64f * density)

        // 1. Vinyl disc outer edge shadow
        vinylOuterPaint.color = Color.argb(160, 10, 10, 14)
        canvas.drawCircle(discCx, discCy, discR + 2f, vinylOuterPaint)

        // 2. Vinyl black body with concentric grooves
        vinylPaint.color = Color.parseColor("#121216")
        canvas.drawCircle(discCx, discCy, discR, vinylPaint)

        vinylGroovePaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawCircle(discCx, discCy, discR * 0.78f, vinylGroovePaint)
        canvas.drawCircle(discCx, discCy, discR * 0.58f, vinylGroovePaint)

        // 3. Center Label (Rotating with BPM)
        val rotAngle = ((elapsedMs * 0.18f * (bpm / 100f)) % 360f)
        val labelR = discR * 0.38f
        val accentColor = parseColorSafe(tm.gradientStartHex, Color.parseColor("#FF007F"))
        vinylLabelPaint.color = accentColor

        canvas.save()
        canvas.rotate(rotAngle, discCx, discCy)
        canvas.drawCircle(discCx, discCy, labelR, vinylLabelPaint)

        // Spindle center hole
        vinylHolePaint.color = Color.WHITE
        canvas.drawCircle(discCx, discCy, 3f * density, vinylHolePaint)
        vinylHolePaint.color = Color.BLACK
        canvas.drawCircle(discCx, discCy, 1.8f * density, vinylHolePaint)
        canvas.restore()

        // 4. Floating Animated Musical Notes (♪, ♫, ♬) rising from the disc
        val noteSymbols = arrayOf("♪", "♫", "♬")
        for (i in 0 until 3) {
            val noteCycle = 1800L
            val noteOffset = i * 600L
            val noteElapsed = (elapsedMs + noteOffset) % noteCycle
            val progress = noteElapsed.toFloat() / noteCycle.toFloat()

            val sway = Math.sin((progress * Math.PI * 2.5) + i).toFloat() * (12f * density)
            val noteX = discCx - (16f * density) - (progress * 22f * density) + sway
            val noteY = discCy - (progress * 80f * density)

            val noteAlpha = if (progress < 0.2f) {
                progress / 0.2f
            } else {
                (1f - progress) / 0.8f
            }.coerceIn(0f, 1f)

            notePaint.apply {
                color = accentColor
                alpha = (noteAlpha * 220).toInt().coerceIn(0, 255)
                textSize = (13f * density) + (beatPulse * 3f * density)
                setShadowLayer(8f, 0f, 0f, accentColor)
            }
            canvas.drawText(noteSymbols[i % noteSymbols.size], noteX, noteY, notePaint)
        }
        notePaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun drawProgressBadge(
        canvas: Canvas,
        w: Float,
        elapsedMs: Long,
        totalDurationMs: Long,
        tm: StatusTemplate
    ) {
        val density = resources.displayMetrics.density
        val progress = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        val padH = 14f * density
        val trackY = 16f * density

        // Background track with rounded ends
        bgPPaint.strokeWidth = 3.5f * density
        pPaint.strokeWidth = 3.5f * density
        pPaint.color = parseColorSafe(tm.gradientStartHex, Color.parseColor("#388BFD"))

        canvas.drawLine(padH, trackY, w - padH, trackY, bgPPaint)
        val currentHeadX = padH + (w - (padH * 2f)) * progress
        canvas.drawLine(padH, trackY, currentHeadX, trackY, pPaint)

        // Glowing white scrubber dot at current head
        scrubberDotPaint.color = Color.WHITE
        scrubberDotPaint.setShadowLayer(6f * density, 0f, 0f, pPaint.color)
        canvas.drawCircle(currentHeadX, trackY, 3.8f * density, scrubberDotPaint)
        scrubberDotPaint.setShadowLayer(0f, 0f, 0f, 0)

        // Top playing badge
        val curSec = elapsedMs / 1000
        val totSec = totalDurationMs / 1000
        val timeStr = String.format("▶ %s • %02d:%02d / %02d:%02d", tm.emoji, curSec / 60, curSec % 60, totSec / 60, totSec % 60)

        val badgeW = 250f * (density / 2.75f).coerceAtLeast(0.85f)
        bgRect.set(w / 2f - badgeW / 2f, 26f * density, w / 2f + badgeW / 2f, 48f * density)
        canvas.drawRoundRect(bgRect, 12f * density, 12f * density, pillBgPaint)
        canvas.drawText(timeStr, w / 2f, 41f * density, badgePaint)
    }

    private fun triggerTapFeedback(isNowPlaying: Boolean) {
        tapFeedbackText = if (isNowPlaying) "▶" else "⏸"
        tapFeedbackAlpha = 1.0f
        tapFeedbackScale = 0.85f
        postInvalidateOnAnimation()
    }

    private fun drawTapFeedbackBadge(canvas: Canvas, w: Float, h: Float) {
        if (tapFeedbackAlpha <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        val radius = 38f * resources.displayMetrics.density * tapFeedbackScale
        val alphaInt = (tapFeedbackAlpha * 200).toInt().coerceIn(0, 255)
        tapFeedbackBgPaint.color = Color.argb(alphaInt, 15, 18, 25)
        tapFeedbackStrokePaint.color = Color.argb((tapFeedbackAlpha * 140).toInt(), 255, 255, 255)
        tapFeedbackTextPaint.alpha = (tapFeedbackAlpha * 255).toInt().coerceIn(0, 255)

        canvas.drawCircle(cx, cy, radius, tapFeedbackBgPaint)
        canvas.drawCircle(cx, cy, radius, tapFeedbackStrokePaint)
        canvas.drawText(tapFeedbackText, cx, cy + (tapFeedbackTextPaint.textSize * 0.35f), tapFeedbackTextPaint)

        // Animate out
        tapFeedbackAlpha = (tapFeedbackAlpha - 0.045f).coerceAtLeast(0f)
        tapFeedbackScale = (tapFeedbackScale + 0.012f).coerceAtMost(1.25f)
        if (tapFeedbackAlpha > 0f) {
            postInvalidateOnAnimation()
        }
    }

    private fun resolveParticleTheme(tm: StatusTemplate): ParticleTheme {
        val override = customTexts.particleTheme?.uppercase()
        if (override != null) {
            when (override) {
                "RAIN" -> return ParticleTheme.RAIN
                "SNOW" -> return ParticleTheme.SNOW
                "VINTAGE", "FILM" -> return ParticleTheme.VINTAGE_FILM
                "EMBERS", "FIRE" -> return ParticleTheme.GOLDEN_EMBERS
                "LOVE", "HEART" -> return ParticleTheme.LOVE
                "ATTITUDE", "PHONK" -> return ParticleTheme.ATTITUDE_PHONK
                "BIRTHDAY" -> return ParticleTheme.BIRTHDAY_SPARKLE
                "BHAKTI", "DEVOTIONAL" -> return ParticleTheme.SPIRITUAL_DEVOTIONAL
            }
        }
        val cat = tm.category.lowercase()
        val title = tm.title.lowercase()
        return when {
            cat.contains("rain") || title.contains("rain") || title.contains("monsoon") || tm.emoji in listOf("🌧️", "⛈️", "☔") ->
                ParticleTheme.RAIN
            cat.contains("snow") || title.contains("snow") || title.contains("winter") || tm.emoji in listOf("❄️", "☃️") ->
                ParticleTheme.SNOW
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
        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        val pillR = 34f * density

        tapFeedbackBgPaint.color = Color.argb(195, 15, 18, 25)
        tapFeedbackStrokePaint.color = Color.argb(160, 255, 255, 255)
        canvas.drawCircle(cx, cy, pillR, tapFeedbackBgPaint)
        canvas.drawCircle(cx, cy, pillR, tapFeedbackStrokePaint)
        canvas.drawText("▶", cx + (2.5f * density), cy + (playIconPaint.textSize * 0.32f), playIconPaint)
    }

    fun togglePlayPause() {
        isPlaying = !isPlaying
        if (!isPlaying) {
            pausedTime = SystemClock.uptimeMillis()
            try { mediaPlayer?.pause() } catch (_: Exception) {}
            triggerTapFeedback(isNowPlaying = false)
        } else {
            startTime += (SystemClock.uptimeMillis() - pausedTime)
            try { mediaPlayer?.start() } catch (_: Exception) {}
            triggerTapFeedback(isNowPlaying = true)
            postInvalidateOnAnimation()
        }
        try { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Exception) {}
        onPlaybackStateChanged?.invoke(isPlaying)
    }

    private var onCreateStatusClick: (() -> Unit)? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    fun bindTemplate(tmpl: StatusTemplate, mp: android.media.MediaPlayer? = null) {
        this.template = tmpl
        this.mediaPlayer = mp
        this.particlesInitialized = false
        this.startTime = SystemClock.uptimeMillis()
        this.isPlaying = true
        sampleSlides.clear()
        prepareSampleSlides()
        postInvalidateOnAnimation()
    }

    fun setOnCreateStatusClickListener(listener: () -> Unit) {
        this.onCreateStatusClick = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        val customText = customStatusText
        val hasCustomText = !customText.isNullOrBlank()

        val touchSlop = 32f * resources.displayMetrics.density
        val hitRect = RectF(
            textBoundingRect.left - touchSlop,
            textBoundingRect.top - touchSlop,
            textBoundingRect.right + touchSlop,
            textBoundingRect.bottom + touchSlop
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchDownTime = SystemClock.uptimeMillis()

                if (hasCustomText && hitRect.contains(event.x, event.y)) {
                    isDraggingText = true
                    isTouchInteractingWithText = true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    postInvalidateOnAnimation()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingText && !scaleGestureDetector.isInProgress) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val wF = width.toFloat().coerceAtLeast(10f)
                    val hF = height.toFloat().coerceAtLeast(10f)
                    customTextNormX = (customTextNormX + (dx / wF)).coerceIn(0.12f, 0.88f)
                    customTextNormY = (customTextNormY + (dy / hF)).coerceIn(0.12f, 0.88f)
                    touchDownX = event.x
                    touchDownY = event.y
                    postInvalidateOnAnimation()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingText) {
                    isDraggingText = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    postDelayed({
                        if (!isDraggingText) {
                            isTouchInteractingWithText = false
                            postInvalidateOnAnimation()
                        }
                    }, 1400L)
                    postInvalidateOnAnimation()
                    return true
                } else {
                    val dx = Math.abs(event.x - touchDownX)
                    val dy = Math.abs(event.y - touchDownY)
                    val dur = SystemClock.uptimeMillis() - touchDownTime
                    if (dx < 25f && dy < 25f && dur < 350L) {
                        if (hitRect.contains(event.x, event.y) && onTextClickListener != null) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onTextClickListener?.invoke()
                            return true
                        }
                        togglePlayPause()
                        return true
                    }
                }
            }
        }

        return super.onTouchEvent(event)
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
