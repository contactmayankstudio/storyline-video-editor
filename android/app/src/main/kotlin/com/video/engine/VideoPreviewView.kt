package com.video.engine

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.video.engine.transition.Transition
import com.video.engine.transition.TransitionType
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Custom SurfaceView for real-time GPU-accelerated video preview.
 * 
 * This view provides OpenGL ES 3.0 rendering of video frames with GPU effects
 * (brightness, contrast, saturation, LUT color grading) at 60fps.
 * 
 * Features:
 * - Hardware acceleration (GPU-only, no CPU)
 * - Real-time frame preview with native JNI bridge
 * 
 * Usage:
 *   val previewView = VideoPreviewView(context)
 *   container.addView(previewView, layoutParams)
 *   
 *   // Native code handles rendering automatically
 *   // onSurfaceCreated → EGL initialization
 *   // onPause → pause rendering
 *   // onResume → resume rendering
 *   // onDestroy → cleanup
 * 
 * Thread model:
 * - Surface callbacks on Android's main thread
 * - Rendering on dedicated native thread (JNI)
 * - Thread-safe via mutex in native code
 * 
 * Lifecycle:
 * 1. Constructor → View created
 * 2. onSurfaceCreated → Native EGL + OpenGL initialization
 * 3. onSurfaceChanged → Surface dimensions known
 * 4. Rendering loop → Native preview controller renders frames
 * 5. onSurfaceDestroyed → Native cleanup + EGL teardown
 */
class VideoPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    // TAG and native library loader moved to single companion at end of file.
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var pendingMoveX = -1f
    private var pendingMoveY = -1f
    private var pendingScale = -1f
    private var pendingRotation = -1000f
    private var isMoveUpdatePending = false
    private val moveUpdateRunnable = Runnable {
        isMoveUpdatePending = false
        if (activeTextOverlayId > 0 && pendingMoveX >= 0f) {
            try {
                val s = if (pendingScale >= 0f) pendingScale else overlayScales[activeTextOverlayId] ?: 1.0f
                val r = if (pendingRotation > -1000f) pendingRotation else overlayRotations[activeTextOverlayId] ?: 0.0f
                nativeUpdateTextOverlayTransform(activeTextOverlayId, pendingMoveX, pendingMoveY, s, r)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeUpdateTextOverlayTransform JNI not implemented")
            }
        }
    }

    /**
     * Initialize SurfaceView holder callback.
     * This must be called to enable surface lifecycle notifications.
     */
    init {
        // Get the SurfaceHolder and register this view as the callback
        holder.addCallback(this)
        // Initialize pinch detector for scaling text overlays
        scaleGestureDetector = ScaleGestureDetector(context, PinchScaleListener())
    }

    // Executor for background rendering tasks to avoid ANR
    private var renderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var pendingSeekTimeMs: Long = Long.MIN_VALUE
    private val seekDrainScheduled = AtomicBoolean(false)
    @Volatile
    private var pendingClipPreviewTransform: PendingClipPreviewTransform? = null
    private val clipPreviewTransformDrainScheduled = AtomicBoolean(false)

    private data class PendingClipPreviewTransform(
        val clipId: Int,
        val zoom: Float,
        val scaleX: Float,
        val scaleY: Float,
        val panXPx: Float,
        val panYPx: Float,
        val rotationDeg: Float,
        val mirrorX: Boolean,
    )

    // Export callback for progress/completion events
    interface ExportCallback {
        fun onExportProgress(progress: Int)
        fun onExportCompleted(success: Boolean, errorMessage: String?)
    }
    private var exportCallback: ExportCallback? = null

    // Map to cache overlay scales on the Java side to avoid jumps during pinch
    private val overlayScales = mutableMapOf<Int, Float>()
    // Cache overlay rotations to allow smooth rotation without jumps
    private val overlayRotations = mutableMapOf<Int, Float>()
    private val overlayPositions = mutableMapOf<Int, Pair<Float, Float>>()
    private var activeTextOverlayId: Int = -1
    private var pinchBaseScale: Float = 1.0f
    // Rotation tracking state
    private var isRotating = false
    private var prevFingerAngle: Float = 0.0f
    private var rotationBase: Float = 0.0f
    // Surface size for normalized coordinates
    private var surfaceW: Int = 1
    private var surfaceH: Int = 1
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var textOverlayUploadCounter: Long = 0L
    @Volatile
    private var nativeSurfaceInitialized: Boolean = false
    private var requestedBufferWidth: Int = 0
    private var requestedBufferHeight: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceReadyCallbackPending = false
    private val surfaceBindInProgress = AtomicBoolean(false)

    private fun notifySurfaceReadySoon() {
        if (surfaceReadyCallbackPending) return
        surfaceReadyCallbackPending = true
        mainHandler.postDelayed({
            surfaceReadyCallbackPending = false
            if (nativeSurfaceInitialized) {
                onSurfaceReady?.invoke()
            }
        }, 200)
    }

    private fun bindNativeSurfaceIfPossible(reason: String): Boolean {
        if (!nativeLibraryLoaded) return false
        if (!surfaceBindInProgress.compareAndSet(false, true)) return false
        try {
            val surface = holder.surface ?: return false
            if (!surface.isValid) {
                nativeSurfaceInitialized = false
                Log.d(TAG, "Native surface bind skipped ($reason): holder surface is not valid")
                return false
            }
            return try {
                var didBindSurface = false
                if (!nativeSurfaceInitialized) {
                    Log.d(TAG, "Binding native preview surface ($reason)")
                    nativeInitPreview(surface)
                    nativeSurfaceInitialized = true
                    didBindSurface = true
                }
                val targetWidth = when {
                    width > 0 -> width
                    surfaceW > 0 -> surfaceW
                    requestedBufferWidth > 0 -> requestedBufferWidth
                    else -> 0
                }
                val targetHeight = when {
                    height > 0 -> height
                    surfaceH > 0 -> surfaceH
                    requestedBufferHeight > 0 -> requestedBufferHeight
                    else -> 0
                }
                var sizeChanged = false
                if (targetWidth > 0 && targetHeight > 0) {
                    sizeChanged =
                        targetWidth != requestedBufferWidth ||
                            targetHeight != requestedBufferHeight
                    if (didBindSurface || sizeChanged) {
                        requestedBufferWidth = targetWidth
                        requestedBufferHeight = targetHeight
                        nativeSetSurfaceSize(targetWidth, targetHeight)
                    }
                }
                if (didBindSurface || sizeChanged) {
                    notifySurfaceReadySoon()
                }
                true
            } catch (e: UnsatisfiedLinkError) {
                nativeSurfaceInitialized = false
                Log.w(TAG, "Native surface bind JNI not implemented ($reason)")
                false
            }
        } finally {
            surfaceBindInProgress.set(false)
        }
    }

    private fun scheduleNativeSurfaceBind(reason: String, attemptsRemaining: Int = 15) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { scheduleNativeSurfaceBind(reason, attemptsRemaining) }
            return
        }
        if (bindNativeSurfaceIfPossible(reason)) return
        if (attemptsRemaining <= 0) return
        // First retry quickly, then back off to reduce CPU spin
        val delayMs = if (attemptsRemaining == 14) 50L else 200L
        mainHandler.postDelayed({
            scheduleNativeSurfaceBind(reason, attemptsRemaining - 1)
        }, delayMs)
    }

    /**
     * Set which text overlay id should respond to pinch gestures.
     * Caller (UI layer) should update this when the user selects an overlay.
     */
    fun setActiveTextOverlayId(id: Int) {
        activeTextOverlayId = id
    }

    /**
     * SurfaceHolder.Callback: Called when the surface is first created.
     * 
     * This is called from Android's main thread when the SurfaceView's
     * underlying surface is created. At this point, we can access the
     * ANativeWindow and initialize EGL + OpenGL ES.
     * 
     * Thread: Main thread
     * 
     * @param holder The SurfaceHolder for this SurfaceView
     */
    var onSurfaceReady: (() -> Unit)? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated - nativeLibraryLoaded=$nativeLibraryLoaded")
        if (!bindNativeSurfaceIfPossible("surfaceCreated")) {
            scheduleNativeSurfaceBind("surfaceCreated_retry")
        }
    }

    /**
     * SurfaceHolder.Callback: Called when the surface dimensions change.
     * 
     * This is called after surfaceCreated when the surface size is determined.
     * It's also called if the surface size changes (e.g., orientation change).
     * 
     * Thread: Main thread
     * 
     * @param holder The SurfaceHolder for this SurfaceView
     * @param format The format (not used)
     * @param width The width of the surface
     * @param height The height of the surface
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width == surfaceW && height == surfaceH && nativeSurfaceInitialized) return
        Log.d(TAG, "surfaceChanged: $width x $height")
        surfaceW = width
        surfaceH = height
        requestedBufferWidth = width
        requestedBufferHeight = height
        if (!nativeLibraryLoaded) {
            Log.w(TAG, "Skipping nativeSetSurfaceSize because native library is not loaded")
            return
        }
        if (!bindNativeSurfaceIfPossible("surfaceChanged")) {
            scheduleNativeSurfaceBind("surfaceChanged_retry")
            return
        }
        try {
            nativeSetSurfaceSize(width, height)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeSetSurfaceSize JNI not implemented")
        }
    }

    /**
     * SurfaceHolder.Callback: Called when the surface is destroyed.
     * 
     * This is called when the SurfaceView is being removed or the surface
     * is being destroyed (e.g., app backgrounding, orientation change).
     * 
     * At this point, we must clean up EGL and OpenGL resources.
     * Any attempt to render after this point will fail.
     * 
     * Thread: Main thread
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        nativeSurfaceInitialized = false
        if (!nativeLibraryLoaded) {
            return
        }
        try {
            // Clean up native resources (EGL, OpenGL)
            nativeReleasePreview()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeReleasePreview JNI not implemented")
        }
        // Don't shutdown renderExecutor — it will be reused on next surfaceCreated
        // renderExecutor.shutdown()
    }

    /**
     * Pause rendering.
     * 
     * Called from Activity.onPause(). Stops the native rendering loop
     * and releases the EGL context to allow other apps to use GPU.
     * 
     * Thread: Main thread
     */
    fun onPause() {
        Log.d(TAG, "onPause")
        try {
            nativePauseRendering()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativePauseRendering JNI not implemented (expected if native_preview disabled)")
        }
    }

    /**
     * Resume rendering.
     * 
     * Called from Activity.onResume(). Resumes the native rendering loop
     * and re-acquires the EGL context.
     * 
     * Thread: Main thread
     */
    fun onResume() {
        Log.d(TAG, "onResume")
        // Ensure surface is bound — surfaceCreated may have been missed
        if (!ensureNativeSurfaceBinding()) {
            scheduleNativeSurfaceBind("onResume_retry")
        }
        try {
            nativeResumeRendering()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeResumeRendering JNI not implemented (expected if native_preview disabled)")
        }
    }

    /**
     * Seek to a timeline position and render one preview frame.
     * 
     * Used for scrubbing (SeekBar drag) or direct seeking.
     * Non-blocking: returns immediately after scheduling render.
     * 
     * Thread: Can be called from any thread
     * 
     * @param timelineMs Timeline position in milliseconds
     */
    fun seekToTime(timelineMs: Long) {
        pendingSeekTimeMs = timelineMs
        scheduleSeekDrain()
    }

    private fun scheduleSeekDrain() {
        if (!seekDrainScheduled.compareAndSet(false, true)) return
        renderExecutor.execute {
            try {
                while (true) {
                    val nextSeekMs = pendingSeekTimeMs
                    pendingSeekTimeMs = Long.MIN_VALUE
                    if (nextSeekMs == Long.MIN_VALUE) {
                        break
                    }
                    nativeSeekPreview(nextSeekMs)
                }
            } finally {
                seekDrainScheduled.set(false)
                if (pendingSeekTimeMs != Long.MIN_VALUE) {
                    scheduleSeekDrain()
                }
            }
        }
    }

    /**
     * Rebind native preview to the current Surface when command-path actions
     * happen before/after lifecycle transitions.
     */
    fun ensureNativeSurfaceBinding(): Boolean {
        return bindNativeSurfaceIfPossible("ensureNativeSurfaceBinding")
    }

    /**
     * Force a fresh native surface initialization on next bind attempt.
     * Useful when native command path reports missing renderer components.
     */
    fun forceNativeSurfaceRebind(): Boolean {
        nativeSurfaceInitialized = false
        val rebound = bindNativeSurfaceIfPossible("forceNativeSurfaceRebind")
        if (!rebound) {
            scheduleNativeSurfaceBind("forceNativeSurfaceRebind_retry")
        }
        return rebound
    }

    fun syncNativeSurfaceSizeToView(): Boolean {
        if (!nativeLibraryLoaded) return false
        if (width <= 0 || height <= 0) return false
        if (requestedBufferWidth == width && requestedBufferHeight == height) return true
        return try {
            requestedBufferWidth = width
            requestedBufferHeight = height
            nativeSetSurfaceSize(width, height)
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "syncNativeSurfaceSizeToView JNI not implemented")
            false
        }
    }

    /**
     * Add a text overlay to the native renderer.
     * The native side should create a texture from the supplied text and
     * composite it after the video frame.
     */
    fun addTextOverlay(id: Int, text: String, x: Float, y: Float, scale: Float, rotation: Float, color: Int, fontSize: Float, startTimeMs: Int, endTimeMs: Int): Long {
        val nativeId = try {
            nativeAddTextOverlay(id, text, x, y, scale, rotation, color, fontSize, startTimeMs, endTimeMs)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeAddTextOverlay JNI not implemented")
            -1L
        }
        if (nativeId > 0) {
            val nid = nativeId.toInt()
            overlayScales[nid] = scale
            overlayRotations[nid] = rotation
            overlayPositions[nid] = Pair(x, y)
        }
        return nativeId
    }

    /**
     * Update an existing text overlay's transform/properties.
     */
    fun updateTextOverlay(id: Int, x: Float, y: Float, scale: Float, rotation: Float, color: Int, fontSize: Float, startTimeMs: Int, endTimeMs: Int) {
        nativeUpdateTextOverlay(id, x, y, scale, rotation, color, fontSize, startTimeMs, endTimeMs)
        overlayScales[id] = scale
        overlayRotations[id] = rotation
        overlayPositions[id] = Pair(x, y)
    }

    fun updateTextOverlayTransform(id: Int, x: Float, y: Float, scale: Float, rotation: Float) {
        nativeUpdateTextOverlayTransform(id, x, y, scale, rotation)
        overlayScales[id] = scale
        overlayRotations[id] = rotation
        overlayPositions[id] = Pair(x, y)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false

        // Rotation handling: only when two fingers are present
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // Begin rotation gesture
                    prevFingerAngle = angleBetweenFingers(event)
                    rotationBase = overlayRotations[activeTextOverlayId] ?: 0.0f
                    isRotating = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRotating && event.pointerCount >= 2 && activeTextOverlayId > 0) {
                    val currentAngle = angleBetweenFingers(event)
                    var angleDelta = currentAngle - prevFingerAngle
                    // Normalize delta to -180..180 to avoid large jumps
                    angleDelta = ((angleDelta + 180f) % 360f + 360f) % 360f - 180f

                    var newRotation = rotationBase + angleDelta
                    // Normalize final rotation to -180..180
                    newRotation = ((newRotation + 180f) % 360f + 360f) % 360f - 180f

                    overlayRotations[activeTextOverlayId] = newRotation
                    
                    pendingRotation = newRotation
                    // We need x, y to push the transform
                    pendingMoveX = overlayPositions[activeTextOverlayId]?.first ?: 0.5f
                    pendingMoveY = overlayPositions[activeTextOverlayId]?.second ?: 0.5f

                    if (!isMoveUpdatePending) {
                        isMoveUpdatePending = true
                        android.view.Choreographer.getInstance().postFrameCallback { moveUpdateRunnable.run() }
                    }
                } else if (event.pointerCount == 1 && activeTextOverlayId > 0 && !isRotating) {
                    // Single-finger drag -> move active overlay (normalized coords)
                    val nx = (event.x / surfaceW).coerceIn(0.0f, 1.0f)
                    val ny = (event.y / surfaceH).coerceIn(0.0f, 1.0f)
                    
                    overlayPositions[activeTextOverlayId] = Pair(nx, ny)
                    pendingMoveX = nx
                    pendingMoveY = ny
                    
                    if (!isMoveUpdatePending) {
                        isMoveUpdatePending = true
                        android.view.Choreographer.getInstance().postFrameCallback { moveUpdateRunnable.run() }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // End rotation when pointers reduced
                if (event.pointerCount <= 2) {
                    isRotating = false
                }
            }
        }

        // Feed events to pinch detector. Two-finger pinch still handled by detector.
        scaleGestureDetector.onTouchEvent(event)

        // Consume touch for overlay gestures
        return true
    }

    private fun angleBetweenFingers(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0.0f
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val angleRad = kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        return Math.toDegrees(angleRad).toFloat()
    }

    private inner class PinchScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Use cached base scale to avoid jumps.
            pinchBaseScale = overlayScales[activeTextOverlayId] ?: 1.0f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Only proceed if an overlay is active and two fingers are used
            if (activeTextOverlayId <= 0) return false

            val scaleFactor = detector.scaleFactor
            var newScale = pinchBaseScale * scaleFactor
            // Clamp to allowed range
            newScale = newScale.coerceIn(0.5f, 3.0f)

            // Update local cache and notify native layer
            overlayScales[activeTextOverlayId] = newScale
            try {
                nativeUpdateTextScale(activeTextOverlayId, newScale)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeUpdateTextScale JNI not implemented")
            }

            return true
        }
    }

    /**
     * Remove a text overlay from native renderer by id.
     */
    fun removeTextOverlay(id: Int) {
        nativeRemoveTextOverlay(id)
    }

    /**
     * Upload pixel data for a text overlay. Pixels should be ARGB_8888 ints (row-major).
     */
    fun setTextOverlayBitmap(id: Int, pixels: IntArray, width: Int, height: Int) {
        val importedWithHardwareBuffer = trySetTextOverlayHardwareBuffer(id, pixels, width, height)
        if (!importedWithHardwareBuffer) {
            nativeSetTextOverlayBitmap(id, pixels, width, height)
        }
        textOverlayUploadCounter += 1L
        maybeLogHardwareBufferTelemetry()
    }

    private fun trySetTextOverlayHardwareBuffer(
        id: Int,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return runCatching {
            val swBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            swBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val hwBitmap = swBitmap.copy(Bitmap.Config.HARDWARE, false) ?: run {
                swBitmap.recycle()
                return@runCatching false
            }
            swBitmap.recycle()
            val hardwareBuffer: HardwareBuffer = hwBitmap.hardwareBuffer
            val imported = try {
                nativeSetTextOverlayHardwareBuffer(id, hardwareBuffer, width, height)
            } finally {
                hardwareBuffer.close()
            }
            hwBitmap.recycle()
            imported
        }.getOrDefault(false)
    }

    private fun maybeLogHardwareBufferTelemetry() {
        if (textOverlayUploadCounter % 20L != 0L) {
            return
        }
        runCatching { nativeGetHardwareBufferTelemetry() }
            .onSuccess { telemetry ->
                Log.i(TAG, "HardwareBuffer telemetry: $telemetry")
            }
            .onFailure { error ->
                Log.w(TAG, "HardwareBuffer telemetry unavailable: ${error.message}")
            }
    }

    fun getHardwareBufferTelemetryJson(): String {
        return runCatching { nativeGetHardwareBufferTelemetry() }
            .getOrDefault("{\"available\":false}")
    }

    fun resetHardwareBufferTelemetry() {
        runCatching { nativeResetHardwareBufferTelemetry() }
            .onFailure { error ->
                Log.w(TAG, "nativeResetHardwareBufferTelemetry JNI not implemented: ${error.message}")
            }
    }

    /**
     * Start playback from the given timeline position.
     * 
     * Begins continuous frame rendering at the specified time.
     * Frames are rendered at the native video frame rate (or 60fps, whichever is lower).
     * 
     * Thread: Can be called from any thread
     * 
     * @param timelineMs Timeline position in milliseconds to start playback
     */
    fun startPlayback(timelineMs: Long = 0) {
        renderExecutor.execute { nativeStartPlayback(timelineMs) }
    }

    /**
     * Stop playback.
     * 
     * Stops the continuous rendering loop. The preview will remain
     * frozen on the last rendered frame.
     * 
     * Thread: Can be called from any thread
     */
    fun stopPlayback() {
        renderExecutor.execute { nativeStopPlayback() }
    }

    /**
     * Enable/disable external audio-master clock mode for A/V sync.
     * When enabled, native preview follows latest audio PTS updates.
     */
    private var lastAudioMasterClockEnabled: Boolean? = null
    fun setAudioMasterClockEnabled(enabled: Boolean) {
        if (lastAudioMasterClockEnabled == enabled) return
        lastAudioMasterClockEnabled = enabled
        try {
            nativeSetAudioMasterClockEnabled(enabled)
        } catch (e: UnsatisfiedLinkError) {
            val bridged = runCatching {
                NativeBridge.setAudioMasterClockEnabled(enabled)
            }.getOrDefault(false)
            if (!bridged) {
                Log.w(TAG, "nativeSetAudioMasterClockEnabled JNI not implemented")
            }
        }
    }

    /**
     * Push latest audio presentation timestamp (microseconds) to native preview.
     * Call from audio playback loop for tight sync.
     */
    fun updateAudioClockUs(ptsUs: Long) {
        try {
            nativeUpdateAudioClockUs(ptsUs)
        } catch (e: UnsatisfiedLinkError) {
            val bridged = runCatching {
                NativeBridge.updateAudioClockUs(ptsUs)
            }.getOrDefault(false)
            if (!bridged) {
                Log.w(TAG, "nativeUpdateAudioClockUs JNI not implemented")
            }
        }
    }

    /**
     * Start an export job in native code. This method launches the native export
     * asynchronously and returns immediately. Progress should be polled via
     * `nativeGetExportProgress()` or reported via native->Java callbacks (not implemented yet).
     *
     * @param outputPath Absolute output file path
     * @param width Output width in pixels
     * @param height Output height in pixels
     * @param fps Target frames-per-second
     * @param callback Optional callback for progress/completion events
     */
    fun startExport(outputPath: String, width: Int, height: Int, fps: Int,
                    audioPaths: Array<String> = emptyArray(),
                    audioStartMs: LongArray = LongArray(0),
                    audioDurMs: LongArray = LongArray(0),
                    audioVols: FloatArray = FloatArray(0),
                    audioFadeInMs: IntArray = IntArray(0),
                    audioFadeOutMs: IntArray = IntArray(0),
                    audioKeyframeCsvs: Array<String> = emptyArray(),
                    callback: ExportCallback? = null) {
        exportCallback = callback
        Thread {
            nativeSetExportCallback(callback)
            nativeSetExportAudioClips(
                audioPaths,
                audioStartMs,
                audioDurMs,
                audioVols,
                audioFadeInMs,
                audioFadeOutMs,
                audioKeyframeCsvs,
            )
            nativeStartExport(outputPath, width, height, fps)
        }.start()
    }

    /**
     * Cancel an in-progress export.
     */
    fun cancelExport() {
        nativeCancelExport()
    }

    /**
     * Poll native export progress (0-100). Returns -1 if native not implemented or no export.
     */
    fun getExportProgress(): Int {
        return try {
            nativeGetExportProgress()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetExportProgress JNI not implemented")
            -1
        }
    }

    /**
     * Set per-clip GPU effect uniforms. Implemented in native code to update
     * shader uniforms for brightness/contrast/saturation for the given clip id.
     * These should be applied on the GPU without restarting playback.
     */
    fun setClipEffects(clipId: Int, brightness: Float, contrast: Float, saturation: Float) {
        nativeSetClipEffects(clipId, brightness, contrast, saturation)
    }

    /**
     * Load a video file for preview.
     * 
     * Must be called before any rendering. The file path should point
     * to a valid video file (MP4, MOV, etc.) that FFmpeg can decode.
     * 
     * Thread: Can be called from any thread (but recommended before rendering starts)
     * 
     * @param videoPath Absolute media path or content URI.
     * @return true if video loaded successfully, false otherwise
     */
    fun loadVideo(videoPath: String): Boolean {
        Log.d(TAG, "loadVideo: $videoPath")
        return nativeLoadVideo(videoPath)
    }

    fun setClipPreviewTransform(
        clipId: Int,
        zoom: Float,
        scaleX: Float = 1.0f,
        scaleY: Float = 1.0f,
        panXPx: Float,
        panYPx: Float,
        rotationDeg: Float,
        mirrorX: Boolean,
        immediate: Boolean = false,
    ) {
        val clampedZoom = zoom.coerceIn(0.15f, 8.0f)
        val clampedScaleX = scaleX.coerceIn(0.15f, 8.0f)
        val clampedScaleY = scaleY.coerceIn(0.15f, 8.0f)
        val clampedRotation = rotationDeg.coerceIn(-180.0f, 180.0f)
        if (!immediate) {
            pendingClipPreviewTransform =
                PendingClipPreviewTransform(
                    clipId = clipId,
                    zoom = clampedZoom,
                    scaleX = clampedScaleX,
                    scaleY = clampedScaleY,
                    panXPx = panXPx,
                    panYPx = panYPx,
                    rotationDeg = clampedRotation,
                    mirrorX = mirrorX,
                )
            scheduleClipPreviewTransformDrain()
            return
        }
        try {
            nativeSetClipPreviewTransform(
                clipId,
                clampedZoom,
                clampedScaleX,
                clampedScaleY,
                panXPx,
                panYPx,
                clampedRotation,
                mirrorX,
                immediate,
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeSetClipPreviewTransform JNI not implemented")
        }
    }

    private fun scheduleClipPreviewTransformDrain() {
        if (!clipPreviewTransformDrainScheduled.compareAndSet(false, true)) return
        renderExecutor.execute {
            try {
                while (true) {
                    val next = pendingClipPreviewTransform ?: break
                    pendingClipPreviewTransform = null
                    nativeSetClipPreviewTransform(
                        next.clipId,
                        next.zoom,
                        next.scaleX,
                        next.scaleY,
                        next.panXPx,
                        next.panYPx,
                        next.rotationDeg,
                        next.mirrorX,
                        false,
                    )
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeSetClipPreviewTransform JNI not implemented")
            } finally {
                clipPreviewTransformDrainScheduled.set(false)
                if (pendingClipPreviewTransform != null) {
                    scheduleClipPreviewTransformDrain()
                }
            }
        }
    }

    fun flushPendingClipPreviewTransformForExport() {
        val next = pendingClipPreviewTransform ?: return
        pendingClipPreviewTransform = null
        try {
            nativeSetClipPreviewTransform(
                next.clipId,
                next.zoom,
                next.scaleX,
                next.scaleY,
                next.panXPx,
                next.panYPx,
                next.rotationDeg,
                next.mirrorX,
                true,
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeSetClipPreviewTransform JNI not implemented")
        }
    }

    fun clearClipPreviewTransform(clipId: Int, immediate: Boolean = true) {
        if (!immediate) {
            renderExecutor.execute {
                try {
                    nativeClearClipPreviewTransform(clipId)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "nativeClearClipPreviewTransform JNI not implemented")
                }
            }
            return
        }
        try {
            nativeClearClipPreviewTransform(clipId)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeClearClipPreviewTransform JNI not implemented")
        }
    }

    fun clearClipPreviewTransforms() {
        try {
            nativeClearClipPreviewTransforms()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeClearClipPreviewTransforms JNI not implemented")
        }
    }

    fun getClipPreviewMinZoom(clipId: Int): Float {
        return try {
            nativeGetClipPreviewMinZoom(clipId)
        } catch (e: UnsatisfiedLinkError) {
            1.0f
        }
    }

    fun getClipPreviewTransform(clipId: Int): FloatArray? {
        return try {
            nativeGetClipPreviewTransform(clipId)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun computeScaleGesturePreviewTransform(
        clipId: Int,
        baseZoom: Float,
        basePanXPx: Float,
        basePanYPx: Float,
        scaleAccumulator: Float,
        focusOffsetXPx: Float,
        focusOffsetYPx: Float,
    ): FloatArray? {
        return try {
            nativeComputeScaleGesturePreviewTransform(
                clipId,
                baseZoom,
                basePanXPx,
                basePanYPx,
                scaleAccumulator,
                focusOffsetXPx,
                focusOffsetYPx,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun computeNormalizedPreviewTransform(
        clipId: Int,
        zoom: Float,
        scaleX: Float = 1.0f,
        scaleY: Float = 1.0f,
        panXPx: Float,
        panYPx: Float,
        rotationDeg: Float,
        mirrorX: Boolean,
    ): FloatArray? {
        return try {
            nativeComputeNormalizedPreviewTransform(
                clipId,
                zoom,
                scaleX,
                scaleY,
                panXPx,
                panYPx,
                rotationDeg,
                mirrorX,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun computeDoubleTapPreviewTransform(
        clipId: Int,
        currentZoom: Float,
        currentPanXPx: Float,
        currentPanYPx: Float,
        tapOffsetXPx: Float,
        tapOffsetYPx: Float,
    ): FloatArray? {
        return try {
            nativeComputeDoubleTapPreviewTransform(
                clipId,
                currentZoom,
                currentPanXPx,
                currentPanYPx,
                tapOffsetXPx,
                tapOffsetYPx,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun computeDragPanPreviewTransform(
        clipId: Int,
        currentZoom: Float,
        currentPanXPx: Float,
        currentPanYPx: Float,
        deltaXPx: Float,
        deltaYPx: Float,
    ): FloatArray? {
        return try {
            nativeComputeDragPanPreviewTransform(
                clipId,
                currentZoom,
                currentPanXPx,
                currentPanYPx,
                deltaXPx,
                deltaYPx,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun computeCornerHandlePreviewTransform(
        clipId: Int,
        baseZoom: Float,
        basePanXPx: Float,
        basePanYPx: Float,
        deltaXPx: Float,
        deltaYPx: Float,
        cornerSignX: Float,
        cornerSignY: Float,
    ): FloatArray? {
        return try {
            nativeComputeCornerHandlePreviewTransform(
                clipId,
                baseZoom,
                basePanXPx,
                basePanYPx,
                deltaXPx,
                deltaYPx,
                cornerSignX,
                cornerSignY,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun beginClipPreviewTransformGesture(
        clipId: Int,
        zoom: Float,
        scaleX: Float = 1.0f,
        scaleY: Float = 1.0f,
        panXPx: Float,
        panYPx: Float,
        rotationDeg: Float,
        mirrorX: Boolean,
        centroidOffsetXPx: Float,
        centroidOffsetYPx: Float,
        spanPx: Float,
        angleDeg: Float,
        mode: Int,
        edgeSignX: Float,
        edgeSignY: Float,
        allowRotation: Boolean,
    ): FloatArray? {
        return try {
            nativeBeginClipPreviewTransformGesture(
                clipId,
                zoom,
                scaleX,
                scaleY,
                panXPx,
                panYPx,
                rotationDeg,
                mirrorX,
                centroidOffsetXPx,
                centroidOffsetYPx,
                spanPx,
                angleDeg,
                mode,
                edgeSignX,
                edgeSignY,
                allowRotation,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun updateClipPreviewTransformGesture(
        centroidOffsetXPx: Float,
        centroidOffsetYPx: Float,
        spanPx: Float,
        angleDeg: Float,
    ): FloatArray? {
        return try {
            nativeUpdateClipPreviewTransformGesture(
                centroidOffsetXPx,
                centroidOffsetYPx,
                spanPx,
                angleDeg,
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun endClipPreviewTransformGesture() {
        try {
            nativeEndClipPreviewTransformGesture()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeEndClipPreviewTransformGesture JNI not implemented")
        }
    }

    // ============ JNI NATIVE METHODS ============
    // These are implemented in native_preview.cpp

    /**
     * Initialize preview system with native window.
     * Called from surfaceCreated.
     */
    private external fun nativeInitPreview(surface: Any)

    /**
     * Set surface dimensions.
     * Called from surfaceChanged.
     */
    private external fun nativeSetSurfaceSize(width: Int, height: Int)

    /**
     * Release preview system.
     * Called from surfaceDestroyed.
     */
    private external fun nativeReleasePreview()

    /**
     * Pause rendering (called from onPause).
     */
    private external fun nativePauseRendering()

    /**
     * Resume rendering (called from onResume).
     */
    private external fun nativeResumeRendering()

    /**
     * Seek to timeline position and render one frame.
     */
    private external fun nativeSeekPreview(timelineMs: Long)

    private external fun nativeSetClipPreviewTransform(
        clipId: Int,
        zoom: Float,
        scaleX: Float,
        scaleY: Float,
        panXPx: Float,
        panYPx: Float,
        rotationDeg: Float,
        mirrorX: Boolean,
        immediate: Boolean,
    )
    private external fun nativeClearClipPreviewTransform(clipId: Int)
    private external fun nativeClearClipPreviewTransforms()
    private external fun nativeGetClipPreviewMinZoom(clipId: Int): Float
    private external fun nativeGetClipPreviewTransform(clipId: Int): FloatArray?
    private external fun nativeComputeScaleGesturePreviewTransform(
        clipId: Int,
        baseZoom: Float,
        basePanXPx: Float,
        basePanYPx: Float,
        scaleAccumulator: Float,
        focusOffsetXPx: Float,
        focusOffsetYPx: Float,
    ): FloatArray?
    private external fun nativeComputeNormalizedPreviewTransform(
        clipId: Int,
        zoom: Float,
        scaleX: Float,
        scaleY: Float,
        panXPx: Float,
        panYPx: Float,
        rotationDeg: Float,
        mirrorX: Boolean,
    ): FloatArray?
    private external fun nativeComputeDoubleTapPreviewTransform(
        clipId: Int,
        currentZoom: Float,
        currentPanXPx: Float,
        currentPanYPx: Float,
        tapOffsetXPx: Float,
        tapOffsetYPx: Float,
    ): FloatArray?
    private external fun nativeComputeDragPanPreviewTransform(
        clipId: Int,
        currentZoom: Float,
        currentPanXPx: Float,
        currentPanYPx: Float,
        deltaXPx: Float,
        deltaYPx: Float,
    ): FloatArray?
    private external fun nativeComputeCornerHandlePreviewTransform(
        clipId: Int,
        baseZoom: Float,
        basePanXPx: Float,
        basePanYPx: Float,
        deltaXPx: Float,
        deltaYPx: Float,
        cornerSignX: Float,
        cornerSignY: Float,
    ): FloatArray?
    private external fun nativeBeginClipPreviewTransformGesture(
        clipId: Int,
        zoom: Float,
        scaleX: Float,
        scaleY: Float,
        panXPx: Float,
        panYPx: Float,
        rotationDeg: Float,
        mirrorX: Boolean,
        centroidOffsetXPx: Float,
        centroidOffsetYPx: Float,
        spanPx: Float,
        angleDeg: Float,
        mode: Int,
        edgeSignX: Float,
        edgeSignY: Float,
        allowRotation: Boolean,
    ): FloatArray?
    private external fun nativeUpdateClipPreviewTransformGesture(
        centroidOffsetXPx: Float,
        centroidOffsetYPx: Float,
        spanPx: Float,
        angleDeg: Float,
    ): FloatArray?
    private external fun nativeEndClipPreviewTransformGesture()

    /**
     * Native: Add text overlay (create texture from text bitmap on native side).
     */
    private external fun nativeAddTextOverlay(id: Int, text: String, x: Float, y: Float, scale: Float, rotation: Float, color: Int, fontSize: Float, startTimeMs: Int, endTimeMs: Int): Long

    /**
     * Native: Update text overlay properties.
     */
    private external fun nativeUpdateTextOverlay(id: Int, x: Float, y: Float, scale: Float, rotation: Float, color: Int, fontSize: Float, startTimeMs: Int, endTimeMs: Int)
    private external fun nativeUpdateTextOverlayTransform(id: Int, x: Float, y: Float, scale: Float, rotation: Float)

    /**
     * Native: Remove a text overlay by id.
     */
    private external fun nativeRemoveTextOverlay(id: Int)

    private external fun nativeSetTextOverlayBitmap(id: Int, pixels: IntArray, width: Int, height: Int)
    private external fun nativeSetTextOverlayHardwareBuffer(id: Int, hardwareBuffer: HardwareBuffer, width: Int, height: Int): Boolean
    private external fun nativeGetHardwareBufferTelemetry(): String
    private external fun nativeResetHardwareBufferTelemetry()

    /**
     * JNI: Update only the scale of a text overlay (called from pinch gestures).
     */
    private external fun nativeUpdateTextScale(clipId: Int, scale: Float)
    /**
     * JNI: Update only the rotation (degrees) of a text overlay (called from two-finger rotate gestures).
     */
    private external fun nativeUpdateTextRotation(clipId: Int, rotationDeg: Float)
    /**
     * JNI: Update overlay opacity and fade durations.
     * @param opacity 0.0..1.0
     */
    private external fun nativeUpdateTextOpacity(clipId: Int, opacity: Float, fadeInMs: Int, fadeOutMs: Int)

    /**
     * Public helper: update overlay opacity and fade durations (0..1, ms).
     */
    fun updateTextOverlayOpacity(id: Int, opacity: Float, fadeInMs: Int, fadeOutMs: Int) {
        try {
            nativeUpdateTextOpacity(id, opacity.coerceIn(0.0f, 1.0f), fadeInMs, fadeOutMs)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeUpdateTextOpacity JNI not implemented")
        }
    }

    /**
     * JNI: Set explicit z-order for a text overlay.
     */
    private external fun nativeSetTextZOrder(id: Int, z: Int)
    private external fun nativeBringTextOverlayToFront(id: Int)
    private external fun nativeSendTextOverlayToBack(id: Int)

    fun setTextZOrder(id: Int, z: Int) {
        try {
            nativeSetTextZOrder(id, z)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeSetTextZOrder JNI not implemented")
        }
    }

    fun bringTextToFront(id: Int) {
        try {
            nativeBringTextOverlayToFront(id)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeBringTextOverlayToFront JNI not implemented")
        }
    }

    fun sendTextToBack(id: Int) {
        try {
            nativeSendTextOverlayToBack(id)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeSendTextOverlayToBack JNI not implemented")
        }
    }

    // Keyframe JNI bindings
    private external fun nativeAddTextKeyframe(id: Int, timeMs: Long, posX: Float, posY: Float, scale: Float, rotation: Float, opacity: Float)
    private external fun nativeDeleteTextKeyframe(id: Int, timeMs: Long)
    private external fun nativeClearTextKeyframes(id: Int)
    private external fun nativeGetTextKeyframeTimes(id: Int): LongArray

    fun addTextKeyframe(id: Int, timeMs: Long, posX: Float, posY: Float, scale: Float, rotation: Float, opacity: Float) {
        try {
            nativeAddTextKeyframe(id, timeMs, posX, posY, scale, rotation, opacity)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeAddTextKeyframe JNI not implemented")
        }
    }

    fun deleteTextKeyframe(id: Int, timeMs: Long) {
        try {
            nativeDeleteTextKeyframe(id, timeMs)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeDeleteTextKeyframe JNI not implemented")
        }
    }

    fun clearTextKeyframes(id: Int) {
        try {
            nativeClearTextKeyframes(id)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeClearTextKeyframes JNI not implemented")
        }
    }

    fun getTextKeyframeTimes(id: Int): LongArray {
        return try {
            nativeGetTextKeyframeTimes(id)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetTextKeyframeTimes JNI not implemented")
            LongArray(0)
        }
    }

    /**
     * Load a video file for preview.
     */
    private external fun nativeLoadVideo(videoPath: String): Boolean

    /**
     * Start playback from timeline position.
     */
    private external fun nativeStartPlayback(timelineMs: Long)

    /**
     * Stop playback.
     */
    private external fun nativeStopPlayback()
    private external fun nativeSetAudioMasterClockEnabled(enabled: Boolean)
    private external fun nativeUpdateAudioClockUs(ptsUs: Long)

    /**
     * Export timeline with text overlays to video file.
     * Renders all clips + text overlays at specified resolution/fps.
     * Result: WYSIWYG (what you see is what you get) - export matches preview.
     * 
     * This is a blocking call - run from background thread.
     * 
     * @param outputPath Output file path supplied by ExportController.
     * @param width Output resolution width
     * @param height Output resolution height
     * @param fps Frames per second
     * @param bitrateMbps Bitrate in Mbps
     * @return true if export succeeded
     */
    fun exportToVideo(
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,
        videoCodec: String = "h264",
    ): Boolean {
        return nativeExportVideo(outputPath, width, height, fps, bitrateMbps, videoCodec)
    }

    /**
     * Native: Export timeline to video with text overlays.
     */
    private external fun nativeExportVideo(
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,
        videoCodec: String,
    ): Boolean

    /**
     * Native: Start export. Implement in native_preview.cpp to run FFmpeg rendering
     * and composite GPU overlays. This call should be blocking on native thread
     * (we call it from a spawned Kotlin thread above).
     */
    private external fun nativeStartExport(outputPath: String, width: Int, height: Int, fps: Int)
    private external fun nativeSetExportCallback(callback: ExportCallback?)
    external fun nativeSetExportAudioClips(
        paths: Array<String>,
        startTimesMs: LongArray,
        durationsMs: LongArray,
        volumes: FloatArray,
        fadeInMs: IntArray,
        fadeOutMs: IntArray,
        keyframeCsvs: Array<String>,
    )
    private external fun nativeCancelExport()

    /**
     * Native: Query export progress (0..100). Return -1 if not available.
     */
    private external fun nativeGetExportProgress(): Int

    private external fun nativeGetLastExportError(): String

    fun getLastExportError(): String {
        return try {
            nativeGetLastExportError()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetLastExportError JNI not implemented")
            ""
        }
    }

    /**
     * Native: Update per-clip effect parameters (shader uniforms).
     */
    private external fun nativeSetClipEffects(clipId: Int, brightness: Float, contrast: Float, saturation: Float)

    /**
     * Save current timeline as project file (JSON).
     * Serializes clips, text overlays, transitions, effects.
     * 
     * @param outputPath Output project file path.
     * @param projectName Human-readable project name
     * @return true if save successful
     */
    fun saveProject(outputPath: String, projectName: String): Boolean {
        return nativeSaveProject(outputPath, projectName)
    }

    /**
     * Load project file and apply to timeline.
     * Reconstructs clips, text overlays, transitions, effects.
     * 
     * @param filePath Input project file path.
     * @return true if load successful
     */
    fun loadProject(filePath: String): Boolean {
        return nativeLoadProject(filePath)
    }

    /**
     * Native: Save project to JSON file.
     */
    private external fun nativeSaveProject(outputPath: String, projectName: String): Boolean

    /**
     * Native: Load project from JSON file.
     */
    private external fun nativeLoadProject(filePath: String): Boolean

    /**
     * Add a clip to the timeline with track-specific parameters.
     */
    fun addClip(
        videoPath: String,
        trackType: String = "VIDEO",
        startTimeMs: Long = -1,
        trackLane: Int = 0,
        zOrder: Int = 0
    ): Int {
        return try {
            nativeAddClip(videoPath, trackType, startTimeMs, trackLane, zOrder)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeAddClip JNI not implemented")
            -1
        }
    }

    /**
     * Remove a clip from the timeline.
     */
    fun removeClip(clipId: Int) {
        try {
            nativeRemoveClip(clipId)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeRemoveClip JNI not implemented")
        }
    }

    /**
     * Get array of clip IDs in timeline order.
     */
    fun getClipIds(): IntArray {
        return try {
            nativeGetClipIds()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetClipIds JNI not implemented")
            intArrayOf()
        }
    }

    /**
     * Get clip duration in milliseconds.
     */
    fun getClipDuration(clipId: Int): Long {
        return try {
            nativeGetClipDuration(clipId)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetClipDuration JNI not implemented")
            0L
        }
    }

    /**
     * Native: Add clip to timeline with multi-track support.
     */
    private external fun nativeAddClip(
        videoPath: String,
        trackType: String,
        startTimeMs: Long,
        trackLane: Int,
        zOrder: Int
    ): Int

    /**
     * Native: Remove clip from timeline.
     */
    private external fun nativeRemoveClip(clipId: Int)

    /**
     * Native: Get array of clip IDs in timeline order.
     */
    private external fun nativeGetClipIds(): IntArray

    /**
     * Native: Get clip duration in milliseconds.
     */
    private external fun nativeGetClipDuration(clipId: Int): Long

    /**
     * Get video duration in milliseconds.
     * Call after loadVideo() to get the total duration.
     */
    fun getDuration(): Long {
        return try {
            nativeGetDuration()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetDuration JNI not implemented")
            0L
        }
    }

    private external fun nativeGetDuration(): Long

    /**
     * Get current playback timeline position in milliseconds.
     * Returns latest native playback clock value.
     */
    fun getCurrentPlaybackTime(): Long {
        return try {
            nativeGetCurrentPlaybackTimeFast()
        } catch (fastError: UnsatisfiedLinkError) {
            try {
                nativeGetCurrentPlaybackTime()
            } catch (fallbackError: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeGetCurrentPlaybackTime JNI not implemented")
                0L
            }
        }
    }

    /**
     * Fast atomic playback clock read.
     * Avoids contending on heavy preview mutex from the UI choreographer thread.
     */
    private external fun nativeGetCurrentPlaybackTimeFast(): Long

    private external fun nativeGetCurrentPlaybackTime(): Long

    /**
     * Fast playback-active state read.
     * Lets the UI stop its play loop as soon as native playback ends.
     */
    fun isPlaybackActive(): Boolean {
        return try {
            nativeIsPlaybackActiveFast()
        } catch (fastError: UnsatisfiedLinkError) {
            try {
                nativeIsPlaybackActive()
            } catch (fallbackError: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeIsPlaybackActive JNI not implemented")
                false
            }
        }
    }

    private external fun nativeIsPlaybackActiveFast(): Boolean

    private external fun nativeIsPlaybackActive(): Boolean

    /**
     * Move a clip to a new layer index (layer index is used as z-order: higher index = top).
     * Triggers render graph rebuild and preview update.
     * 
     * @param clipId Clip identifier
     * @param newLayerIndex New layer index (0 = bottom, higher = top)
     */
    fun moveLayer(clipId: Int, newLayerIndex: Int) {
        nativeMoveLayer(clipId, newLayerIndex)
    }

    /**
     * Toggle visibility of a clip/layer (show/hide).
     * Triggers render graph update.
     * 
     * @param clipId Clip identifier
     * @param enabled true = visible, false = hidden
     */
    fun toggleLayerVisibility(clipId: Int, enabled: Boolean) {
        nativeToggleLayerVisibility(clipId, enabled)
    }

    /**
     * Native: Move clip to new layer index.
     */
    private external fun nativeMoveLayer(clipId: Int, newLayerIndex: Int)

    /**
     * Native: Toggle clip visibility.
     */
    private external fun nativeToggleLayerVisibility(clipId: Int, enabled: Boolean)

    // ============ TRANSITION SUPPORT ============
    /**
     * JNI: Add a transition between two clips.
     * @param outClipId Clip being transitioned out
     * @param inClipId Clip being transitioned in
     * @param typeId Transition type (0=NONE, 1=FADE, 2=CROSS, etc.)
     * @param durationMs Transition duration in milliseconds
     * @param startTimeMs When transition starts (typically end of outgoing clip)
     */
    private external fun nativeAddTransition(
        outClipId: Int,
        inClipId: Int,
        typeId: Int,
        durationMs: Int,
        startTimeMs: Long
    ): Long

    /**
     * JNI: Update transition parameters.
     */
    private external fun nativeUpdateTransition(
        transitionId: Long,
        outClipId: Int,
        inClipId: Int,
        typeId: Int,
        durationMs: Int,
        startTimeMs: Long
    )

    /**
     * JNI: Remove a transition.
     */
    private external fun nativeRemoveTransition(transitionId: Long)

    /**
     * Public helper: Add transition via Transition object.
     */
    fun addTransition(transition: com.video.engine.transition.Transition): Long {
        return try {
            nativeAddTransition(
                transition.outgoingClipId,
                transition.incomingClipId,
                transition.type.ordinal,
                transition.durationMs,
                transition.startTimeMs
            )
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w(TAG, "nativeAddTransition JNI not implemented")
            -1L
        }
    }

    /**
     * Public helper: Update transition.
     */
    fun updateTransition(transition: com.video.engine.transition.Transition) {
        try {
            nativeUpdateTransition(
                transition.id,
                transition.outgoingClipId,
                transition.incomingClipId,
                transition.type.ordinal,
                transition.durationMs,
                transition.startTimeMs
            )
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w(TAG, "nativeUpdateTransition JNI not implemented")
        }
    }

    /**
     * Public helper: Remove transition.
     */
    fun removeTransition(transitionId: Long) {
        try {
            nativeRemoveTransition(transitionId)
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w(TAG, "nativeRemoveTransition JNI not implemented")
        }
    }

    fun getTransitions(): List<Transition> {
        val rawJson = try {
            nativeGetTransitionsJson()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeGetTransitionsJson JNI not implemented")
            return emptyList()
        }
        return runCatching {
            val items = JSONArray(rawJson)
            buildList(items.length()) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    add(
                        Transition(
                            id = item.optLong("id", -1L),
                            type = transitionTypeFromId(item.optInt("typeId", 0)),
                            durationMs = item.optInt("durationMs", 300).coerceAtLeast(1),
                            outgoingClipId = item.optInt("outgoingClipId", -1),
                            incomingClipId = item.optInt("incomingClipId", -1),
                            startTimeMs = item.optLong("startTimeMs", 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to parse transitions JSON: ${error.message}")
            emptyList()
        }
    }

    private fun transitionTypeFromId(typeId: Int): TransitionType {
        return when (typeId) {
            1 -> TransitionType.FADE
            2 -> TransitionType.CROSS
            3 -> TransitionType.WIPE
            4 -> TransitionType.SLIDE
            else -> TransitionType.NONE
        }
    }

    companion object {
        private const val TAG = "VideoPreviewView"
        private const val MAX_PREVIEW_WIDTH_LANDSCAPE = 1280
        private const val MAX_PREVIEW_HEIGHT_LANDSCAPE = 720
        private const val MAX_PREVIEW_WIDTH_PORTRAIT = 720
        private const val MAX_PREVIEW_HEIGHT_PORTRAIT = 1280
        private var nativeLibraryLoaded = false

        // Load the native library when the class is first used
        init {
            nativeLibraryLoaded = NativeBridge.isNativeRuntimeReady()
            if (nativeLibraryLoaded) {
                Log.i(TAG, "[NATIVE LOADER] Native runtime ready")
            } else {
                Log.e(TAG, "[NATIVE LOADER] Native runtime unavailable")
            }
        }

        fun isNativeLibraryLoaded(): Boolean {
            if (!nativeLibraryLoaded) {
                Log.e(TAG, "[NATIVE LOADER] Native library is NOT loaded. GPU rendering disabled.")
            }
            return nativeLibraryLoaded
        }
    }

    private external fun nativeGetTransitionsJson(): String

    private fun resolvePreviewBufferSize(viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val profile = DeviceDetector.getQualityProfile()
        val landscapeTargetWidth = max(profile.previewWidth, profile.previewHeight)
        val landscapeTargetHeight = min(profile.previewWidth, profile.previewHeight)
        val portraitTargetWidth = min(profile.previewWidth, profile.previewHeight)
        val portraitTargetHeight = max(profile.previewWidth, profile.previewHeight)
        if (viewWidth <= 0 || viewHeight <= 0) {
            return landscapeTargetWidth to landscapeTargetHeight
        }
        val isLandscape = viewWidth >= viewHeight
        val maxWidth = if (isLandscape) landscapeTargetWidth.toFloat() else portraitTargetWidth.toFloat()
        val maxHeight = if (isLandscape) landscapeTargetHeight.toFloat() else portraitTargetHeight.toFloat()
        val scale = minOf(maxWidth / viewWidth.toFloat(), maxHeight / viewHeight.toFloat(), 1f)
        val scaledWidth = maxOf((viewWidth * scale).roundToInt(), 1)
        val scaledHeight = maxOf((viewHeight * scale).roundToInt(), 1)
        return alignEven(scaledWidth) to alignEven(scaledHeight)
    }

    private fun alignEven(value: Int): Int {
        if (value <= 1) return 1
        return if (value % 2 == 0) value else value - 1
    }
}
