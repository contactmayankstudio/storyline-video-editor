package com.video.engine.status

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.video.engine.EditorLaunchIntents
import com.video.engine.R
import com.video.engine.VideoEditorActivity
import com.video.engine.audio.AudioExtractorHelper
import com.video.engine.VideoPreviewView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusQuickExportActivity : ComponentActivity() {

    companion object {
        private const val TAG = "StatusQuickExport"
        const val EXTRA_PROJECT_PATH = "extra_status_project_path"
        const val EXTRA_TEMPLATE_ID = "extra_status_template_id"
        const val EXTRA_TEMPLATE_TITLE = "extra_status_template_title"
        const val EXTRA_TEMPLATE_DURATION_MS = "extra_status_template_duration_ms"
        const val EXTRA_TEMPLATE_GRADIENT_START = "extra_status_gradient_start"
        const val EXTRA_TEMPLATE_GRADIENT_END = "extra_status_gradient_end"
        const val EXTRA_PHOTO_PATHS = "extra_status_photo_paths"
        const val EXTRA_CUSTOM_TEXT = "extra_status_custom_text"
        const val EXTRA_CUSTOM_AUDIO_PATH = "extra_status_custom_audio_path"
    }

    private var projectPath: String? = null
    private var templateId: String? = null
    private var templateTitle: String = "Status Video"
    private var durationMs: Long = 12000L
    private var gradientStart: String = "#FF416C"
    private var gradientEnd: String = "#8A2387"
    private var photoPaths: ArrayList<String> = arrayListOf()
    private var customText: String? = null
    private var is1080pSelected: Boolean = true

    private var currentTemplate: StatusTemplate? = null
    private var currentAudioPath: String? = null
    private var currentAudioTitle: String = "Energetic Reels Trap Beat"

    private lateinit var previewOuterContainer: FrameLayout
    private lateinit var previewContainer: FrameLayout
    private lateinit var playPauseIcon: ImageView
    private lateinit var exportProgressOverlay: FrameLayout
    private lateinit var exportProgressBar: ProgressBar
    private lateinit var exportPercentText: TextView
    private lateinit var musicCard: LinearLayout
    private lateinit var musicTitleText: TextView
    private lateinit var musicEmojiText: TextView

    private var previewView: VideoPreviewView? = null
    private var livePreviewView: StatusTemplateLivePreviewView? = null
    private var previewMediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isExporting = false
    private var isProjectLoaded = false
    private val handler = Handler(Looper.getMainLooper())
    private var progressCheckRunnable: Runnable? = null

    // Audio picker contract for custom songs from device storage
    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val copied = StatusMediaHelper.copyAudioUriToLocal(this, uri)
        if (copied != null) {
            onNewMusicSelected(copied.first, copied.second, "📁")
        } else {
            Toast.makeText(this, "Could not load selected audio file", Toast.LENGTH_SHORT).show()
        }
    }

    // Video picker contract for extracting audio from Reel / Video
    private val pickVideoForAudioExtractionLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "⏳ Extracting audio from video...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = AudioExtractorHelper.extractAudioFromVideoUri(this, uri)
            runOnUiThread {
                if (result != null && result.file.exists()) {
                    onNewMusicSelected(result.file.absolutePath, "Reel Audio 🎵", "🎵")
                    Toast.makeText(this, "🎵 Audio extracted & applied!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not extract audio from this video", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_quick_export)

        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)
        templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
        templateTitle = intent.getStringExtra(EXTRA_TEMPLATE_TITLE) ?: "Status Video"
        durationMs = intent.getLongExtra(EXTRA_TEMPLATE_DURATION_MS, 12000L)
        gradientStart = intent.getStringExtra(EXTRA_TEMPLATE_GRADIENT_START) ?: "#FF416C"
        gradientEnd = intent.getStringExtra(EXTRA_TEMPLATE_GRADIENT_END) ?: "#8A2387"
        photoPaths = intent.getStringArrayListExtra(EXTRA_PHOTO_PATHS) ?: arrayListOf()
        customText = intent.getStringExtra(EXTRA_CUSTOM_TEXT)

        val passedAudioPath = intent.getStringExtra(EXTRA_CUSTOM_AUDIO_PATH)
        if (!passedAudioPath.isNullOrBlank() && File(passedAudioPath).exists()) {
            currentAudioPath = passedAudioPath
        }

        if (templateId != null) {
            currentTemplate = StatusTemplateRepository.getById(templateId!!)
        }
        if (currentTemplate == null) {
            currentTemplate = StatusTemplateRepository.allTemplates.firstOrNull { it.title.equals(templateTitle, ignoreCase = true) }
                ?: StatusTemplateRepository.allTemplates.firstOrNull()
        }

        val template = currentTemplate
        if (template != null) {
            val soundtrack = StatusSoundtrackRepository.getByAssetPath(template.audioAssetPath)
            currentAudioTitle = soundtrack?.title ?: "Original Soundtrack"
            val localAudio = StatusMediaHelper.extractAudioAssetToLocal(this, template.audioAssetPath)
            currentAudioPath = localAudio?.absolutePath
        }

        if (projectPath == null || !File(projectPath!!).exists()) {
            Toast.makeText(this, "Status project file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<View>(R.id.quickExportRoot)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                findViewById<View>(R.id.quickExportTopBar)?.setPadding(
                    16.dpToPx(this),
                    systemBars.top + 8.dpToPx(this),
                    16.dpToPx(this),
                    12.dpToPx(this)
                )
                findViewById<View>(R.id.quickExportBottomBar)?.setPadding(
                    20.dpToPx(this),
                    14.dpToPx(this),
                    20.dpToPx(this),
                    systemBars.bottom + 16.dpToPx(this)
                )
                insets
            }
        }

        findViewById<TextView>(R.id.quickExportTitle)?.text = templateTitle
        findViewById<TextView>(R.id.quickExportDurationBadge)?.text = "${durationMs / 1000}s HD"
        findViewById<FrameLayout>(R.id.quickExportBackBtn)?.setOnClickListener { finish() }

        previewOuterContainer = findViewById(R.id.previewOuterContainer)
        previewContainer = findViewById(R.id.previewSurfaceContainer)
        playPauseIcon = findViewById(R.id.quickExportPlayPauseIcon)
        exportProgressOverlay = findViewById(R.id.exportProgressOverlay)
        exportProgressBar = findViewById(R.id.exportProgressBar)
        exportPercentText = findViewById(R.id.exportPercentText)
        musicCard = findViewById(R.id.quickExportMusicCard)
        musicTitleText = findViewById(R.id.quickExportMusicTitle)
        musicEmojiText = findViewById(R.id.quickExportMusicEmoji)

        musicTitleText.text = currentAudioTitle

        // Apply template gradient to Export Button
        try {
            val btnBg = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(gradientStart), Color.parseColor(gradientEnd))
            ).apply {
                cornerRadius = 14f * resources.displayMetrics.density
            }
            findViewById<FrameLayout>(R.id.quickExportButton)?.background = btnBg
        } catch (_: Exception) {}

        // Enforce 9:16 Aspect Ratio container
        previewOuterContainer.post {
            adjustPreviewAspectRatio()
        }

        setupPreviewView()
        setupMusicCard()

        // Setup Export Quality Selector Chips (1080p Full HD vs 720p Fast)
        val quality1080pBtn = findViewById<FrameLayout>(R.id.quickExportQuality1080pBtn)
        val quality1080pText = findViewById<TextView>(R.id.quickExportQuality1080pText)
        val quality720pBtn = findViewById<FrameLayout>(R.id.quickExportQuality720pBtn)
        val quality720pText = findViewById<TextView>(R.id.quickExportQuality720pText)

        fun updateQualityUi() {
            val density = resources.displayMetrics.density
            if (is1080pSelected) {
                quality1080pBtn?.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1F385C"))
                    cornerRadius = 16 * density
                    setStroke((1.5f * density).toInt(), Color.parseColor("#388BFD"))
                }
                quality1080pText?.setTextColor(Color.parseColor("#58A6FF"))

                quality720pBtn?.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#161B22"))
                    cornerRadius = 16 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
                }
                quality720pText?.setTextColor(Color.parseColor("#8A99AD"))
            } else {
                quality720pBtn?.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1F385C"))
                    cornerRadius = 16 * density
                    setStroke((1.5f * density).toInt(), Color.parseColor("#388BFD"))
                }
                quality720pText?.setTextColor(Color.parseColor("#58A6FF"))

                quality1080pBtn?.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#161B22"))
                    cornerRadius = 16 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
                }
                quality1080pText?.setTextColor(Color.parseColor("#8A99AD"))
            }
        }
        updateQualityUi()

        quality1080pBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            is1080pSelected = true
            updateQualityUi()
            Toast.makeText(this, "💎 1080p Full HD Export Selected", Toast.LENGTH_SHORT).show()
        }

        quality720pBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            is1080pSelected = false
            updateQualityUi()
            Toast.makeText(this, "⚡ 720p Fast Export Selected", Toast.LENGTH_SHORT).show()
        }

        // 1-Click Export click
        findViewById<FrameLayout>(R.id.quickExportButton)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startOneClickExport()
        }

        // Open in Advanced Editor
        findViewById<TextView>(R.id.openAdvancedEditorBtn)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openInTimelineEditor()
        }
    }

    private fun adjustPreviewAspectRatio() {
        val outerW = previewOuterContainer.width
        val outerH = previewOuterContainer.height
        if (outerW <= 0 || outerH <= 0) return

        // Desired ratio: 9 width to 16 height (0.5625)
        var targetW = (outerH * 9f / 16f).toInt()
        var targetH = outerH
        if (targetW > outerW) {
            targetW = outerW
            targetH = (outerW * 16f / 9f).toInt()
        }

        val lp = FrameLayout.LayoutParams(targetW, targetH).apply {
            gravity = Gravity.CENTER
        }
        previewContainer.layoutParams = lp
    }

    private fun setupPreviewView() {
        val pv = VideoPreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewView = pv
        previewContainer.addView(pv, 0)

        // Instant Live Canvas Preview on top with Ken Burns, beat pulse, and visualizer!
        val lpv = StatusTemplateLivePreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        livePreviewView = lpv
        if (!customText.isNullOrBlank()) {
            lpv.customStatusText = customText
        }
        currentTemplate?.let { tm ->
            lpv.bindTemplate(tm, previewMediaPlayer)
            if (photoPaths.isNotEmpty()) {
                lpv.setCustomPhoto(photoPaths[0])
            }
        }
        previewContainer.addView(lpv)

        pv.onSurfaceReady = {
            runOnUiThread {
                loadStatusProject(attempt = 0)
            }
        }

        // Tap on preview toggles playback
        previewContainer.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            togglePlayback()
        }

        // Trigger load with retry
        handler.postDelayed({
            loadStatusProject(attempt = 0)
        }, 150)
    }

    private fun loadStatusProject(attempt: Int = 0) {
        val pv = previewView ?: return
        val path = projectPath ?: return

        if (!pv.ensureNativeSurfaceBinding()) {
            if (attempt < 20) {
                handler.postDelayed({ loadStatusProject(attempt + 1) }, 100)
            }
            return
        }

        Thread {
            try {
                val success = pv.loadProject(path)
                runOnUiThread {
                    if (success) {
                        isProjectLoaded = true
                        pv.seekToTime(0L)
                        startPlaybackInternal()
                    } else if (attempt < 15) {
                        handler.postDelayed({ loadStatusProject(attempt + 1) }, 200)
                    } else {
                        Toast.makeText(this, "Could not load preview", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading status project", e)
            }
        }.start()
    }

    private fun setupMusicCard() {
        val onMusicClick = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showMusicSelectionDialog()
        }
        musicCard.setOnClickListener(onMusicClick)
        findViewById<View>(R.id.quickExportChangeMusicBtn)?.setOnClickListener(onMusicClick)
    }

    private fun showMusicSelectionDialog() {
        val presets = StatusSoundtrackRepository.presets
        val items = mutableListOf<String>()
        items.add("🎵  Extract Audio from Video / Reel...")
        items.add("📁  Choose from Device Storage...")
        presets.forEach { s ->
            items.add("${s.emoji}  ${s.title} (${s.category})")
        }

        AlertDialog.Builder(this)
            .setTitle("🎵 Change Background Music")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        // Extract from video / reel
                        pickVideoForAudioExtractionLauncher.launch("video/*")
                    }
                    1 -> {
                        // Launch system audio picker
                        pickAudioLauncher.launch("audio/*")
                    }
                    else -> {
                        val selectedSoundtrack = presets[which - 2]
                        val localAudio = StatusMediaHelper.extractAudioAssetToLocal(this, selectedSoundtrack.assetPath)
                        if (localAudio != null && localAudio.exists()) {
                            onNewMusicSelected(
                                audioPath = localAudio.absolutePath,
                                title = selectedSoundtrack.title,
                                emoji = selectedSoundtrack.emoji
                            )
                        } else {
                            Toast.makeText(this, "Could not load preset soundtrack", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onNewMusicSelected(audioPath: String, title: String, emoji: String) {
        currentAudioPath = audioPath
        currentAudioTitle = title
        musicTitleText.text = title
        musicEmojiText.text = emoji

        val template = currentTemplate ?: return
        if (photoPaths.isEmpty()) return

        Toast.makeText(this, "Applying music: $title 🎵", Toast.LENGTH_SHORT).show()

        Thread {
            val updatedProject = StatusProjectBuilder.buildProjectFile(
                context = this,
                template = template,
                photoPaths = photoPaths,
                customAudioPath = audioPath,
                customText = customText
            )

            runOnUiThread {
                if (updatedProject != null && updatedProject.exists()) {
                    projectPath = updatedProject.absolutePath
                    // Reload audio player
                    stopAudioPlayback()
                    setupAudioPlayer(audioPath)
                    // Reload preview
                    previewView?.loadProject(updatedProject.absolutePath)
                    startPlaybackInternal()
                }
            }
        }.start()
    }

    private fun setupAudioPlayer(audioPath: String) {
        stopAudioPlayback()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(audioPath)
                prepare()
                isLooping = true
            }
            previewMediaPlayer = mp
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting up audio player: ${e.message}")
        }
    }

    private fun startAudioPlayback() {
        try {
            if (previewMediaPlayer == null && currentAudioPath != null) {
                setupAudioPlayer(currentAudioPath!!)
            }
            previewMediaPlayer?.let { mp ->
                if (!mp.isPlaying) {
                    mp.seekTo(0)
                    mp.start()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting audio playback", e)
        }
    }

    private fun pauseAudioPlayback() {
        try {
            previewMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing audio playback", e)
        }
    }

    private fun stopAudioPlayback() {
        try {
            previewMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio player", e)
        } finally {
            previewMediaPlayer = null
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            pausePlaybackInternal()
        } else {
            startPlaybackInternal()
        }
    }

    private fun startPlaybackInternal() {
        val pv = previewView ?: return
        pv.startPlayback(0)
        isPlaying = true
        playPauseIcon.visibility = View.GONE
        startAudioPlayback()
    }

    private fun pausePlaybackInternal() {
        val pv = previewView ?: return
        pv.stopPlayback()
        isPlaying = false
        playPauseIcon.visibility = View.VISIBLE
        pauseAudioPlayback()
    }

    private fun startOneClickExport() {
        if (isExporting) return
        isExporting = true

        pausePlaybackInternal()
        stopAudioPlayback()

        exportProgressOverlay.visibility = View.VISIBLE
        exportProgressBar.progress = 0
        val qualityLabel = if (is1080pSelected) "1080p Full HD" else "720p Fast"
        exportPercentText.text = "Exporting Status Video ($qualityLabel)... 0%"
        livePreviewView?.visibility = View.GONE

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = templateTitle.replace(Regex("[^a-zA-Z0-9]"), "_")
        val exportDir = File(cacheDir, "status_exports").also { it.mkdirs() }
        val exportFile = File(exportDir, "Status_${safeTitle}_${timeStamp}.mp4")

        val expWidth = if (is1080pSelected) 1080 else 720
        val expHeight = if (is1080pSelected) 1920 else 1280
        val expBitrate = if (is1080pSelected) 8 else 4

        Thread {
            try {
                // Brief pause so any playback rendering thread completely releases EGL surface
                Thread.sleep(150)

                val pv = previewView
                val success = pv?.exportToVideo(
                    outputPath = exportFile.absolutePath,
                    width = expWidth,
                    height = expHeight,
                    fps = 30,
                    bitrateMbps = expBitrate,
                    videoCodec = "h264"
                ) ?: false

                if (success && exportFile.exists() && exportFile.length() > 0) {
                    val savedFile = saveVideoToGallery(exportFile)
                    runOnUiThread {
                        exportProgressOverlay.visibility = View.GONE
                        isExporting = false
                        showExportSuccessCelebration(savedFile ?: exportFile)
                    }
                } else {
                    runOnUiThread {
                        exportProgressOverlay.visibility = View.GONE
                        livePreviewView?.visibility = View.VISIBLE
                        isExporting = false
                        Toast.makeText(
                            this@StatusQuickExportActivity,
                            "Export failed. You can open in Editor to export.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    exportProgressOverlay.visibility = View.GONE
                    livePreviewView?.visibility = View.VISIBLE
                    isExporting = false
                    Toast.makeText(this@StatusQuickExportActivity, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        // Poll progress
        var currentProgress = 0
        progressCheckRunnable = object : Runnable {
            override fun run() {
                if (!isExporting) return
                val p = previewView?.getExportProgress() ?: 0
                if (p in 0..100) {
                    currentProgress = p
                } else {
                    if (currentProgress < 90) currentProgress += 5
                }
                exportProgressBar.progress = currentProgress
                exportPercentText.text = "Exporting Status Video... $currentProgress%"
                handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(progressCheckRunnable!!, 300)
    }

    private fun saveVideoToGallery(videoFile: File): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Status_${timeStamp}.mp4"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Storyline_Status")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(videoFile).use { it.copyTo(out) }
                    }
                }
            }

            // Also copy to reliable local status directory
            val statusDir = File(getExternalFilesDir(null), "exported_status").also { it.mkdirs() }
            val localCopy = File(statusDir, fileName)
            FileInputStream(videoFile).use { input ->
                FileOutputStream(localCopy).use { output ->
                    input.copyTo(output)
                }
            }
            localCopy
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to gallery", e)
            null
        }
    }

    private fun showExportSuccessCelebration(videoFile: File) {
        val celebrationOverlay = findViewById<View>(R.id.exportCelebrationOverlay)
        val confettiView = findViewById<ConfettiCelebrationView>(R.id.confettiCelebrationView)
        val whatsAppBtn = findViewById<View>(R.id.celebrationWhatsAppBtn)
        val instagramBtn = findViewById<View>(R.id.celebrationInstagramBtn)
        val moreShareBtn = findViewById<View>(R.id.celebrationMoreShareBtn)
        val doneBtn = findViewById<View>(R.id.celebrationDoneBtn)
        val savedBadge = findViewById<TextView>(R.id.celebrationSavedBadge)

        savedBadge?.text = "💾 Saved to Gallery ✓ (Movies/Storyline_Status)"
        celebrationOverlay?.visibility = View.VISIBLE
        celebrationOverlay?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        confettiView?.startCelebration()

        whatsAppBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            shareToWhatsAppStatus(videoFile)
        }

        instagramBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            shareToInstagramReels(videoFile)
        }

        moreShareBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            shareUniversal(videoFile, "Share Status Video")
        }

        doneBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }
    }

    private fun showExportSuccessDialog(videoFile: File) {
        val options = arrayOf(
            "Share to WhatsApp Status 🟢",
            "Share to Instagram Reels 🟣",
            "More Share Options 🚀",
            "Create Another Status 🔁"
        )

        AlertDialog.Builder(this)
            .setTitle("Status Video Ready! 🎉")
            .setMessage("Saved in full 1080p HD to your phone gallery (Movies/Storyline_Status).")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareToWhatsAppStatus(videoFile)
                    1 -> shareToInstagramReels(videoFile)
                    2 -> shareUniversal(videoFile, "Share Status Video")
                    3 -> finish()
                }
            }
            .setPositiveButton("Done") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun shareToWhatsAppStatus(videoFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            videoFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(shareIntent)
        } catch (_: Exception) {
            shareUniversal(videoFile, "Share to WhatsApp Status")
        }
    }

    private fun shareToInstagramReels(videoFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            videoFile
        )
        val reelsIntent = Intent("com.instagram.share.ADD_TO_REEL").apply {
            type = "video/mp4"
            putExtra("interactive_asset_uri", uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.instagram.android")
        }
        try {
            if (packageManager.resolveActivity(reelsIntent, 0) != null) {
                startActivity(reelsIntent)
            } else {
                val igIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage("com.instagram.android")
                }
                startActivity(igIntent)
            }
        } catch (_: Exception) {
            shareUniversal(videoFile, "Share to Instagram Reels")
        }
    }

    private fun shareUniversal(videoFile: File, title: String) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            videoFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, title))
    }

    private fun openInTimelineEditor() {
        val path = projectPath ?: return
        val nextIntent = EditorLaunchIntents.markForEditorBoot(
            this,
            Intent(this, VideoEditorActivity::class.java)
        )
            .putExtra(EditorLaunchIntents.EXTRA_START_SHELL_ACTION, EditorLaunchIntents.START_ACTION_OPEN)
            .putExtra(EditorLaunchIntents.EXTRA_START_PROJECT_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(nextIntent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isProjectLoaded && !isPlaying && !isExporting) {
            startPlaybackInternal()
        }
    }

    override fun onPause() {
        pausePlaybackInternal()
        super.onPause()
    }

    override fun onDestroy() {
        progressCheckRunnable?.let { handler.removeCallbacks(it) }
        stopAudioPlayback()
        previewView?.stopPlayback()
        super.onDestroy()
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
