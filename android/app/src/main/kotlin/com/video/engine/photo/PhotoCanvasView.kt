package com.video.engine.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class PhotoCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    companion object {
        const val DEFAULT_CANVAS_WIDTH = 1080
        const val DEFAULT_CANVAS_HEIGHT = 1080
        private const val MIN_CANVAS_SIDE = 256
        private const val MAX_CANVAS_SIDE = 4096
        private const val MIN_LAYER_SCALE = 0.18f
        private const val MAX_LAYER_SCALE = 8f
        private const val MAX_VIEW_ZOOM = 5f
        private const val MIN_VIEW_ZOOM = 0.55f
        private const val SNAP_DISTANCE = 18f
        const val ALIGN_START = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_END = 2
        private const val PHOTO_BACKGROUND_LAYER_ID = 0
        private const val LAYER_GESTURE_MOVE = 0
        private const val LAYER_GESTURE_RESIZE = 1
        private const val IMAGE_HANDLE_NONE = 0
        private const val IMAGE_HANDLE_TOP_LEFT = 1
        private const val IMAGE_HANDLE_TOP = 2
        private const val IMAGE_HANDLE_TOP_RIGHT = 3
        private const val IMAGE_HANDLE_RIGHT = 4
        private const val IMAGE_HANDLE_BOTTOM_RIGHT = 5
        private const val IMAGE_HANDLE_BOTTOM = 6
        private const val IMAGE_HANDLE_BOTTOM_LEFT = 7
        private const val IMAGE_HANDLE_LEFT = 8
        private const val RESIZE_HANDLE_RADIUS = 42f
        private const val MIN_RESIZE_SIDE = 36f
        const val IMAGE_MASK_NONE = 0
        const val IMAGE_MASK_ROUND = 1
        const val IMAGE_MASK_CIRCLE = 2
        const val IMAGE_MASK_WIDE = 3
        const val IMAGE_CUTOUT_OFF = 0
        const val IMAGE_CUTOUT_AUTO = 1
        const val IMAGE_CUTOUT_GREEN = 2
        const val IMAGE_CUTOUT_LIGHT = 3
    }

    private val canvasRect = RectF(
        0f,
        0f,
        DEFAULT_CANVAS_WIDTH.toFloat(),
        DEFAULT_CANVAS_HEIGHT.toFloat(),
    )
    private val viewMatrix = Matrix()
    private val inverseViewMatrix = Matrix()
    private val imageDest = RectF()
    private val imageMaskPath = Path()
    private val imageColorMatrix = ColorMatrix()
    private val imageAdjustMatrix = ColorMatrix()
    private val imageLayerColorMatrix = ColorMatrix()
    private val imageLayerAdjustMatrix = ColorMatrix()
    private val magicBeforeMatrix = ColorMatrix()
    private val magicBeforeAdjustMatrix = ColorMatrix()
    private val textBounds = Rect()
    private val hitRect = RectF()
    private val shapeRect = RectF()
    private val selectionRect = RectF()
    private val shapePath = Path()
    private val brushPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(99, 218, 255)
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(16f, 14f), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 255, 255, 255)
        strokeWidth = 1f
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 215, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 132, 255)
        style = Paint.Style.FILL
    }

    private var baseImage: Bitmap? = null
    private var processedImageBitmap: Bitmap? = null
    private var processedImageCacheKey: String = ""
    private var nextLayerId = 1
    private var selectedLayerType: Int? = null
    private var selectedLayerId: Int? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var layerStartX = 0f
    private var layerStartY = 0f
    private var layerStartScale = 1f
    private var layerStartRotation = 0f
    private var pinchStartSpan = 1f
    private var pinchStartAngle = 0f
    private var pinchStartCenterX = 0f
    private var pinchStartCenterY = 0f
    private var layerGestureMode = LAYER_GESTURE_MOVE
    private var activeImageResizeHandle = IMAGE_HANDLE_NONE
    private var layerStartWidth = 1f
    private var layerStartHeight = 1f
    private var viewStartZoom = 1f
    private var viewStartPanX = 0f
    private var viewStartPanY = 0f
    private var viewGestureStartX = 0f
    private var viewGestureStartY = 0f
    private var viewportZoom = 1f
    private var viewportPanX = 0f
    private var viewportPanY = 0f
    private var snapCenterX = false
    private var snapCenterY = false
    private var lastSnapCenterX = false
    private var lastSnapCenterY = false
    private var suppressHistory = false
    private var magicSweepInProgress = false
    private var magicSweepRevealFraction = 1f
    private var magicSweepBeforeBitmap: Bitmap? = null
    private var currentStroke: PhotoBrushStroke? = null

    private val undoStack = ArrayDeque<PhotoCanvasSnapshot>()
    private val redoStack = ArrayDeque<PhotoCanvasSnapshot>()

    var canvasBackgroundColor: Int = Color.rgb(248, 252, 255)
        set(value) {
            if (field == value) return
            pushUndoSnapshot()
            field = value
            markDesignChanged()
        }
    var backgroundRotationDeg: Float = 0f
        set(value) {
            val nextValue = normalizeRotation(value)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var canvasOutputWidth: Int = DEFAULT_CANVAS_WIDTH
        private set
    var canvasOutputHeight: Int = DEFAULT_CANVAS_HEIGHT
        private set
    var canvasSizeLabel: String = "Square"
        private set
    var transparentBackground: Boolean = false
        set(value) {
            if (field == value) return
            pushUndoSnapshot()
            field = value
            markDesignChanged()
        }
    var gridVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var canvasTransformLocked: Boolean = true
    var imageBrightness: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(-100f, 100f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageContrast: Float = 1f
        set(value) {
            val nextValue = value.coerceIn(0.5f, 2.5f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageSaturation: Float = 1f
        set(value) {
            val nextValue = value.coerceIn(0f, 2f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageTemperature: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(-100f, 100f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageBlur: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(0f, 32f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageVignette: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(0f, 1f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageFilterIntensity: Float = 1f
        set(value) {
            val nextValue = value.coerceIn(0f, 1f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageTintColor: Int = Color.TRANSPARENT
        private set
    var imageTintStrength: Float = 0f
        private set
    var imageMaskMode: Int = IMAGE_MASK_NONE
        set(value) {
            val nextValue = value.coerceIn(IMAGE_MASK_NONE, IMAGE_MASK_WIDE)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            markDesignChanged()
        }
    var imageCutoutMode: Int = IMAGE_CUTOUT_OFF
        set(value) {
            val nextValue = value.coerceIn(IMAGE_CUTOUT_OFF, IMAGE_CUTOUT_LIGHT)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            invalidateProcessedImageCache()
            markDesignChanged()
        }
    var imageCutoutThreshold: Float = 42f
        set(value) {
            val nextValue = value.coerceIn(8f, 180f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            invalidateProcessedImageCache()
            markDesignChanged()
        }
    var imageCutoutFeather: Float = 24f
        set(value) {
            val nextValue = value.coerceIn(0f, 120f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            invalidateProcessedImageCache()
            markDesignChanged()
        }
    var imageCurveShadows: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(-100f, 100f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            invalidateProcessedImageCache()
            markDesignChanged()
        }
    var imageCurveMidtones: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(-100f, 100f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            invalidateProcessedImageCache()
            markDesignChanged()
        }
    var imageCurveHighlights: Float = 0f
        set(value) {
            val nextValue = value.coerceIn(-100f, 100f)
            if (field == nextValue) return
            pushUndoSnapshot()
            field = nextValue
            invalidateProcessedImageCache()
            markDesignChanged()
        }
    fun setImageCutout(mode: Int, threshold: Float = imageCutoutThreshold, feather: Float = imageCutoutFeather) {
        val nextMode = mode.coerceIn(IMAGE_CUTOUT_OFF, IMAGE_CUTOUT_LIGHT)
        val nextThreshold = threshold.coerceIn(8f, 180f)
        val nextFeather = feather.coerceIn(0f, 120f)
        if (
            imageCutoutMode == nextMode &&
            imageCutoutThreshold == nextThreshold &&
            imageCutoutFeather == nextFeather
        ) {
            return
        }
        pushUndoSnapshot()
        imageCutoutMode = nextMode
        imageCutoutThreshold = nextThreshold
        imageCutoutFeather = nextFeather
        invalidateProcessedImageCache()
        markDesignChanged()
    }
    fun setImageCurve(shadows: Float, midtones: Float, highlights: Float) {
        val nextShadows = shadows.coerceIn(-100f, 100f)
        val nextMidtones = midtones.coerceIn(-100f, 100f)
        val nextHighlights = highlights.coerceIn(-100f, 100f)
        if (
            imageCurveShadows == nextShadows &&
            imageCurveMidtones == nextMidtones &&
            imageCurveHighlights == nextHighlights
        ) {
            return
        }
        pushUndoSnapshot()
        imageCurveShadows = nextShadows
        imageCurveMidtones = nextMidtones
        imageCurveHighlights = nextHighlights
        invalidateProcessedImageCache()
        markDesignChanged()
    }
    var drawModeEnabled: Boolean = false
    var brushType: Int = BRUSH_PEN
    var brushColor: Int = Color.WHITE
    var brushSize: Float = 18f
    var brushOpacity: Int = 255
    var brushEraser: Boolean = false

    val textLayers: MutableList<PhotoTextLayer> = mutableListOf()
    val imageLayers: MutableList<PhotoImageLayer> = mutableListOf()
    val shapeLayers: MutableList<PhotoShapeLayer> = mutableListOf()
    val brushStrokes: MutableList<PhotoBrushStroke> = mutableListOf()

    var onSelectionChanged: ((PhotoLayerItem?) -> Unit)? = null
    var onSelectionPicked: ((PhotoLayerItem?) -> Unit)? = null
    var onDesignChanged: (() -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun selectedTextLayer(): PhotoTextLayer? =
        if (selectedLayerType == PHOTO_LAYER_TEXT) {
            selectedLayerId?.let { id -> textLayers.firstOrNull { it.id == id } }
        } else {
            null
        }

    fun selectedShapeLayer(): PhotoShapeLayer? =
        if (selectedLayerType == PHOTO_LAYER_SHAPE) {
            selectedLayerId?.let { id -> shapeLayers.firstOrNull { it.id == id } }
        } else {
            null
        }

    fun selectedLayerItem(): PhotoLayerItem? =
        selectedLayerType?.let { type ->
            selectedLayerId?.let { id -> buildLayerItem(type, id) }
        }

    fun hasBaseImage(): Boolean = baseImage != null

    fun hasImageLayers(): Boolean = imageLayers.isNotEmpty()

    fun selectedImageLayer(): PhotoImageLayer? =
        if (selectedLayerType == PHOTO_LAYER_IMAGE) {
            selectedLayerId?.let { id -> imageLayers.firstOrNull { it.id == id } }
        } else {
            null
        }

    private fun selectedBackgroundImage(): Boolean =
        selectedLayerType == PHOTO_LAYER_BACKGROUND && selectedLayerId == PHOTO_BACKGROUND_LAYER_ID && baseImage != null

    fun selectBaseImage(): Boolean {
        if (baseImage == null) return false
        selectLayer(PHOTO_LAYER_BACKGROUND, PHOTO_BACKGROUND_LAYER_ID)
        return true
    }

    fun selectTopImageLayer(): Boolean {
        val layer = imageLayers.lastOrNull() ?: return false
        selectLayer(PHOTO_LAYER_IMAGE, layer.id)
        return true
    }

    fun selectTopEditableLayer(): Boolean {
        val item = layerItems().firstOrNull { it.type != PHOTO_LAYER_BACKGROUND } ?: return false
        selectLayer(item.type, item.id)
        return true
    }

    fun setBaseImage(bitmap: Bitmap?) {
        pushUndoSnapshot()
        baseImage = bitmap
        invalidateProcessedImageCache()
        if (bitmap == null) {
            if (selectedLayerType == PHOTO_LAYER_BACKGROUND) {
                selectedLayerType = null
                selectedLayerId = null
            }
        } else {
            selectedLayerType = PHOTO_LAYER_BACKGROUND
            selectedLayerId = PHOTO_BACKGROUND_LAYER_ID
        }
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    fun rotateBackgroundBy(deltaDegrees: Float) {
        backgroundRotationDeg = backgroundRotationDeg + deltaDegrees
    }

    fun addImageLayer(uri: String?, bitmap: Bitmap): PhotoImageLayer {
        pushUndoSnapshot()
        val fitScale = min(canvasRect.width() / bitmap.width, canvasRect.height() / bitmap.height)
            .coerceAtMost(1f)
        val layer = PhotoImageLayer(
            id = nextLayerId++,
            uri = uri,
            bitmap = bitmap,
            x = canvasRect.centerX(),
            y = canvasRect.centerY(),
            width = bitmap.width * fitScale * 0.82f,
            height = bitmap.height * fitScale * 0.82f,
        )
        imageLayers += layer
        selectLayer(PHOTO_LAYER_IMAGE, layer.id)
        markDesignChanged()
        return layer
    }

    fun hasSelectedOrAnyImageLayer(): Boolean =
        selectedImageLayer() != null || imageLayers.isNotEmpty()

    fun setImageTint(color: Int, strength: Float) {
        val nextStrength = strength.coerceIn(0f, 1f)
        if (imageTintColor == color && imageTintStrength == nextStrength) return
        pushUndoSnapshot()
        imageTintColor = color
        imageTintStrength = nextStrength
        markDesignChanged()
    }

    fun beginMagicSweep() {
        if (magicSweepInProgress) return
        val hasLockedBefore = magicSweepBeforeBitmap?.let { !it.isRecycled } == true
        if (!hasLockedBefore) {
            magicSweepBeforeBitmap?.let { if (!it.isRecycled) it.recycle() }
            magicSweepBeforeBitmap = renderToBitmap(forceTransparentBackground = false)
            magicSweepRevealFraction = 1f
        }
        pushUndoSnapshot()
        suppressHistory = true
        magicSweepInProgress = true
    }

    fun previewMagicSweep(revealStartFraction: Float) {
        if (!magicSweepInProgress) beginMagicSweep()
        magicSweepRevealFraction = revealStartFraction.coerceIn(0f, 1f)
        applyMagicSweepValues(1f)
        invalidate()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    fun commitMagicSweep() {
        if (!magicSweepInProgress) return
        magicSweepInProgress = false
        magicSweepBeforeBitmap?.let { if (!it.isRecycled) it.recycle() }
        magicSweepBeforeBitmap = null
        magicSweepRevealFraction = 1f
        suppressHistory = false
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    fun magicSweepViewportBounds(): RectF {
        updateViewMatrix()
        val points = floatArrayOf(canvasRect.left, canvasRect.top, canvasRect.right, canvasRect.bottom)
        viewMatrix.mapPoints(points)
        return RectF(
            min(points[0], points[2]),
            min(points[1], points[3]),
            max(points[0], points[2]),
            max(points[1], points[3]),
        )
    }

    fun magicSweepViewXForFraction(fraction: Float): Float {
        updateViewMatrix()
        val splitX = canvasRect.left + canvasRect.width() * fraction.coerceIn(0f, 1f)
        val point = floatArrayOf(splitX, canvasRect.centerY())
        viewMatrix.mapPoints(point)
        return point[0]
    }

    fun magicSweepFractionForViewX(viewX: Float): Float {
        updateViewMatrix()
        val point = floatArrayOf(viewX, 0f)
        inverseViewMatrix.mapPoints(point)
        return ((point[0] - canvasRect.left) / canvasRect.width()).coerceIn(0f, 1f)
    }

    fun magicSweepRevealFraction(): Float = magicSweepRevealFraction.coerceIn(0f, 1f)

    private fun applyMagicSweepValues(intensity: Float) {
        val strong = intensity.coerceIn(0f, 1f)
        imageBrightness = 10f * strong
        imageContrast = 1f + 0.14f * strong
        imageSaturation = 1f + 0.20f * strong
        imageTemperature = 4f * strong
        imageBlur = 0f
        imageVignette = 0f
        imageFilterIntensity = 1f
        imageTintColor = Color.TRANSPARENT
        imageTintStrength = 0f
        imageCurveShadows = 0f
        imageCurveMidtones = 0f
        imageCurveHighlights = 0f
        imageLayers.forEach { layer ->
            layer.brightness = 10f * strong
            layer.contrast = 1f + 0.14f * strong
            layer.saturation = 1f + 0.20f * strong
            layer.temperature = 4f * strong
            layer.blur = 0f
        }
        invalidateProcessedImageCache()
    }

    private fun magicHueColor(hue: Float, alpha: Int): Int =
        Color.HSVToColor(alpha.coerceIn(0, 255), floatArrayOf(((hue % 360f) + 360f) % 360f, 0.88f, 1f))

    fun setCanvasSize(width: Int, height: Int, label: String) {
        val nextWidth = width.coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
        val nextHeight = height.coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
        val nextLabel = label.ifBlank { "${nextWidth}x${nextHeight}" }
        if (
            canvasOutputWidth == nextWidth &&
            canvasOutputHeight == nextHeight &&
            canvasSizeLabel == nextLabel
        ) {
            return
        }
        pushUndoSnapshot()
        scaleLayersForCanvasResize(nextWidth, nextHeight)
        canvasOutputWidth = nextWidth
        canvasOutputHeight = nextHeight
        canvasSizeLabel = nextLabel
        canvasRect.set(0f, 0f, nextWidth.toFloat(), nextHeight.toFloat())
        resetViewport()
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    fun canvasSizeSummary(): String = "$canvasSizeLabel ${canvasOutputWidth}x${canvasOutputHeight}"

    fun addTextLayer(text: String = "New Text"): PhotoTextLayer {
        pushUndoSnapshot()
        val layer = PhotoTextLayer(
            id = nextLayerId++,
            text = text,
            x = canvasRect.centerX(),
            y = canvasRect.centerY(),
        )
        textLayers += layer
        selectLayer(PHOTO_LAYER_TEXT, layer.id)
        markDesignChanged()
        return layer
    }

    fun addShapeLayer(shapeType: Int = SHAPE_RECTANGLE): PhotoShapeLayer {
        pushUndoSnapshot()
        val layer = PhotoShapeLayer(
            id = nextLayerId++,
            shapeType = shapeType,
            x = canvasRect.centerX(),
            y = canvasRect.centerY(),
        )
        if (shapeType == SHAPE_LINE) {
            layer.height = 12f
            layer.strokeWidth = 12f
            layer.fillColor = Color.TRANSPARENT
        }
        shapeLayers += layer
        selectLayer(PHOTO_LAYER_SHAPE, layer.id)
        markDesignChanged()
        return layer
    }

    fun addBrushStamp(label: String): PhotoTextLayer {
        val layer = addTextLayer(label)
        updateSelectedTextLayer {
            it.backgroundColor = Color.rgb(19, 27, 34)
            it.strokeColor = Color.WHITE
            it.strokeWidth = 3f
            it.shadowEnabled = true
        }
        return layer
    }

    fun updateSelectedTextLayer(mutator: (PhotoTextLayer) -> Unit): Boolean {
        val layer = selectedTextLayer() ?: return false
        pushUndoSnapshot()
        mutator(layer)
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
        return true
    }

    fun updateSelectedShapeLayer(mutator: (PhotoShapeLayer) -> Unit): Boolean {
        val layer = selectedShapeLayer() ?: return false
        pushUndoSnapshot()
        mutator(layer)
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
        return true
    }

    fun updateSelectedImageLayer(mutator: (PhotoImageLayer) -> Unit): Boolean {
        val layer = selectedImageLayer() ?: return false
        pushUndoSnapshot()
        mutator(layer)
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
        return true
    }

    fun deleteSelectedLayer(): Boolean {
        val type = selectedLayerType ?: return false
        val id = selectedLayerId ?: return false
        pushUndoSnapshot()
        val removed = when (type) {
            PHOTO_LAYER_TEXT -> textLayers.removeAll { it.id == id }
            PHOTO_LAYER_SHAPE -> shapeLayers.removeAll { it.id == id }
            PHOTO_LAYER_BRUSH -> brushStrokes.removeAll { it.id == id }
            PHOTO_LAYER_IMAGE -> imageLayers.removeAll { it.id == id }
            PHOTO_LAYER_BACKGROUND -> {
                if (baseImage != null) {
                    baseImage = null
                    invalidateProcessedImageCache()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
        if (removed) {
            selectTopLayer()
            markDesignChanged()
        }
        return removed
    }

    fun duplicateSelectedLayer(): Boolean {
        val type = selectedLayerType ?: return false
        val id = selectedLayerId ?: return false
        pushUndoSnapshot()
        when (type) {
            PHOTO_LAYER_TEXT -> {
                val source = textLayers.firstOrNull { it.id == id } ?: return false
                val copy = source.copy(id = nextLayerId++, x = source.x + 42f, y = source.y + 42f)
                textLayers += copy
                selectLayer(PHOTO_LAYER_TEXT, copy.id)
            }
            PHOTO_LAYER_SHAPE -> {
                val source = shapeLayers.firstOrNull { it.id == id } ?: return false
                val copy = source.copy(id = nextLayerId++, x = source.x + 42f, y = source.y + 42f)
                shapeLayers += copy
                selectLayer(PHOTO_LAYER_SHAPE, copy.id)
            }
            PHOTO_LAYER_IMAGE -> {
                val source = imageLayers.firstOrNull { it.id == id } ?: return false
                val copy = source.copy(id = nextLayerId++, x = source.x + 42f, y = source.y + 42f)
                imageLayers += copy
                selectLayer(PHOTO_LAYER_IMAGE, copy.id)
            }
            PHOTO_LAYER_BRUSH -> {
                val source = brushStrokes.firstOrNull { it.id == id } ?: return false
                val copy = source.copy(
                    id = nextLayerId++,
                    points = source.points.map { PhotoPoint(it.x + 42f, it.y + 42f) }.toMutableList(),
                )
                brushStrokes += copy
                selectLayer(PHOTO_LAYER_BRUSH, copy.id)
            }
        }
        markDesignChanged()
        return true
    }

    fun moveSelectedToFront(): Boolean {
        val moved = moveSelectedWithinList(toFront = true)
        if (moved) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return moved
    }

    fun moveSelectedForward(): Boolean {
        val moved = moveSelectedOneStep(forward = true)
        if (moved) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return moved
    }

    fun moveSelectedBackward(): Boolean {
        val moved = moveSelectedOneStep(forward = false)
        if (moved) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return moved
    }

    fun moveSelectedToBack(): Boolean {
        val moved = moveSelectedWithinList(toFront = false)
        if (moved) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return moved
    }

    fun moveLayerForward(type: Int, id: Int): Boolean {
        if (!selectLayerFromPanel(type, id)) return false
        return moveSelectedForward()
    }

    fun moveLayerBackward(type: Int, id: Int): Boolean {
        if (!selectLayerFromPanel(type, id)) return false
        return moveSelectedBackward()
    }

    fun toggleLayerVisibility(type: Int, id: Int): Boolean {
        if (!selectLayerFromPanel(type, id)) return false
        return toggleSelectedVisibility()
    }

    fun toggleLayerLock(type: Int, id: Int): Boolean {
        if (!selectLayerFromPanel(type, id)) return false
        return toggleSelectedLock()
    }

    fun duplicateLayer(type: Int, id: Int): Boolean {
        if (!selectLayerFromPanel(type, id)) return false
        return duplicateSelectedLayer()
    }

    fun deleteLayer(type: Int, id: Int): Boolean {
        if (!selectLayerFromPanel(type, id)) return false
        return deleteSelectedLayer()
    }

    fun toggleSelectedVisibility(): Boolean {
        val type = selectedLayerType ?: return false
        val id = selectedLayerId ?: return false
        pushUndoSnapshot()
        when (type) {
            PHOTO_LAYER_TEXT -> textLayers.firstOrNull { it.id == id }?.let { it.visible = !it.visible } ?: return false
            PHOTO_LAYER_SHAPE -> shapeLayers.firstOrNull { it.id == id }?.let { it.visible = !it.visible } ?: return false
            PHOTO_LAYER_IMAGE -> imageLayers.firstOrNull { it.id == id }?.let { it.visible = !it.visible } ?: return false
            PHOTO_LAYER_BRUSH -> brushStrokes.firstOrNull { it.id == id }?.let { it.visible = !it.visible } ?: return false
        }
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
        return true
    }

    fun toggleSelectedLock(): Boolean {
        val type = selectedLayerType ?: return false
        val id = selectedLayerId ?: return false
        pushUndoSnapshot()
        when (type) {
            PHOTO_LAYER_TEXT -> textLayers.firstOrNull { it.id == id }?.let { it.locked = !it.locked } ?: return false
            PHOTO_LAYER_SHAPE -> shapeLayers.firstOrNull { it.id == id }?.let { it.locked = !it.locked } ?: return false
            PHOTO_LAYER_IMAGE -> imageLayers.firstOrNull { it.id == id }?.let { it.locked = !it.locked } ?: return false
            PHOTO_LAYER_BRUSH -> return false
        }
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
        return true
    }

    fun alignSelectedLayer(horizontal: Int? = null, vertical: Int? = null): Boolean {
        val bounds = selectedLayerBounds() ?: return false
        var dx = 0f
        var dy = 0f
        horizontal?.let { align ->
            val targetX = when (align) {
                ALIGN_START -> canvasRect.left + bounds.width() / 2f
                ALIGN_END -> canvasRect.right - bounds.width() / 2f
                else -> canvasRect.centerX()
            }
            dx = targetX - bounds.centerX()
        }
        vertical?.let { align ->
            val targetY = when (align) {
                ALIGN_START -> canvasRect.top + bounds.height() / 2f
                ALIGN_END -> canvasRect.bottom - bounds.height() / 2f
                else -> canvasRect.centerY()
            }
            dy = targetY - bounds.centerY()
        }
        if (dx == 0f && dy == 0f) return true
        pushUndoSnapshot()
        translateSelectedLayer(dx, dy)
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
        return true
    }

    fun setSelectedLayerScale(scale: Float): Boolean {
        val nextScale = scale.coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        pushUndoSnapshot()
        val updated = when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let { if (it.locked) false else { it.scale = nextScale; true } } == true
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let { if (it.locked) false else { it.scale = nextScale; true } } == true
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let { if (it.locked) false else { it.scale = nextScale; true } } == true
            else -> false
        }
        if (updated) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return updated
    }

    fun setSelectedLayerRotation(degrees: Float): Boolean {
        val nextRotation = normalizeRotation(degrees)
        pushUndoSnapshot()
        val updated = when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let { if (it.locked) false else { it.rotationDeg = nextRotation; true } } == true
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let { if (it.locked) false else { it.rotationDeg = nextRotation; true } } == true
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let { if (it.locked) false else { it.rotationDeg = nextRotation; true } } == true
            else -> false
        }
        if (updated) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return updated
    }

    fun rotateSelectedLayerBy(deltaDegrees: Float): Boolean =
        setSelectedLayerRotation(selectedLayerRotation() + deltaDegrees)

    fun flipSelectedLayer(horizontal: Boolean): Boolean {
        pushUndoSnapshot()
        val updated = when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let {
                if (it.locked) false else {
                    if (horizontal) it.flipX = !it.flipX else it.flipY = !it.flipY
                    true
                }
            } == true
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let {
                if (it.locked) false else {
                    if (horizontal) it.flipX = !it.flipX else it.flipY = !it.flipY
                    true
                }
            } == true
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let {
                if (it.locked) false else {
                    if (horizontal) it.flipX = !it.flipX else it.flipY = !it.flipY
                    true
                }
            } == true
            else -> false
        }
        if (updated) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return updated
    }

    fun setSelectedBlendMode(mode: Int): Boolean {
        val nextMode = mode.coerceIn(BLEND_NORMAL, BLEND_SCREEN)
        pushUndoSnapshot()
        val updated = when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let { it.blendMode = nextMode; true } == true
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let { it.blendMode = nextMode; true } == true
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let { it.blendMode = nextMode; true } == true
            else -> false
        }
        if (updated) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return updated
    }

    fun selectedLayerScale(): Float =
        selectedTextLayer()?.scale
            ?: selectedShapeLayer()?.scale
            ?: selectedImageLayer()?.scale
            ?: 1f

    fun selectedLayerRotation(): Float =
        selectedTextLayer()?.rotationDeg
            ?: selectedShapeLayer()?.rotationDeg
            ?: selectedImageLayer()?.rotationDeg
            ?: 0f

    fun selectedLayerOpacity(): Int =
        selectedTextLayer()?.opacity
            ?: selectedShapeLayer()?.opacity
            ?: selectedImageLayer()?.opacity
            ?: 255

    fun setSelectedLayerOpacity(opacity: Int): Boolean {
        val nextOpacity = opacity.coerceIn(0, 255)
        pushUndoSnapshot()
        val updated = when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let { it.opacity = nextOpacity; true } == true
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let { it.opacity = nextOpacity; true } == true
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let { it.opacity = nextOpacity; true } == true
            else -> false
        }
        if (updated) {
            markDesignChanged()
            onSelectionChanged?.invoke(selectedLayerItem())
        }
        return updated
    }

    fun selectedBlendMode(): Int =
        selectedTextLayer()?.blendMode
            ?: selectedShapeLayer()?.blendMode
            ?: selectedImageLayer()?.blendMode
            ?: BLEND_NORMAL

    fun selectLayerFromPanel(type: Int, id: Int): Boolean {
        if (buildLayerItem(type, id) == null) return false
        selectLayer(type, id)
        return true
    }

    fun layerItems(): List<PhotoLayerItem> {
        val items = mutableListOf<PhotoLayerItem>()
        if (baseImage != null) {
            items += PhotoLayerItem(PHOTO_LAYER_BACKGROUND, PHOTO_BACKGROUND_LAYER_ID, "Background", true, false)
        }
        imageLayers.forEach { items += PhotoLayerItem(PHOTO_LAYER_IMAGE, it.id, "Image ${it.id}", it.visible, it.locked) }
        brushStrokes.forEach { items += PhotoLayerItem(PHOTO_LAYER_BRUSH, it.id, "Brush ${it.id}", it.visible, false) }
        shapeLayers.forEach { items += PhotoLayerItem(PHOTO_LAYER_SHAPE, it.id, shapeLabel(it), it.visible, it.locked) }
        textLayers.forEach { items += PhotoLayerItem(PHOTO_LAYER_TEXT, it.id, it.text.take(22).ifBlank { "Text" }, it.visible, it.locked) }
        return items.asReversed()
    }

    fun clearLastBrushStroke(): Boolean {
        val stroke = brushStrokes.lastOrNull() ?: return false
        pushUndoSnapshot()
        brushStrokes.remove(stroke)
        markDesignChanged()
        return true
    }

    fun resetViewport() {
        viewportZoom = 1f
        viewportPanX = 0f
        viewportPanY = 0f
        invalidate()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val current = captureSnapshot()
        redoStack.push(current)
        val snapshot = undoStack.pop()
        restoreSnapshot(snapshot)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val current = captureSnapshot()
        undoStack.push(current)
        val snapshot = redoStack.pop()
        restoreSnapshot(snapshot)
        return true
    }

    fun resetDesign() {
        pushUndoSnapshot()
        suppressHistory = true
        magicSweepBeforeBitmap?.let { if (!it.isRecycled) it.recycle() }
        magicSweepBeforeBitmap = null
        magicSweepInProgress = false
        magicSweepRevealFraction = 1f
        baseImage = null
        imageBrightness = 0f
        imageContrast = 1f
        imageSaturation = 1f
        imageTemperature = 0f
        imageBlur = 0f
        imageVignette = 0f
        imageFilterIntensity = 1f
        imageTintColor = Color.TRANSPARENT
        imageTintStrength = 0f
        imageMaskMode = IMAGE_MASK_NONE
        imageCutoutMode = IMAGE_CUTOUT_OFF
        imageCutoutThreshold = 42f
        imageCutoutFeather = 24f
        imageCurveShadows = 0f
        imageCurveMidtones = 0f
        imageCurveHighlights = 0f
        transparentBackground = false
        backgroundRotationDeg = 0f
        invalidateProcessedImageCache()
        textLayers.clear()
        imageLayers.clear()
        shapeLayers.clear()
        brushStrokes.clear()
        selectedLayerType = null
        selectedLayerId = null
        canvasOutputWidth = DEFAULT_CANVAS_WIDTH
        canvasOutputHeight = DEFAULT_CANVAS_HEIGHT
        canvasSizeLabel = "Square"
        canvasRect.set(0f, 0f, DEFAULT_CANVAS_WIDTH.toFloat(), DEFAULT_CANVAS_HEIGHT.toFloat())
        canvasBackgroundColor = Color.rgb(248, 252, 255)
        suppressHistory = false
        markDesignChanged()
        onSelectionChanged?.invoke(null)
    }

    fun restoreDesign(
        backgroundColor: Int,
        bitmap: Bitmap?,
        layers: List<PhotoTextLayer>,
        imageBrightness: Float = 0f,
        imageContrast: Float = 1f,
        imageSaturation: Float = 1f,
        imageTintColor: Int = Color.TRANSPARENT,
        imageTintStrength: Float = 0f,
        imageMaskMode: Int = IMAGE_MASK_NONE,
        imageTemperature: Float = 0f,
        imageBlur: Float = 0f,
        imageVignette: Float = 0f,
        imageFilterIntensity: Float = 1f,
        imageCutoutMode: Int = IMAGE_CUTOUT_OFF,
        imageCutoutThreshold: Float = 42f,
        imageCutoutFeather: Float = 24f,
        imageCurveShadows: Float = 0f,
        imageCurveMidtones: Float = 0f,
        imageCurveHighlights: Float = 0f,
        transparentBackground: Boolean = false,
        backgroundRotationDeg: Float = 0f,
        imageLayers: List<PhotoImageLayer> = emptyList(),
        shapeLayers: List<PhotoShapeLayer> = emptyList(),
        brushStrokes: List<PhotoBrushStroke> = emptyList(),
        canvasOutputWidth: Int = DEFAULT_CANVAS_WIDTH,
        canvasOutputHeight: Int = DEFAULT_CANVAS_HEIGHT,
        canvasSizeLabel: String = "Square",
    ) {
        suppressHistory = true
        val nextWidth = canvasOutputWidth.coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
        val nextHeight = canvasOutputHeight.coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
        this.canvasOutputWidth = nextWidth
        this.canvasOutputHeight = nextHeight
        this.canvasSizeLabel = canvasSizeLabel.ifBlank { "${nextWidth}x${nextHeight}" }
        canvasRect.set(0f, 0f, nextWidth.toFloat(), nextHeight.toFloat())
        baseImage = bitmap
        magicSweepBeforeBitmap?.let { if (!it.isRecycled) it.recycle() }
        magicSweepBeforeBitmap = null
        magicSweepInProgress = false
        magicSweepRevealFraction = 1f
        this.imageBrightness = imageBrightness
        this.imageContrast = imageContrast
        this.imageSaturation = imageSaturation
        this.imageTemperature = imageTemperature
        this.imageBlur = imageBlur
        this.imageVignette = imageVignette
        this.imageFilterIntensity = imageFilterIntensity
        this.imageTintColor = imageTintColor
        this.imageTintStrength = imageTintStrength.coerceIn(0f, 1f)
        this.imageMaskMode = imageMaskMode
        this.imageCutoutMode = imageCutoutMode.coerceIn(IMAGE_CUTOUT_OFF, IMAGE_CUTOUT_LIGHT)
        this.imageCutoutThreshold = imageCutoutThreshold.coerceIn(8f, 180f)
        this.imageCutoutFeather = imageCutoutFeather.coerceIn(0f, 120f)
        this.imageCurveShadows = imageCurveShadows.coerceIn(-100f, 100f)
        this.imageCurveMidtones = imageCurveMidtones.coerceIn(-100f, 100f)
        this.imageCurveHighlights = imageCurveHighlights.coerceIn(-100f, 100f)
        invalidateProcessedImageCache()
        this.transparentBackground = transparentBackground
        this.backgroundRotationDeg = backgroundRotationDeg
        textLayers.clear()
        layers.forEach { layer -> textLayers.add(layer.copy()) }
        this.imageLayers.clear()
        imageLayers.forEach { layer -> this.imageLayers.add(layer.copy()) }
        this.shapeLayers.clear()
        shapeLayers.forEach { layer -> this.shapeLayers.add(layer.copy()) }
        this.brushStrokes.clear()
        brushStrokes.forEach { stroke ->
            this.brushStrokes.add(stroke.copy(points = stroke.points.map { it.copy() }.toMutableList()))
        }
        nextLayerId = maxOf(
            textLayers.maxOfOrNull { it.id } ?: 0,
            this.imageLayers.maxOfOrNull { it.id } ?: 0,
            this.shapeLayers.maxOfOrNull { it.id } ?: 0,
            this.brushStrokes.maxOfOrNull { it.id } ?: 0,
        ) + 1
        canvasBackgroundColor = backgroundColor
        suppressHistory = false
        selectTopLayer()
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    fun renderToBitmap(
        outputWidth: Int = canvasOutputWidth,
        outputHeight: Int = canvasOutputHeight,
        forceTransparentBackground: Boolean = false,
    ): Bitmap {
        val width = outputWidth.coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
        val height = outputHeight.coerceIn(MIN_CANVAS_SIDE, MAX_CANVAS_SIDE)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.scale(width / canvasRect.width(), height / canvasRect.height())
        drawDesign(
            canvas,
            showSelection = false,
            transparentOutput = forceTransparentBackground || transparentBackground,
        )
        return output
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateViewMatrix()
        canvas.drawColor(Color.rgb(218, 236, 252))
        canvas.save()
        canvas.concat(viewMatrix)
        if (magicSweepBeforeBitmap != null && magicSweepRevealFraction < 0.98f) {
            drawMagicSweepPreview(canvas)
        } else {
            drawDesign(canvas, showSelection = true, transparentOutput = false)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mapped = mapToCanvas(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                if (drawModeEnabled) {
                    startBrushStroke(mapped.first, mapped.second)
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val hit = findTopLayerAt(mapped.first, mapped.second)
                if (hit != null) {
                    selectLayer(hit.first, hit.second)
                    onSelectionPicked?.invoke(selectedLayerItem())
                    if (selectedLayerIsTransformable()) {
                        startLayerGesture(mapped.first, mapped.second)
                    } else {
                        viewGestureStartX = event.x
                        viewGestureStartY = event.y
                        viewStartPanX = viewportPanX
                        viewStartPanY = viewportPanY
                    }
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                selectLayer(null, null)
                onSelectionPicked?.invoke(null)
                viewGestureStartX = event.x
                viewGestureStartY = event.y
                viewStartPanX = viewportPanX
                viewStartPanY = viewportPanY
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!drawModeEnabled && selectedLayerIsTransformable()) {
                    startLayerTransform(event)
                } else if (!canvasTransformLocked) {
                    startViewportTransform(event)
                }
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawModeEnabled) {
                    appendBrushStroke(mapped.first, mapped.second)
                    return true
                }
                if (selectedLayerIsTransformable()) {
                    if (event.pointerCount >= 2) {
                        updateSelectedLayerTransform(event)
                    } else {
                        moveSelectedLayer(mapped.first, mapped.second)
                    }
                    invalidate()
                    return true
                }
                if (event.pointerCount >= 2 && !canvasTransformLocked) {
                    updateViewportTransform(event)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (drawModeEnabled) {
                    finishBrushStroke()
                } else if (selectedLayerIsTransformable()) {
                    finishLayerGesture()
                }
                snapCenterX = false
                snapCenterY = false
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun selectedLayerIsTransformable(): Boolean =
        selectedLayerType == PHOTO_LAYER_TEXT || selectedLayerType == PHOTO_LAYER_SHAPE || selectedLayerType == PHOTO_LAYER_IMAGE

    private fun startBrushStroke(x: Float, y: Float) {
        pushUndoSnapshot()
        currentStroke = PhotoBrushStroke(
            id = nextLayerId++,
            brushType = brushType,
            color = brushColor,
            size = brushSize,
            opacity = brushOpacity,
            eraser = brushEraser,
            points = mutableListOf(PhotoPoint(x, y)),
        )
        brushStrokes += currentStroke!!
        selectedLayerType = PHOTO_LAYER_BRUSH
        selectedLayerId = currentStroke!!.id
        onSelectionChanged?.invoke(selectedLayerItem())
        invalidate()
    }

    private fun appendBrushStroke(x: Float, y: Float) {
        val stroke = currentStroke ?: return
        val last = stroke.points.lastOrNull()
        if (last == null || hypot((x - last.x).toDouble(), (y - last.y).toDouble()) > 3.5) {
            stroke.points += PhotoPoint(x, y)
            invalidate()
        }
    }

    private fun finishBrushStroke() {
        currentStroke = null
        markDesignChanged()
    }

    private fun startLayerGesture(x: Float, y: Float) {
        pushUndoSnapshot()
        layerGestureMode = LAYER_GESTURE_MOVE
        activeImageResizeHandle = IMAGE_HANDLE_NONE
        gestureStartX = x
        gestureStartY = y
        selectedTextLayer()?.let {
            layerStartX = it.x
            layerStartY = it.y
            layerStartScale = it.scale
            layerStartRotation = it.rotationDeg
            layerStartWidth = 1f
            layerStartHeight = 1f
            return
        }
        selectedShapeLayer()?.let {
            layerStartX = it.x
            layerStartY = it.y
            layerStartScale = it.scale
            layerStartRotation = it.rotationDeg
            layerStartWidth = it.width
            layerStartHeight = it.height
            return
        }
        selectedImageLayer()?.let {
            layerStartX = it.x
            layerStartY = it.y
            layerStartScale = it.scale
            layerStartRotation = it.rotationDeg
            layerStartWidth = it.width.coerceAtLeast(MIN_RESIZE_SIDE)
            layerStartHeight = it.height.coerceAtLeast(MIN_RESIZE_SIDE)
            activeImageResizeHandle = imageLayerResizeHandleAt(it, x, y)
            layerGestureMode = if (activeImageResizeHandle == IMAGE_HANDLE_NONE) LAYER_GESTURE_MOVE else LAYER_GESTURE_RESIZE
        }
    }

    private fun startLayerTransform(event: MotionEvent) {
        selectedTextLayer()?.let {
            layerStartX = it.x
            layerStartY = it.y
            layerStartScale = it.scale
            layerStartRotation = it.rotationDeg
        }
        selectedShapeLayer()?.let {
            layerStartX = it.x
            layerStartY = it.y
            layerStartScale = it.scale
            layerStartRotation = it.rotationDeg
            return@let
        }
        selectedImageLayer()?.let {
            layerStartX = it.x
            layerStartY = it.y
            layerStartScale = it.scale
            layerStartRotation = it.rotationDeg
            layerStartWidth = it.width
            layerStartHeight = it.height
        }
        layerGestureMode = LAYER_GESTURE_MOVE
        activeImageResizeHandle = IMAGE_HANDLE_NONE
        pinchStartSpan = pointerSpan(event).coerceAtLeast(1f)
        pinchStartAngle = pointerAngle(event)
        val center = pointerCenterInCanvas(event)
        pinchStartCenterX = center.first
        pinchStartCenterY = center.second
    }

    private fun startViewportTransform(event: MotionEvent) {
        pinchStartSpan = pointerSpan(event).coerceAtLeast(1f)
        viewStartZoom = viewportZoom
        viewStartPanX = viewportPanX
        viewStartPanY = viewportPanY
        val center = pointerCenterInView(event)
        viewGestureStartX = center.first
        viewGestureStartY = center.second
    }

    private fun moveSelectedLayer(x: Float, y: Float) {
        val dx = x - gestureStartX
        val dy = y - gestureStartY
        selectedTextLayer()?.let {
            if (it.locked) return
            it.x = layerStartX + dx
            it.y = layerStartY + dy
            val snapped = snapLayerPosition(it.x, it.y)
            it.x = snapped.first
            it.y = snapped.second
        }
        selectedShapeLayer()?.let {
            if (it.locked) return
            it.x = layerStartX + dx
            it.y = layerStartY + dy
            val snapped = snapLayerPosition(it.x, it.y)
            it.x = snapped.first
            it.y = snapped.second
            return
        }
        selectedImageLayer()?.let {
            if (it.locked) return
            if (layerGestureMode == LAYER_GESTURE_RESIZE) {
                resizeImageLayerFromHandle(it, x, y)
            } else {
                it.x = layerStartX + dx
                it.y = layerStartY + dy
                val snapped = snapLayerPosition(it.x, it.y)
                it.x = snapped.first
                it.y = snapped.second
            }
        }
    }

    private fun resizeImageLayerFromHandle(layer: PhotoImageLayer, x: Float, y: Float) {
        val handle = activeImageResizeHandle
        if (handle == IMAGE_HANDLE_NONE) return
        val scale = layerStartScale.coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        val minWidth = (MIN_RESIZE_SIDE / scale).coerceAtLeast(12f)
        val minHeight = (MIN_RESIZE_SIDE / scale).coerceAtLeast(12f)
        val local = startLayerLocalPoint(x, y)
        var left = -layerStartWidth / 2f
        var right = layerStartWidth / 2f
        var top = -layerStartHeight / 2f
        var bottom = layerStartHeight / 2f

        when (handle) {
            IMAGE_HANDLE_LEFT -> left = min(local.first, right - minWidth)
            IMAGE_HANDLE_RIGHT -> right = max(local.first, left + minWidth)
            IMAGE_HANDLE_TOP -> top = min(local.second, bottom - minHeight)
            IMAGE_HANDLE_BOTTOM -> bottom = max(local.second, top + minHeight)
            IMAGE_HANDLE_TOP_LEFT,
            IMAGE_HANDLE_TOP_RIGHT,
            IMAGE_HANDLE_BOTTOM_RIGHT,
            IMAGE_HANDLE_BOTTOM_LEFT -> {
                val anchorX = if (handle == IMAGE_HANDLE_TOP_LEFT || handle == IMAGE_HANDLE_BOTTOM_LEFT) right else left
                val anchorY = if (handle == IMAGE_HANDLE_TOP_LEFT || handle == IMAGE_HANDLE_TOP_RIGHT) bottom else top
                val signX = if (handle == IMAGE_HANDLE_TOP_LEFT || handle == IMAGE_HANDLE_BOTTOM_LEFT) -1f else 1f
                val signY = if (handle == IMAGE_HANDLE_TOP_LEFT || handle == IMAGE_HANDLE_TOP_RIGHT) -1f else 1f
                val aspect = (layerStartWidth / layerStartHeight.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
                var targetWidth = max(minWidth, kotlin.math.abs(local.first - anchorX))
                var targetHeight = max(minHeight, kotlin.math.abs(local.second - anchorY))
                if (targetWidth / aspect < targetHeight) {
                    targetWidth = targetHeight * aspect
                } else {
                    targetHeight = targetWidth / aspect
                }
                val draggedX = anchorX + signX * targetWidth
                val draggedY = anchorY + signY * targetHeight
                left = min(anchorX, draggedX)
                right = max(anchorX, draggedX)
                top = min(anchorY, draggedY)
                bottom = max(anchorY, draggedY)
            }
        }

        val nextWidth = (right - left).coerceAtLeast(minWidth)
        val nextHeight = (bottom - top).coerceAtLeast(minHeight)
        val centerLocalX = (left + right) / 2f
        val centerLocalY = (top + bottom) / 2f
        val center = startLayerWorldPoint(centerLocalX, centerLocalY)
        layer.x = center.first
        layer.y = center.second
        layer.width = nextWidth
        layer.height = nextHeight
        layer.scale = scale
        layer.rotationDeg = layerStartRotation
    }

    private fun updateSelectedLayerTransform(event: MotionEvent) {
        val currentSpan = pointerSpan(event).coerceAtLeast(1f)
        val currentAngle = pointerAngle(event)
        val center = pointerCenterInCanvas(event)
        val nextScale = (layerStartScale * (currentSpan / pinchStartSpan)).coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        val nextRotation = layerStartRotation + normalizeRotation(currentAngle - pinchStartAngle)
        selectedTextLayer()?.let {
            if (it.locked) return
            it.scale = nextScale
            it.rotationDeg = nextRotation
            it.x = layerStartX + center.first - pinchStartCenterX
            it.y = layerStartY + center.second - pinchStartCenterY
            val snapped = snapLayerPosition(it.x, it.y)
            it.x = snapped.first
            it.y = snapped.second
        }
        selectedShapeLayer()?.let {
            if (it.locked) return
            it.scale = nextScale
            it.rotationDeg = nextRotation
            it.x = layerStartX + center.first - pinchStartCenterX
            it.y = layerStartY + center.second - pinchStartCenterY
            val snapped = snapLayerPosition(it.x, it.y)
            it.x = snapped.first
            it.y = snapped.second
            return
        }
        selectedImageLayer()?.let {
            if (it.locked) return
            it.scale = nextScale
            it.rotationDeg = nextRotation
            it.x = layerStartX + center.first - pinchStartCenterX
            it.y = layerStartY + center.second - pinchStartCenterY
            val snapped = snapLayerPosition(it.x, it.y)
            it.x = snapped.first
            it.y = snapped.second
        }
    }

    private fun updateViewportTransform(event: MotionEvent) {
        val currentSpan = pointerSpan(event).coerceAtLeast(1f)
        val center = pointerCenterInView(event)
        viewportZoom = (viewStartZoom * (currentSpan / pinchStartSpan)).coerceIn(MIN_VIEW_ZOOM, MAX_VIEW_ZOOM)
        viewportPanX = viewStartPanX + center.first - viewGestureStartX
        viewportPanY = viewStartPanY + center.second - viewGestureStartY
    }

    private fun finishLayerGesture() {
        layerGestureMode = LAYER_GESTURE_MOVE
        activeImageResizeHandle = IMAGE_HANDLE_NONE
        markDesignChanged()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    private fun snapLayerPosition(currentX: Float, currentY: Float): Pair<Float, Float> {
        var nextX = currentX
        var nextY = currentY
        snapCenterX = false
        snapCenterY = false
        if (kotlin.math.abs(nextX - canvasRect.centerX()) <= SNAP_DISTANCE) {
            nextX = canvasRect.centerX()
            snapCenterX = true
        }
        if (kotlin.math.abs(nextY - canvasRect.centerY()) <= SNAP_DISTANCE) {
            nextY = canvasRect.centerY()
            snapCenterY = true
        }
        if ((snapCenterX && !lastSnapCenterX) || (snapCenterY && !lastSnapCenterY)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        lastSnapCenterX = snapCenterX
        lastSnapCenterY = snapCenterY
        return nextX to nextY
    }

    private fun markDesignChanged() {
        invalidate()
        if (!suppressHistory) {
            redoStack.clear()
            onDesignChanged?.invoke()
        }
    }

    private fun pushUndoSnapshot() {
        if (suppressHistory) return
        val snapshot = captureSnapshot()
        if (undoStack.peek() == snapshot) return
        undoStack.push(snapshot)
        while (undoStack.size > 50) {
            undoStack.removeLast()
        }
    }

    private fun restoreSnapshot(snapshot: PhotoCanvasSnapshot) {
        suppressHistory = true
        canvasOutputWidth = snapshot.canvasOutputWidth
        canvasOutputHeight = snapshot.canvasOutputHeight
        canvasSizeLabel = snapshot.canvasSizeLabel
        canvasRect.set(0f, 0f, canvasOutputWidth.toFloat(), canvasOutputHeight.toFloat())
        canvasBackgroundColor = snapshot.canvasBackgroundColor
        backgroundRotationDeg = snapshot.backgroundRotationDeg
        transparentBackground = snapshot.transparentBackground
        imageBrightness = snapshot.imageBrightness
        imageContrast = snapshot.imageContrast
        imageSaturation = snapshot.imageSaturation
        imageTemperature = snapshot.imageTemperature
        imageBlur = snapshot.imageBlur
        imageVignette = snapshot.imageVignette
        imageFilterIntensity = snapshot.imageFilterIntensity
        imageTintColor = snapshot.imageTintColor
        imageTintStrength = snapshot.imageTintStrength
        imageMaskMode = snapshot.imageMaskMode
        imageCutoutMode = snapshot.imageCutoutMode
        imageCutoutThreshold = snapshot.imageCutoutThreshold
        imageCutoutFeather = snapshot.imageCutoutFeather
        imageCurveShadows = snapshot.imageCurveShadows
        imageCurveMidtones = snapshot.imageCurveMidtones
        imageCurveHighlights = snapshot.imageCurveHighlights
        invalidateProcessedImageCache()
        textLayers.clear()
        textLayers.addAll(snapshot.textLayers.map { it.copy() })
        imageLayers.clear()
        imageLayers.addAll(snapshot.imageLayers.map { it.copy() })
        shapeLayers.clear()
        shapeLayers.addAll(snapshot.shapeLayers.map { it.copy() })
        brushStrokes.clear()
        brushStrokes.addAll(snapshot.brushStrokes.map { it.copy(points = it.points.map { point -> point.copy() }.toMutableList()) })
        selectedLayerType = snapshot.selectedLayerType
        selectedLayerId = snapshot.selectedLayerId
        nextLayerId = maxOf(
            textLayers.maxOfOrNull { it.id } ?: 0,
            imageLayers.maxOfOrNull { it.id } ?: 0,
            shapeLayers.maxOfOrNull { it.id } ?: 0,
            brushStrokes.maxOfOrNull { it.id } ?: 0,
        ) + 1
        suppressHistory = false
        invalidate()
        onDesignChanged?.invoke()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    private fun captureSnapshot(): PhotoCanvasSnapshot =
        PhotoCanvasSnapshot(
            canvasBackgroundColor = canvasBackgroundColor,
            backgroundRotationDeg = backgroundRotationDeg,
            transparentBackground = transparentBackground,
            imageBrightness = imageBrightness,
            imageContrast = imageContrast,
            imageSaturation = imageSaturation,
            imageTemperature = imageTemperature,
            imageBlur = imageBlur,
            imageVignette = imageVignette,
            imageFilterIntensity = imageFilterIntensity,
            imageTintColor = imageTintColor,
            imageTintStrength = imageTintStrength,
            imageMaskMode = imageMaskMode,
            imageCutoutMode = imageCutoutMode,
            imageCutoutThreshold = imageCutoutThreshold,
            imageCutoutFeather = imageCutoutFeather,
            imageCurveShadows = imageCurveShadows,
            imageCurveMidtones = imageCurveMidtones,
            imageCurveHighlights = imageCurveHighlights,
            canvasOutputWidth = canvasOutputWidth,
            canvasOutputHeight = canvasOutputHeight,
            canvasSizeLabel = canvasSizeLabel,
            textLayers = textLayers.map { it.copy() },
            imageLayers = imageLayers.map { it.copy() },
            shapeLayers = shapeLayers.map { it.copy() },
            brushStrokes = brushStrokes.map { it.copy(points = it.points.map { point -> point.copy() }.toMutableList()) },
            selectedLayerType = selectedLayerType,
            selectedLayerId = selectedLayerId,
        )

    private fun selectLayer(type: Int?, id: Int?) {
        selectedLayerType = type
        selectedLayerId = id
        invalidate()
        onSelectionChanged?.invoke(selectedLayerItem())
    }

    private fun selectTopLayer() {
        val item = layerItems().firstOrNull()
        selectedLayerType = item?.type
        selectedLayerId = item?.id
        onSelectionChanged?.invoke(item)
    }

    private fun buildLayerItem(type: Int, id: Int): PhotoLayerItem? =
        when (type) {
            PHOTO_LAYER_TEXT -> textLayers.firstOrNull { it.id == id }?.let {
                PhotoLayerItem(type, id, it.text.take(22).ifBlank { "Text" }, it.visible, it.locked)
            }
            PHOTO_LAYER_SHAPE -> shapeLayers.firstOrNull { it.id == id }?.let {
                PhotoLayerItem(type, id, shapeLabel(it), it.visible, it.locked)
            }
            PHOTO_LAYER_BRUSH -> brushStrokes.firstOrNull { it.id == id }?.let {
                PhotoLayerItem(type, id, "Brush $id", it.visible, false)
            }
            PHOTO_LAYER_IMAGE -> imageLayers.firstOrNull { it.id == id }?.let {
                PhotoLayerItem(type, id, "Image $id", it.visible, it.locked)
            }
            PHOTO_LAYER_BACKGROUND -> baseImage?.let {
                PhotoLayerItem(type, PHOTO_BACKGROUND_LAYER_ID, "Background", true, false)
            }
            else -> null
        }

    private fun moveSelectedWithinList(toFront: Boolean): Boolean {
        val type = selectedLayerType ?: return false
        val id = selectedLayerId ?: return false
        pushUndoSnapshot()
        return when (type) {
            PHOTO_LAYER_TEXT -> moveInList(textLayers, id, toFront) { it.id }
            PHOTO_LAYER_SHAPE -> moveInList(shapeLayers, id, toFront) { it.id }
            PHOTO_LAYER_IMAGE -> moveInList(imageLayers, id, toFront) { it.id }
            PHOTO_LAYER_BRUSH -> moveInList(brushStrokes, id, toFront) { it.id }
            else -> false
        }
    }

    private fun moveSelectedOneStep(forward: Boolean): Boolean {
        val type = selectedLayerType ?: return false
        val id = selectedLayerId ?: return false
        pushUndoSnapshot()
        return when (type) {
            PHOTO_LAYER_TEXT -> moveInListOneStep(textLayers, id, forward) { it.id }
            PHOTO_LAYER_SHAPE -> moveInListOneStep(shapeLayers, id, forward) { it.id }
            PHOTO_LAYER_IMAGE -> moveInListOneStep(imageLayers, id, forward) { it.id }
            PHOTO_LAYER_BRUSH -> moveInListOneStep(brushStrokes, id, forward) { it.id }
            else -> false
        }
    }

    private fun <T> moveInList(
        list: MutableList<T>,
        id: Int,
        toFront: Boolean,
        idOf: (T) -> Int,
    ): Boolean {
        val index = list.indexOfFirst { idOf(it) == id }
        if (index < 0) return false
        if (toFront) {
            if (index == list.lastIndex) return false
            val item = list.removeAt(index)
            list += item
            return true
        }
        if (index == 0) return false
        val item = list.removeAt(index)
        list.add(0, item)
        return true
    }

    private fun <T> moveInListOneStep(
        list: MutableList<T>,
        id: Int,
        forward: Boolean,
        idOf: (T) -> Int,
    ): Boolean {
        val index = list.indexOfFirst { idOf(it) == id }
        if (index < 0) return false
        val nextIndex = if (forward) index + 1 else index - 1
        if (nextIndex !in list.indices) return false
        val item = list.removeAt(index)
        list.add(nextIndex, item)
        return true
    }

    private fun selectedLayerBounds(): RectF? =
        when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let { layer ->
                val metrics = textMetrics(layer)
                val padding = layer.backgroundPadding + 18f
                val width = (metrics.first + padding * 2f) * layer.scale
                val height = (metrics.second + padding * 2f) * layer.scale
                RectF(layer.x - width / 2f, layer.y - height / 2f, layer.x + width / 2f, layer.y + height / 2f)
            }
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let { layer ->
                val width = layer.width * layer.scale
                val height = layer.height * layer.scale
                RectF(layer.x - width / 2f, layer.y - height / 2f, layer.x + width / 2f, layer.y + height / 2f)
            }
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let { layer ->
                val width = layer.width * layer.scale
                val height = layer.height * layer.scale
                RectF(layer.x - width / 2f, layer.y - height / 2f, layer.x + width / 2f, layer.y + height / 2f)
            }
            PHOTO_LAYER_BRUSH -> selectedLayerId?.let { id ->
                brushStrokes.firstOrNull { it.id == id }?.points?.takeIf { it.isNotEmpty() }?.let { points ->
                    RectF(
                        points.minOf { it.x },
                        points.minOf { it.y },
                        points.maxOf { it.x },
                        points.maxOf { it.y },
                    )
                }
            }
            else -> null
        }

    private fun translateSelectedLayer(dx: Float, dy: Float) {
        when (selectedLayerType) {
            PHOTO_LAYER_TEXT -> selectedTextLayer()?.let {
                if (!it.locked) {
                    it.x += dx
                    it.y += dy
                }
            }
            PHOTO_LAYER_SHAPE -> selectedShapeLayer()?.let {
                if (!it.locked) {
                    it.x += dx
                    it.y += dy
                }
            }
            PHOTO_LAYER_IMAGE -> selectedImageLayer()?.let {
                if (!it.locked) {
                    it.x += dx
                    it.y += dy
                }
            }
            PHOTO_LAYER_BRUSH -> selectedLayerId?.let { id ->
                brushStrokes.firstOrNull { it.id == id }?.let { stroke ->
                    stroke.points = stroke.points.map { point -> PhotoPoint(point.x + dx, point.y + dy) }.toMutableList()
                }
            }
        }
    }

    private fun findTopLayerAt(x: Float, y: Float): Pair<Int, Int>? {
        for (index in textLayers.indices.reversed()) {
            val layer = textLayers[index]
            if (layer.visible && !layer.locked && textLayerHitRect(layer).contains(x, y)) {
                return PHOTO_LAYER_TEXT to layer.id
            }
        }
        for (index in shapeLayers.indices.reversed()) {
            val layer = shapeLayers[index]
            if (layer.visible && !layer.locked && shapeLayerHitRect(layer).contains(x, y)) {
                return PHOTO_LAYER_SHAPE to layer.id
            }
        }
        for (index in imageLayers.indices.reversed()) {
            val layer = imageLayers[index]
            if (layer.visible && !layer.locked && imageLayerHitRect(layer).contains(x, y)) {
                return PHOTO_LAYER_IMAGE to layer.id
            }
        }
        return null
    }

    private fun baseImageHitContains(x: Float, y: Float): Boolean {
        val bitmap = baseImage ?: return false
        updateBaseImageDest(bitmap)
        hitRect.set(imageDest)
        hitRect.inset(-24f, -24f)
        return hitRect.contains(x, y)
    }

    private fun textLayerHitRect(layer: PhotoTextLayer): RectF {
        val metrics = textMetrics(layer)
        val padding = layer.backgroundPadding + 18f
        hitRect.set(
            layer.x - metrics.first * layer.scale / 2f - padding,
            layer.y - metrics.second * layer.scale / 2f - padding,
            layer.x + metrics.first * layer.scale / 2f + padding,
            layer.y + metrics.second * layer.scale / 2f + padding,
        )
        return hitRect
    }

    private fun shapeLayerHitRect(layer: PhotoShapeLayer): RectF {
        val width = layer.width * layer.scale
        val height = layer.height * layer.scale
        hitRect.set(
            layer.x - width / 2f - 28f,
            layer.y - height / 2f - 28f,
            layer.x + width / 2f + 28f,
            layer.y + height / 2f + 28f,
        )
        return hitRect
    }

    private fun imageLayerHitRect(layer: PhotoImageLayer): RectF {
        val width = layer.width * layer.scale
        val height = layer.height * layer.scale
        val margin = max(32f, RESIZE_HANDLE_RADIUS / layer.scale.coerceAtLeast(0.25f))
        hitRect.set(
            layer.x - width / 2f - margin,
            layer.y - height / 2f - margin,
            layer.x + width / 2f + margin,
            layer.y + height / 2f + margin,
        )
        return hitRect
    }

    private fun imageLayerResizeHandleAt(layer: PhotoImageLayer, x: Float, y: Float): Int {
        val scale = layer.scale.coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        val local = imageLayerLocalPoint(layer, x, y)
        val halfWidth = layer.width / 2f
        val halfHeight = layer.height / 2f
        val handle = RESIZE_HANDLE_RADIUS / scale
        val nearLeft = kotlin.math.abs(local.first + halfWidth) <= handle
        val nearRight = kotlin.math.abs(local.first - halfWidth) <= handle
        val nearTop = kotlin.math.abs(local.second + halfHeight) <= handle
        val nearBottom = kotlin.math.abs(local.second - halfHeight) <= handle
        val withinX = local.first in (-halfWidth - handle)..(halfWidth + handle)
        val withinY = local.second in (-halfHeight - handle)..(halfHeight + handle)
        return when {
            nearLeft && nearTop -> IMAGE_HANDLE_TOP_LEFT
            nearRight && nearTop -> IMAGE_HANDLE_TOP_RIGHT
            nearRight && nearBottom -> IMAGE_HANDLE_BOTTOM_RIGHT
            nearLeft && nearBottom -> IMAGE_HANDLE_BOTTOM_LEFT
            nearTop && withinX -> IMAGE_HANDLE_TOP
            nearRight && withinY -> IMAGE_HANDLE_RIGHT
            nearBottom && withinX -> IMAGE_HANDLE_BOTTOM
            nearLeft && withinY -> IMAGE_HANDLE_LEFT
            else -> IMAGE_HANDLE_NONE
        }
    }

    private fun imageLayerLocalPoint(layer: PhotoImageLayer, x: Float, y: Float): Pair<Float, Float> {
        val scale = layer.scale.coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        val dx = x - layer.x
        val dy = y - layer.y
        val radians = Math.toRadians((-layer.rotationDeg).toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        val localX = (dx * cosValue - dy * sinValue) / scale
        val localY = (dx * sinValue + dy * cosValue) / scale
        return (if (layer.flipX) -localX else localX) to (if (layer.flipY) -localY else localY)
    }

    private fun startLayerLocalPoint(x: Float, y: Float): Pair<Float, Float> {
        val scale = layerStartScale.coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        val dx = x - layerStartX
        val dy = y - layerStartY
        val radians = Math.toRadians((-layerStartRotation).toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        return ((dx * cosValue - dy * sinValue) / scale) to ((dx * sinValue + dy * cosValue) / scale)
    }

    private fun startLayerWorldPoint(localX: Float, localY: Float): Pair<Float, Float> {
        val scale = layerStartScale.coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
        val radians = Math.toRadians(layerStartRotation.toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        val scaledX = localX * scale
        val scaledY = localY * scale
        return (layerStartX + scaledX * cosValue - scaledY * sinValue) to
            (layerStartY + scaledX * sinValue + scaledY * cosValue)
    }

    private fun drawDesign(
        canvas: Canvas,
        showSelection: Boolean,
        transparentOutput: Boolean,
    ) {
        if (transparentOutput) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } else {
            backgroundPaint.shader = null
            backgroundPaint.style = Paint.Style.FILL
            backgroundPaint.color = canvasBackgroundColor
            canvas.drawRect(canvasRect, backgroundPaint)
        }
        canvas.save()
        canvas.clipRect(canvasRect)
        if (gridVisible && showSelection) drawGrid(canvas)
        drawBaseImage(canvas, showSelection && selectedBackgroundImage())
        imageLayers.forEach { if (it.visible) drawImageLayer(canvas, it, showSelection && selectedLayerType == PHOTO_LAYER_IMAGE && selectedLayerId == it.id) }
        shapeLayers.forEach { if (it.visible) drawShapeLayer(canvas, it, showSelection && selectedLayerType == PHOTO_LAYER_SHAPE && selectedLayerId == it.id) }
        drawBrushStrokes(canvas)
        textLayers.forEach { if (it.visible) drawTextLayer(canvas, it, showSelection && selectedLayerType == PHOTO_LAYER_TEXT && selectedLayerId == it.id) }
        canvas.restore()
        if (showSelection) drawSnapGuides(canvas)
    }

    private fun drawMagicSweepPreview(canvas: Canvas) {
        val beforeBitmap = magicSweepBeforeBitmap
        if (beforeBitmap == null || beforeBitmap.isRecycled) {
            drawDesign(canvas, showSelection = true, transparentOutput = false)
            return
        }
        val splitX = canvasRect.left + canvasRect.width() * magicSweepRevealFraction.coerceIn(0f, 1f)
        canvas.save()
        canvas.clipRect(canvasRect.left, canvasRect.top, splitX, canvasRect.bottom)
        paint.alpha = 255
        paint.colorFilter = null
        paint.maskFilter = null
        canvas.drawBitmap(beforeBitmap, null, canvasRect, paint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(splitX, canvasRect.top, canvasRect.right, canvasRect.bottom)
        drawDesign(canvas, showSelection = false, transparentOutput = false)
        canvas.restore()

        val lineWidth = 3f
        strokePaint.reset()
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = lineWidth + 8f
        strokePaint.color = Color.argb(88, 255, 48, 64)
        canvas.drawLine(splitX, canvasRect.top, splitX, canvasRect.bottom, strokePaint)
        strokePaint.strokeWidth = lineWidth
        strokePaint.color = Color.rgb(255, 48, 64)
        canvas.drawLine(splitX, canvasRect.top, splitX, canvasRect.bottom, strokePaint)
    }

    private fun buildMagicBeforeFilter(): ColorMatrixColorFilter {
        magicBeforeMatrix.reset()
        magicBeforeMatrix.setSaturation(0.72f)
        magicBeforeAdjustMatrix.set(
            floatArrayOf(
                0.92f, 0f, 0f, 0f, -8f,
                0f, 0.92f, 0f, 0f, -8f,
                0f, 0f, 0.92f, 0f, -8f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        magicBeforeMatrix.postConcat(magicBeforeAdjustMatrix)
        return ColorMatrixColorFilter(magicBeforeMatrix)
    }

    private fun drawMagicSweepLabels(canvas: Canvas, splitX: Float) {
        val textSize = (canvasRect.width() * 0.034f).coerceIn(28f, 42f)
        val padX = 16f
        val padY = 8f
        val top = canvasRect.top + 26f
        textPaint.color = Color.WHITE
        textPaint.alpha = 255
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = textSize
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textAlign = Paint.Align.LEFT
        val metrics = textPaint.fontMetrics
        fun drawLabel(label: String, x: Float, maxRight: Float) {
            val labelWidth = textPaint.measureText(label)
            val rect = RectF(x, top, x + labelWidth + padX * 2f, top + textSize + padY * 2f)
            if (rect.right > maxRight) return
            backgroundPaint.shader = null
            backgroundPaint.xfermode = null
            backgroundPaint.style = Paint.Style.FILL
            backgroundPaint.color = Color.argb(162, 0, 0, 0)
            canvas.drawRoundRect(rect, 14f, 14f, backgroundPaint)
            canvas.drawText(label, rect.left + padX, rect.top + padY - metrics.ascent, textPaint)
        }
        if (splitX - canvasRect.left > 170f) {
            drawLabel("BEFORE", canvasRect.left + 24f, splitX - 10f)
        }
        if (canvasRect.right - splitX > 150f) {
            val afterWidth = textPaint.measureText("AFTER") + padX * 2f
            val afterX = max(splitX + 24f, canvasRect.right - afterWidth - 24f)
            drawLabel("AFTER", afterX, canvasRect.right - 10f)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 90f
        var value = 0f
        while (value <= max(canvasRect.width(), canvasRect.height())) {
            if (value <= canvasRect.width()) {
                canvas.drawLine(value, 0f, value, canvasRect.height(), gridPaint)
            }
            if (value <= canvasRect.height()) {
                canvas.drawLine(0f, value, canvasRect.width(), value, gridPaint)
            }
            value += step
        }
    }

    private fun drawBaseImage(canvas: Canvas, selected: Boolean) {
        val sourceBitmap = baseImage ?: return
        val bitmap = resolveProcessedImageBitmap(sourceBitmap)
        updateBaseImageDest(bitmap)
        canvas.save()
        clipImageMask(canvas)
        canvas.rotate(backgroundRotationDeg, imageDest.centerX(), imageDest.centerY())
        paint.alpha = 255
        paint.colorFilter = buildImageFilter()
        paint.maskFilter = if (imageBlur > 0f) BlurMaskFilter(imageBlur, BlurMaskFilter.Blur.NORMAL) else null
        canvas.drawBitmap(bitmap, null, imageDest, paint)
        paint.colorFilter = null
        paint.maskFilter = null
        drawImageTint(canvas)
        drawImageVignette(canvas)
        canvas.restore()
        if (selected) {
            selectionRect.set(imageDest)
            selectionRect.inset(-10f, -10f)
            canvas.drawRoundRect(selectionRect, 18f, 18f, selectionPaint)
        }
    }

    private fun resolveProcessedImageBitmap(source: Bitmap): Bitmap {
        if (
            imageCutoutMode == IMAGE_CUTOUT_OFF &&
            imageCurveShadows == 0f &&
            imageCurveMidtones == 0f &&
            imageCurveHighlights == 0f
        ) {
            return source
        }
        val key = buildProcessedImageCacheKey(source)
        val cached = processedImageBitmap
        if (cached != null && !cached.isRecycled && processedImageCacheKey == key) {
            return cached
        }
        val processed = buildProcessedImageBitmap(source)
        processedImageBitmap = processed
        processedImageCacheKey = key
        return processed
    }

    private fun buildProcessedImageCacheKey(source: Bitmap): String =
        listOf(
            System.identityHashCode(source),
            source.width,
            source.height,
            imageCutoutMode,
            imageCutoutThreshold.toInt(),
            imageCutoutFeather.toInt(),
            imageCurveShadows.toInt(),
            imageCurveMidtones.toInt(),
            imageCurveHighlights.toInt(),
        ).joinToString(":")

    private fun buildProcessedImageBitmap(source: Bitmap): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = output.width
        val height = output.height
        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)
        val backgroundColor = if (imageCutoutMode == IMAGE_CUTOUT_AUTO) sampleBorderColor(pixels, width, height) else Color.TRANSPARENT
        val toneCurve = buildToneCurve()
        for (index in pixels.indices) {
            var pixel = pixels[index]
            if (imageCutoutMode != IMAGE_CUTOUT_OFF) {
                pixel = applyCutoutAlpha(pixel, backgroundColor)
            }
            if (toneCurve != null) {
                pixel = applyToneCurve(pixel, toneCurve)
            }
            pixels[index] = pixel
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun sampleBorderColor(pixels: IntArray, width: Int, height: Int): Int {
        val step = max(1, min(width, height) / 48)
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var count = 0L
        fun addPixel(x: Int, y: Int) {
            val pixel = pixels[y * width + x]
            redTotal += Color.red(pixel)
            greenTotal += Color.green(pixel)
            blueTotal += Color.blue(pixel)
            count += 1L
        }
        var x = 0
        while (x < width) {
            addPixel(x, 0)
            addPixel(x, height - 1)
            x += step
        }
        var y = 0
        while (y < height) {
            addPixel(0, y)
            addPixel(width - 1, y)
            y += step
        }
        if (count == 0L) return Color.TRANSPARENT
        return Color.rgb((redTotal / count).toInt(), (greenTotal / count).toInt(), (blueTotal / count).toInt())
    }

    private fun applyCutoutAlpha(pixel: Int, backgroundColor: Int): Int {
        val sourceAlpha = Color.alpha(pixel)
        if (sourceAlpha == 0) return pixel
        val backgroundScore = when (imageCutoutMode) {
            IMAGE_CUTOUT_GREEN -> greenScreenScore(pixel)
            IMAGE_CUTOUT_LIGHT -> lightBackgroundScore(pixel)
            else -> colorDistance(pixel, backgroundColor)
        }
        val keepAmount = smoothStep(
            imageCutoutThreshold,
            imageCutoutThreshold + imageCutoutFeather.coerceAtLeast(1f),
            backgroundScore,
        )
        val nextAlpha = (sourceAlpha * keepAmount).toInt().coerceIn(0, 255)
        return Color.argb(nextAlpha, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    }

    private fun greenScreenScore(pixel: Int): Float {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        val dominance = green - max(red, blue)
        return (140 - dominance + kotlin.math.abs(green - 180) * 0.25f).coerceAtLeast(0f)
    }

    private fun lightBackgroundScore(pixel: Int): Float {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        val brightnessGap = 255 - max(red, max(green, blue))
        val colorSpread = max(red, max(green, blue)) - min(red, min(green, blue))
        return brightnessGap + colorSpread * 0.7f
    }

    private fun colorDistance(pixel: Int, color: Int): Float {
        val red = Color.red(pixel) - Color.red(color)
        val green = Color.green(pixel) - Color.green(color)
        val blue = Color.blue(pixel) - Color.blue(color)
        return kotlin.math.sqrt((red * red + green * green + blue * blue).toFloat())
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val span = (edge1 - edge0).coerceAtLeast(1f)
        val t = ((value - edge0) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun buildToneCurve(): IntArray? {
        if (imageCurveShadows == 0f && imageCurveMidtones == 0f && imageCurveHighlights == 0f) return null
        return IntArray(256) { value ->
            val x = value / 255f
            val shadows = imageCurveShadows * (1f - x) * (1f - x)
            val mids = imageCurveMidtones * (1f - kotlin.math.abs(2f * x - 1f))
            val highlights = imageCurveHighlights * x * x
            (value + (shadows + mids + highlights) * 0.72f).toInt().coerceIn(0, 255)
        }
    }

    private fun applyToneCurve(pixel: Int, toneCurve: IntArray): Int =
        Color.argb(
            Color.alpha(pixel),
            toneCurve[Color.red(pixel)],
            toneCurve[Color.green(pixel)],
            toneCurve[Color.blue(pixel)],
        )

    private fun invalidateProcessedImageCache() {
        processedImageBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        processedImageBitmap = null
        processedImageCacheKey = ""
    }

    private fun updateBaseImageDest(bitmap: Bitmap) {
        val scale =
            if (imageMaskMode == IMAGE_MASK_WIDE) {
                max(canvasRect.width() / bitmap.width, canvasRect.height() / bitmap.height)
            } else {
                min(canvasRect.width() / bitmap.width, canvasRect.height() / bitmap.height)
            }
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        imageDest.set(
            canvasRect.centerX() - width / 2f,
            canvasRect.centerY() - height / 2f,
            canvasRect.centerX() + width / 2f,
            canvasRect.centerY() + height / 2f,
        )
    }

    private fun clipImageMask(canvas: Canvas) {
        if (imageMaskMode == IMAGE_MASK_NONE || imageMaskMode == IMAGE_MASK_WIDE) return
        imageMaskPath.reset()
        if (imageMaskMode == IMAGE_MASK_CIRCLE) {
            val radius = min(imageDest.width(), imageDest.height()) / 2f
            imageMaskPath.addCircle(imageDest.centerX(), imageDest.centerY(), radius, Path.Direction.CW)
        } else {
            imageMaskPath.addRoundRect(imageDest, 64f, 64f, Path.Direction.CW)
        }
        canvas.clipPath(imageMaskPath)
    }

    private fun buildImageFilter(): ColorMatrixColorFilter? {
        if (
            imageBrightness == 0f &&
            imageContrast == 1f &&
            imageSaturation == 1f &&
            imageTemperature == 0f &&
            imageFilterIntensity == 1f
        ) {
            return null
        }
        val intensity = imageFilterIntensity
        imageColorMatrix.reset()
        imageColorMatrix.setSaturation(1f + (imageSaturation - 1f) * intensity)
        val contrast = 1f + (imageContrast - 1f) * intensity
        val translate = imageBrightness * intensity
        val temp = imageTemperature * intensity
        imageAdjustMatrix.set(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate + temp * 0.45f,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate - temp * 0.45f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        imageColorMatrix.postConcat(imageAdjustMatrix)
        return ColorMatrixColorFilter(imageColorMatrix)
    }

    private fun buildImageLayerFilter(layer: PhotoImageLayer): ColorMatrixColorFilter? {
        if (
            layer.brightness == 0f &&
            layer.contrast == 1f &&
            layer.saturation == 1f &&
            layer.temperature == 0f
        ) {
            return null
        }
        imageLayerColorMatrix.reset()
        imageLayerColorMatrix.setSaturation(layer.saturation.coerceIn(0f, 2.5f))
        val contrast = layer.contrast.coerceIn(0.35f, 2.8f)
        val translate = (-128f * contrast + 128f) + layer.brightness.coerceIn(-100f, 100f)
        val temperature = layer.temperature.coerceIn(-100f, 100f)
        imageLayerAdjustMatrix.set(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate + temperature * 0.45f,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate - temperature * 0.45f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        imageLayerColorMatrix.postConcat(imageLayerAdjustMatrix)
        return ColorMatrixColorFilter(imageLayerColorMatrix)
    }

    private fun drawImageTint(canvas: Canvas) {
        if (imageTintStrength <= 0f || imageTintColor == Color.TRANSPARENT) return
        backgroundPaint.style = Paint.Style.FILL
        backgroundPaint.shader = null
        backgroundPaint.color = Color.argb(
            (imageTintStrength * 255f).toInt().coerceIn(0, 255),
            Color.red(imageTintColor),
            Color.green(imageTintColor),
            Color.blue(imageTintColor),
        )
        canvas.drawRect(imageDest, backgroundPaint)
    }

    private fun drawImageVignette(canvas: Canvas) {
        if (imageVignette <= 0f) return
        backgroundPaint.shader = RadialGradient(
            imageDest.centerX(),
            imageDest.centerY(),
            max(imageDest.width(), imageDest.height()) * 0.64f,
            intArrayOf(Color.TRANSPARENT, Color.argb((190f * imageVignette).toInt(), 0, 0, 0)),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(imageDest, backgroundPaint)
        backgroundPaint.shader = null
    }

    private fun drawImageLayer(canvas: Canvas, layer: PhotoImageLayer, selected: Boolean) {
        val bitmap = layer.bitmap ?: return
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotationDeg)
        canvas.scale(layer.scale * if (layer.flipX) -1f else 1f, layer.scale * if (layer.flipY) -1f else 1f)
        imageDest.set(-layer.width / 2f, -layer.height / 2f, layer.width / 2f, layer.height / 2f)
        val radius = layer.cornerRadius.coerceAtLeast(0f)
        if (layer.shadowEnabled) {
            backgroundPaint.reset()
            backgroundPaint.isAntiAlias = true
            backgroundPaint.style = Paint.Style.FILL
            backgroundPaint.color = Color.argb(1, 0, 0, 0)
            backgroundPaint.setShadowLayer(
                layer.shadowBlur.coerceIn(0f, 96f),
                layer.shadowOffsetX,
                layer.shadowOffsetY,
                layer.shadowColor,
            )
            canvas.drawRoundRect(imageDest, radius, radius, backgroundPaint)
            backgroundPaint.clearShadowLayer()
        }
        paint.alpha = layer.opacity.coerceIn(0, 255)
        paint.colorFilter = buildImageLayerFilter(layer)
        paint.maskFilter = if (layer.blur > 0f) BlurMaskFilter(layer.blur.coerceIn(0f, 48f), BlurMaskFilter.Blur.NORMAL) else null
        paint.xfermode = xfermodeForBlendMode(layer.blendMode)
        if (radius > 0f) {
            imageMaskPath.reset()
            imageMaskPath.addRoundRect(imageDest, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(imageMaskPath)
            canvas.drawBitmap(bitmap, null, imageDest, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, null, imageDest, paint)
        }
        paint.xfermode = null
        paint.colorFilter = null
        paint.maskFilter = null
        if (layer.borderWidth > 0f && Color.alpha(layer.borderColor) > 0) {
            strokePaint.reset()
            strokePaint.isAntiAlias = true
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = layer.borderWidth.coerceIn(0f, 64f)
            strokePaint.color = layer.borderColor
            canvas.drawRoundRect(imageDest, radius, radius, strokePaint)
        }
        paint.alpha = 255
        if (selected) {
            selectionRect.set(imageDest)
            selectionRect.inset(-10f, -10f)
            canvas.drawRoundRect(selectionRect, 18f, 18f, selectionPaint)
            drawImageResizeHandles(canvas, layer.scale)
        }
        canvas.restore()
    }

    private fun drawImageResizeHandles(canvas: Canvas, layerScale: Float) {
        val radius = 13f / layerScale.coerceAtLeast(0.25f)
        val points = floatArrayOf(
            imageDest.left, imageDest.top,
            imageDest.centerX(), imageDest.top,
            imageDest.right, imageDest.top,
            imageDest.right, imageDest.centerY(),
            imageDest.right, imageDest.bottom,
            imageDest.centerX(), imageDest.bottom,
            imageDest.left, imageDest.bottom,
            imageDest.left, imageDest.centerY(),
        )
        handlePaint.color = Color.rgb(10, 132, 255)
        handlePaint.style = Paint.Style.FILL
        var index = 0
        while (index < points.size) {
            canvas.drawCircle(points[index], points[index + 1], radius, handlePaint)
            index += 2
        }
        handlePaint.color = Color.WHITE
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 2.5f / layerScale.coerceAtLeast(0.25f)
        index = 0
        while (index < points.size) {
            canvas.drawCircle(points[index], points[index + 1], radius, handlePaint)
            index += 2
        }
        handlePaint.style = Paint.Style.FILL
    }

    private fun drawShapeLayer(canvas: Canvas, layer: PhotoShapeLayer, selected: Boolean) {
        shapeRect.set(-layer.width / 2f, -layer.height / 2f, layer.width / 2f, layer.height / 2f)
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotationDeg)
        canvas.scale(layer.scale * if (layer.flipX) -1f else 1f, layer.scale * if (layer.flipY) -1f else 1f)

        shapePaint.reset()
        shapePaint.isAntiAlias = true
        shapePaint.alpha = layer.opacity.coerceIn(0, 255)
        shapePaint.xfermode = xfermodeForBlendMode(layer.blendMode)
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = layer.fillColor
        shapePaint.shader =
            if (layer.gradientEnabled) {
                LinearGradient(
                    shapeRect.left,
                    shapeRect.top,
                    shapeRect.right,
                    shapeRect.bottom,
                    layer.gradientStartColor,
                    layer.gradientEndColor,
                    Shader.TileMode.CLAMP,
                )
            } else {
                null
            }
        if (layer.shadowEnabled || layer.glowEnabled) {
            val glowColor = if (layer.glowEnabled) layer.strokeColor else Color.argb(180, 0, 0, 0)
            shapePaint.setShadowLayer(if (layer.glowEnabled) 22f else 12f, 4f, 8f, glowColor)
        }
        drawShapeFill(canvas, layer, shapePaint)
        shapePaint.shader = null
        shapePaint.clearShadowLayer()
        if (layer.strokeWidth > 0f || layer.shapeType == SHAPE_LINE) {
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeCap = Paint.Cap.ROUND
            shapePaint.strokeJoin = Paint.Join.ROUND
            shapePaint.strokeWidth = if (layer.shapeType == SHAPE_LINE) layer.strokeWidth.coerceAtLeast(8f) else layer.strokeWidth
            shapePaint.color = layer.strokeColor
            drawShapeFill(canvas, layer, shapePaint)
        }
        if (selected) {
            selectionRect.set(shapeRect)
            canvas.drawRoundRect(selectionRect, 18f, 18f, selectionPaint)
        }
        shapePaint.xfermode = null
        canvas.restore()
    }

    private fun drawShapeFill(canvas: Canvas, layer: PhotoShapeLayer, drawPaint: Paint) {
        shapePath.reset()
        when (layer.shapeType) {
            SHAPE_CIRCLE -> canvas.drawOval(shapeRect, drawPaint)
            SHAPE_TRIANGLE -> {
                shapePath.moveTo(0f, shapeRect.top)
                shapePath.lineTo(shapeRect.right, shapeRect.bottom)
                shapePath.lineTo(shapeRect.left, shapeRect.bottom)
                shapePath.close()
                canvas.drawPath(shapePath, drawPaint)
            }
            SHAPE_LINE -> canvas.drawLine(shapeRect.left, 0f, shapeRect.right, 0f, drawPaint)
            SHAPE_STAR -> {
                buildStarPath(shapePath, min(layer.width, layer.height) / 2f, min(layer.width, layer.height) / 4f)
                canvas.drawPath(shapePath, drawPaint)
            }
            SHAPE_ARROW -> {
                shapePath.moveTo(shapeRect.left, -layer.height * 0.12f)
                shapePath.lineTo(layer.width * 0.16f, -layer.height * 0.12f)
                shapePath.lineTo(layer.width * 0.16f, -layer.height * 0.28f)
                shapePath.lineTo(shapeRect.right, 0f)
                shapePath.lineTo(layer.width * 0.16f, layer.height * 0.28f)
                shapePath.lineTo(layer.width * 0.16f, layer.height * 0.12f)
                shapePath.lineTo(shapeRect.left, layer.height * 0.12f)
                shapePath.close()
                canvas.drawPath(shapePath, drawPaint)
            }
            else -> canvas.drawRoundRect(shapeRect, layer.cornerRadius, layer.cornerRadius, drawPaint)
        }
    }

    private fun buildStarPath(path: Path, outerRadius: Float, innerRadius: Float) {
        for (index in 0 until 10) {
            val angle = Math.toRadians((-90 + index * 36).toDouble())
            val radius = if (index % 2 == 0) outerRadius else innerRadius
            val x = (cos(angle) * radius).toFloat()
            val y = (sin(angle) * radius).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun drawBrushStrokes(canvas: Canvas) {
        brushStrokes.forEach { stroke ->
            if (!stroke.visible || stroke.points.size < 2) return@forEach
            brushPath.reset()
            val first = stroke.points.first()
            brushPath.moveTo(first.x, first.y)
            stroke.points.drop(1).forEach { point -> brushPath.lineTo(point.x, point.y) }
            strokePaint.reset()
            strokePaint.isAntiAlias = true
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeJoin = Paint.Join.ROUND
            strokePaint.strokeWidth = stroke.size
            strokePaint.alpha = stroke.opacity.coerceIn(0, 255)
            strokePaint.color = if (stroke.eraser) canvasBackgroundColor else stroke.color
            if (stroke.brushType == BRUSH_MARKER) {
                strokePaint.alpha = (strokePaint.alpha * 0.55f).toInt().coerceIn(0, 255)
                strokePaint.strokeWidth = stroke.size * 1.7f
            }
            if (stroke.brushType == BRUSH_NEON && !stroke.eraser) {
                strokePaint.setShadowLayer(stroke.size * 1.15f, 0f, 0f, stroke.color)
            }
            canvas.drawPath(brushPath, strokePaint)
        }
    }

    private fun drawTextLayer(canvas: Canvas, layer: PhotoTextLayer, selected: Boolean) {
        val metrics = textMetrics(layer)
        val width = metrics.first
        val height = metrics.second
        val padding = layer.backgroundPadding

        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotationDeg)
        canvas.scale(layer.scale * if (layer.flipX) -1f else 1f, layer.scale * if (layer.flipY) -1f else 1f)
        if (layer.curveAmount != 0f) {
            canvas.skew(layer.curveAmount / 280f, 0f)
        }

        val left = -width / 2f - padding
        val top = -height / 2f - padding
        val right = width / 2f + padding
        val bottom = height / 2f + padding
        if (layer.backgroundColor != Color.TRANSPARENT) {
            backgroundPaint.style = Paint.Style.FILL
            backgroundPaint.shader = null
            backgroundPaint.color = withAlpha(layer.backgroundColor, layer.opacity)
            backgroundPaint.xfermode = xfermodeForBlendMode(layer.blendMode)
            canvas.drawRoundRect(left, top, right, bottom, layer.backgroundCornerRadius, layer.backgroundCornerRadius, backgroundPaint)
            backgroundPaint.xfermode = null
        }
        if (selected) {
            canvas.drawRoundRect(left, top, right, bottom, 14f, 14f, selectionPaint)
        }
        drawTextContent(canvas, layer, width, height)
        canvas.restore()
    }

    private fun drawTextContent(canvas: Canvas, layer: PhotoTextLayer, blockWidth: Float, blockHeight: Float) {
        configureTextPaint(layer)
        textPaint.xfermode = xfermodeForBlendMode(layer.blendMode)
        val lines = layer.text.lines().ifEmpty { listOf("Text") }
        val lineStep = layer.fontSize * layer.lineHeight
        var baseline = -blockHeight / 2f + layer.fontSize

        if (layer.depth > 0f) {
            textPaint.shader = null
            textPaint.style = Paint.Style.FILL
            textPaint.color = Color.argb((130 * layer.opacity / 255f).toInt(), 0, 0, 0)
            val steps = layer.depth.toInt().coerceIn(1, 16)
            for (step in steps downTo 1) {
                drawTextLines(canvas, lines, blockWidth, baseline + step * 2.2f, lineStep, step * 2.2f)
            }
        }

        if (layer.strokeColor != Color.TRANSPARENT && layer.strokeWidth > 0f) {
            textPaint.shader = null
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = layer.strokeWidth
            textPaint.color = withAlpha(layer.strokeColor, layer.opacity)
            drawTextLines(canvas, lines, blockWidth, baseline, lineStep, 0f)
        }

        textPaint.style = Paint.Style.FILL
        textPaint.strokeWidth = 0f
        if (layer.gradientEnabled) {
            textPaint.shader = LinearGradient(
                -blockWidth / 2f,
                -blockHeight / 2f,
                blockWidth / 2f,
                blockHeight / 2f,
                layer.gradientStartColor,
                layer.gradientEndColor,
                Shader.TileMode.CLAMP,
            )
        } else {
            textPaint.shader = null
            textPaint.color = withAlpha(layer.textColor, layer.opacity)
        }
        drawTextLines(canvas, lines, blockWidth, baseline, lineStep, 0f)
        textPaint.shader = null
        textPaint.xfermode = null
    }

    private fun drawTextLines(
        canvas: Canvas,
        lines: List<String>,
        blockWidth: Float,
        firstBaseline: Float,
        lineStep: Float,
        xOffset: Float,
    ) {
        var baseline = firstBaseline
        lines.forEach { line ->
            val text = line.ifEmpty { " " }
            val x = when (textPaint.textAlign) {
                Paint.Align.LEFT -> -blockWidth / 2f + xOffset
                Paint.Align.RIGHT -> blockWidth / 2f + xOffset
                else -> xOffset
            }
            canvas.drawText(text, x, baseline, textPaint)
            baseline += lineStep
        }
    }

    private fun configureTextPaint(layer: PhotoTextLayer) {
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.isSubpixelText = true
        textPaint.textSize = layer.fontSize.coerceIn(18f, 260f)
        textPaint.letterSpacing = layer.letterSpacing.coerceIn(-8f, 24f) / 100f
        textPaint.isUnderlineText = layer.underline
        textPaint.textAlign = when (layer.alignment) {
            TEXT_ALIGN_LEFT -> Paint.Align.LEFT
            TEXT_ALIGN_RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        textPaint.typeface = Typeface.create(
            when (layer.fontFamily) {
                "serif" -> Typeface.SERIF
                "mono" -> Typeface.MONOSPACE
                "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                "display" -> Typeface.create("casual", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            },
            when {
                layer.bold && layer.italic -> Typeface.BOLD_ITALIC
                layer.bold -> Typeface.BOLD
                layer.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
        if (layer.shadowEnabled) {
            textPaint.setShadowLayer(
                layer.shadowBlur,
                layer.shadowOffsetX,
                layer.shadowOffsetY,
                Color.argb(layer.shadowOpacity.coerceIn(0, 255), 0, 0, 0),
            )
        } else {
            textPaint.clearShadowLayer()
        }
    }

    private fun textMetrics(layer: PhotoTextLayer): Pair<Float, Float> {
        configureTextPaint(layer)
        val lines = layer.text.lines().ifEmpty { listOf("Text") }
        var width = 1f
        lines.forEach { line -> width = max(width, textPaint.measureText(line.ifEmpty { " " })) }
        textPaint.getTextBounds("Hg", 0, 2, textBounds)
        val height = max(1f, layer.fontSize * layer.lineHeight * lines.size)
        return width to height
    }

    private fun drawSnapGuides(canvas: Canvas) {
        if (snapCenterX) {
            canvas.drawLine(canvasRect.centerX(), 0f, canvasRect.centerX(), canvasRect.bottom, guidePaint)
        }
        if (snapCenterY) {
            canvas.drawLine(0f, canvasRect.centerY(), canvasRect.right, canvasRect.centerY(), guidePaint)
        }
    }

    private fun updateViewMatrix() {
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        val viewHeight = height.toFloat().coerceAtLeast(1f)
        val scale = min(viewWidth / canvasRect.width(), viewHeight / canvasRect.height()) * viewportZoom
        val dx = (viewWidth - canvasRect.width() * scale) / 2f + viewportPanX
        val dy = (viewHeight - canvasRect.height() * scale) / 2f + viewportPanY
        viewMatrix.reset()
        viewMatrix.postScale(scale, scale)
        viewMatrix.postTranslate(dx, dy)
        viewMatrix.invert(inverseViewMatrix)
    }

    private fun mapToCanvas(x: Float, y: Float): Pair<Float, Float> {
        val points = floatArrayOf(x, y)
        inverseViewMatrix.mapPoints(points)
        return points[0] to points[1]
    }

    private fun pointerCenterInCanvas(event: MotionEvent): Pair<Float, Float> {
        val centerX = (event.getX(0) + event.getX(1)) / 2f
        val centerY = (event.getY(0) + event.getY(1)) / 2f
        return mapToCanvas(centerX, centerY)
    }

    private fun pointerCenterInView(event: MotionEvent): Pair<Float, Float> =
        (event.getX(0) + event.getX(1)) / 2f to (event.getY(0) + event.getY(1)) / 2f

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        return hypot(
            (event.getX(1) - event.getX(0)).toDouble(),
            (event.getY(1) - event.getY(0)).toDouble(),
        ).toFloat()
    }

    private fun pointerAngle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return Math.toDegrees(
            atan2(
                (event.getY(1) - event.getY(0)).toDouble(),
                (event.getX(1) - event.getX(0)).toDouble(),
            ),
        ).toFloat()
    }

    private fun normalizeRotation(value: Float): Float {
        var result = value
        while (result > 180f) result -= 360f
        while (result < -180f) result += 360f
        return result
    }

    private fun shapeLabel(layer: PhotoShapeLayer): String =
        when (layer.shapeType) {
            SHAPE_CIRCLE -> "Circle ${layer.id}"
            SHAPE_TRIANGLE -> "Triangle ${layer.id}"
            SHAPE_LINE -> "Line ${layer.id}"
            SHAPE_STAR -> "Star ${layer.id}"
            SHAPE_ARROW -> "Arrow ${layer.id}"
            else -> "Rectangle ${layer.id}"
        }

    private fun scaleLayersForCanvasResize(nextWidth: Int, nextHeight: Int) {
        val oldWidth = canvasRect.width().takeIf { it > 0f } ?: DEFAULT_CANVAS_WIDTH.toFloat()
        val oldHeight = canvasRect.height().takeIf { it > 0f } ?: DEFAULT_CANVAS_HEIGHT.toFloat()
        val sx = nextWidth / oldWidth
        val sy = nextHeight / oldHeight
        textLayers.forEach { layer ->
            layer.x *= sx
            layer.y *= sy
        }
        shapeLayers.forEach { layer ->
            layer.x *= sx
            layer.y *= sy
            layer.width *= sx
            layer.height *= sy
        }
        imageLayers.forEach { layer ->
            layer.x *= sx
            layer.y *= sy
            layer.width *= sx
            layer.height *= sy
        }
        brushStrokes.forEach { stroke ->
            stroke.points = stroke.points
                .map { point -> PhotoPoint(point.x * sx, point.y * sy) }
                .toMutableList()
            stroke.size *= min(sx, sy).coerceAtLeast(0.5f)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun xfermodeForBlendMode(blendMode: Int): PorterDuffXfermode? =
        when (blendMode) {
            BLEND_MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            BLEND_OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            BLEND_SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            else -> null
        }

    private data class PhotoCanvasSnapshot(
        val canvasBackgroundColor: Int,
        val backgroundRotationDeg: Float,
        val transparentBackground: Boolean,
        val imageBrightness: Float,
        val imageContrast: Float,
        val imageSaturation: Float,
        val imageTemperature: Float,
        val imageBlur: Float,
        val imageVignette: Float,
        val imageFilterIntensity: Float,
        val imageTintColor: Int,
        val imageTintStrength: Float,
        val imageMaskMode: Int,
        val imageCutoutMode: Int,
        val imageCutoutThreshold: Float,
        val imageCutoutFeather: Float,
        val imageCurveShadows: Float,
        val imageCurveMidtones: Float,
        val imageCurveHighlights: Float,
        val canvasOutputWidth: Int,
        val canvasOutputHeight: Int,
        val canvasSizeLabel: String,
        val textLayers: List<PhotoTextLayer>,
        val imageLayers: List<PhotoImageLayer>,
        val shapeLayers: List<PhotoShapeLayer>,
        val brushStrokes: List<PhotoBrushStroke>,
        val selectedLayerType: Int?,
        val selectedLayerId: Int?,
    )
}
