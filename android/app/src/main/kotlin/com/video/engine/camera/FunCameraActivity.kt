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
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
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
        photoModeBtn = findViewById(R.id.cameraPhotoModeBtn)
        videoModeBtn = findViewById(R.id.cameraVideoModeBtn)
        shutterButton = findViewById(R.id.cameraShutterButton)
        floatingLensPill = findViewById(R.id.floatingLensPill)
        zoomPill = findViewById(R.id.cameraZoomPill)
        swipeToast = findViewById(R.id.cameraLensSwipeToast)

        findViewById<FrameLayout>(R.id.cameraBackBtn)?.setOnClickListener { finish() }

        findViewById<FrameLayout>(R.id.cameraFlipBtn)?.setOnClickListener {
            if (!isRecordingVideo) {
                flipCamera()
            }
        }

        setupTopBarControls()
        setupViewfinderGestures()
        setupModeSelector()
        setupShutterControl()

        // Load lenses
        allLenses = LensRepository.getLenses(this)
        selectedLens = allLenses.firstOrNull { it.id == "beauty" } ?: allLenses.firstOrNull()
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
            photoModeBtn.setTextColor(Color.parseColor("#388BFD"))
            photoModeBtn.setBackgroundResource(R.drawable.launch_count_chip_background)
            videoModeBtn.setTextColor(Color.parseColor("#8A99AD"))
            videoModeBtn.background = null
        } else {
            videoModeBtn.setTextColor(Color.parseColor("#FF4444"))
            videoModeBtn.setBackgroundResource(R.drawable.launch_count_chip_background)
            photoModeBtn.setTextColor(Color.parseColor("#8A99AD"))
            photoModeBtn.background = null
        }
    }

    private fun setupShutterControl() {
        shutterButton.maxRecordingDurationMs = 15_000L

        shutterButton.onPhotoCapture = {
            capturePhoto()
        }

        shutterButton.onRecordStart = {
            startVideoRecording()
        }

        shutterButton.onRecordStop = {
            stopVideoRecording()
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission needed for video audio", Toast.LENGTH_SHORT).show()
        }

        if (!prepareMediaRecorder()) {
            Toast.makeText(this, "Failed to start video recorder", Toast.LENGTH_SHORT).show()
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
                    captureSession = session
                    try {
                        session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler)
                        mediaRecorder?.start()
                        isRecordingVideo = true
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
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configure recording session failed")
                    runOnUiThread {
                        if (shutterButton.isRecording) {
                            shutterButton.stopRecording()
                        }
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "startVideoRecording error", e)
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
            try {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            } catch (_: Exception) {}
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(currentVideoFile!!.absolutePath)
            mr.setVideoEncodingBitRate(8_000_000)
            mr.setVideoFrameRate(30)
            mr.setVideoSize(720, 1280) // 720p 9:16 vertical video for maximum hardware compatibility
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            try {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            } catch (_: Exception) {}

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
            false
        }
    }

    private fun stopVideoRecording() {
        if (!isRecordingVideo) return
        isRecordingVideo = false
        stopRecordingTimer()
        if (shutterButton.isRecording) {
            shutterButton.stopRecording()
        }

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder", e)
        }

        setRecordingUiState(false)
        createCameraPreview()

        val recorded = currentVideoFile
        if (recorded != null && recorded.exists() && recorded.length() > 1024) {
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

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Storyline_Camera")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(videoFile).use { it.copyTo(out) }
                    }
                }
            }
            videoFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun showVideoSavedDialog(videoFile: File) {
        val options = arrayOf(
            "Watch Video 👁️",
            "Edit in Storyline Video Editor ✂️",
            "Make Status & Reels ⚡",
            "Share on WhatsApp / Instagram 🚀",
            "Take Another 📸"
        )
        AlertDialog.Builder(this)
            .setTitle("Video Saved! 🎬")
            .setMessage("Saved in HD to your phone gallery (Movies/Storyline_Camera).")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", videoFile)
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(viewIntent, "Play Video"))
                    }
                    1 -> {
                        val nextIntent = com.video.engine.EditorLaunchIntents.markForEditorBoot(
                            this,
                            Intent(this, com.video.engine.VideoEditorActivity::class.java)
                        ).putExtra(
                            com.video.engine.EditorLaunchIntents.EXTRA_START_SHELL_ACTION,
                            com.video.engine.EditorLaunchIntents.START_ACTION_IMPORT
                        ).putExtra(
                            com.video.engine.EditorLaunchIntents.EXTRA_START_MEDIA_PATH,
                            videoFile.absolutePath
                        )
                        startActivity(nextIntent)
                    }
                    2 -> {
                        startActivity(Intent(this, StatusMakerActivity::class.java))
                    }
                    3 -> {
                        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", videoFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Fun Video"))
                    }
                    4 -> { /* Resume camera */ }
                }
            }
            .setCancelable(true)
            .show()
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
                    val circleSize = if (isSelected) 58.dpToPx(context) else 50.dpToPx(context)
                    layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                        bottomMargin = 4.dpToPx(context)
                    }

                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (isSelected) {
                            setColor(Color.parseColor("#44388BFD"))
                            setStroke(3.dpToPx(context), Color.parseColor("#388BFD"))
                        } else {
                            setColor(Color.parseColor("#44000000"))
                            setStroke(1.dpToPx(context), Color.parseColor("#44FFFFFF"))
                        }
                    }
                    background = bg

                    val iconPath = if (lens.iconAssetPath.isNotEmpty()) lens.iconAssetPath else lens.assetPath
                    if (iconPath.isNotEmpty()) {
                        val iconIv = ImageView(context).apply {
                            val imgSize = if (isSelected) 42.dpToPx(context) else 36.dpToPx(context)
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
                    textSize = 10f
                    setTextColor(if (isSelected) Color.parseColor("#388BFD") else Color.parseColor("#9AA4B2"))
                    gravity = android.view.Gravity.CENTER
                }

                addView(circleFrame)
                addView(labelTv)

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectedLens = lens
                    overlayView.currentLens = lens
                    updateLensBadge(lens)
                    setupLensCarousel()
                }
            }

            filterContainer.addView(lensItemView)
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
        val fileName = "Storyline_Fun_${timeStamp}.jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Storyline_Camera")
                }
                val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
            }
            // Also store a reliable local file copy
            val cameraDir = File(getExternalFilesDir(null), "fun_photos").also { it.mkdirs() }
            val localFile = File(cameraDir, fileName)
            FileOutputStream(localFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            localFile
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo", e)
            null
        }
    }

    private fun showPhotoSavedDialog(photoFile: File) {
        val options = arrayOf(
            "Take Another 📸",
            "Edit in Storyline Video Editor ✂️",
            "Edit in Photo Editor 🎨",
            "Make Status Video ⚡",
            "Share Photo 🚀"
        )
        AlertDialog.Builder(this)
            .setTitle("Photo Saved! 🎉")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { /* Resume camera */ }
                    1 -> {
                        val nextIntent = com.video.engine.EditorLaunchIntents.markForEditorBoot(
                            this,
                            Intent(this, com.video.engine.VideoEditorActivity::class.java)
                        ).putExtra(
                            com.video.engine.EditorLaunchIntents.EXTRA_START_SHELL_ACTION,
                            com.video.engine.EditorLaunchIntents.START_ACTION_IMPORT
                        ).putExtra(
                            com.video.engine.EditorLaunchIntents.EXTRA_START_MEDIA_PATH,
                            photoFile.absolutePath
                        )
                        startActivity(nextIntent)
                    }
                    2 -> {
                        val intent = Intent(this, PhotoEditorActivity::class.java).apply {
                            putExtra(PhotoEditorActivity.EXTRA_RESUME_PHOTO, false)
                            putExtra(PhotoEditorActivity.EXTRA_PHOTO_PROJECT_PATH, photoFile.absolutePath)
                        }
                        startActivity(intent)
                    }
                    3 -> {
                        startActivity(Intent(this, StatusMakerActivity::class.java))
                    }
                    4 -> {
                        val uri = FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            photoFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Fun Photo"))
                    }
                }
            }
            .setCancelable(true)
            .show()
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
        if (overlayView.isNightFlashEnabled) {
            val params = window.attributes
            params.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
        faceTracker.release()
        super.onDestroy()
    }

    private fun setupTopBarControls() {
        findViewById<View>(R.id.cameraNightFlashBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val newState = !overlayView.isNightFlashEnabled
            overlayView.isNightFlashEnabled = newState
            it.alpha = if (newState) 1.0f else 0.5f

            // Front Screen Night Flash brightness integration
            val windowParams = window.attributes
            if (newState) {
                previousScreenBrightness = windowParams.screenBrightness
                windowParams.screenBrightness = 1.0f
            } else {
                windowParams.screenBrightness = if (previousScreenBrightness >= 0f) previousScreenBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = windowParams

            val msg = if (newState) "💡 Night Ring Light ON" else "Night Ring Light OFF"
            showSwipeToast(msg)
        }

        findViewById<View>(R.id.cameraGridBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            overlayView.isGridEnabled = !overlayView.isGridEnabled
            it.alpha = if (overlayView.isGridEnabled) 1.0f else 0.5f
            showSwipeToast(if (overlayView.isGridEnabled) "▦ 3x3 Grid ON" else "▦ Grid OFF")
        }

        findViewById<View>(R.id.cameraFilterStyleBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val styles = listOf("NORMAL", "BEAUTY", "VHS_90S", "CYBERPUNK", "WARM_SUNSET")
            val currIdx = styles.indexOf(overlayView.filterStyle).coerceAtLeast(0)
            val nextIdx = (currIdx + 1) % styles.size
            overlayView.filterStyle = styles[nextIdx]
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
