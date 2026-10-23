package com.video.engine.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.video.engine.R
import com.video.engine.photo.PhotoEditorActivity
import com.video.engine.status.StatusMakerActivity
import com.video.engine.status.StatusMediaHelper
import com.video.engine.stickers.StickerOverlayView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FunCameraActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FunCameraActivity"
    }

    private lateinit var textureView: TextureView
    private lateinit var overlayView: CameraFilterOverlayView
    private lateinit var lensBadge: TextView
    private lateinit var recordingTimerBadge: TextView
    private lateinit var categoryContainer: LinearLayout
    private lateinit var filterContainer: LinearLayout
    private lateinit var photoModeBtn: TextView
    private lateinit var videoModeBtn: TextView
    private lateinit var shutterButton: CameraShutterButton
    private var floatingLensPill: TextView? = null
    private var zoomPill: TextView? = null
    private var swipeToast: TextView? = null
    private var shutterFlashView: View? = null
    private var cameraLensScrollView: android.widget.HorizontalScrollView? = null

    // Snapchat Camera Pro: Live BGM Music Overlay
    private var cameraMusicBtn: TextView? = null
    private var activeBgmPath: String? = null
    private var activeBgmTitle: String? = null
    private var activeBgmIsAsset: Boolean = false
    private var bgmPlayer: MediaPlayer? = null

    // Snapchat Camera Pro: Hands-Free Countdown Timer (0 = Off, 3s, 10s)
    private var cameraTimerBtn: TextView? = null
    private var countdownOverlay: FrameLayout? = null
    private var countdownText: TextView? = null
    private var countdownSeconds: Int = 0
    private var isCountingDown: Boolean = false
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    // Snapchat Camera Pro: Warm Amber Screen Flash (Selfie Glow)
    private var warmAmberFlashView: View? = null

    // Snapchat Pro Vertical Tool Rail
    private var cameraActiveSoundBadge: TextView? = null
    private var cameraSpeedBtn: TextView? = null
    private var cameraBeautyBtn: TextView? = null
    private var cameraLockTarget: LinearLayout? = null
    private var cameraLockLabel: TextView? = null
    private var cameraLockIcon: TextView? = null
    private var videoRecordSpeed: Float = 1.0f
    private var flashModeState: Int = 0

    // Snapchat Post-Capture Screen
    private var postCaptureContainer: FrameLayout? = null
    private var postCaptureImageView: ImageView? = null
    private var postCaptureVideoView: VideoView? = null
    private var postCaptureFilterOverlay: PostCaptureFilterOverlayView? = null
    private var postCaptureDoodleCanvas: DoodleCanvasView? = null
    private var postCaptureStickersContainer: FrameLayout? = null
    private var postCaptureCaptionContainer: FrameLayout? = null
    private var postCaptureCaptionTv: TextView? = null
    private var postCaptureDoodleBar: LinearLayout? = null
    private var postCaptureFilterBadge: TextView? = null
    private var postCaptureSwipeToast: TextView? = null
    private var currentPostCaptureMediaFile: File? = null
    private var isPostCaptureVideo: Boolean = false
    private var currentPostCaptureBitmap: Bitmap? = null
    private var currentCaptionText: String = ""
    private var isDoodleModeActive: Boolean = false

    // Carousel snapping handler
    private val snapHandler = Handler(Looper.getMainLooper())
    private var snapRunnable: Runnable? = null

    private val pickCameraAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val result = StatusMediaHelper.copyAudioUriToLocal(this, uri)
            if (result != null) {
                runOnUiThread {
                    setCameraBgm(path = result.first, title = result.second, isAsset = false)
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Could not load selected audio", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var isFrontFacing = true
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // ML Kit Face Tracker
    private val faceTracker = FaceLandmarkTracker()
    private var lastTrackingTimestamp = 0L

    // Lenses & Categories state
    private var allLenses: List<LensItem> = emptyList()
    private var currentCategory = "ALL"
    private var selectedLens: LensItem? = null

    // Camera2 Zoom Characteristics & State
    private var optimalPreviewSize = Size(1280, 720)
    private var activeArraySize: Rect? = null
    private var sensorOrientation: Int = 90
    private var maxDigitalZoom: Float = 5.0f
    private var currentZoomRatio: Float = 1.0f
    private var currentZoom = 1.0f
    private var supportsControlZoomRatio: Boolean = false
    private var shutterTouchStartY = 0f
    private var previousScreenBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    // Photo / Video Mode
    private var isVideoMode = false
    private var isRecordingVideo = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingStartTime = 0L
    private var currentVideoFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            startBackgroundThread()
            if (textureView.isAvailable) {
                openCamera()
            }
        } else {
            Toast.makeText(this, "Camera permission needed for Fun Camera", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fun_camera)

        findViewById<View>(R.id.funCameraRoot)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                findViewById<View>(R.id.cameraTopBar)?.setPadding(
                    16.dpToPx(this),
                    systemBars.top + 8.dpToPx(this),
                    16.dpToPx(this),
                    8.dpToPx(this)
                )
                findViewById<View>(R.id.cameraBottomContainer)?.setPadding(
                    0,
                    10.dpToPx(this),
                    0,
                    systemBars.bottom + 16.dpToPx(this)
                )
                insets
            }
        }

        textureView = findViewById(R.id.cameraTextureView)
        overlayView = findViewById(R.id.cameraFilterOverlay)
        overlayView.cameraTextureView = textureView
        lensBadge = findViewById(R.id.cameraLensNameBadge)
        recordingTimerBadge = findViewById(R.id.cameraRecordingTimer)
        categoryContainer = findViewById(R.id.cameraCategoryContainer)
        filterContainer = findViewById(R.id.cameraFilterChipsContainer)
        cameraLensScrollView = findViewById(R.id.cameraLensScrollView)
        photoModeBtn = findViewById(R.id.cameraPhotoModeBtn)
        videoModeBtn = findViewById(R.id.cameraVideoModeBtn)
        shutterButton = findViewById(R.id.cameraShutterButton)
        floatingLensPill = findViewById(R.id.floatingLensPill)
        zoomPill = findViewById(R.id.cameraZoomPill)
        swipeToast = findViewById(R.id.cameraLensSwipeToast)
        shutterFlashView = findViewById(R.id.cameraShutterFlash)
        cameraMusicBtn = findViewById(R.id.cameraMusicBtn)
        cameraTimerBtn = findViewById(R.id.cameraTimerBtn)
        cameraActiveSoundBadge = findViewById(R.id.cameraActiveSoundBadge)
        cameraSpeedBtn = findViewById(R.id.cameraSpeedBtn)
        cameraBeautyBtn = findViewById(R.id.cameraBeautyBtn)
        cameraLockTarget = findViewById(R.id.cameraLockTarget)
        cameraLockLabel = findViewById(R.id.cameraLockLabel)
        cameraLockIcon = findViewById(R.id.cameraLockIcon)
        warmAmberFlashView = findViewById(R.id.cameraWarmAmberFlash)
        countdownOverlay = findViewById(R.id.cameraCountdownOverlay)
        countdownText = findViewById(R.id.cameraCountdownText)

        findViewById<FrameLayout>(R.id.cameraBackBtn)?.setOnClickListener { finish() }

        setupVerticalToolRail()
        setupTopBarControls()
        setupViewfinderGestures()
        setupModeSelector()
        setupShutterControl()
        setupPostCaptureScreen()

        // Load lenses (default to popular fun puppy or aviator lens so face sync is immediately active)
        allLenses = LensRepository.getLenses(this)
        selectedLens = allLenses.firstOrNull { it.id == "lens_animal_puppy" }
            ?: allLenses.firstOrNull { it.id == "lens_glasses_aviator" }
            ?: allLenses.firstOrNull { it.id != "none" }
            ?: allLenses.firstOrNull()
        selectedLens?.let {
            overlayView.currentLens = it
            updateLensBadge(it)
        }

        setupCategoryTabs()
        setupLensCarousel()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                checkPermissionsAndStart()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                val now = SystemClock.uptimeMillis()
                // Process face detection every 33ms (~30 FPS) with aspect-ratio-matched frame
                if (now - lastTrackingTimestamp > 33L) {
                    lastTrackingTimestamp = now
                    var bmp: Bitmap? = null
                    try {
                        val vw = textureView.width
                        val vh = textureView.height
                        if (vw > 0 && vh > 0) {
                            val targetW = 360
                            val targetH = (360 * vh / vw).coerceIn(360, 800)
                            bmp = textureView.getBitmap(targetW, targetH)
                            if (bmp != null) {
                                val accepted = faceTracker.processFrame(bmp, isFrontFacing) { faceData ->
                                    runOnUiThread {
                                        overlayView.faceData = faceData
                                    }
                                }
                                if (!accepted) {
                                    bmp.recycle()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        try { bmp?.recycle() } catch (_: Exception) {}
                    }
                }
                overlayView.postInvalidate()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startBackgroundThread()
            openCamera()
        } else {
            requestCameraPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun setupModeSelector() {
        photoModeBtn.setOnClickListener {
            if (isRecordingVideo) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isVideoMode = false
            shutterButton.mode = CameraShutterButton.Mode.PHOTO
            updateModeUi()
        }

        videoModeBtn.setOnClickListener {
            if (isRecordingVideo) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isVideoMode = true
            shutterButton.mode = CameraShutterButton.Mode.VIDEO
            updateModeUi()
        }
    }

    private fun updateModeUi() {
        if (!isVideoMode) {
            photoModeBtn.setTextColor(Color.WHITE)
            photoModeBtn.setBackgroundResource(R.drawable.camera_active_mode_chip)
            videoModeBtn.setTextColor(Color.parseColor("#C8D1DC"))
            videoModeBtn.background = null
        } else {
            videoModeBtn.setTextColor(Color.WHITE)
            videoModeBtn.setBackgroundResource(R.drawable.camera_active_video_chip)
            photoModeBtn.setTextColor(Color.parseColor("#C8D1DC"))
            photoModeBtn.background = null
        }
    }

    private fun setupShutterControl() {
        shutterButton.maxRecordingDurationMs = 30_000L

        shutterButton.onPhotoCapture = {
            if (countdownSeconds > 0 && !isRecordingVideo) {
                startCountdownTimer(countdownSeconds) {
                    capturePhoto()
                }
            } else {
                capturePhoto()
            }
        }

        shutterButton.onRecordStart = {
            if (countdownSeconds > 0 && !isRecordingVideo) {
                startCountdownTimer(countdownSeconds) {
                    startVideoRecording()
                }
            } else {
                startVideoRecording()
            }
        }

        shutterButton.onRecordStop = {
            if (isCountingDown) {
                cancelCountdownTimer()
            } else {
                stopVideoRecording()
            }
        }

        shutterButton.onDragLockProgress = { prog ->
            cameraLockTarget?.let { target ->
                if (prog > 0.05f) {
                    target.visibility = View.VISIBLE
                    target.alpha = (prog * 1.5f).coerceIn(0.4f, 1.0f)
                    val s = 1.0f + prog * 0.25f
                    target.scaleX = s
                    target.scaleY = s
                } else if (!shutterButton.isLocked && !shutterButton.isRecording) {
                    target.visibility = View.GONE
                }
            }
        }

        shutterButton.onRecordLock = {
            cameraLockTarget?.let { target ->
                target.visibility = View.VISIBLE
                target.alpha = 1.0f
                target.scaleX = 1.15f
                target.scaleY = 1.15f
                cameraLockLabel?.text = "Locked"
                target.setBackgroundColor(Color.parseColor("#55FFD700"))
            }
            showSwipeToast("🔒 Hands-Free Recording Locked")
        }

        shutterButton.onDragZoom = { zoomFraction ->
            val targetZoom = 1.0f + zoomFraction * (maxDigitalZoom - 1.0f)
            applyZoom(targetZoom)
            if (zoomFraction > 0.01f) {
                showZoomPill(targetZoom)
            } else {
                mainHandler.postDelayed({ zoomPill?.visibility = View.GONE }, 600L)
            }
        }

        cameraLockTarget?.setOnClickListener {
            if (shutterButton.isRecording) {
                shutterButton.stopRecording()
            }
        }
    }

    private fun startVideoRecording() {
        if (isRecordingVideo) return

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            Toast.makeText(this, "Microphone permission not granted, recording video without audio", Toast.LENGTH_SHORT).show()
        }

        if (!prepareMediaRecorder()) {
            Toast.makeText(this, "Failed to initialize video recorder", Toast.LENGTH_SHORT).show()
            if (shutterButton.isRecording) {
                shutterButton.stopRecording()
            }
            return
        }

        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(optimalPreviewSize.width, optimalPreviewSize.height)
        val previewSurface = Surface(texture)
        val recorderSurface = mediaRecorder?.surface ?: return

        try {
            // Gracefully stop repeating on current session before starting recording session
            try {
                captureSession?.stopRepeating()
                captureSession?.close()
                captureSession = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous preview session", e)
            }

            val surfaces = listOf(previewSurface, recorderSurface)
            val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            previewRequestBuilder = recordBuilder
            applyCurrentZoomToBuilder(recordBuilder)

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler)
                        mediaRecorder?.start()
                        isRecordingVideo = true
                        startBgmPlayback()
                        recordingStartTime = SystemClock.uptimeMillis()
                        runOnUiThread {
                            setRecordingUiState(true)
                            if (!shutterButton.isRecording) {
                                shutterButton.startRecording()
                            }
                            startRecordingTimer()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating recording request", e)
                        runOnUiThread {
                            stopVideoRecording()
                        }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configure recording session failed")
                    runOnUiThread {
                        if (shutterButton.isRecording) {
                            shutterButton.stopRecording()
                        }
                        createCameraPreview()
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "startVideoRecording error", e)
            createCameraPreview()
        }
    }

    private fun prepareMediaRecorder(): Boolean {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        mediaRecorder = mr

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cameraDir = File(getExternalFilesDir(null), "fun_videos").also { it.mkdirs() }
        currentVideoFile = File(cameraDir, "Storyline_Video_${timeStamp}.mp4")

        return try {
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            var audioConfigured = false
            if (hasAudioPermission) {
                try {
                    mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                    audioConfigured = true
                } catch (e: Exception) {
                    Log.w(TAG, "setAudioSource failed, continuing without audio", e)
                }
            }

            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(currentVideoFile!!.absolutePath)
            mr.setVideoEncodingBitRate(10_000_000)
            mr.setVideoFrameRate(30)
            if (videoRecordSpeed != 1.0f) {
                try {
                    val captureRate = (30 * videoRecordSpeed).toDouble()
                    mr.setCaptureRate(captureRate)
                } catch (e: Exception) {
                    Log.w(TAG, "setCaptureRate not supported", e)
                }
            }

            // Landscape-aligned dimensions for hardware encoder (e.g. 1280x720) with 16-pixel multiples
            val pWidth = optimalPreviewSize.width
            val pHeight = optimalPreviewSize.height
            val recW = maxOf(pWidth, pHeight).coerceAtMost(1920)
            val recH = minOf(pWidth, pHeight).coerceAtMost(1080)
            val alignedW = (recW / 16) * 16
            val alignedH = (recH / 16) * 16
            mr.setVideoSize(alignedW, alignedH)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (audioConfigured) {
                try {
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioEncodingBitRate(128_000)
                    mr.setAudioSamplingRate(44100)
                } catch (e: Exception) {
                    Log.w(TAG, "Audio encoder setup failed", e)
                }
            }

            // Calculate precise orientation hint matching sensor and display rotation
            val deviceRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            val deviceDegrees = when (deviceRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val orientationHint = if (isFrontFacing) {
                (sensorOrientation + deviceDegrees) % 360
            } else {
                (sensorOrientation - deviceDegrees + 360) % 360
            }
            mr.setOrientationHint(orientationHint)
            mr.prepare()
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareMediaRecorder failed", e)
            try {
                mr.reset()
                mr.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            false
        }
    }

    private fun stopVideoRecording() {
        if (!isRecordingVideo) return
        isRecordingVideo = false
        stopBgmPlayback()
        stopRecordingTimer()
        if (shutterButton.isRecording) {
            shutterButton.stopRecording()
        }

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "Error aborting captureSession", e)
        }

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder", e)
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing mediaRecorder", e)
        }
        mediaRecorder = null

        setRecordingUiState(false)
        createCameraPreview()

        val recorded = currentVideoFile
        if (recorded != null && recorded.exists() && recorded.length() > 4096) {
            Thread {
                val saved = saveVideoToGallery(recorded)
                runOnUiThread {
                    enterPostCaptureMode(saved ?: recorded, isVideo = true, baseBitmap = null)
                }
            }.start()
        } else {
            Toast.makeText(this, "Recording was too short", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setRecordingUiState(recording: Boolean) {
        if (recording) {
            recordingTimerBadge.visibility = View.VISIBLE
            lensBadge.visibility = View.GONE
        } else {
            recordingTimerBadge.visibility = View.GONE
            lensBadge.visibility = View.VISIBLE
            cameraLockTarget?.visibility = View.GONE
            cameraLockTarget?.alpha = 0f
            cameraLockTarget?.scaleX = 1.0f
            cameraLockTarget?.scaleY = 1.0f
            cameraLockLabel?.text = "Lock"
            cameraLockTarget?.setBackgroundResource(R.drawable.camera_glass_button_bg)
        }
    }

    private fun startRecordingTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (!isRecordingVideo) return
                val elapsedMs = SystemClock.uptimeMillis() - recordingStartTime
                val elapsedSec = elapsedMs / 1000
                val min = elapsedSec / 60
                val sec = elapsedSec % 60
                recordingTimerBadge.text = String.format("🔴 REC %02d:%02d", min, sec)

                if (elapsedMs >= shutterButton.maxRecordingDurationMs) {
                    stopVideoRecording()
                } else {
                    mainHandler.postDelayed(this, 100)
                }
            }
        }
        mainHandler.post(timerRunnable!!)
    }

    private fun stopRecordingTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun saveVideoToGallery(videoFile: File): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Storyline_Fun_${timeStamp}.mp4"

        // 1. Write to public Movies/Storyline_Camera or DCIM/Camera directory
        val publicMoviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Storyline_Camera")
        if (!publicMoviesDir.exists()) publicMoviesDir.mkdirs()
        val targetFile = if (publicMoviesDir.canWrite()) {
            File(publicMoviesDir, fileName)
        } else {
            val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            if (!dcimDir.exists()) dcimDir.mkdirs()
            File(dcimDir, fileName)
        }

        var copied = false
        try {
            FileInputStream(videoFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            copied = true
        } catch (e: Exception) {
            Log.w(TAG, "Direct public save failed for video", e)
        }

        val fileToReturn = if (copied && targetFile.exists() && targetFile.length() > 0) targetFile else videoFile

        // 2. Insert into Android MediaStore with IS_PENDING handling
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Storyline_Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(videoFile).use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore video insert failed", e)
        }

        // 3. Trigger system MediaScanner so Gallery apps instantly display the recorded video
        try {
            MediaScannerConnection.scanFile(
                this,
                arrayOf(fileToReturn.absolutePath),
                arrayOf("video/mp4")
            ) { path, scannedUri ->
                Log.d(TAG, "Scanned video $path -> $scannedUri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner video failed", e)
        }

        return fileToReturn
    }

    private fun showVideoSavedDialog(videoFile: File) {
        enterPostCaptureMode(videoFile, isVideo = true)
    }

    private fun setupCategoryTabs() {
        categoryContainer.removeAllViews()
        val categories = LensRepository.getCategories()

        categories.forEach { (catKey, catTitle) ->
            val isSelected = catKey == currentCategory
            val tabView = TextView(this).apply {
                text = catTitle
                textSize = 12.5f
                setPadding(14.dpToPx(context), 6.dpToPx(context), 14.dpToPx(context), 6.dpToPx(context))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8.dpToPx(context)
                }
                layoutParams = params

                val bg = GradientDrawable().apply {
                    cornerRadius = 20.dpToPx(context).toFloat()
                    if (isSelected) {
                        setColor(Color.parseColor("#388BFD"))
                    } else {
                        setColor(Color.parseColor("#33FFFFFF"))
                    }
                }
                background = bg
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#D0D8E0"))

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    currentCategory = catKey
                    setupCategoryTabs()
                    setupLensCarousel()
                }
            }
            categoryContainer.addView(tabView)
        }
    }

    private fun setupLensCarousel() {
        filterContainer.removeAllViews()

        val screenW = resources.displayMetrics.widthPixels
        val halfW = (screenW / 2 - 32.dpToPx(this)).coerceAtLeast(16.dpToPx(this))
        filterContainer.setPadding(halfW, 0, halfW, 6.dpToPx(this))

        val filteredLenses = if (currentCategory == "ALL") {
            allLenses
        } else {
            allLenses.filter { it.category == currentCategory || it.category == "ALL" }
        }

        filteredLenses.forEach { lens ->
            val isSelected = lens.id == selectedLens?.id

            val lensItemView = LinearLayout(this).apply {
                tag = lens
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 12.dpToPx(context)
                }
                layoutParams = params

                // Circle Container
                val circleFrame = FrameLayout(context).apply {
                    val circleSize = if (isSelected) 60.dpToPx(context) else 50.dpToPx(context)
                    layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                        bottomMargin = 4.dpToPx(context)
                    }

                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (isSelected) {
                            setColor(Color.parseColor("#5500F2FE"))
                            setStroke(3.dpToPx(context), Color.parseColor("#00F2FE"))
                        } else {
                            setColor(Color.parseColor("#44000000"))
                            setStroke(1.dpToPx(context), Color.parseColor("#33FFFFFF"))
                        }
                    }
                    background = bg

                    val iconPath = if (lens.iconAssetPath.isNotEmpty()) lens.iconAssetPath else lens.assetPath
                    if (iconPath.isNotEmpty()) {
                        val iconIv = ImageView(context).apply {
                            val imgSize = if (isSelected) 44.dpToPx(context) else 36.dpToPx(context)
                            layoutParams = FrameLayout.LayoutParams(imgSize, imgSize).apply {
                                gravity = android.view.Gravity.CENTER
                            }
                            try {
                                context.assets.open(iconPath).use { stream: InputStream ->
                                    setImageBitmap(BitmapFactory.decodeStream(stream))
                                }
                            } catch (_: Exception) {}
                        }
                        addView(iconIv)
                    } else {
                        val textTv = TextView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = android.view.Gravity.CENTER
                            }
                            text = if (lens.id == "none") "📷" else "✨"
                            textSize = 20f
                        }
                        addView(textTv)
                    }
                }

                // Name label
                val labelTv = TextView(context).apply {
                    text = lens.name
                    textSize = 10.5f
                    setTextColor(if (isSelected) Color.parseColor("#00F2FE") else Color.parseColor("#B0BCC8"))
                    gravity = android.view.Gravity.CENTER
                }

                addView(circleFrame)
                addView(labelTv)

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60L).withEndAction {
                        it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100L).start()
                    }.start()
                    selectedLens = lens
                    overlayView.currentLens = lens
                    updateLensBadge(lens)
                    setupLensCarousel()
                    showFloatingLensPill(lens)
                    val scroll = cameraLensScrollView ?: return@setOnClickListener
                    val scrollX = left - (scroll.width - width) / 2
                    scroll.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
                }
            }

            filterContainer.addView(lensItemView)

            if (isSelected) {
                lensItemView.post {
                    val scroll = cameraLensScrollView ?: return@post
                    val scrollX = lensItemView.left - (scroll.width - lensItemView.width) / 2
                    scroll.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
                }
            }
        }

        cameraLensScrollView?.setOnScrollChangeListener { _, _, _, _, _ ->
            snapHandler.removeCallbacks(snapRunnable ?: return@setOnScrollChangeListener)
            snapRunnable = Runnable {
                val scroll = cameraLensScrollView ?: return@Runnable
                val centerX = scroll.scrollX + scroll.width / 2
                var closestChild: View? = null
                var closestDist = Int.MAX_VALUE
                for (i in 0 until filterContainer.childCount) {
                    val child = filterContainer.getChildAt(i)
                    val childCenter = child.left + child.width / 2
                    val dist = Math.abs(centerX - childCenter)
                    if (dist < closestDist) {
                        closestDist = dist
                        closestChild = child
                    }
                }
                closestChild?.let { target ->
                    val targetLens = target.tag as? LensItem
                    if (targetLens != null && targetLens.id != selectedLens?.id) {
                        selectedLens = targetLens
                        overlayView.currentLens = targetLens
                        updateLensBadge(targetLens)
                        showFloatingLensPill(targetLens)
                        try { target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) {}
                        val targetScrollX = target.left - (scroll.width - target.width) / 2
                        scroll.smoothScrollTo(targetScrollX.coerceAtLeast(0), 0)
                    }
                }
            }
            snapHandler.postDelayed(snapRunnable!!, 130L)
        }
    }

    private fun updateLensBadge(lens: LensItem) {
        lensBadge.text = "${lens.name} (${lens.nameHi})"
    }

    private fun flipCamera() {
        isFrontFacing = !isFrontFacing
        currentZoomRatio = 1.0f
        currentZoom = 1.0f
        faceTracker.reset()
        overlayView.faceData = SmoothedFaceData(hasFace = false)

        // Silky smooth 3D flip transition animation (Snapchat / Instagram style)
        textureView.animate().rotationYBy(180f).setDuration(280L).withEndAction {
            textureView.rotationY = 0f
        }.start()
        overlayView.animate().rotationYBy(180f).setDuration(280L).withEndAction {
            overlayView.rotationY = 0f
        }.start()

        closeCamera()
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (isFrontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        var chosenId: String? = null
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) {
                chosenId = id
                break
            }
        }
        if (chosenId == null && manager.cameraIdList.isNotEmpty()) {
            chosenId = manager.cameraIdList[0]
        }
        if (chosenId == null) {
            Toast.makeText(this, "No camera found on device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val characteristics = manager.getCameraCharacteristics(chosenId)
            setupCameraCharacteristics(characteristics)

            manager.openCamera(chosenId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open camera", e)
        }
    }

    private fun setupCameraCharacteristics(characteristics: CameraCharacteristics) {
        activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFrontFacing) 270 else 90)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
        if (!sizes.isNullOrEmpty()) {
            optimalPreviewSize = sizes.filter {
                val ratio = if (it.width > it.height) it.width.toFloat() / it.height.toFloat() else it.height.toFloat() / it.width.toFloat()
                Math.abs(ratio - (16f / 9f)) < 0.15f
            }.maxByOrNull { it.width * it.height } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
            Log.i(TAG, "Selected optimal camera preview size: ${optimalPreviewSize.width}x${optimalPreviewSize.height}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                supportsControlZoomRatio = true
                maxDigitalZoom = Math.min(5.0f, range.upper)
            } else {
                supportsControlZoomRatio = false
                val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
                maxDigitalZoom = Math.min(5.0f, maxZoom)
            }
        } else {
            supportsControlZoomRatio = false
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            maxDigitalZoom = Math.min(5.0f, maxZoom)
        }
        if (maxDigitalZoom < 1.0f) maxDigitalZoom = 1.0f
    }

    private fun createCameraPreview() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        try {
            texture.setDefaultBufferSize(optimalPreviewSize.width, optimalPreviewSize.height)
            val surface = Surface(texture)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            previewRequestBuilder = builder
            applyCurrentZoomToBuilder(builder)

            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera preview", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera preview config failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Preview creation failed", e)
        }
    }

    private fun capturePhoto() {
        playShutterSound()
        triggerShutterFlashAnimation()
        val rawBitmap = textureView.bitmap ?: return

        // Bake all active AR props, gesture particles, and visual filter styles onto captured photo
        val finalPhoto = Bitmap.createBitmap(rawBitmap.width, rawBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalPhoto)
        canvas.drawBitmap(rawBitmap, 0f, 0f, null)
        overlayView.drawCaptureLayers(canvas, rawBitmap.width.toFloat(), rawBitmap.height.toFloat(), rawBitmap)

        Thread {
            val savedFile = saveBitmapToGallery(finalPhoto)
            runOnUiThread {
                if (savedFile != null) {
                    enterPostCaptureMode(savedFile, isVideo = false, baseBitmap = finalPhoto)
                } else {
                    Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Storyline_Photo_${timeStamp}.jpg"

        // 1. Create public file in DCIM/Camera or Pictures/Storyline_Camera
        val publicDcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!publicDcimDir.exists()) publicDcimDir.mkdirs()
        val targetFile = if (publicDcimDir.canWrite()) {
            File(publicDcimDir, fileName)
        } else {
            val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Storyline_Camera")
            if (!picturesDir.exists()) picturesDir.mkdirs()
            File(picturesDir, fileName)
        }

        var success = false
        try {
            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
            success = true
        } catch (e: Exception) {
            Log.w(TAG, "Direct public save failed, falling back to app dir", e)
        }

        val fileToReturn = if (success && targetFile.exists() && targetFile.length() > 0) {
            targetFile
        } else {
            val cameraDir = File(getExternalFilesDir(null), "fun_photos").also { it.mkdirs() }
            val fallback = File(cameraDir, fileName)
            FileOutputStream(fallback).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
            fallback
        }

        // 2. Insert into Android MediaStore with IS_PENDING handling for instant gallery visibility
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore photo insert failed", e)
        }

        // 3. Trigger system MediaScanner so Gallery apps immediately pick up the new photo
        try {
            MediaScannerConnection.scanFile(
                this,
                arrayOf(fileToReturn.absolutePath),
                arrayOf("image/jpeg")
            ) { path, scannedUri ->
                Log.d(TAG, "Scanned photo $path -> $scannedUri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner photo failed", e)
        }

        return fileToReturn
    }

    private fun showPhotoSavedDialog(photoFile: File) {
        enterPostCaptureMode(photoFile, isVideo = false)
    }

    private fun triggerShutterFlashAnimation() {
        val isWarmAmber = isFrontFacing && overlayView.isNightFlashEnabled
        val flash = if (isWarmAmber) {
            warmAmberFlashView ?: shutterFlashView ?: return
        } else {
            shutterFlashView ?: return
        }
        flash.alpha = if (isWarmAmber) 0.95f else 0.85f
        flash.visibility = View.VISIBLE
        flash.animate()?.cancel()
        flash.animate()
            ?.alpha(0f)
            ?.setDuration(if (isWarmAmber) 280L else 160L)
            ?.withEndAction { flash.visibility = View.GONE }
            ?.start()
    }

    private fun enterPostCaptureMode(mediaFile: File, isVideo: Boolean, baseBitmap: Bitmap? = null) {
        currentPostCaptureMediaFile = mediaFile
        isPostCaptureVideo = isVideo
        currentPostCaptureBitmap = baseBitmap
        currentCaptionText = ""
        isDoodleModeActive = false

        val container = findViewById<FrameLayout>(R.id.postCaptureContainer) ?: return
        val imgView = findViewById<ImageView>(R.id.postCaptureImageView)
        val videoView = findViewById<VideoView>(R.id.postCaptureVideoView)
        val filterOverlay = findViewById<PostCaptureFilterOverlayView>(R.id.postCaptureFilterOverlay)
        val doodleCanvas = findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)
        val stickersContainer = findViewById<FrameLayout>(R.id.postCaptureStickersContainer)
        val captionContainer = findViewById<FrameLayout>(R.id.postCaptureCaptionContainer)
        val captionTv = findViewById<TextView>(R.id.postCaptureCaptionTv)
        val doodleBar = findViewById<LinearLayout>(R.id.postCaptureDoodleBar)
        val filterBadge = findViewById<TextView>(R.id.postCaptureFilterBadge)

        container.visibility = View.VISIBLE
        filterOverlay?.currentFilter = PostCaptureGeofilter.CLEAN
        filterBadge?.visibility = View.VISIBLE
        filterBadge?.text = "✨ Original (Swipe for Geofilters)"
        doodleCanvas?.clear()
        doodleCanvas?.isDrawingEnabled = false
        doodleBar?.visibility = View.GONE
        stickersContainer?.removeAllViews()
        captionContainer?.visibility = View.GONE
        captionTv?.text = ""

        if (isVideo) {
            imgView?.visibility = View.GONE
            videoView?.visibility = View.VISIBLE
            try {
                videoView?.setVideoPath(mediaFile.absolutePath)
                videoView?.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.start()
                }
                videoView?.start()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start post-capture video preview", e)
            }
        } else {
            videoView?.visibility = View.GONE
            imgView?.visibility = View.VISIBLE
            val bmp = baseBitmap ?: BitmapFactory.decodeFile(mediaFile.absolutePath)
            currentPostCaptureBitmap = bmp
            imgView?.setImageBitmap(bmp)
        }
    }

    private fun exitPostCaptureMode() {
        try {
            findViewById<VideoView>(R.id.postCaptureVideoView)?.stopPlayback()
        } catch (_: Exception) {}
        findViewById<FrameLayout>(R.id.postCaptureContainer)?.visibility = View.GONE
        currentPostCaptureBitmap = null
        currentPostCaptureMediaFile = null
        isDoodleModeActive = false
        findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)?.isDrawingEnabled = false
        findViewById<LinearLayout>(R.id.postCaptureDoodleBar)?.visibility = View.GONE
        findViewById<TextView>(R.id.postCaptureFilterBadge)?.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (findViewById<FrameLayout>(R.id.postCaptureContainer)?.visibility == View.VISIBLE) {
            exitPostCaptureMode()
            return
        }
        super.onBackPressed()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPostCaptureScreen() {
        postCaptureContainer = findViewById(R.id.postCaptureContainer)
        postCaptureImageView = findViewById(R.id.postCaptureImageView)
        postCaptureVideoView = findViewById(R.id.postCaptureVideoView)
        postCaptureFilterOverlay = findViewById(R.id.postCaptureFilterOverlay)
        postCaptureDoodleCanvas = findViewById(R.id.postCaptureDoodleCanvas)
        postCaptureStickersContainer = findViewById(R.id.postCaptureStickersContainer)
        postCaptureCaptionContainer = findViewById(R.id.postCaptureCaptionContainer)
        postCaptureCaptionTv = findViewById(R.id.postCaptureCaptionTv)
        postCaptureDoodleBar = findViewById(R.id.postCaptureDoodleBar)
        postCaptureFilterBadge = findViewById(R.id.postCaptureFilterBadge)
        postCaptureSwipeToast = findViewById(R.id.postCaptureSwipeToast)

        setupDoodleColorPalette()

        // 1. Swipe Geofilter Gesture Detector
        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isDoodleModeActive) return false
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(velocityX) > 260f) {
                    val overlay = findViewById<PostCaptureFilterOverlayView>(R.id.postCaptureFilterOverlay) ?: return false
                    val filter = if (dx < 0 || velocityX < 0) {
                        overlay.nextFilter()
                    } else {
                        overlay.prevFilter()
                    }
                    val badgeText = overlay.getFilterNameWithEmoji()
                    findViewById<TextView>(R.id.postCaptureFilterBadge)?.text = badgeText
                    showPostCaptureSwipeToast(badgeText)
                    try { overlay.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        })

        postCaptureContainer?.setOnTouchListener { _, event ->
            if (!isDoodleModeActive) {
                swipeDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        // 2. Discard / Close Button
        findViewById<FrameLayout>(R.id.postCaptureCloseBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            exitPostCaptureMode()
        }

        // 3. 🔤 Caption Action
        findViewById<FrameLayout>(R.id.postCaptureCaptionBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            promptCaptionInput()
        }
        postCaptureCaptionTv?.setOnClickListener {
            promptCaptionInput()
        }

        // Draggable Caption along Y axis
        var captionDownRawY = 0f
        var captionStartY = 0f
        postCaptureCaptionContainer?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    captionDownRawY = event.rawY
                    captionStartY = v.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newY = captionStartY + (event.rawY - captionDownRawY)
                    val parentH = (v.parent as? View)?.height?.toFloat() ?: 1200f
                    v.y = newY.coerceIn(80f, parentH - v.height - 120f)
                    true
                }
                else -> false
            }
        }

        // 4. ✏️ Doodle Pen Action
        findViewById<FrameLayout>(R.id.postCaptureDoodleBtn)?.setOnClickListener { btn ->
            btn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isDoodleModeActive = !isDoodleModeActive
            findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)?.isDrawingEnabled = isDoodleModeActive
            val doodleBar = findViewById<LinearLayout>(R.id.postCaptureDoodleBar)
            val filterBadge = findViewById<TextView>(R.id.postCaptureFilterBadge)
            if (isDoodleModeActive) {
                doodleBar?.visibility = View.VISIBLE
                filterBadge?.visibility = View.GONE
                btn.setBackgroundColor(Color.parseColor("#55FF007F"))
                showPostCaptureSwipeToast("✏️ Doodle Mode ON: Draw on screen!")
            } else {
                doodleBar?.visibility = View.GONE
                filterBadge?.visibility = View.VISIBLE
                btn.setBackgroundResource(R.drawable.camera_glass_button_bg)
            }
        }

        findViewById<TextView>(R.id.postCaptureDoodleUndoBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)?.undo()
        }

        findViewById<TextView>(R.id.postCaptureDoodleClearBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)?.clear()
            showPostCaptureSwipeToast("🗑️ Canvas Cleared")
        }

        // 5. 🎭 Stickers Action
        findViewById<FrameLayout>(R.id.postCaptureStickersBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showStickersPicker()
        }

        // 6. ⚡ Send to Status Maker
        val onStatusMakerClick = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val mediaFile = currentPostCaptureMediaFile ?: return@OnClickListener
            if (isPostCaptureVideo) {
                val statusIntent = Intent(this@FunCameraActivity, StatusMakerActivity::class.java).apply {
                    putExtra(StatusMakerActivity.EXTRA_INITIAL_PHOTO_PATH, mediaFile.absolutePath)
                }
                startActivity(statusIntent)
            } else {
                Thread {
                    val composite = compositeCurrentPhoto()
                    val savedFile = saveBitmapToGallery(composite) ?: mediaFile
                    runOnUiThread {
                        val statusIntent = Intent(this@FunCameraActivity, StatusMakerActivity::class.java).apply {
                            putExtra(StatusMakerActivity.EXTRA_INITIAL_PHOTO_PATH, savedFile.absolutePath)
                        }
                        startActivity(statusIntent)
                    }
                }.start()
            }
        }

        findViewById<FrameLayout>(R.id.postCaptureStatusMakerBtn)?.setOnClickListener(onStatusMakerClick)
        findViewById<TextView>(R.id.postCaptureSendActionBtn)?.setOnClickListener(onStatusMakerClick)

        // 7. 💾 Save to Gallery
        findViewById<FrameLayout>(R.id.postCaptureSaveBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (isPostCaptureVideo) {
                Toast.makeText(this, "💾 Video saved to Gallery!", Toast.LENGTH_SHORT).show()
            } else {
                Thread {
                    val composite = compositeCurrentPhoto()
                    val saved = saveBitmapToGallery(composite)
                    runOnUiThread {
                        if (saved != null) {
                            Toast.makeText(this, "💾 Photo saved to Gallery!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }

        // 8. 📤 Share / Send
        findViewById<FrameLayout>(R.id.postCaptureShareBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val mediaFile = currentPostCaptureMediaFile ?: return@setOnClickListener
            if (isPostCaptureVideo) {
                shareMediaFile(mediaFile, isVideo = true)
            } else {
                Thread {
                    val composite = compositeCurrentPhoto()
                    val saved = saveBitmapToGallery(composite) ?: mediaFile
                    runOnUiThread {
                        shareMediaFile(saved, isVideo = false)
                    }
                }.start()
            }
        }
    }

    private fun setupDoodleColorPalette() {
        val paletteContainer = findViewById<LinearLayout>(R.id.postCaptureColorPalette) ?: return
        paletteContainer.removeAllViews()

        val colors = listOf(
            Pair("#FF007F", "Neon Pink"),
            Pair("#00F2FE", "Neon Cyan"),
            Pair("#FFD700", "Neon Gold"),
            Pair("#00FF66", "Neon Lime"),
            Pair("#FFFFFF", "Crisp White"),
            Pair("#FF3B30", "Sunset Red")
        )

        colors.forEach { (colorHex, name) ->
            val colorInt = Color.parseColor(colorHex)
            val circleView = View(this).apply {
                val size = 28.dpToPx(this@FunCameraActivity)
                val params = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = 8.dpToPx(this@FunCameraActivity)
                }
                layoutParams = params
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    setStroke(2.dpToPx(this@FunCameraActivity), Color.WHITE)
                }
                isClickable = true
                isFocusable = true
                contentDescription = name
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)?.currentColor = colorInt
                    it.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100L).withEndAction {
                        it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120L).start()
                    }.start()
                    showPostCaptureSwipeToast("✏️ Brush: $name")
                }
            }
            paletteContainer.addView(circleView)
        }
    }

    private fun showStickersPicker() {
        val stickers = listOf(
            "🔥", "❤️", "✨", "⚡",
            "😎", "🎂", "💯", "🎉",
            "👑", "🌸", "🐶", "📸",
            "🚀", "💫", "🧸", "💎"
        )

        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(16.dpToPx(context), 16.dpToPx(context), 16.dpToPx(context), 24.dpToPx(context))
            background = GradientDrawable().apply {
                cornerRadius = 24.dpToPx(context).toFloat()
                setColor(Color.parseColor("#1B212C"))
                setStroke(1.dpToPx(context), Color.parseColor("#33FFFFFF"))
            }
        }

        var dialog: AlertDialog? = null

        stickers.forEach { emoji ->
            val tv = TextView(this).apply {
                text = emoji
                textSize = 34f
                gravity = Gravity.CENTER
                setPadding(12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    dialog?.dismiss()
                    addStickerToPreview(emoji)
                }
            }
            grid.addView(tv)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("🎭 Select Snapchat Sticker")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addStickerToPreview(emoji: String) {
        val container = findViewById<FrameLayout>(R.id.postCaptureStickersContainer) ?: return
        val sticker = StickerOverlayView(this).apply {
            setSticker(emoji, 5000)
            showControls(true)
            onDeleteRequested = {
                container.removeView(this)
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        container.addView(sticker, params)
        showPostCaptureSwipeToast("🎭 Sticker added! Drag & pinch to resize")
    }

    private fun promptCaptionInput() {
        val input = EditText(this).apply {
            setText(currentCaptionText)
            setSelection(currentCaptionText.length)
            hint = "Add a caption... (e.g. Vibes 🔥)"
            setPadding(16.dpToPx(context), 16.dpToPx(context), 16.dpToPx(context), 16.dpToPx(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("🔤 Add Caption")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val text = input.text.toString().trim()
                currentCaptionText = text
                val captionContainer = findViewById<FrameLayout>(R.id.postCaptureCaptionContainer)
                val captionTv = findViewById<TextView>(R.id.postCaptureCaptionTv)
                if (text.isNotEmpty()) {
                    captionTv?.text = text
                    captionContainer?.visibility = View.VISIBLE
                } else {
                    captionContainer?.visibility = View.GONE
                }
            }
            .setNegativeButton("Clear") { _, _ ->
                currentCaptionText = ""
                findViewById<FrameLayout>(R.id.postCaptureCaptionContainer)?.visibility = View.GONE
            }
            .show()
    }

    private fun compositeCurrentPhoto(): Bitmap {
        val base = currentPostCaptureBitmap ?: run {
            val file = currentPostCaptureMediaFile ?: return Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            BitmapFactory.decodeFile(file.absolutePath)
        }

        val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        findViewById<PostCaptureFilterOverlayView>(R.id.postCaptureFilterOverlay)?.getColorFilterForBase()?.let {
            basePaint.colorFilter = it
        }
        canvas.drawBitmap(base, 0f, 0f, basePaint)

        val bw = base.width.toFloat()
        val bh = base.height.toFloat()

        // 1. Geofilter
        findViewById<PostCaptureFilterOverlayView>(R.id.postCaptureFilterOverlay)?.drawGeofilter(canvas, bw, bh)

        // 2. Doodle canvas
        findViewById<DoodleCanvasView>(R.id.postCaptureDoodleCanvas)?.let { doodle ->
            val scaleX = bw / doodle.width.coerceAtLeast(1)
            val scaleY = bh / doodle.height.coerceAtLeast(1)
            canvas.save()
            canvas.scale(scaleX, scaleY)
            doodle.renderStrokes(canvas)
            canvas.restore()
        }

        // 3. Caption
        val captionContainer = findViewById<FrameLayout>(R.id.postCaptureCaptionContainer)
        if (captionContainer?.visibility == View.VISIBLE && currentCaptionText.isNotEmpty()) {
            val containerH = findViewById<FrameLayout>(R.id.postCaptureContainer)?.height?.toFloat() ?: bh
            val relY = if (containerH > 0) captionContainer.y / containerH else 0.5f
            val captionCenterY = relY * bh + (captionContainer.height / 2f) * (bh / containerH)

            val barHeight = 52f * resources.displayMetrics.density * (bh / 1920f).coerceAtLeast(1f)
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#99000000")
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, captionCenterY - barHeight / 2f, bw, captionCenterY + barHeight / 2f, barPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f * resources.displayMetrics.density * (bw / 1080f).coerceAtLeast(1f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val textY = captionCenterY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(currentCaptionText, bw / 2f, textY, textPaint)
        }

        // 4. Stickers
        val stickersContainer = findViewById<FrameLayout>(R.id.postCaptureStickersContainer)
        if (stickersContainer != null) {
            val sw = stickersContainer.width.toFloat().coerceAtLeast(1f)
            val sh = stickersContainer.height.toFloat().coerceAtLeast(1f)
            val scaleX = bw / sw
            val scaleY = bh / sh

            for (i in 0 until stickersContainer.childCount) {
                val child = stickersContainer.getChildAt(i)
                if (child is StickerOverlayView) {
                    val cx = (child.x + child.width / 2f) * scaleX
                    val cy = (child.y + child.height / 2f) * scaleY
                    val stickerScale = child.scaleX * scaleX
                    val stickerRotation = child.rotation
                    val emoji = child.stickerText

                    if (emoji.isNotEmpty()) {
                        canvas.save()
                        canvas.translate(cx, cy)
                        canvas.rotate(stickerRotation)
                        canvas.scale(stickerScale, stickerScale)

                        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = 36f * resources.displayMetrics.density
                            textAlign = Paint.Align.CENTER
                        }
                        val yPos = -(emojiPaint.descent() + emojiPaint.ascent()) / 2f
                        canvas.drawText(emoji, 0f, yPos, emojiPaint)
                        canvas.restore()
                    }
                }
            }
        }

        return result
    }

    private fun shareMediaFile(file: File, isVideo: Boolean) {
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Snap via"))
    }

    private fun showPostCaptureSwipeToast(text: String) {
        val toast = findViewById<TextView>(R.id.postCaptureSwipeToast) ?: return
        toast.text = text
        toast.visibility = View.VISIBLE
        toast.alpha = 1.0f
        toast.scaleX = 0.85f
        toast.scaleY = 0.85f
        toast.animate()?.cancel()
        toast.animate()
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(160L)
            ?.withEndAction {
                toast.animate()
                    ?.alpha(0f)
                    ?.setStartDelay(1000L)
                    ?.setDuration(300L)
                    ?.withEndAction { toast.visibility = View.GONE }
                    ?.start()
            }
            ?.start()
    }

    private fun playShutterSound() {
        try {
            assets.openFd("sound_effects/camera_shutter.wav").use { afd ->
                val player = MediaPlayer()
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                player.prepare()
                player.start()
                player.setOnCompletionListener { it.release() }
            }
        } catch (_: Exception) {}
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also {
                it.start()
                backgroundHandler = Handler(it.looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun closeCamera() {
        try {
            previewRequestBuilder = null
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (overlayView.isNightFlashEnabled) {
            val params = window.attributes
            params.screenBrightness = 1.0f
            window.attributes = params
        }
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        cancelCountdownTimer()
        stopBgmPlayback()
        if (isRecordingVideo) {
            stopVideoRecording()
        }
        if (overlayView.isNightFlashEnabled) {
            val params = window.attributes
            params.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        overlayView.clearAssetCache()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            if (findViewById<FrameLayout>(R.id.postCaptureContainer)?.visibility != View.VISIBLE) {
                currentPostCaptureBitmap?.recycle()
                currentPostCaptureBitmap = null
            }
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        overlayView.clearAssetCache()
        if (findViewById<FrameLayout>(R.id.postCaptureContainer)?.visibility != View.VISIBLE) {
            currentPostCaptureBitmap?.recycle()
            currentPostCaptureBitmap = null
        }
        System.gc()
    }

    override fun onDestroy() {
        cancelCountdownTimer()
        stopBgmPlayback()
        if (overlayView.isNightFlashEnabled) {
            val params = window.attributes
            params.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
        faceTracker.release()
        overlayView.clearAssetCache()
        currentPostCaptureBitmap?.recycle()
        currentPostCaptureBitmap = null
        super.onDestroy()
    }

    private fun setupVerticalToolRail() {
        // 1. 🔄 Flip Camera (Smooth 3D Y-Axis Flip)
        findViewById<FrameLayout>(R.id.cameraFlipBtn)?.setOnClickListener {
            if (!isRecordingVideo) {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val flipIcon = findViewById<ImageView>(R.id.cameraFlipIcon) ?: it
                flipIcon.animate()
                    .rotationYBy(180f)
                    .scaleX(0.82f)
                    .scaleY(0.82f)
                    .setDuration(180L)
                    .withEndAction {
                        flipIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160L).start()
                    }
                    .start()
                flipCamera()
            }
        }

        // 2. 💡 Ring Light / Flash (Off -> Torch -> Warm Amber Glow)
        val flashBtn = findViewById<TextView>(R.id.cameraNightFlashBtn)
        flashBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            flashModeState = (flashModeState + 1) % 3
            when (flashModeState) {
                1 -> {
                    overlayView.isNightFlashEnabled = true
                    val windowParams = window.attributes
                    previousScreenBrightness = windowParams.screenBrightness
                    windowParams.screenBrightness = 1.0f
                    window.attributes = windowParams

                    flashBtn.setTextColor(Color.parseColor("#FFD700"))
                    flashBtn.setBackgroundColor(Color.parseColor("#55FFD700"))
                    setTorchMode(true)
                    val msg = if (isFrontFacing) "💡 Warm Amber Selfie Glow ON" else "💡 Camera Torch ON"
                    showSwipeToast(msg)
                }
                2 -> {
                    overlayView.isNightFlashEnabled = true
                    warmAmberFlashView?.visibility = View.VISIBLE
                    warmAmberFlashView?.alpha = 0.45f
                    flashBtn.setTextColor(Color.parseColor("#FFA000"))
                    flashBtn.setBackgroundColor(Color.parseColor("#55FFA000"))
                    showSwipeToast("💡 Warm Amber Studio Glow")
                }
                else -> {
                    overlayView.isNightFlashEnabled = false
                    warmAmberFlashView?.visibility = View.GONE
                    setTorchMode(false)
                    val windowParams = window.attributes
                    windowParams.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = windowParams

                    flashBtn.setTextColor(Color.WHITE)
                    flashBtn.setBackgroundResource(R.drawable.camera_glass_button_bg)
                    showSwipeToast("💡 Flash OFF")
                }
            }
        }

        // 3. 🎵 Add Music
        cameraMusicBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showMusicSelectionDialog()
        }

        // 4. ⏱️ Hands-Free Countdown Timer (Off -> 3s -> 10s)
        cameraTimerBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            countdownSeconds = when (countdownSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            updateTimerBtnUi()
            val msg = if (countdownSeconds > 0) "⏱️ Hands-Free Timer: ${countdownSeconds}s" else "⏱️ Timer: OFF"
            showSwipeToast(msg)
        }

        // 5. 🏎️ Video Record Speed Toggle (0.5x, 1x, 2x)
        cameraSpeedBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            videoRecordSpeed = when (videoRecordSpeed) {
                1.0f -> 0.5f
                0.5f -> 2.0f
                else -> 1.0f
            }
            val label = when (videoRecordSpeed) {
                0.5f -> "0.5x"
                2.0f -> "2x"
                else -> "1x"
            }
            cameraSpeedBtn?.text = label
            if (videoRecordSpeed != 1.0f) {
                cameraSpeedBtn?.setTextColor(Color.parseColor("#00F2FE"))
                cameraSpeedBtn?.setBackgroundColor(Color.parseColor("#5500F2FE"))
            } else {
                cameraSpeedBtn?.setTextColor(Color.WHITE)
                cameraSpeedBtn?.setBackgroundResource(R.drawable.camera_glass_button_bg)
            }
            val msg = when (videoRecordSpeed) {
                0.5f -> "🏎️ 0.5x Slow-Motion Recording"
                2.0f -> "🏎️ 2x Fast-Forward Recording"
                else -> "🏎️ 1x Normal Speed"
            }
            showSwipeToast(msg)
        }

        // 6. ✨ Beauty Glow Filter Toggle & Intensity
        cameraBeautyBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val beautyProfiles = listOf("NORMAL", "BEAUTY", "BEAUTY_PEACH", "BEAUTY_PORCELAIN")
            val currIdx = beautyProfiles.indexOf(overlayView.filterStyle).coerceAtLeast(0)
            val nextIdx = (currIdx + 1) % beautyProfiles.size
            overlayView.filterStyle = beautyProfiles[nextIdx]
            if (overlayView.filterStyle != "NORMAL") {
                cameraBeautyBtn?.setTextColor(Color.parseColor("#FF69B4"))
                cameraBeautyBtn?.setBackgroundColor(Color.parseColor("#55FF69B4"))
            } else {
                cameraBeautyBtn?.setTextColor(Color.WHITE)
                cameraBeautyBtn?.setBackgroundResource(R.drawable.camera_glass_button_bg)
            }
            val msg = when (overlayView.filterStyle) {
                "BEAUTY" -> "✨ Beauty Radiance Soft Glow"
                "BEAUTY_PEACH" -> "🌸 Beauty Peach Blush Glow"
                "BEAUTY_PORCELAIN" -> "💎 Beauty Porcelain Smooth"
                else -> "✨ Beauty Filter OFF"
            }
            showSwipeToast(msg)
        }

        // 7. ▦ Rule-of-Thirds Grid Toggle
        val gridBtn = findViewById<TextView>(R.id.cameraGridBtn)
        gridBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            overlayView.isGridEnabled = !overlayView.isGridEnabled
            if (overlayView.isGridEnabled) {
                gridBtn.setTextColor(Color.parseColor("#00F2FE"))
                gridBtn.setBackgroundColor(Color.parseColor("#5500F2FE"))
            } else {
                gridBtn.setTextColor(Color.WHITE)
                gridBtn.setBackgroundResource(R.drawable.camera_glass_button_bg)
            }
            showSwipeToast(if (overlayView.isGridEnabled) "▦ 3x3 Grid ON" else "▦ Grid OFF")
        }
    }

    private fun setTorchMode(enable: Boolean) {
        if (!isFrontFacing) {
            val builder = previewRequestBuilder ?: return
            val session = captureSession ?: return
            try {
                builder.set(CaptureRequest.FLASH_MODE, if (enable) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle torch", e)
            }
        }
    }

    private fun setupTopBarControls() {
        // Handled via setupVerticalToolRail()
    }

    private fun showMusicSelectionDialog() {
        val tracks = listOf(
            Triple("None / Mute BGM 🔇", null, false),
            Triple("Attitude Bass Drop 💥", "music_bgm/attitude_bass_drop.wav", true),
            Triple("Energetic Trap Beat ⚡", "music_bgm/energetic_reels_trap_beat.wav", true),
            Triple("Romantic Acoustic Love 💖", "music_bgm/romantic_acoustic_love.wav", true),
            Triple("Lo-Fi Chill Beat ☕", "music_bgm/lofi_chill_vlog_beat.wav", true),
            Triple("Devotional Ambient Flute 🕉️", "music_bgm/devotional_ambient_flute.wav", true),
            Triple("Emotional Sad Piano 🥺", "music_bgm/sad_piano_emotional.wav", true),
            Triple("Choose from Phone Storage... 📁", "CHOOSE_STORAGE", false)
        )

        val titles = tracks.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎵 Select Live BGM for Recording")
            .setItems(titles) { _, which ->
                val chosen = tracks[which]
                if (chosen.second == "CHOOSE_STORAGE") {
                    pickCameraAudioLauncher.launch("audio/*")
                } else if (chosen.second == null) {
                    setCameraBgm(null, null, false)
                } else {
                    setCameraBgm(chosen.second, chosen.first.substringBefore(" ").trim(), true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setCameraBgm(path: String?, title: String?, isAsset: Boolean) {
        activeBgmPath = path
        activeBgmTitle = title
        activeBgmIsAsset = isAsset

        val btn = cameraMusicBtn
        val activeBadge = cameraActiveSoundBadge
        if (path != null && title != null) {
            val shortTitle = if (title.length > 8) title.take(7) + "…" else title
            btn?.text = "🎵"
            btn?.setTextColor(Color.parseColor("#FF007F"))
            btn?.setBackgroundColor(Color.parseColor("#55FF007F"))
            activeBadge?.visibility = View.VISIBLE
            activeBadge?.text = "🎵 $shortTitle"
            showSwipeToast("🎵 Live BGM: $title")
        } else {
            btn?.text = "🎵"
            btn?.setTextColor(Color.WHITE)
            btn?.setBackgroundResource(R.drawable.camera_glass_button_bg)
            activeBadge?.visibility = View.GONE
            showSwipeToast("🎵 BGM Muted")
        }
    }

    private fun startBgmPlayback() {
        val path = activeBgmPath ?: return
        try {
            stopBgmPlayback()
            val player = MediaPlayer()
            if (activeBgmIsAsset) {
                assets.openFd(path).use { afd ->
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } else {
                player.setDataSource(path)
            }
            player.isLooping = true
            player.setVolume(1.0f, 1.0f)
            player.prepare()
            player.start()
            bgmPlayer = player
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start BGM playback", e)
        }
    }

    private fun stopBgmPlayback() {
        try {
            if (bgmPlayer?.isPlaying == true) {
                bgmPlayer?.stop()
            }
            bgmPlayer?.release()
        } catch (_: Exception) {}
        bgmPlayer = null
    }

    private fun updateTimerBtnUi() {
        val btn = cameraTimerBtn ?: return
        if (countdownSeconds > 0) {
            btn.text = "${countdownSeconds}s"
            btn.setTextColor(Color.parseColor("#FFD700"))
            btn.setBackgroundColor(Color.parseColor("#55FFD700"))
        } else {
            btn.text = "⏱️"
            btn.setTextColor(Color.WHITE)
            btn.setBackgroundResource(R.drawable.camera_glass_button_bg)
        }
    }

    private fun startCountdownTimer(seconds: Int, onFinish: () -> Unit) {
        if (isCountingDown) return
        isCountingDown = true
        var current = seconds

        countdownOverlay?.visibility = View.VISIBLE
        countdownOverlay?.setOnClickListener {
            cancelCountdownTimer()
        }

        fun tick() {
            if (!isCountingDown) return
            if (current > 0) {
                countdownText?.text = current.toString()
                countdownText?.scaleX = 0.4f
                countdownText?.scaleY = 0.4f
                countdownText?.alpha = 1.0f
                countdownText?.animate()
                    ?.scaleX(1.15f)
                    ?.scaleY(1.15f)
                    ?.setDuration(220L)
                    ?.withEndAction {
                        countdownText?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120L)?.start()
                    }
                    ?.start()

                try {
                    countdownOverlay?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playCountdownTickSound()
                } catch (_: Exception) {}

                current--
                countdownRunnable = Runnable { tick() }
                countdownHandler.postDelayed(countdownRunnable!!, 1000L)
            } else {
                isCountingDown = false
                countdownOverlay?.visibility = View.GONE
                onFinish()
            }
        }

        tick()
    }

    private fun cancelCountdownTimer() {
        if (!isCountingDown) return
        isCountingDown = false
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = null
        countdownOverlay?.visibility = View.GONE
        showSwipeToast("⏱️ Timer Cancelled")
    }

    private fun playCountdownTickSound() {
        try {
            assets.openFd("sound_effects/kenney_click1.wav").use { afd ->
                val player = MediaPlayer()
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                player.prepare()
                player.start()
                player.setOnCompletionListener { it.release() }
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViewfinderGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                overlayView.showFocusRing(e.x, e.y)
                triggerAutoFocus(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isRecordingVideo) {
                    overlayView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    flipCamera()
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(velocityX) > 350f) {
                    if (dx < 0 || velocityX < 0) {
                        switchToNextLens()
                    } else {
                        switchToPrevLens()
                    }
                    return true
                }
                return false
            }
        })

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newZoom = (currentZoomRatio * detector.scaleFactor).coerceIn(1.0f, maxDigitalZoom)
                applyZoom(newZoom)
                showZoomPill(newZoom)
                return true
            }
        })

        overlayView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                mainHandler.postDelayed({ zoomPill?.visibility = View.GONE }, 1200L)
            }
            true
        }
    }

    private fun switchToNextLens() {
        if (allLenses.isEmpty()) return
        val currIdx = allLenses.indexOfFirst { it.id == selectedLens?.id }.coerceAtLeast(0)
        val nextIdx = (currIdx + 1) % allLenses.size
        selectLensDirectly(allLenses[nextIdx])
    }

    private fun switchToPrevLens() {
        if (allLenses.isEmpty()) return
        val currIdx = allLenses.indexOfFirst { it.id == selectedLens?.id }.coerceAtLeast(0)
        val prevIdx = if (currIdx <= 0) allLenses.size - 1 else currIdx - 1
        selectLensDirectly(allLenses[prevIdx])
    }

    private fun selectLensDirectly(lens: LensItem) {
        selectedLens = lens
        overlayView.currentLens = lens
        updateLensBadge(lens)
        setupLensCarousel()
        showFloatingLensPill(lens)
    }

    private fun showFloatingLensPill(lens: LensItem) {
        val pill = floatingLensPill ?: swipeToast ?: return
        pill.text = "${lens.name} (${lens.nameHi})"
        pill.alpha = 0f
        pill.scaleX = 0.82f
        pill.scaleY = 0.82f
        pill.visibility = View.VISIBLE
        pill.animate()?.cancel()
        pill.animate()
            ?.alpha(1.0f)
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(180L)
            ?.withEndAction {
                pill.animate()
                    ?.alpha(0f)
                    ?.scaleX(0.92f)
                    ?.scaleY(0.92f)
                    ?.setStartDelay(1100L)
                    ?.setDuration(240L)
                    ?.withEndAction { pill.visibility = View.GONE }
                    ?.start()
            }
            ?.start()
    }

    private fun showSwipeToast(text: String) {
        val toast = swipeToast ?: return
        toast.text = text
        toast.visibility = View.VISIBLE
        toast.alpha = 1.0f
        toast.animate()?.cancel()
        toast.animate()
            ?.alpha(0f)
            ?.setStartDelay(1200L)
            ?.setDuration(400L)
            ?.withEndAction { toast.visibility = View.GONE }
            ?.start()
    }

    private fun showZoomPill(zoom: Float) {
        val pill = zoomPill ?: return
        pill.text = String.format(Locale.US, "%.1fx", zoom)
        pill.visibility = View.VISIBLE
    }

    private fun applyCurrentZoomToBuilder(builder: CaptureRequest.Builder) {
        val clampedZoom = currentZoomRatio.coerceIn(1.0f, maxDigitalZoom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsControlZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
        } else {
            val sensorRect = activeArraySize ?: Rect(0, 0, 1920, 1080)
            val cropW = (sensorRect.width() / clampedZoom).toInt()
            val cropH = (sensorRect.height() / clampedZoom).toInt()
            val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
            val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
        }
    }

    private fun applyZoom(zoomRatio: Float) {
        val clampedZoom = zoomRatio.coerceIn(1.0f, maxDigitalZoom)
        currentZoomRatio = clampedZoom
        currentZoom = clampedZoom
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            applyCurrentZoomToBuilder(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "applyZoom failed", e)
        }
    }

    private fun triggerAutoFocus(viewX: Float, viewY: Float) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val sensorRect = activeArraySize ?: Rect(0, 0, 1920, 1080)

        val viewW = if (textureView.width > 0) textureView.width.toFloat() else 1080f
        val viewH = if (textureView.height > 0) textureView.height.toFloat() else 1920f

        val normalizedX = (viewX / viewW).coerceIn(0f, 1f)
        val normalizedY = (viewY / viewH).coerceIn(0f, 1f)

        val sensorX = (normalizedX * sensorRect.width()).toInt()
        val sensorY = (normalizedY * sensorRect.height()).toInt()

        val halfSize = 120
        val focusArea = Rect(
            (sensorX - halfSize).coerceAtLeast(0),
            (sensorY - halfSize).coerceAtLeast(0),
            (sensorX + halfSize).coerceAtMost(sensorRect.width()),
            (sensorY + halfSize).coerceAtMost(sensorRect.height())
        )
        val meteringRect = MeteringRectangle(focusArea, MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            // Cancel previous AF
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            // Set region & start AF
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)

            // Reset AF trigger to IDLE
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "triggerAutoFocus failed", e)
        }
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
