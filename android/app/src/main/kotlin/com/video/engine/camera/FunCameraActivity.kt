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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        warmAmberFlashView = findViewById(R.id.cameraWarmAmberFlash)
        countdownOverlay = findViewById(R.id.cameraCountdownOverlay)
        countdownText = findViewById(R.id.cameraCountdownText)

        findViewById<FrameLayout>(R.id.cameraBackBtn)?.setOnClickListener { finish() }

        findViewById<FrameLayout>(R.id.cameraFlipBtn)?.setOnClickListener {
            if (!isRecordingVideo) {
                it.animate().rotationBy(180f).setDuration(280L).start()
                flipCamera()
            }
        }

        setupTopBarControls()
        setupViewfinderGestures()
        setupModeSelector()
        setupShutterControl()

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
        shutterButton.maxRecordingDurationMs = 15_000L

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

        shutterButton.onRecordLock = {
            showSwipeToast("🔒 Recording Locked")
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
                    showVideoSavedDialog(saved ?: recorded)
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
        showPostCaptureModal(videoFile, isVideo = true)
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

        val filteredLenses = if (currentCategory == "ALL") {
            allLenses
        } else {
            allLenses.filter { it.category == currentCategory || it.category == "ALL" }
        }

        filteredLenses.forEach { lens ->
            val isSelected = lens.id == selectedLens?.id

            val lensItemView = LinearLayout(this).apply {
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
                    showPhotoSavedDialog(savedFile)
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
        showPostCaptureModal(photoFile, isVideo = false)
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

    private fun showPostCaptureModal(mediaFile: File, isVideo: Boolean) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(context), 24.dpToPx(context), 24.dpToPx(context), 24.dpToPx(context))
            background = GradientDrawable().apply {
                cornerRadius = 24.dpToPx(context).toFloat()
                setColor(Color.parseColor("#1B212C"))
                setStroke(1.dpToPx(context), Color.parseColor("#33FFFFFF"))
            }
        }

        // Title
        val titleTv = TextView(this).apply {
            text = if (isVideo) "🎬 Video Recorded in HD!" else "🎉 Studio Photo Captured!"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        dialogView.addView(titleTv)

        // Subtitle
        val subTv = TextView(this).apply {
            text = "Saved to Gallery. Choose instant action:"
            textSize = 13f
            setTextColor(Color.parseColor("#8E9AA8"))
            setPadding(0, 4.dpToPx(context), 0, 18.dpToPx(context))
        }
        dialogView.addView(subTv)

        var dialog: AlertDialog? = null

        // 1. Primary Action: Make Status Video (Instant 9:16 Live Preview)
        val makeStatusBtn = TextView(this).apply {
            text = "⚡ Make Status Video (9:16 Live Preview)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(context), 14.dpToPx(context), 16.dpToPx(context), 14.dpToPx(context))
            val bg = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#FF512F"), Color.parseColor("#DD2476"))
            ).apply {
                cornerRadius = 14.dpToPx(context).toFloat()
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog?.dismiss()
                val statusIntent = Intent(this@FunCameraActivity, StatusMakerActivity::class.java).apply {
                    if (!isVideo) {
                        putExtra(StatusMakerActivity.EXTRA_INITIAL_PHOTO_PATH, mediaFile.absolutePath)
                    }
                }
                startActivity(statusIntent)
            }
        }
        dialogView.addView(makeStatusBtn)

        // 2. Secondary Action: Edit in Storyline
        val editStorylineBtn = TextView(this).apply {
            text = "✂️ Edit in Storyline Video Editor"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(context), 14.dpToPx(context), 16.dpToPx(context), 14.dpToPx(context))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dpToPx(context)
            }
            layoutParams = params
            val bg = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#0052D4"), Color.parseColor("#4364F7"))
            ).apply {
                cornerRadius = 14.dpToPx(context).toFloat()
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog?.dismiss()
                val nextIntent = com.video.engine.EditorLaunchIntents.markForEditorBoot(
                    this@FunCameraActivity,
                    Intent(this@FunCameraActivity, com.video.engine.VideoEditorActivity::class.java)
                ).putExtra(
                    com.video.engine.EditorLaunchIntents.EXTRA_START_SHELL_ACTION,
                    com.video.engine.EditorLaunchIntents.START_ACTION_IMPORT
                ).putExtra(
                    com.video.engine.EditorLaunchIntents.EXTRA_START_MEDIA_PATH,
                    mediaFile.absolutePath
                )
                startActivity(nextIntent)
            }
        }
        dialogView.addView(editStorylineBtn)

        // Row of 2 buttons: (Photo Editor / Play) + Share
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dpToPx(context)
            }
            layoutParams = params
        }

        val extraBtn = TextView(this).apply {
            text = if (isVideo) "👁️ Play Video" else "🎨 Photo FX"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D0D8E0"))
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6.dpToPx(context)
            }
            layoutParams = params
            val bg = GradientDrawable().apply {
                cornerRadius = 12.dpToPx(context).toFloat()
                setColor(Color.parseColor("#262C38"))
                setStroke(1.dpToPx(context), Color.parseColor("#33FFFFFF"))
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog?.dismiss()
                if (isVideo) {
                    val uri = FileProvider.getUriForFile(
                        this@FunCameraActivity,
                        "${applicationContext.packageName}.fileprovider",
                        mediaFile
                    )
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(viewIntent, "Play Video"))
                } else {
                    val intent = Intent(this@FunCameraActivity, PhotoEditorActivity::class.java).apply {
                        putExtra(PhotoEditorActivity.EXTRA_RESUME_PHOTO, false)
                        putExtra(PhotoEditorActivity.EXTRA_PHOTO_PROJECT_PATH, mediaFile.absolutePath)
                    }
                    startActivity(intent)
                }
            }
        }
        row.addView(extraBtn)

        val shareBtn = TextView(this).apply {
            text = "🚀 Share"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D0D8E0"))
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context))
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6.dpToPx(context)
            }
            layoutParams = params
            val bg = GradientDrawable().apply {
                cornerRadius = 12.dpToPx(context).toFloat()
                setColor(Color.parseColor("#262C38"))
                setStroke(1.dpToPx(context), Color.parseColor("#33FFFFFF"))
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog?.dismiss()
                val mime = if (isVideo) "video/mp4" else "image/jpeg"
                val uri = FileProvider.getUriForFile(
                    this@FunCameraActivity,
                    "${applicationContext.packageName}.fileprovider",
                    mediaFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share"))
            }
        }
        row.addView(shareBtn)
        dialogView.addView(row)

        // Dismiss / Retake button
        val retakeBtn = TextView(this).apply {
            text = "📸 Take Another"
            textSize = 13f
            setTextColor(Color.parseColor("#8E9AA8"))
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context), 8.dpToPx(context))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx(context)
            }
            layoutParams = params
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog?.dismiss()
            }
        }
        dialogView.addView(retakeBtn)

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
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

    override fun onDestroy() {
        cancelCountdownTimer()
        stopBgmPlayback()
        if (overlayView.isNightFlashEnabled) {
            val params = window.attributes
            params.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
        faceTracker.release()
        super.onDestroy()
    }

    private fun setupTopBarControls() {
        // 1. Live BGM Music Toggle & Picker
        cameraMusicBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showMusicSelectionDialog()
        }

        // 2. Hands-Free Countdown Timer Toggle (Off -> 3s -> 10s -> Off)
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

        val nightFlashBtn = findViewById<TextView>(R.id.cameraNightFlashBtn)
        nightFlashBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val newState = !overlayView.isNightFlashEnabled
            overlayView.isNightFlashEnabled = newState

            // Front Screen Warm Selfie Glow brightness integration
            val windowParams = window.attributes
            if (newState) {
                previousScreenBrightness = windowParams.screenBrightness
                windowParams.screenBrightness = 1.0f
                nightFlashBtn.setTextColor(Color.parseColor("#FFD700"))
                nightFlashBtn.setBackgroundColor(Color.parseColor("#55FFD700"))
            } else {
                windowParams.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                nightFlashBtn.setTextColor(Color.WHITE)
                nightFlashBtn.setBackgroundResource(R.drawable.camera_glass_button_bg)
            }
            window.attributes = windowParams

            val msg = if (newState) {
                if (isFrontFacing) "💡 Warm Amber Selfie Glow ON" else "💡 Night Fill Light ON"
            } else {
                "💡 Night Light OFF"
            }
            showSwipeToast(msg)
        }

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

        val styleBtn = findViewById<TextView>(R.id.cameraFilterStyleBtn)
        styleBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val styles = listOf("NORMAL", "BEAUTY", "VHS_90S", "CYBERPUNK", "WARM_SUNSET")
            val currIdx = styles.indexOf(overlayView.filterStyle).coerceAtLeast(0)
            val nextIdx = (currIdx + 1) % styles.size
            overlayView.filterStyle = styles[nextIdx]
            styleBtn.text = when (styles[nextIdx]) {
                "BEAUTY" -> "🌸 BEAUTY"
                "VHS_90S" -> "📼 VHS"
                "CYBERPUNK" -> "⚡ CYBER"
                "WARM_SUNSET" -> "🌅 SUNSET"
                else -> "🎨 FX"
            }
            val labels = mapOf(
                "NORMAL" to "🎨 Normal Lens",
                "BEAUTY" to "🌸 Beauty Soft Glow",
                "VHS_90S" to "📼 90s VHS Camcorder",
                "CYBERPUNK" to "⚡ Cyberpunk Neon",
                "WARM_SUNSET" to "🌅 Golden Sunset"
            )
            showSwipeToast(labels[overlayView.filterStyle] ?: overlayView.filterStyle)
        }
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

        val btn = cameraMusicBtn ?: return
        if (path != null && title != null) {
            val shortTitle = if (title.length > 8) title.take(7) + "…" else title
            btn.text = "🎵 $shortTitle"
            btn.setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                cornerRadius = 19.dpToPx(this@FunCameraActivity).toFloat()
                setColor(Color.parseColor("#FF1053")) // Vibrant energetic pink/red BGM badge
            }
            btn.background = bg
            showSwipeToast("🎵 Live BGM: $title")
        } else {
            btn.text = "🎵 Music"
            btn.setTextColor(Color.WHITE)
            btn.setBackgroundResource(R.drawable.camera_mode_pill_bg)
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
            btn.text = "⏱️ ${countdownSeconds}s"
            btn.setTextColor(Color.parseColor("#FFD700"))
            val bg = GradientDrawable().apply {
                cornerRadius = 19.dpToPx(this@FunCameraActivity).toFloat()
                setColor(Color.parseColor("#44FFD700"))
                setStroke(1.dpToPx(this@FunCameraActivity), Color.parseColor("#FFD700"))
            }
            btn.background = bg
        } else {
            btn.text = "⏱️ Off"
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
