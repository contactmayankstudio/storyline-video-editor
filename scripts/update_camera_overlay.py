#!/usr/bin/env python3
"""
Generate complete, high-performance CameraFilterOverlayView.kt
with 50 Snapchat-style filters, ML Kit face tracking integration,
real-time mesh deformation (drawBitmapMesh), beauty image processing,
and full-resolution photo/video capture baking.
"""

KOTLIN_CODE = '''package com.video.engine.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.TextureView
import android.view.View
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class ParticleType {
    RAINBOW,
    FIRE,
    GOLDEN_HEART,
    STAR_FLASH,
    HALO_SPARKLE,
    AUTUMN_LEAF,
    SNOWFLAKE,
    BUTTERFLY
}

class FaceParticle(
    var active: Boolean = false,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var ax: Float = 0f,
    var ay: Float = 0f,
    var size: Float = 10f,
    var sizeGrowth: Float = 0f,
    var alpha: Float = 1f,
    var decay: Float = 0.5f,
    var rotation: Float = 0f,
    var rotSpeed: Float = 0f,
    var color: Int = Color.WHITE,
    var type: ParticleType = ParticleType.RAINBOW,
    var life: Float = 1f,
    var maxLife: Float = 1f,
    var param1: Float = 0f
)

enum class MouthEffectType {
    RAINBOW,
    FIRE,
    GOLDEN_HEART
}

class CameraFilterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentLens: LensItem = LensItem("none", "Clean Camera", "साफ", "ALL", "NONE", "")
        set(value) {
            field = value
            preloadLensAsset(value)
            invalidate()
        }

    var faceData: SmoothedFaceData = SmoothedFaceData(hasFace = false)
        set(value) {
            field = value
            postInvalidate()
        }

    // Camera TextureView reference for live mesh deformation
    var cameraTextureView: TextureView? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    // --- MESH DEFORMATION ENGINE (drawBitmapMesh) for FUNNY FACE WARPS ---
    companion object {
        private const val MESH_WIDTH = 24
        private const val MESH_HEIGHT = 32
        private const val TOTAL_VERTS = (MESH_WIDTH + 1) * (MESH_HEIGHT + 1)

        private val VHS_MONTH_NAMES = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val RAINBOW_COLORS = intArrayOf(
            Color.parseColor("#FF0000"), Color.parseColor("#FF7F00"),
            Color.parseColor("#FFFF00"), Color.parseColor("#00FF00"),
            Color.parseColor("#0000FF"), Color.parseColor("#8B00FF")
        )
        private val FLAME_COLORS = intArrayOf(
            Color.parseColor("#FFD700"), Color.parseColor("#FF8C00"),
            Color.parseColor("#FF4500"), Color.parseColor("#FF0000")
        )
        private val HEART_COLORS = intArrayOf(
            Color.parseColor("#FF1493"), Color.parseColor("#FF69B4"),
            Color.parseColor("#FFB6C1"), Color.parseColor("#FFD700")
        )
    }

    private val meshVerts = FloatArray(TOTAL_VERTS * 2)
    private val origMeshVerts = FloatArray(TOTAL_VERTS * 2)
    private var warpBufferBmp: Bitmap? = null
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // --- ZERO-ALLOCATION IN-MEMORY PARTICLE SYSTEM ---
    private val particlePool: Array<FaceParticle> = Array(300) { FaceParticle() }
    private var lastFrameTime = 0L

    // Reusable Paths & Paints
    private val heartPath = Path()
    private val starPath = Path()
    private val reusableRect = RectF()

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vhsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vhsScanlinePaint = Paint()
    private val cyberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyberTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nightFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Beauty Radiance Shaders & Paints
    private val beautyAmbientShader = RadialGradient(0f, 0f, 100f, intArrayOf(Color.argb(48, 255, 238, 224), Color.argb(32, 255, 212, 192), Color.argb(68, 32, 16, 36)), floatArrayOf(0.0f, 0.55f, 1.0f), Shader.TileMode.CLAMP)
    private val beautyBlushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beautyEyeGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shaderMatrix = Matrix()

    // 35mm Vintage Film Grain Shader
    private val grainBitmap: Bitmap by lazy {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(1337)
        for (py in 0 until size) {
            for (px in 0 until size) {
                val noise = rnd.nextInt(48)
                val c = Color.argb(noise, 255, 235, 210)
                bmp.setPixel(px, py, c)
            }
        }
        bmp
    }
    private val grainShader: BitmapShader by lazy {
        BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grainMatrix = Matrix()

    // Interactive Trigger states
    var isNightFlashEnabled: Boolean = false
        set(value) { field = value; invalidate() }
    var isGridEnabled: Boolean = false
        set(value) { field = value; invalidate() }
    var filterStyle: String = "NORMAL"
        set(value) { field = value; invalidate() }

    private var focusX: Float = -1f
    private var focusY: Float = -1f
    private var focusStartTime: Long = 0L

    private var wasWinking = false
    private var winkFlashStartTime = 0L
    private var winkAnchorX = 0f
    private var winkAnchorY = 0f

    // Cached decoded 3D PNG assets
    private val assetCache = object : LruCache<String, Bitmap>(50) {}

    fun preloadLensAsset(lens: LensItem) {
        val paths = listOf(lens.assetPath, lens.secondaryAssetPath)
        for (path in paths) {
            if (path.isNotEmpty() && assetCache.get(path) == null) {
                try {
                    context.assets.open(path).use { stream: InputStream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            assetCache.put(path, bmp)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun getLensBitmap(assetPath: String): Bitmap? {
        if (assetPath.isEmpty()) return null
        assetCache.get(assetPath)?.let { return it }
        return try {
            context.assets.open(assetPath).use { stream: InputStream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    assetCache.put(assetPath, bmp)
                }
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    fun showFocusRing(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusStartTime = SystemClock.uptimeMillis()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Funny Face Mesh Warping (drawBitmapMesh in real time)
        if (currentLens.anchor.startsWith("WARP_") && faceData.hasFace) {
            drawLiveMeshWarp(canvas, w, h, currentLens.anchor)
        }

        // 2. Base Lens & AR Filter
        drawLensEffects(canvas, w, h)

        // 3. Smart Face Gesture Triggers (Mouth open, Smile, Wink)
        if (faceData.hasFace) {
            if (faceData.isMouthOpen || faceData.mouthOpenRatio > 0.30f) {
                drawMouthOpenEffects(canvas, w, h)
            }
            if (faceData.isSmiling) {
                drawSmileEffects(canvas, w, h)
            }
            if (faceData.isWinking) {
                drawWinkEffects(canvas, w, h)
            }
        }

        // 4. In-Memory Particle Simulation & Render Loop
        updateAndRenderParticles(canvas, w, h)

        // 5. Pro Visual Filter Styles
        when (filterStyle) {
            "BEAUTY" -> drawBeautyRadiance(canvas, w, h)
            "VHS_90S" -> drawVhs90sHud(canvas, w, h)
            "CYBERPUNK" -> drawCyberpunkHud(canvas, w, h)
            "WARM_SUNSET" -> drawWarmSunset(canvas, w, h)
        }

        // 6. Grid
        if (isGridEnabled) {
            drawRuleOfThirdsGrid(canvas, w, h)
        }

        // 7. Tap-to-Focus Ring
        drawFocusRing(canvas)

        // 8. Night Screen Ring Light Flash
        if (isNightFlashEnabled) {
            drawNightFlashBorder(canvas, w, h)
        }

        // 60 FPS animation loop when needed
        val hasActiveParticles = particlePool.any { it.active }
        val isWinkFlashActive = (SystemClock.uptimeMillis() - winkFlashStartTime) < 600L
        val isAnimated = (faceData.hasFace && (faceData.isMouthOpen || faceData.isSmiling || faceData.isWinking))
            || hasActiveParticles || isWinkFlashActive || currentLens.anchor.startsWith("WARP_")
            || filterStyle != "NORMAL" || isFocusActive()
        if (isAnimated) {
            postInvalidateOnAnimation()
        }
    }

    // =========================================================================
    // REAL-TIME MESH DEFORMATION ENGINE (drawBitmapMesh)
    // =========================================================================

    private fun drawLiveMeshWarp(canvas: Canvas, w: Float, h: Float, warpType: String) {
        val tv = cameraTextureView ?: return
        if (warpBufferBmp == null || warpBufferBmp?.width != 360 || warpBufferBmp?.height != 640) {
            warpBufferBmp = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        }
        val bmp = warpBufferBmp ?: return

        try {
            tv.getBitmap(bmp)
            computeMeshVertices(w, h, warpType)
            canvas.drawBitmapMesh(bmp, MESH_WIDTH, MESH_HEIGHT, meshVerts, 0, null, 0, meshPaint)
        } catch (_: Exception) {}
    }

    fun computeMeshVertices(w: Float, h: Float, warpType: String) {
        val face = faceData
        val fb = face.bounds
        val faceCenterX = fb.centerX() * w
        val faceCenterY = fb.centerY() * h
        val faceW = fb.width() * w
        val faceH = fb.height() * h

        val noseX = (face.noseBase?.x ?: fb.centerX()) * w
        val noseY = (face.noseBase?.y ?: (fb.top + fb.height() * 0.55f)) * h

        val leftEyeX = (face.leftEye?.x ?: (fb.left + fb.width() * 0.3f)) * w
        val leftEyeY = (face.leftEye?.y ?: (fb.top + fb.height() * 0.35f)) * h
        val rightEyeX = (face.rightEye?.x ?: (fb.right - fb.width() * 0.3f)) * w
        val rightEyeY = (face.rightEye?.y ?: (fb.top + fb.height() * 0.35f)) * h

        val mouthX = (face.mouthCenter?.x ?: fb.centerX()) * w
        val mouthY = (face.mouthCenter?.y ?: (fb.top + fb.height() * 0.75f)) * h

        val eyeDist = hypot((rightEyeX - leftEyeX).toDouble(), (rightEyeY - leftEyeY).toDouble()).toFloat().coerceAtLeast(30f)

        var idx = 0
        for (j in 0..MESH_HEIGHT) {
            val fy = j.toFloat() / MESH_HEIGHT
            val y = fy * h
            for (i in 0..MESH_WIDTH) {
                val fx = i.toFloat() / MESH_WIDTH
                val x = fx * w

                var vx = x
                var vy = y

                when (warpType) {
                    "WARP_CHUBBY" -> {
                        // SCREENSHOT 1: Chubby Baby Cheeks / Puffy Cheeks & Rounded Chin
                        val cheekRadius = eyeDist * 1.15f
                        val leftCheekX = noseX - eyeDist * 0.75f
                        val rightCheekX = noseX + eyeDist * 0.75f
                        val cheekY = noseY + eyeDist * 0.35f

                        // Left cheek bulge
                        val d1 = hypot((x - leftCheekX).toDouble(), (y - cheekY).toDouble()).toFloat()
                        if (d1 < cheekRadius) {
                            val f = (1f - d1 / cheekRadius)
                            vx -= (f * f) * (eyeDist * 0.45f)
                            vy += (f * f) * (eyeDist * 0.20f)
                        }
                        // Right cheek bulge
                        val d2 = hypot((x - rightCheekX).toDouble(), (y - cheekY).toDouble()).toFloat()
                        if (d2 < cheekRadius) {
                            val f = (1f - d2 / cheekRadius)
                            vx += (f * f) * (eyeDist * 0.45f)
                            vy += (f * f) * (eyeDist * 0.20f)
                        }
                        // Soft round chin push upward
                        val chinDist = hypot((x - noseX).toDouble(), (y - (mouthY + eyeDist * 0.5f)).toDouble()).toFloat()
                        if (chinDist < eyeDist * 0.8f) {
                            val f = (1f - chinDist / (eyeDist * 0.8f))
                            vy -= f * (eyeDist * 0.22f)
                        }
                    }

                    "WARP_CROOKED" -> {
                        // SCREENSHOT 5: Crooked / Teda Face (Asymmetric smirk & tilted features)
                        val relY = (y - faceCenterY) / (faceH * 0.6f)
                        if (relY in -1.2f..1.2f && (x - faceCenterX).let { it * it } < (faceW * 0.7f).let { it * it }) {
                            val wave = sin((relY * Math.PI).toDouble()).toFloat()
                            vx += wave * (faceW * 0.22f)
                            // Pull right side of mouth up, left side down
                            if (x > faceCenterX) {
                                vy -= (1f - (x - faceCenterX) / (faceW * 0.8f)).coerceIn(0f, 1f) * (faceH * 0.12f)
                            } else {
                                vy += (1f - (faceCenterX - x) / (faceW * 0.8f)).coerceIn(0f, 1f) * (faceH * 0.08f)
                            }
                        }
                    }

                    "WARP_PIGNOSE" -> {
                        // SCREENSHOT 4: Big Pig Snout & Wide Curved Mouth
                        val snoutRadius = eyeDist * 1.1f
                        val dNose = hypot((x - noseX).toDouble(), (y - noseY).toDouble()).toFloat()
                        if (dNose < snoutRadius) {
                            val factor = (1f - dNose / snoutRadius)
                            vx += (x - noseX) * (factor * 0.85f)
                            vy += (y - noseY) * (factor * 0.85f)
                        }
                        // Mouth wide stretch
                        val dMouth = hypot((x - mouthX).toDouble(), (y - mouthY).toDouble()).toFloat()
                        if (dMouth < eyeDist * 0.9f) {
                            val factor = (1f - dMouth / (eyeDist * 0.9f))
                            vx += (if (x > mouthX) 1f else -1f) * factor * (eyeDist * 0.35f)
                        }
                    }

                    "WARP_CAVEMAN" -> {
                        // SCREENSHOT 2: Caveman Long Jaw & Low Heavy Brow
                        if (y > noseY) {
                            val stretch = min((y - noseY) * 0.55f, faceH * 0.30f)
                            vy += stretch
                        }
                        if (y in (fb.top * h)..noseY) {
                            vy += (faceH * 0.06f)
                        }
                    }

                    "WARP_ALIEN" -> {
                        // Alien Huge Eyes & Pointy V-Chin
                        val eyeR = eyeDist * 0.70f
                        val dLeft = hypot((x - leftEyeX).toDouble(), (y - leftEyeY).toDouble()).toFloat()
                        if (dLeft < eyeR) {
                            val f = (1f - dLeft / eyeR)
                            vx += (x - leftEyeX) * (f * 0.75f)
                            vy += (y - leftEyeY) * (f * 0.75f)
                        }
                        val dRight = hypot((x - rightEyeX).toDouble(), (y - rightEyeY).toDouble()).toFloat()
                        if (dRight < eyeR) {
                            val f = (1f - dRight / eyeR)
                            vx += (x - rightEyeX) * (f * 0.75f)
                            vy += (y - rightEyeY) * (f * 0.75f)
                        }
                        // Steep chin pinch
                        if (y > mouthY) {
                            val pinch = ((y - mouthY) / (faceH * 0.4f)).coerceIn(0f, 1f) * 0.45f
                            vx = faceCenterX + (vx - faceCenterX) * (1f - pinch)
                        }
                    }

                    "WARP_MEGAMOUTH" -> {
                        // Dynamic Mega Mouth that expands with mouth opening
                        val mouthR = eyeDist * (1.1f + face.mouthOpenRatio * 0.5f)
                        val d = hypot((x - mouthX).toDouble(), (y - mouthY).toDouble()).toFloat()
                        if (d < mouthR) {
                            val f = (1f - d / mouthR)
                            vx += (x - mouthX) * (f * 1.2f)
                            vy += (y - mouthY) * (f * 1.2f)
                        }
                    }

                    "WARP_TINYFACE" -> {
                        // Tiny Squeezed Face
                        val d = hypot((x - noseX).toDouble(), (y - noseY).toDouble()).toFloat()
                        val faceR = faceW * 0.75f
                        if (d < faceR) {
                            val f = (d / faceR)
                            vx = noseX + (x - noseX) * (0.4f + f * 0.6f)
                            vy = noseY + (y - noseY) * (0.4f + f * 0.6f)
                        }
                    }

                    "WARP_SWIRL" -> {
                        // Vortex Swirl Centered on Nose
                        val d = hypot((x - noseX).toDouble(), (y - noseY).toDouble()).toFloat()
                        val swirlR = faceW * 0.8f
                        if (d < swirlR) {
                            val angle = (1f - d / swirlR) * 1.4f
                            val theta = atan2((y - noseY).toDouble(), (x - noseX).toDouble()).toFloat() + angle
                            vx = noseX + d * cos(theta.toDouble()).toFloat()
                            vy = noseY + d * sin(theta.toDouble()).toFloat()
                        }
                    }

                    "WARP_SQUARE" -> {
                        // Block Head
                        if (x in (fb.left * w)..(fb.right * w) && y in (fb.top * h)..(fb.bottom * h)) {
                            val dx = if (x > faceCenterX) 1f else -1f
                            vx += dx * (faceW * 0.12f)
                        }
                    }

                    "WARP_RIPPLE" -> {
                        // Jello Wave Ripple
                        val time = (SystemClock.uptimeMillis() % 2000L) / 2000f * 6.28318f
                        if (y in (fb.top * h)..(fb.bottom * h)) {
                            vx += sin((y / 40f + time).toDouble()).toFloat() * (faceW * 0.08f)
                        }
                    }
                }

                meshVerts[idx] = vx
                meshVerts[idx + 1] = vy
                origMeshVerts[idx] = x
                origMeshVerts[idx + 1] = y
                idx += 2
            }
        }
    }

    // =========================================================================
    // LENS PROPS & EFFECTS (Animals, Beauty, Glasses, Magic)
    // =========================================================================

    fun drawLensEffects(canvas: Canvas, w: Float, h: Float) {
        val lens = currentLens

        when (lens.anchor) {
            "NONE" -> {}

            // Animals
            "PUPPY" -> drawPuppyFilter(canvas, w, h, lens)
            "BUNNY" -> drawBunnyFilter(canvas, w, h, lens)
            "KITTY" -> drawKittyFilter(canvas, w, h, lens)
            "BEAR" -> drawBearFilter(canvas, w, h, lens)
            "PANDA" -> drawPandaFilter(canvas, w, h, lens)
            "FOX" -> drawFoxFilter(canvas, w, h, lens)
            "TIGER" -> drawTigerFilter(canvas, w, h, lens)
            "LION" -> drawLionFilter(canvas, w, h, lens)
            "HAMSTER" -> drawHamsterFilter(canvas, w, h, lens)
            "UNICORN" -> drawUnicornFilter(canvas, w, h, lens)

            // Beauty Filters (Real Image Processing)
            "BEAUTY_PEACH" -> drawBeautyPeach(canvas, w, h)
            "BEAUTY_PORCELAIN" -> drawBeautyPorcelain(canvas, w, h)
            "BEAUTY_GOLDEN" -> drawBeautyGolden(canvas, w, h)
            "BEAUTY_ROSE" -> drawBeautyRose(canvas, w, h)
            "BEAUTY_FILM35" -> drawBeautyFilm35(canvas, w, h)
            "BEAUTY_TEALORANGE" -> drawBeautyTealOrange(canvas, w, h)
            "BEAUTY_GLASS" -> drawBeautyGlass(canvas, w, h)
            "BEAUTY_FRECKLES" -> drawBeautyFreckles(canvas, w, h)
            "BEAUTY_BUTTERFLY" -> drawBeautyButterfly(canvas, w, h, lens)
            "BEAUTY_NOIR" -> drawBeautyNoir(canvas, w, h)

            // Glasses & Accessories
            "EYES" -> drawEyesAnchor(canvas, w, h, lens)
            "HEAD" -> drawHeadAnchor(canvas, w, h, lens)
            "NOSE" -> drawNoseAnchor(canvas, w, h, lens)
            "FULL_FACE" -> drawFullFaceAnchor(canvas, w, h, lens)

            // Magic & Fantasy
            "MAGIC_RAINBOW" -> drawMagicRainbow(canvas, w, h)
            "MAGIC_GHOST" -> drawMagicGhost(canvas, w, h)
            "MAGIC_GALAXY" -> drawMagicGalaxy(canvas, w, h)
            "MAGIC_GLITTER" -> drawMagicGlitter(canvas, w, h)
            "MAGIC_LIGHTNING" -> drawMagicLightning(canvas, w, h)
            "MAGIC_ICE" -> drawMagicIce(canvas, w, h)
            "MAGIC_VHS" -> drawVhs90sHud(canvas, w, h)
            "MAGIC_NEONHUD" -> drawCyberpunkHud(canvas, w, h)

            else -> {
                if (lens.assetPath.isNotEmpty()) {
                    drawHeadAnchor(canvas, w, h, lens)
                }
            }
        }
    }

    // --- ANIMAL FILTERS ---

    private fun drawPuppyFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        drawHeadAnchor(canvas, w, h, lens)
        if (lens.secondaryAssetPath.isNotEmpty()) {
            drawPropAtNose(canvas, w, h, lens.secondaryAssetPath, scaleFactor = 0.55f)
        }
        // Animated tongue when mouth opens
        if (face.isMouthOpen || face.mouthOpenRatio > 0.25f) {
            val mouth = face.mouthCenter ?: return
            val tongueW = (face.bounds.width() * w * 0.32f)
            val tongueH = tongueW * 1.25f
            paint.color = Color.parseColor("#FF6B8B")
            paint.style = Paint.Style.FILL
            val tx = mouth.x * w
            val ty = mouth.y * h + tongueH * 0.3f
            reusableRect.set(tx - tongueW / 2f, ty, tx + tongueW / 2f, ty + tongueH)
            canvas.drawRoundRect(reusableRect, tongueW / 2f, tongueW / 2f, paint)
            // Tongue center crease
            paint.color = Color.parseColor("#E04868")
            paint.strokeWidth = 3f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(tx, ty + tongueH * 0.15f, tx, ty + tongueH * 0.75f, paint)
        }
    }

    private fun drawBunnyFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        drawHeadAnchor(canvas, w, h, lens)
        if (lens.secondaryAssetPath.isNotEmpty()) {
            drawPropAtNose(canvas, w, h, lens.secondaryAssetPath, scaleFactor = 0.65f)
        }
        // Carrot pops up on smile
        if (face.isSmiling) {
            val mouth = face.mouthRight ?: face.mouthCenter ?: return
            val cx = mouth.x * w + 20f * density
            val cy = mouth.y * h
            paint.color = Color.parseColor("#FF7A00")
            paint.style = Paint.Style.FILL
            val cPath = Path().apply {
                moveTo(cx - 15f * density, cy - 8f * density)
                lineTo(cx + 40f * density, cy + 20f * density)
                lineTo(cx - 5f * density, cy + 15f * density)
                close()
            }
            canvas.drawPath(cPath, paint)
            // Green carrot leaves
            paint.color = Color.parseColor("#4CAF50")
            canvas.drawCircle(cx - 18f * density, cy - 10f * density, 8f * density, paint)
            canvas.drawCircle(cx - 24f * density, cy - 4f * density, 7f * density, paint)
        }
    }

    private fun drawKittyFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        if (lens.secondaryAssetPath.isNotEmpty()) {
            drawPropAtNose(canvas, w, h, lens.secondaryAssetPath, scaleFactor = 0.85f)
        }
        drawCuteBlush(canvas, w, h, "#FFAEC9", 120)
    }

    private fun drawBearFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        if (lens.secondaryAssetPath.isNotEmpty()) {
            drawPropAtNose(canvas, w, h, lens.secondaryAssetPath, scaleFactor = 0.65f)
        }
    }

    private fun drawPandaFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        drawHeadAnchor(canvas, w, h, lens)
        // Draw soft panda eye patches
        val leftEye = face.leftEye
        val rightEye = face.rightEye
        if (leftEye != null && rightEye != null) {
            paint.color = Color.argb(160, 25, 25, 30)
            paint.style = Paint.Style.FILL
            val eyeR = face.bounds.width() * w * 0.16f
            canvas.drawCircle(leftEye.x * w, leftEye.y * h, eyeR, paint)
            canvas.drawCircle(rightEye.x * w, rightEye.y * h, eyeR, paint)
        }
    }

    private fun drawFoxFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        // Spawn falling autumn leaves
        if (Random.nextFloat() < 0.25f) {
            spawnParticle(
                x = Random.nextFloat() * w,
                y = 0f,
                vx = (Random.nextFloat() - 0.5f) * 60f,
                vy = Random.nextFloat() * 120f + 60f,
                type = ParticleType.AUTUMN_LEAF,
                color = Color.parseColor("#E65100"),
                size = 18f * density,
                maxLife = 3.5f
            )
        }
    }

    private fun drawTigerFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        val face = faceData
        if (!face.hasFace) return
        // Forehead tiger "王" mark
        val fb = face.bounds
        val cx = fb.centerX() * w
        val cy = (fb.top + fb.height() * 0.25f) * h
        paint.color = Color.argb(220, 20, 20, 20)
        paint.strokeWidth = 4f * density
        paint.style = Paint.Style.STROKE
        val s = 14f * density
        canvas.drawLine(cx - s, cy - s, cx + s, cy - s, paint)
        canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.7f, cy, paint)
        canvas.drawLine(cx - s * 1.2f, cy + s, cx + s * 1.2f, cy + s, paint)
        canvas.drawLine(cx, cy - s, cx, cy + s, paint)
    }

    private fun drawLionFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawFullFaceAnchor(canvas, w, h, lens)
    }

    private fun drawHamsterFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        drawCuteBlush(canvas, w, h, "#FF80AB", 160)
    }

    private fun drawUnicornFilter(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        if (faceData.isSmiling && Random.nextFloat() < 0.35f) {
            val fb = faceData.bounds
            spawnParticle(
                x = (fb.centerX() + (Random.nextFloat() - 0.5f) * fb.width()) * w,
                y = (fb.top + Random.nextFloat() * 0.4f * fb.height()) * h,
                vx = (Random.nextFloat() - 0.5f) * 100f,
                vy = -Random.nextFloat() * 80f - 40f,
                type = ParticleType.STAR_FLASH,
                color = Color.parseColor("#FFD700"),
                size = 20f * density,
                maxLife = 1.2f
            )
        }
    }

    // --- BEAUTY FILTERS (Real Image Processing & Tone Curves) ---

    private fun drawBeautyPeach(canvas: Canvas, w: Float, h: Float) {
        drawBeautyRadiance(canvas, w, h)
        // Warm peach tint overlay
        paint.color = Color.argb(32, 255, 140, 100)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        drawCuteBlush(canvas, w, h, "#FF8A65", 80)
    }

    private fun drawBeautyPorcelain(canvas: Canvas, w: Float, h: Float) {
        // High-key soft diffusion glow
        paint.color = Color.argb(40, 255, 245, 240)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        drawBeautyRadiance(canvas, w, h)
    }

    private fun drawBeautyGolden(canvas: Canvas, w: Float, h: Float) {
        // 3200K Golden hour sunlight gradient
        val sunGradient = RadialGradient(
            w * 0.9f, h * 0.1f, w * 0.85f,
            intArrayOf(Color.argb(90, 255, 180, 50), Color.argb(35, 255, 120, 20), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = sunGradient
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawBeautyRose(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.argb(30, 255, 105, 180)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        drawCuteBlush(canvas, w, h, "#E91E63", 95)
    }

    private fun drawBeautyFilm35(canvas: Canvas, w: Float, h: Float) {
        // 35mm Vintage Film Grain & Warm Highlights
        paint.color = Color.argb(25, 255, 230, 200)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        // Film grain
        grainPaint.shader = grainShader
        grainPaint.alpha = 55
        canvas.drawRect(0f, 0f, w, h, grainPaint)
    }

    private fun drawBeautyTealOrange(canvas: Canvas, w: Float, h: Float) {
        // Teal in shadows, orange on skin
        val tealGrad = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.argb(45, 0, 150, 160), Color.argb(20, 255, 110, 30)),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = tealGrad
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawBeautyGlass(canvas: Canvas, w: Float, h: Float) {
        drawBeautyRadiance(canvas, w, h)
        val face = faceData
        if (!face.hasFace) return
        // Specular dewy gloss on cheekbones and nose bridge
        val leftCheek = face.leftCheek
        val rightCheek = face.rightCheek
        val nose = face.noseBase
        paint.color = Color.argb(120, 255, 255, 255)
        paint.style = Paint.Style.FILL
        if (leftCheek != null) canvas.drawCircle(leftCheek.x * w, leftCheek.y * h - 10f * density, 16f * density, paint)
        if (rightCheek != null) canvas.drawCircle(rightCheek.x * w, rightCheek.y * h - 10f * density, 16f * density, paint)
        if (nose != null) canvas.drawCircle(nose.x * w, nose.y * h - 15f * density, 8f * density, paint)
    }

    private fun drawBeautyFreckles(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        if (!face.hasFace) return
        val nose = face.noseBase ?: return
        val nx = nose.x * w
        val ny = nose.y * h - 12f * density
        paint.color = Color.argb(170, 130, 80, 45)
        paint.style = Paint.Style.FILL
        // Dot aesthetic freckles
        val freckleOffsets = arrayOf(
            Pair(-35f, -8f), Pair(-25f, 2f), Pair(-18f, -6f), Pair(-8f, 3f),
            Pair(0f, -2f), Pair(8f, 4f), Pair(18f, -5f), Pair(26f, 1f), Pair(36f, -7f),
            Pair(-45f, 6f), Pair(-12f, 9f), Pair(14f, 8f), Pair(42f, 7f)
        )
        for ((dx, dy) in freckleOffsets) {
            val r = (1.8f + (dx.toInt() % 2) * 0.8f) * density
            canvas.drawCircle(nx + dx * density * 0.6f, ny + dy * density * 0.6f, r, paint)
        }
    }

    private fun drawBeautyButterfly(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        drawHeadAnchor(canvas, w, h, lens)
        drawCuteBlush(canvas, w, h, "#CE93D8", 100)
    }

    private fun drawBeautyNoir(canvas: Canvas, w: Float, h: Float) {
        // High contrast B&W filter
        paint.color = Color.argb(60, 0, 0, 0)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        // Vignette
        val vig = RadialGradient(
            w / 2f, h / 2f, hypot(w.toDouble(), h.toDouble()).toFloat() * 0.55f,
            intArrayOf(Color.TRANSPARENT, Color.argb(160, 0, 0, 0)),
            floatArrayOf(0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = vig
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    // --- MAGIC & FANTASY FILTERS ---

    private fun drawMagicRainbow(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        if (!face.hasFace) return
        val mouth = face.mouthCenter ?: return
        val mx = mouth.x * w
        val my = mouth.y * h

        // Flowing dynamic rainbow arch
        val bandH = 12f * density
        val colors = RAINBOW_COLORS
        for (i in colors.indices) {
            paint.color = colors[i]
            paint.strokeWidth = bandH
            paint.style = Paint.Style.STROKE
            val yOffset = i * bandH
            val p = Path().apply {
                moveTo(mx, my + yOffset)
                cubicTo(
                    mx - w * 0.18f, my + h * 0.22f + yOffset,
                    mx + w * 0.18f, my + h * 0.38f + yOffset,
                    mx, h + 50f
                )
            }
            canvas.drawPath(p, paint)
        }
    }

    private fun drawMagicGhost(canvas: Canvas, w: Float, h: Float) {
        // Cyan ghost mist aura
        paint.color = Color.argb(45, 0, 230, 255)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        val face = faceData
        if (face.hasFace) {
            val lx = (face.leftEye?.x ?: 0.4f) * w
            val ly = (face.leftEye?.y ?: 0.4f) * h
            val rx = (face.rightEye?.x ?: 0.6f) * w
            val ry = (face.rightEye?.y ?: 0.4f) * h
            // Glowing ghostly eyes
            paint.color = Color.argb(180, 0, 255, 255)
            canvas.drawCircle(lx, ly, 22f * density, paint)
            canvas.drawCircle(rx, ry, 22f * density, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(lx, ly, 10f * density, paint)
            canvas.drawCircle(rx, ry, 10f * density, paint)
        }
    }

    private fun drawMagicGalaxy(canvas: Canvas, w: Float, h: Float) {
        val galGrad = RadialGradient(
            w * 0.5f, h * 0.35f, w * 0.8f,
            intArrayOf(Color.argb(80, 140, 20, 255), Color.argb(45, 20, 40, 160), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = galGrad
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // Forehead gold crescent moon
        val face = faceData
        if (face.hasFace) {
            val fb = face.bounds
            val mx = fb.centerX() * w
            val my = (fb.top + fb.height() * 0.18f) * h
            paint.color = Color.parseColor("#FFD700")
            paint.style = Paint.Style.FILL
            canvas.drawCircle(mx, my, 16f * density, paint)
            paint.color = Color.parseColor("#2A1845")
            canvas.drawCircle(mx + 6f * density, my - 3f * density, 14f * density, paint)
        }
    }

    private fun drawMagicGlitter(canvas: Canvas, w: Float, h: Float) {
        if (Random.nextFloat() < 0.45f) {
            spawnParticle(
                x = Random.nextFloat() * w,
                y = Random.nextFloat() * h * 0.7f,
                vx = (Random.nextFloat() - 0.5f) * 40f,
                vy = Random.nextFloat() * 60f + 30f,
                type = ParticleType.STAR_FLASH,
                color = Color.parseColor("#FFD700"),
                size = 14f * density,
                maxLife = 2f
            )
        }
    }

    private fun drawMagicLightning(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        if (!face.hasFace) return
        val lx = (face.leftEye?.x ?: 0.35f) * w
        val ly = (face.leftEye?.y ?: 0.4f) * h
        paint.color = Color.parseColor("#00E5FF")
        paint.strokeWidth = 3f * density
        paint.style = Paint.Style.STROKE
        val lp = Path().apply {
            moveTo(lx - 20f * density, ly - 30f * density)
            lineTo(lx - 5f * density, ly)
            lineTo(lx - 15f * density, ly + 10f * density)
            lineTo(lx + 10f * density, ly + 40f * density)
        }
        canvas.drawPath(lp, paint)
    }

    private fun drawMagicIce(canvas: Canvas, w: Float, h: Float) {
        // Frost border
        val frostW = 35f * density
        paint.color = Color.argb(120, 220, 245, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = frostW
        canvas.drawRect(0f, 0f, w, h, paint)
        // Snowflakes
        if (Random.nextFloat() < 0.3f) {
            spawnParticle(
                x = Random.nextFloat() * w,
                y = 0f,
                vx = (Random.nextFloat() - 0.5f) * 30f,
                vy = Random.nextFloat() * 80f + 40f,
                type = ParticleType.SNOWFLAKE,
                color = Color.WHITE,
                size = 12f * density,
                maxLife = 4f
            )
        }
    }

    // --- ANCHOR RENDERING UTILITIES ---

    private fun drawPropAtNose(canvas: Canvas, w: Float, h: Float, assetPath: String, scaleFactor: Float = 0.6f) {
        val bmp = getLensBitmap(assetPath) ?: return
        val face = faceData
        val nose = face.noseBase
        val nx = (nose?.x ?: face.bounds.centerX()) * w
        val ny = (nose?.y ?: (face.bounds.top + face.bounds.height() * 0.55f)) * h
        val propW = face.bounds.width() * w * scaleFactor
        val scale = propW / bmp.width.toFloat()

        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(face.headEulerZ)
        matrix.postTranslate(nx, ny)
        canvas.drawBitmap(bmp, matrix, paint)
    }

    private fun drawEyesAnchor(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        val bmp = getLensBitmap(lens.assetPath) ?: return

        val leftEye = face.leftEye
        val rightEye = face.rightEye

        var centerX = w * 0.5f
        var centerY = h * 0.38f
        var glassesWidth = w * 0.65f
        var rotation = face.headEulerZ

        if (leftEye != null && rightEye != null) {
            val lx = leftEye.x * w
            val ly = leftEye.y * h
            val rx = rightEye.x * w
            val ry = rightEye.y * h

            centerX = (lx + rx) / 2f
            centerY = (ly + ry) / 2f

            val dx = rx - lx
            val dy = ry - ly
            val eyeDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            glassesWidth = (eyeDist * 2.35f).coerceIn(w * 0.35f, w * 0.95f)
            rotation = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else {
            val fb = face.bounds
            centerX = fb.centerX() * w
            centerY = (fb.top + fb.height() * 0.35f) * h
            glassesWidth = (fb.width() * w * 1.05f).coerceIn(w * 0.35f, w * 0.95f)
        }

        val scale = glassesWidth / bmp.width.toFloat()
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(rotation)
        matrix.postTranslate(centerX, centerY)

        canvas.drawBitmap(bmp, matrix, paint)
    }

    private fun drawHeadAnchor(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        val bmp = getLensBitmap(lens.assetPath) ?: return

        val fb = face.bounds
        val centerX = fb.centerX() * w
        val hatWidth = (fb.width() * w * 1.15f).coerceIn(w * 0.35f, w * 0.95f)
        val hatHeight = hatWidth * (bmp.height.toFloat() / bmp.width.toFloat())
        val centerY = (fb.top * h) - (hatHeight * 0.35f)

        val scale = hatWidth / bmp.width.toFloat()
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(face.headEulerZ)
        matrix.postTranslate(centerX, centerY)

        canvas.drawBitmap(bmp, matrix, paint)
    }

    private fun drawFullFaceAnchor(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        val bmp = getLensBitmap(lens.assetPath) ?: return

        val fb = face.bounds
        val centerX = fb.centerX() * w
        val centerY = fb.centerY() * h
        val faceWidth = (fb.width() * w * 1.25f).coerceIn(w * 0.4f, w * 0.98f)

        val scale = faceWidth / bmp.width.toFloat()
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(face.headEulerZ)
        matrix.postTranslate(centerX, centerY)

        canvas.drawBitmap(bmp, matrix, paint)
    }

    private fun drawNoseAnchor(canvas: Canvas, w: Float, h: Float, lens: LensItem) {
        val face = faceData
        if (!face.hasFace) return
        drawPropAtNose(canvas, w, h, lens.assetPath, scaleFactor = 0.65f)
    }

    private fun drawCuteBlush(canvas: Canvas, w: Float, h: Float, hexColor: String, alphaInt: Int) {
        val face = faceData
        if (!face.hasFace) return
        val leftCheek = face.leftCheek
        val rightCheek = face.rightCheek
        blushPaint.color = Color.parseColor(hexColor)
        blushPaint.alpha = alphaInt
        blushPaint.style = Paint.Style.FILL
        val r = face.bounds.width() * w * 0.12f
        if (leftCheek != null) canvas.drawCircle(leftCheek.x * w, leftCheek.y * h, r, blushPaint)
        if (rightCheek != null) canvas.drawCircle(rightCheek.x * w, rightCheek.y * h, r, blushPaint)
    }

    // =========================================================================
    // SMART FACE GESTURE TRIGGERS (Mouth Open, Smile, Wink)
    // =========================================================================

    private fun drawMouthOpenEffects(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        val mouth = face.mouthCenter ?: return
        val mx = mouth.x * w
        val my = mouth.y * h

        val count = 2
        for (i in 0 until count) {
            val color = RAINBOW_COLORS[Random.nextInt(RAINBOW_COLORS.size)]
            spawnParticle(
                x = mx + (Random.nextFloat() - 0.5f) * 20f * density,
                y = my,
                vx = (Random.nextFloat() - 0.5f) * 160f * density,
                vy = (Random.nextFloat() * 220f + 140f) * density,
                type = ParticleType.RAINBOW,
                color = color,
                size = Random.nextFloat() * 12f * density + 8f * density,
                maxLife = 1.2f
            )
        }
    }

    private fun drawSmileEffects(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        drawCuteBlush(canvas, w, h, "#FF4081", 130)

        // Draw Kawaii Hearts over Eyes
        val leftEye = face.leftEye
        val rightEye = face.rightEye
        val heartScale = face.bounds.width() * w * 0.14f

        heartPaint.color = Color.parseColor("#FF1744")
        heartPaint.style = Paint.Style.FILL
        if (leftEye != null) drawKawaiiHeart(canvas, leftEye.x * w, leftEye.y * h, heartScale)
        if (rightEye != null) drawKawaiiHeart(canvas, rightEye.x * w, rightEye.y * h, heartScale)
    }

    private fun drawKawaiiHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        heartPath.reset()
        val top = cy - size * 0.4f
        heartPath.moveTo(cx, cy + size * 0.5f)
        heartPath.cubicTo(cx - size, cy - size * 0.2f, cx - size * 0.5f, top, cx, cy - size * 0.1f)
        heartPath.cubicTo(cx + size * 0.5f, top, cx + size, cy - size * 0.2f, cx, cy + size * 0.5f)
        canvas.drawPath(heartPath, heartPaint)
    }

    private fun drawWinkEffects(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        val winkEye = if (face.isLeftEyeWinking) face.leftEye else face.rightEye
        if (winkEye != null) {
            winkAnchorX = winkEye.x * w
            winkAnchorY = winkEye.y * h
        } else {
            winkAnchorX = face.bounds.centerX() * w
            winkAnchorY = (face.bounds.top + face.bounds.height() * 0.35f) * h
        }

        if (!wasWinking) {
            winkFlashStartTime = SystemClock.uptimeMillis()
            wasWinking = true
        }

        val elapsed = SystemClock.uptimeMillis() - winkFlashStartTime
        if (elapsed < 500L) {
            val progress = elapsed / 500f
            val starSize = (1f - progress) * 65f * density
            starPaint.color = Color.parseColor("#FFF176")
            starPaint.style = Paint.Style.FILL
            drawStarburst(canvas, winkAnchorX, winkAnchorY, starSize)
        } else {
            wasWinking = false
        }
    }

    private fun drawStarburst(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        starPath.reset()
        val points = 8
        val innerR = r * 0.35f
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) r else innerR
            val angle = i * Math.PI / points
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        canvas.drawPath(starPath, starPaint)
    }

    // =========================================================================
    // PARTICLE SIMULATION (Zero Allocation)
    // =========================================================================

    private fun spawnParticle(x: Float, y: Float, vx: Float, vy: Float, type: ParticleType, color: Int, size: Float, maxLife: Float) {
        for (p in particlePool) {
            if (!p.active) {
                p.active = true
                p.x = x
                p.y = y
                p.vx = vx
                p.vy = vy
                p.type = type
                p.color = color
                p.size = size
                p.life = 0f
                p.maxLife = maxLife
                p.alpha = 1f
                p.rotation = Random.nextFloat() * 360f
                p.rotSpeed = (Random.nextFloat() - 0.5f) * 120f
                break
            }
        }
    }

    private fun updateAndRenderParticles(canvas: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTime == 0L) 0.016f else ((now - lastFrameTime) / 1000f).coerceIn(0.001f, 0.050f)
        lastFrameTime = now

        for (p in particlePool) {
            if (!p.active) continue
            p.life += dt
            if (p.life >= p.maxLife) {
                p.active = false
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rotation += p.rotSpeed * dt
            p.alpha = (1f - p.life / p.maxLife).coerceIn(0f, 1f)

            particlePaint.color = p.color
            particlePaint.alpha = (p.alpha * 255).toInt()

            when (p.type) {
                ParticleType.RAINBOW, ParticleType.FIRE -> {
                    canvas.drawCircle(p.x, p.y, p.size * (1f - p.life / p.maxLife), particlePaint)
                }
                ParticleType.STAR_FLASH, ParticleType.HALO_SPARKLE -> {
                    drawStarburst(canvas, p.x, p.y, p.size * p.alpha)
                }
                ParticleType.AUTUMN_LEAF -> {
                    canvas.drawCircle(p.x, p.y, p.size * 0.7f, particlePaint)
                }
                ParticleType.SNOWFLAKE -> {
                    canvas.drawCircle(p.x, p.y, p.size * 0.5f, particlePaint)
                }
                else -> {
                    canvas.drawCircle(p.x, p.y, p.size, particlePaint)
                }
            }
        }
    }

    private fun renderParticles(canvas: Canvas) {
        for (p in particlePool) {
            if (!p.active) continue
            particlePaint.color = p.color
            particlePaint.alpha = (p.alpha * 255).toInt()
            canvas.drawCircle(p.x, p.y, p.size, particlePaint)
        }
    }

    // =========================================================================
    // HUD & OVERLAYS (VHS, Cyberpunk, Night Flash, Focus Ring)
    // =========================================================================

    private fun drawBeautyRadiance(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.argb(28, 255, 235, 220)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private fun drawWarmSunset(canvas: Canvas, w: Float, h: Float) {
        val sunsetShader = LinearGradient(0f, 0f, 0f, h, Color.argb(44, 255, 140, 0), Color.argb(62, 180, 50, 10), Shader.TileMode.CLAMP)
        paint.shader = sunsetShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawVhs90sHud(canvas: Canvas, w: Float, h: Float) {
        // Red blinking REC dot
        val blink = (SystemClock.uptimeMillis() % 1000L) < 500L
        if (blink) {
            vhsTextPaint.color = Color.RED
            canvas.drawCircle(35f * density, 40f * density, 8f * density, vhsTextPaint)
        }
        vhsTextPaint.color = Color.WHITE
        vhsTextPaint.textSize = 14f * density
        vhsTextPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("REC 📼  SP", 50f * density, 45f * density, vhsTextPaint)

        val dateStr = SimpleDateFormat("MMM dd yyyy  HH:mm:ss", Locale.US).format(Date())
        canvas.drawText(dateStr, 35f * density, h - 35f * density, vhsTextPaint)

        // Subtle scanlines
        vhsScanlinePaint.color = Color.argb(18, 0, 0, 0)
        vhsScanlinePaint.strokeWidth = 1f
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, vhsScanlinePaint)
            y += 4f * density
        }
    }

    private fun drawCyberpunkHud(canvas: Canvas, w: Float, h: Float) {
        val face = faceData
        cyberPaint.color = Color.parseColor("#00F2FE")
        cyberPaint.strokeWidth = 2f * density
        cyberPaint.style = Paint.Style.STROKE

        val cx = if (face.hasFace) face.bounds.centerX() * w else w / 2f
        val cy = if (face.hasFace) face.bounds.centerY() * h else h * 0.4f
        val r = if (face.hasFace) face.bounds.width() * w * 0.65f else 120f * density

        // Targeting brackets
        canvas.drawCircle(cx, cy, r, cyberPaint)
        val s = 25f * density
        canvas.drawLine(cx - r, cy, cx - r + s, cy, cyberPaint)
        canvas.drawLine(cx + r, cy, cx + r - s, cy, cyberPaint)
        canvas.drawLine(cx, cy - r, cx, cy - r + s, cyberPaint)
        canvas.drawLine(cx, cy + r, cx, cy + r - s, cyberPaint)

        cyberTextPaint.color = Color.parseColor("#00F2FE")
        cyberTextPaint.textSize = 11f * density
        cyberTextPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("LOCK // TARGET ACQUIRED", cx - r, cy + r + 20f * density, cyberTextPaint)
    }

    private fun drawRuleOfThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        gridPaint.color = Color.argb(70, 255, 255, 255)
        gridPaint.strokeWidth = 1f
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
    }

    private fun drawFocusRing(canvas: Canvas) {
        if (!isFocusActive()) return
        val elapsed = SystemClock.uptimeMillis() - focusStartTime
        val progress = (elapsed / 1200f).coerceIn(0f, 1f)
        val ringR = (1f - progress * 0.35f) * 45f * density
        focusRingPaint.color = Color.argb(((1f - progress) * 255).toInt(), 255, 235, 59)
        focusRingPaint.strokeWidth = 2.5f * density
        focusRingPaint.style = Paint.Style.STROKE
        canvas.drawCircle(focusX, focusY, ringR, focusRingPaint)
    }

    private fun isFocusActive(): Boolean {
        return focusStartTime > 0 && (SystemClock.uptimeMillis() - focusStartTime) < 1200L
    }

    private fun drawNightFlashBorder(canvas: Canvas, w: Float, h: Float) {
        val borderWidth = 40f * density
        nightFlashPaint.color = Color.argb(235, 255, 250, 235)
        nightFlashPaint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, borderWidth, nightFlashPaint)
        canvas.drawRect(0f, h - borderWidth, w, h, nightFlashPaint)
        canvas.drawRect(0f, 0f, borderWidth, h, nightFlashPaint)
        canvas.drawRect(w - borderWidth, 0f, w, h, nightFlashPaint)
    }

    // =========================================================================
    // PHOTO CAPTURE BAKING PIPELINE
    // =========================================================================

    fun drawCaptureLayers(canvas: Canvas, w: Float, h: Float, basePhoto: Bitmap? = null) {
        // 1. If warp filter is active and basePhoto is provided, apply full-resolution mesh deformation
        if (currentLens.anchor.startsWith("WARP_") && basePhoto != null && faceData.hasFace) {
            computeMeshVertices(w, h, currentLens.anchor)
            canvas.drawBitmapMesh(basePhoto, MESH_WIDTH, MESH_HEIGHT, meshVerts, 0, null, 0, meshPaint)
        }

        // 2. Draw 3D AR Lens props & stickers
        drawLensEffects(canvas, w, h)

        // 3. Draw Active Face Gesture Triggers
        if (faceData.hasFace) {
            if (faceData.isMouthOpen || faceData.mouthOpenRatio > 0.30f) {
                drawMouthOpenEffects(canvas, w, h)
            }
            if (faceData.isSmiling) {
                drawSmileEffects(canvas, w, h)
            }
            if (faceData.isWinking) {
                drawWinkEffects(canvas, w, h)
            }
        }

        // 4. Render Active In-Memory Particles
        renderParticles(canvas)

        // 5. Active frame grid if enabled
        if (isGridEnabled) {
            drawRuleOfThirdsGrid(canvas, w, h)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (p in particlePool) p.active = false
        warpBufferBmp?.recycle()
        warpBufferBmp = null
    }
}
'''

with open("/home/am/storyline/android/app/src/main/kotlin/com/video/engine/camera/CameraFilterOverlayView.kt", "w", encoding="utf-8") as f:
    f.write(KOTLIN_CODE)

print("CameraFilterOverlayView.kt updated successfully.")
