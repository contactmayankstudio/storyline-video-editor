package com.video.engine.status

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.R
import com.video.engine.audio.AudioExtractorHelper
import java.io.File
import java.io.FileOutputStream

class StatusMakerActivity : ComponentActivity() {

    private lateinit var templatesRecycler: RecyclerView
    private lateinit var categoryContainer: LinearLayout
    private var currentCategory: String = "All"
    private var pendingTemplate: StatusTemplate? = null
    private var previewMediaPlayer: MediaPlayer? = null
    private var activePreviewDialog: AlertDialog? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var lastCustomPhotoPath: String? = null
    private var lastPhotoPaths: List<String> = emptyList()
    private var activeCustomText: String? = null
    private var activeCustomTexts = StatusCustomTexts()
    private var activeCustomAudioPath: String? = null
    private var activeCustomAudioTitle: String? = null
    private var onAudioSelectedCallback: ((String, String) -> Unit)? = null
    private var isGridMode: Boolean = true
    private var activePlayingTemplateId: String? = null

    // Phone audio picker
    private val pickAudioForPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val localAudio = resolveUriToLocalFile(this, uri) ?: return@Thread
            runOnUiThread {
                onAudioSelectedCallback?.invoke(localAudio, "Phone Audio")
            }
        }.start()
    }

    // Video picker for Reel / Video audio extraction
    private val pickVideoForAudioExtractionLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "⏳ Extracting audio from video...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = AudioExtractorHelper.extractAudioFromVideoUri(this, uri)
            runOnUiThread {
                if (result != null && result.file.exists()) {
                    Toast.makeText(this, "🎵 Audio extracted successfully!", Toast.LENGTH_SHORT).show()
                    onAudioSelectedCallback?.invoke(result.file.absolutePath, "Reel Audio 🎵")
                } else {
                    Toast.makeText(this, "Could not extract audio from selected video", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // Multiple photo picker
    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val template = pendingTemplate ?: StatusTemplateRepository.allTemplates.first()
        if (uris.isEmpty()) {
            Toast.makeText(this, "Select a photo to preview status video", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        Thread {
            val localPaths = uris.mapNotNull { uri ->
                StatusMediaHelper.normalizeImageUriForStatus(this, uri)
                    ?: resolveUriToLocalFile(this, uri)
            }
            if (localPaths.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Could not read selected photos", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            lastPhotoPaths = localPaths
            lastCustomPhotoPath = localPaths.first()

            // Pre-build project in background for instant 1-click export
            var prebuiltProject: File? = null
            try {
                prebuiltProject = StatusProjectBuilder.buildProjectFile(
                    this,
                    template,
                    localPaths,
                    activeCustomAudioPath,
                    activeCustomText
                )
            } catch (_: Exception) {}

            runOnUiThread {
                // PHOTO DALTE HI LIVE PREVIEW START HO JAYEGA!
                showTemplateLivePreviewDialog(
                    initialTemplate = template,
                    customPhotoPath = lastCustomPhotoPath,
                    selectedPhotoPaths = localPaths,
                    prebuiltProjectFile = prebuiltProject
                )
            }
        }.start()
    }

    // Single photo picker to test/change in live preview dialog
    private val pickSinglePhotoForPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val localPath = StatusMediaHelper.normalizeImageUriForStatus(this, uri)
                ?: resolveUriToLocalFile(this, uri)
                ?: return@Thread
            lastCustomPhotoPath = localPath
            lastPhotoPaths = listOf(localPath)
            runOnUiThread {
                activePreviewDialog?.findViewById<StatusTemplateLivePreviewView>(R.id.statusLivePreviewView)?.setCustomPhoto(localPath)
                activePreviewDialog?.findViewById<TextView>(R.id.statusPreviewTestPhotoText)?.text = "Swap Photo 📸"
                activePreviewDialog?.findViewById<TextView>(R.id.statusPreviewCtaText)?.text = "⚡ 1-CLICK EXPORT TO GALLERY"
                activePreviewDialog?.findViewById<TextView>(R.id.statusPreviewPhotosChip)?.text = "📸 Photo Loaded"
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_maker)

        findViewById<View>(R.id.statusMakerRoot)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                findViewById<View>(R.id.statusTopBar)?.setPadding(
                    16.dpToPx(this),
                    systemBars.top + 8.dpToPx(this),
                    16.dpToPx(this),
                    12.dpToPx(this)
                )
                templatesRecycler.setPadding(
                    16.dpToPx(this),
                    0,
                    16.dpToPx(this),
                    systemBars.bottom + 16.dpToPx(this)
                )
                insets
            }
        }

        findViewById<FrameLayout>(R.id.statusBackBtn)?.setOnClickListener {
            finish()
        }

        // Wire Top Hero Carousel Cards
        findViewById<View>(R.id.heroCardViral)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            currentCategory = "All"
            setupCategoryChips()
            updateTemplatesList()
        }
        findViewById<View>(R.id.heroCardLove)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            currentCategory = "💖 Love"
            setupCategoryChips()
            updateTemplatesList()
        }
        findViewById<View>(R.id.heroCardAttitude)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            currentCategory = "😎 Attitude"
            setupCategoryChips()
            updateTemplatesList()
        }
        findViewById<View>(R.id.heroCardBirthday)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            currentCategory = "🎂 Birthday"
            setupCategoryChips()
            updateTemplatesList()
        }

        // Hero Card background gradients
        val density = resources.displayMetrics.density
        findViewById<View>(R.id.heroCardViralBg)?.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FF0844"), Color.parseColor("#FFB199"))
        ).apply { cornerRadius = 16f * density }
        findViewById<View>(R.id.heroCardLoveBg)?.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#F857A6"), Color.parseColor("#FF5858"))
        ).apply { cornerRadius = 16f * density }
        findViewById<View>(R.id.heroCardAttitudeBg)?.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#8E2DE2"), Color.parseColor("#4A00E0"))
        ).apply { cornerRadius = 16f * density }
        findViewById<View>(R.id.heroCardBirthdayBg)?.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#F7971E"), Color.parseColor("#FFD200"))
        ).apply { cornerRadius = 16f * density }

        // Prominent 1-Photo Instant Hero Card: click -> pick photo -> immediate live preview!
        findViewById<View>(R.id.statusInstantHeroCard)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pendingTemplate = StatusTemplateRepository.allTemplates.first()
            pickPhotosLauncher.launch("image/*")
        }

        categoryContainer = findViewById(R.id.statusCategoryChipsContainer)
        templatesRecycler = findViewById(R.id.statusTemplatesRecyclerView)
        templatesRecycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

        findViewById<TextView>(R.id.statusViewModeToggleBtn)?.let { toggleBtn ->
            toggleBtn.text = if (isGridMode) "🎬 Reels Feed" else "📱 Grid View"
            toggleBtn.setTextColor(if (isGridMode) Color.parseColor("#00F2FE") else Color.parseColor("#FFE500"))
            toggleBtn.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                isGridMode = !isGridMode
                templatesRecycler.layoutManager = if (isGridMode) {
                    androidx.recyclerview.widget.GridLayoutManager(this, 2)
                } else {
                    androidx.recyclerview.widget.LinearLayoutManager(this)
                }
                toggleBtn.text = if (isGridMode) "🎬 Reels Feed" else "📱 Grid View"
                toggleBtn.setTextColor(if (isGridMode) Color.parseColor("#00F2FE") else Color.parseColor("#FFE500"))
                updateTemplatesList()
            }
        }

        setupCategoryChips()
        updateTemplatesList()

        val initialPhotoPath = intent?.getStringExtra(EXTRA_INITIAL_PHOTO_PATH)
        if (!initialPhotoPath.isNullOrBlank()) {
            val photoFile = File(initialPhotoPath)
            if (photoFile.exists()) {
                lastCustomPhotoPath = initialPhotoPath
                lastPhotoPaths = listOf(initialPhotoPath)
                val templateId = intent?.getStringExtra(EXTRA_INITIAL_TEMPLATE_ID)
                val template = StatusTemplateRepository.allTemplates.find { it.id == templateId }
                    ?: StatusTemplateRepository.allTemplates.first()
                window.decorView.post {
                    showTemplateLivePreviewDialog(
                        initialTemplate = template,
                        customPhotoPath = initialPhotoPath,
                        selectedPhotoPaths = listOf(initialPhotoPath)
                    )
                }
            }
        }
    }

    private fun setupCategoryChips() {
        categoryContainer.removeAllViews()
        StatusTemplateRepository.categories.forEach { category ->
            val isSelected = category.equals(currentCategory, ignoreCase = true)
            val count = StatusTemplateRepository.getByCategory(category, this).size
            val label = when {
                category.equals("all", ignoreCase = true) -> "🔥 All ($count)"
                category.contains("1-Photo", ignoreCase = true) -> "⚡ 1-Photo Magic ($count)"
                category.contains("Love", ignoreCase = true) -> "💖 Love ($count)"
                category.contains("Attitude", ignoreCase = true) -> "😎 Attitude ($count)"
                category.contains("Birthday", ignoreCase = true) -> "🎂 Birthday ($count)"
                category.contains("Bhakti", ignoreCase = true) -> "🔱 Bhakti ($count)"
                category.contains("My Templates", ignoreCase = true) -> "✨ My Templates ($count)"
                else -> "$category ($count)"
            }

            val chip = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#9AA4B2"))
                setPadding(16.dpToPx(context), 8.dpToPx(context), 16.dpToPx(context), 8.dpToPx(context))
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
                        setColor(Color.parseColor("#141822"))
                        setStroke(1.dpToPx(context), Color.parseColor("#33FFFFFF"))
                    }
                }
                background = bg

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    currentCategory = category
                    setupCategoryChips()
                    updateTemplatesList()
                }
            }
            categoryContainer.addView(chip)
        }
    }

    private fun updateTemplatesList() {
        val list = StatusTemplateRepository.getByCategory(currentCategory, this)
        templatesRecycler.adapter = StatusTemplateAdapter(
            templates = list,
            isGrid = isGridMode,
            onCardClick = { template ->
                stopPreviewAudio()
                showTemplateLivePreviewDialog(template)
            },
            onUseTemplate = { template ->
                stopPreviewAudio()
                pendingTemplate = template
                pickPhotosLauncher.launch("image/*")
            },
            onToggleSound = { template, onStateChange ->
                toggleSoundtrackPreview(template, onStateChange)
            }
        )
    }

    private fun toggleSoundtrackPreview(template: StatusTemplate, onStateChange: (Boolean) -> Unit) {
        if (activePlayingTemplateId == template.id && previewMediaPlayer?.isPlaying == true) {
            stopPreviewAudio()
            activePlayingTemplateId = null
            onStateChange(false)
            return
        }
        stopPreviewAudio()
        activePlayingTemplateId = template.id
        requestAudioFocus()
        try {
            val audioPath = template.audioAssetPath
            val mp = MediaPlayer().apply {
                if (audioPath.startsWith("/") && File(audioPath).exists()) {
                    setDataSource(audioPath)
                } else {
                    val afd = assets.openFd(audioPath)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                    afd.close()
                }
                isLooping = true
                prepare()
                start()
            }
            previewMediaPlayer = mp
            onStateChange(true)
        } catch (e: Exception) {
            Log.w("StatusMakerActivity", "Failed to play soundtrack preview", e)
            onStateChange(false)
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> stopPreviewAudio()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            try { previewMediaPlayer?.pause() } catch (_: Exception) {}
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            try { previewMediaPlayer?.setVolume(0.2f, 0.2f) } catch (_: Exception) {}
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            try {
                                previewMediaPlayer?.setVolume(1.0f, 1.0f)
                                if (previewMediaPlayer?.isPlaying == false) {
                                    previewMediaPlayer?.start()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                .build()
                .also { audioFocusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) stopPreviewAudio()
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        hasAudioFocus = (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    private fun stopPreviewAudio() {
        try {
            if (previewMediaPlayer?.isPlaying == true) {
                previewMediaPlayer?.stop()
            }
            previewMediaPlayer?.release()
        } catch (_: Exception) {}
        previewMediaPlayer = null
        abandonAudioFocus()
    }

    private fun showTemplateLivePreviewDialog(
        initialTemplate: StatusTemplate,
        customPhotoPath: String? = null,
        selectedPhotoPaths: List<String>? = null,
        prebuiltProjectFile: File? = null
    ) {
        stopPreviewAudio()
        activePreviewDialog?.dismiss()

        var currentActiveTemplate = initialTemplate
        var currentProjectFile = prebuiltProjectFile
        val activePhotoPath = customPhotoPath ?: lastCustomPhotoPath
        val activePhotoPaths = selectedPhotoPaths ?: (if (activePhotoPath != null) listOf(activePhotoPath) else lastPhotoPaths)

        // Inflate custom 9:16 Live Preview dialog layout
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.dialog_status_template_live_preview,
            null
        )

        val livePreviewView = dialogView.findViewById<StatusTemplateLivePreviewView>(
            R.id.statusLivePreviewView
        )
        val titleTv = dialogView.findViewById<TextView>(R.id.statusPreviewTitle)
        val categoryBadge = dialogView.findViewById<TextView>(R.id.statusPreviewCategoryBadge)
        val durationChip = dialogView.findViewById<TextView>(R.id.statusPreviewDurationChip)
        val photosChip = dialogView.findViewById<TextView>(R.id.statusPreviewPhotosChip)
        val bpmChip = dialogView.findViewById<TextView>(R.id.statusPreviewBpmChip)
        val descTv = dialogView.findViewById<TextView>(R.id.statusPreviewDesc)
        val closeBtn = dialogView.findViewById<View>(R.id.statusPreviewCloseBtn)
        val soundBtn = dialogView.findViewById<View>(R.id.statusPreviewSoundBtn)
        val soundIcon = dialogView.findViewById<TextView>(R.id.statusPreviewSoundIcon)
        var isAudioMuted = false
        val ctaBtn = dialogView.findViewById<View>(R.id.statusPreviewCtaBtn)
        val ctaText = dialogView.findViewById<TextView>(R.id.statusPreviewCtaText)
        val testPhotoBtn = dialogView.findViewById<View>(R.id.statusPreviewTestPhotoBtn)
        val testPhotoText = dialogView.findViewById<TextView>(R.id.statusPreviewTestPhotoText)
        val nextTemplateBtn = dialogView.findViewById<View>(R.id.statusPreviewNextTemplateBtn)
        val editTextBtn = dialogView.findViewById<View>(R.id.statusPreviewEditTextBtn)
        val editTextTitle = dialogView.findViewById<TextView>(R.id.statusPreviewEditText)
        val changeMusicBtn = dialogView.findViewById<View>(R.id.statusPreviewChangeMusicBtn)
        val changeMusicText = dialogView.findViewById<TextView>(R.id.statusPreviewChangeMusicText)

        fun bindTemplateState(tmpl: StatusTemplate) {
            currentActiveTemplate = tmpl
            titleTv?.text = if (activePhotoPath != null) "Preview: ${tmpl.title}" else tmpl.title
            categoryBadge?.text = "${tmpl.emoji} ${tmpl.category.uppercase()}"
            durationChip?.text = "⏱️ ${tmpl.durationMs / 1000}s"
            photosChip?.text = if (activePhotoPath != null) "📸 Photo Loaded" else "⚡ 1-Photo Ready"
            bpmChip?.text = "🎵 ${tmpl.bpm} BPM"
            descTv?.text = if (activePhotoPath != null) "⚡ Live animated preview of your photo with ${tmpl.title}! Tap Export to save." else tmpl.description
            ctaText?.text = if (activePhotoPath != null) "⚡ 1-CLICK EXPORT TO GALLERY" else "⚡ CREATE 1-PHOTO / REELS STATUS"
            testPhotoText?.text = if (activePhotoPath != null) "Swap Photo 📸" else "Pick Photo 📸"
            val display = activeCustomTexts.userName ?: activeCustomTexts.quoteText ?: activeCustomText
            if (!display.isNullOrBlank()) {
                editTextTitle?.text = "✏️ ${display.take(12)}"
            } else {
                editTextTitle?.text = "Edit Texts & Name ✏️"
            }
            if (!activeCustomAudioTitle.isNullOrBlank()) {
                changeMusicText?.text = "🎵 ${activeCustomAudioTitle!!.take(12)}"
            } else {
                changeMusicText?.text = "Change Music 🎵"
            }

            val density = resources.displayMetrics.density
            val startColor = try { Color.parseColor(tmpl.gradientStartHex) } catch (_: Exception) { Color.parseColor("#FF416C") }
            val endColor = try { Color.parseColor(tmpl.gradientEndHex) } catch (_: Exception) { Color.parseColor("#8A2387") }
            val ctaGradient = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(startColor, endColor)
            ).apply {
                cornerRadius = 16 * density
            }
            ctaBtn?.background = ctaGradient

            stopPreviewAudio()
            requestAudioFocus()
            try {
                if (activeCustomAudioPath != null && File(activeCustomAudioPath!!).exists()) {
                    val mp = MediaPlayer().apply {
                        setDataSource(activeCustomAudioPath)
                        isLooping = true
                        prepare()
                        start()
                    }
                    previewMediaPlayer = mp
                } else {
                    assets.openFd(tmpl.audioAssetPath).use { afd ->
                        val mp = MediaPlayer().apply {
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            isLooping = true
                            prepare()
                            start()
                        }
                        previewMediaPlayer = mp
                    }
                }
            } catch (_: Exception) {}

            if (isAudioMuted) {
                try { previewMediaPlayer?.setVolume(0f, 0f) } catch (_: Exception) {}
            }

            livePreviewView?.bindTemplate(tmpl, previewMediaPlayer)
            livePreviewView?.customTexts = activeCustomTexts
            livePreviewView?.customStatusText = activeCustomText
            livePreviewView?.setCustomPhoto(activePhotoPath)
            livePreviewView?.onTextClickListener = {
                showProTextAndNameEditorDialog(currentActiveTemplate) {
                    livePreviewView?.customTexts = activeCustomTexts
                    val d = activeCustomTexts.userName ?: activeCustomTexts.quoteText ?: activeCustomText
                    editTextTitle?.text = if (!d.isNullOrBlank()) "✏️ ${d.take(12)}" else "Edit Texts & Name ✏️"
                }
            }
        }

        bindTemplateState(initialTemplate)

        soundBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isAudioMuted = !isAudioMuted
            val vol = if (isAudioMuted) 0f else 1f
            try {
                previewMediaPlayer?.setVolume(vol, vol)
            } catch (_: Exception) {}
            soundIcon?.text = if (isAudioMuted) "🔇" else "🔊"
        }

        // Custom Name / Lyrical Text Editor
        editTextBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showProTextAndNameEditorDialog(currentActiveTemplate) {
                livePreviewView?.customTexts = activeCustomTexts
                val d = activeCustomTexts.userName ?: activeCustomTexts.quoteText ?: activeCustomText
                editTextTitle?.text = if (!d.isNullOrBlank()) "✏️ ${d.take(12)}" else "Edit Texts & Name ✏️"
            }
        }

        // Change Music Soundtrack (1-Tap switcher)
        fun playSelectedAudio(pathOrAsset: String, displayTitle: String, isLocal: Boolean) {
            stopPreviewAudio()
            requestAudioFocus()
            try {
                val mp = MediaPlayer()
                if (isLocal) {
                    mp.setDataSource(pathOrAsset)
                    activeCustomAudioPath = pathOrAsset
                } else {
                    assets.openFd(pathOrAsset).use { afd ->
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                    activeCustomAudioPath = StatusMediaHelper.extractAudioAssetToLocal(this@StatusMakerActivity, pathOrAsset)?.absolutePath
                }
                mp.isLooping = true
                mp.prepare()
                mp.start()
                previewMediaPlayer = mp
                activeCustomAudioTitle = displayTitle
            } catch (_: Exception) {
                Toast.makeText(this@StatusMakerActivity, "Could not play soundtrack", Toast.LENGTH_SHORT).show()
            }

            livePreviewView?.bindTemplate(currentActiveTemplate, previewMediaPlayer)
            changeMusicText?.text = "🎵 ${displayTitle.take(12)}"
            Toast.makeText(this@StatusMakerActivity, "🎵 Track: $displayTitle", Toast.LENGTH_SHORT).show()

            if (activePhotoPaths.isNotEmpty()) {
                Thread {
                    currentProjectFile = StatusProjectBuilder.buildProjectFile(
                        this@StatusMakerActivity,
                        currentActiveTemplate,
                        activePhotoPaths,
                        activeCustomAudioPath,
                        activeCustomText
                    )
                }.start()
            }
        }

        changeMusicBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showChangeMusicDialog { path, title, isLocal ->
                playSelectedAudio(path, title, isLocal)
            }
        }

        // Next Template switcher on the fly!
        nextTemplateBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val all = StatusTemplateRepository.allTemplates
            val currentIdx = all.indexOfFirst { it.id == currentActiveTemplate.id }
            val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % all.size else 0
            val nextTmpl = all[nextIdx]
            currentProjectFile = null
            bindTemplateState(nextTmpl)
            if (activePhotoPaths.isNotEmpty()) {
                Thread {
                    currentProjectFile = StatusProjectBuilder.buildProjectFile(
                        this@StatusMakerActivity,
                        nextTmpl,
                        activePhotoPaths,
                        activeCustomAudioPath,
                        activeCustomText
                    )
                }.start()
            }
        }

        testPhotoBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pickSinglePhotoForPreviewLauncher.launch("image/*")
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                livePreviewView?.release()
                stopPreviewAudio()
                activePreviewDialog = null
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activePreviewDialog = dialog

        closeBtn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        val onCtaAction = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (activePhotoPath != null) {
                val textX = livePreviewView?.customTextNormX ?: 0.5f
                val textY = livePreviewView?.customTextNormY ?: 0.74f
                val textScale = livePreviewView?.customTextScaleFactor ?: 1.0f
                dialog.dismiss()
                val photosToUse = activePhotoPaths.ifEmpty { listOf(activePhotoPath) }
                Toast.makeText(this@StatusMakerActivity, "Preparing Export...", Toast.LENGTH_SHORT).show()
                Thread {
                    val proj = StatusProjectBuilder.buildProjectFile(
                        this@StatusMakerActivity,
                        currentActiveTemplate,
                        photosToUse,
                        activeCustomAudioPath,
                        activeCustomText,
                        textNormX = textX,
                        textNormY = textY,
                        textScale = textScale,
                        customTexts = activeCustomTexts
                    )
                    runOnUiThread {
                        if (proj != null && proj.exists()) {
                            launchQuickExport(
                                proj,
                                currentActiveTemplate,
                                photosToUse,
                                activeCustomText,
                                activeCustomAudioPath,
                                activeCustomTexts
                            )
                        } else {
                            Toast.makeText(this@StatusMakerActivity, "Failed to build status project", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                dialog.dismiss()
                pendingTemplate = currentActiveTemplate
                pickPhotosLauncher.launch("image/*")
            }
        }

        ctaBtn?.setOnClickListener(onCtaAction)
        livePreviewView?.setOnCreateStatusClickListener {
            if (activePhotoPath == null) {
                dialog.dismiss()
                pendingTemplate = currentActiveTemplate
                pickPhotosLauncher.launch("image/*")
            }
        }

        dialog.show()
        dialog.window?.let { window ->
            val dm = resources.displayMetrics
            val targetW = (dm.widthPixels * 0.92f).toInt().coerceAtMost((420 * dm.density).toInt())
            window.setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
        }
    }

    private fun showProTextAndNameEditorDialog(
        template: StatusTemplate,
        onApplied: (() -> Unit)? = null
    ) {
        val ctx = this
        val density = resources.displayMetrics.density

        val scrollView = androidx.core.widget.NestedScrollView(ctx).apply {
            isFillViewport = true
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (14 * density).toInt())
        }
        scrollView.addView(container)

        val subHeader = TextView(ctx).apply {
            text = "✏️ Change any text, name, title, or quote. Everything is 100% customizable!"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 12f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        container.addView(subHeader)

        // 1. Title / Headline
        val titleLabel = TextView(ctx).apply {
            text = "🏷️ Main Title / Headline:"
            setTextColor(Color.parseColor("#00F2FE"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        container.addView(titleLabel)

        val titleEditText = EditText(ctx).apply {
            hint = "e.g. ${template.title}"
            setHintTextColor(Color.parseColor("#556070"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setText(activeCustomTexts.titleText ?: template.title)
            setSelection(text.length)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2128"))
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), Color.parseColor("#388BFD"))
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        container.addView(titleEditText)

        // 2. Name / Couple Name
        val nameLabel = TextView(ctx).apply {
            text = "👑 My Name / Couple Name / Handle:"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(nameLabel)

        val nameEditText = EditText(ctx).apply {
            hint = "e.g. Rahul Sharma / Rahul ❤️ Priya / @rohan_reels"
            setHintTextColor(Color.parseColor("#556070"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setText(activeCustomTexts.userName ?: "")
            setSelection(text.length)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2128"))
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), Color.parseColor("#FFD700"))
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        container.addView(nameEditText)

        // 3. Shayari / Lyrical Quote
        val quoteLabel = TextView(ctx).apply {
            text = "💬 Shayari / Lyrical Quote:"
            setTextColor(Color.parseColor("#FF758C"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(quoteLabel)

        val quoteEditText = EditText(ctx).apply {
            hint = "Type your own quote, lyrics, or pick from below..."
            setHintTextColor(Color.parseColor("#556070"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setText(activeCustomTexts.quoteText ?: activeCustomText ?: "")
            setSelection(text.length)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2128"))
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), Color.parseColor("#FF4081"))
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        container.addView(quoteEditText)

        // Ready-made Lyrical Shayari Categories & Chips
        val shayariHeader = TextView(ctx).apply {
            text = "📚 1-Tap Shayari & Quote Library:"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(shayariHeader)

        val categoryScrollView = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val categoryContainer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val quotesScrollView = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val quotesContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }

        var activeCategoryIdx = 0
        fun populateQuotes(catIdx: Int) {
            quotesContainer.removeAllViews()
            val category = ShayariLibraryRepository.categories.getOrNull(catIdx) ?: return
            category.quotes.forEach { quote ->
                val quoteChip = TextView(ctx).apply {
                    text = quote
                    textSize = 11f
                    setTextColor(Color.parseColor("#E6EDF3"))
                    setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (6 * density).toInt() }
                    layoutParams = params
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1F242C"))
                        cornerRadius = 12 * density
                        setStroke((1 * density).toInt(), Color.parseColor("#388BFD"))
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        quoteEditText.setText(quote)
                        quoteEditText.setSelection(quote.length)
                    }
                }
                quotesContainer.addView(quoteChip)
            }
        }

        fun updateCategoryTabs() {
            categoryContainer.removeAllViews()
            ShayariLibraryRepository.categories.forEachIndexed { idx, cat ->
                val isSelected = idx == activeCategoryIdx
                val tabChip = TextView(ctx).apply {
                    text = "${cat.emoji} ${cat.title}"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#8B949E"))
                    setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (6 * density).toInt() }
                    layoutParams = params
                    background = GradientDrawable().apply {
                        setColor(if (isSelected) Color.parseColor("#388BFD") else Color.parseColor("#21262D"))
                        cornerRadius = 12 * density
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        activeCategoryIdx = idx
                        updateCategoryTabs()
                        populateQuotes(idx)
                    }
                }
                categoryContainer.addView(tabChip)
            }
        }

        updateCategoryTabs()
        populateQuotes(0)
        categoryScrollView.addView(categoryContainer)
        quotesScrollView.addView(quotesContainer)
        container.addView(categoryScrollView)
        container.addView(quotesScrollView)

        // 4. Tagline / Date
        val taglineLabel = TextView(ctx).apply {
            text = "📅 Tagline / Date / Subtitle:"
            setTextColor(Color.parseColor("#7EE787"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(taglineLabel)

        val taglineEditText = EditText(ctx).apply {
            hint = "e.g. 26 Oct 2026 / Forever & Always / Unstoppable"
            setHintTextColor(Color.parseColor("#556070"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setText(activeCustomTexts.taglineText ?: "")
            setSelection(text.length)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2128"))
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), Color.parseColor("#2EA043"))
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        container.addView(taglineEditText)

        // 5. Neon Color & Glow Presets
        val colorLabel = TextView(ctx).apply {
            text = "🎨 Text Color & Glow Style:"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
        }
        container.addView(colorLabel)

        val colorScrollView = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val colorContainer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val colorPresets = listOf(
            Triple("🟡 Gold Neon", "#FFE500", "#FFD700"),
            Triple("🔵 Electric Cyan", "#00F2FE", "#388BFD"),
            Triple("💖 Romantic Pink", "#FF4081", "#FF80AB"),
            Triple("🔴 Fiery Red", "#FF1744", "#FF5252"),
            Triple("🟢 Cyber Lime", "#00E676", "#76FF03"),
            Triple("⚪ Diamond White", "#FFFFFF", "#E0E0E0")
        )

        var selectedTextColorHex: String? = activeCustomTexts.textColorHex
        var selectedGlowColorHex: String? = activeCustomTexts.glowColorHex

        colorPresets.forEach { (cTitle, cTextHex, cGlowHex) ->
            val colorChip = TextView(ctx).apply {
                text = cTitle
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(cTextHex))
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * density).toInt() }
                layoutParams = params
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C2128"))
                    cornerRadius = 12 * density
                    setStroke((1.5f * density).toInt(), Color.parseColor(cGlowHex))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectedTextColorHex = cTextHex
                    selectedGlowColorHex = cGlowHex
                    Toast.makeText(ctx, "Color: $cTitle", Toast.LENGTH_SHORT).show()
                }
            }
            colorContainer.addView(colorChip)
        }
        colorScrollView.addView(colorContainer)
        container.addView(colorScrollView)

        // 6. Typography Font Style
        val fontLabel = TextView(ctx).apply {
            text = "✍️ Typography Font Style:"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
        }
        container.addView(fontLabel)

        val fontScrollView = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val fontContainer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var selectedFontName: String? = activeCustomTexts.fontName

        val fontSpecs = listOf(
            Pair("✍️ Kalam (हिंदी)", "kalam"),
            Pair("👑 Rozha (Royal)", "rozha"),
            Pair("⚡ Poppins (Bold)", "poppins-bold"),
            Pair("💥 Bangers", "bangers"),
            Pair("🎬 Anton", "anton"),
            Pair("🖊️ Marker", "marker")
        )

        fontSpecs.forEach { (fTitle, fKey) ->
            val isCurrent = selectedFontName == fKey || (selectedFontName == null && fKey == "kalam")
            val fontChip = TextView(ctx).apply {
                text = fTitle
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isCurrent) Color.parseColor("#388BFD") else Color.WHITE)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * density).toInt() }
                layoutParams = params
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C2128"))
                    cornerRadius = 12 * density
                    setStroke((1.5f * density).toInt(), if (isCurrent) Color.parseColor("#388BFD") else Color.parseColor("#33FFFFFF"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectedFontName = fKey
                    Toast.makeText(ctx, "Font: $fTitle", Toast.LENGTH_SHORT).show()
                }
            }
            fontContainer.addView(fontChip)
        }
        fontScrollView.addView(fontContainer)
        container.addView(fontScrollView)

        // 7. Atmospheric Particle Overlay
        val particleLabel = TextView(ctx).apply {
            text = "✨ Magical Particle Overlay:"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
        }
        container.addView(particleLabel)

        val particleScrollView = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val particleContainer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var selectedParticleTheme: String? = activeCustomTexts.particleTheme

        val particleSpecs = listOf(
            Pair("✨ Auto Theme", null),
            Pair("🌧️ Rain Drops", "RAIN"),
            Pair("❄️ Snow Flakes", "SNOW"),
            Pair("💖 Neon Hearts", "LOVE"),
            Pair("🔥 Fire Embers", "EMBERS"),
            Pair("📼 90s Vintage", "VINTAGE"),
            Pair("🎉 Birthday Confetti", "BIRTHDAY"),
            Pair("🙏 Divine Aura", "BHAKTI")
        )

        particleSpecs.forEach { (pTitle, pCode) ->
            val isCurrent = selectedParticleTheme == pCode
            val particleChip = TextView(ctx).apply {
                text = pTitle
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isCurrent) Color.parseColor("#00F2FE") else Color.WHITE)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * density).toInt() }
                layoutParams = params
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C2128"))
                    cornerRadius = 12 * density
                    setStroke((1.5f * density).toInt(), if (isCurrent) Color.parseColor("#00F2FE") else Color.parseColor("#33FFFFFF"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectedParticleTheme = pCode
                    Toast.makeText(ctx, "Effect: $pTitle", Toast.LENGTH_SHORT).show()
                }
            }
            particleContainer.addView(particleChip)
        }
        particleScrollView.addView(particleContainer)
        container.addView(particleScrollView)

        // 8. Hide default template overlays toggle chip
        var hideDefaultsState = activeCustomTexts.hideDefaultOverlays
        val hideDefaultsChip = TextView(ctx).apply {
            text = if (hideDefaultsState) "✅ Hide Default Template Texts (Custom Only)" else "⬜ Hide Default Template Texts"
            textSize = 11.5f
            setTextColor(if (hideDefaultsState) Color.parseColor("#388BFD") else Color.parseColor("#9AA4B2"))
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hideDefaultsState = !hideDefaultsState
                text = if (hideDefaultsState) "✅ Hide Default Template Texts (Custom Only)" else "⬜ Hide Default Template Texts"
                setTextColor(if (hideDefaultsState) Color.parseColor("#388BFD") else Color.parseColor("#9AA4B2"))
            }
        }
        container.addView(hideDefaultsChip)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("✨ Edit All Texts & Names")
            .setView(scrollView)
            .setPositiveButton("Apply to Status ✨") { _, _ ->
                val newTitle = titleEditText.text.toString().trim()
                val newName = nameEditText.text.toString().trim()
                val newQuote = quoteEditText.text.toString().trim()
                val newTagline = taglineEditText.text.toString().trim()

                activeCustomTexts = StatusCustomTexts(
                    titleText = if (newTitle.isNotBlank()) newTitle else null,
                    userName = if (newName.isNotBlank()) newName else null,
                    quoteText = if (newQuote.isNotBlank()) newQuote else null,
                    taglineText = if (newTagline.isNotBlank()) newTagline else null,
                    hideDefaultOverlays = hideDefaultsState,
                    textColorHex = selectedTextColorHex,
                    glowColorHex = selectedGlowColorHex,
                    fontName = selectedFontName,
                    particleTheme = selectedParticleTheme
                )

                activeCustomText = activeCustomTexts.quoteText ?: activeCustomTexts.userName ?: activeCustomTexts.titleText
                activePreviewDialog?.findViewById<StatusTemplateLivePreviewView>(R.id.statusLivePreviewView)?.let { lpv ->
                    lpv.customTexts = activeCustomTexts
                    lpv.customStatusText = activeCustomText
                }

                val display = activeCustomTexts.userName ?: activeCustomTexts.quoteText ?: activeCustomText
                activePreviewDialog?.findViewById<TextView>(R.id.statusPreviewEditText)?.text =
                    if (!display.isNullOrBlank()) "✏️ ${display.take(12)}" else "Edit Texts & Name ✏️"

                Toast.makeText(ctx, "✨ Custom texts & names applied!", Toast.LENGTH_SHORT).show()
                onApplied?.invoke()

                if (lastPhotoPaths.isNotEmpty()) {
                    Thread {
                        StatusProjectBuilder.buildProjectFile(
                            this@StatusMakerActivity,
                            template,
                            lastPhotoPaths,
                            activeCustomAudioPath,
                            activeCustomText,
                            customTexts = activeCustomTexts
                        )
                    }.start()
                }
            }
            .setNeutralButton("Reset") { _, _ ->
                activeCustomTexts = StatusCustomTexts()
                activeCustomText = null
                activePreviewDialog?.findViewById<StatusTemplateLivePreviewView>(R.id.statusLivePreviewView)?.let { lpv ->
                    lpv.customTexts = activeCustomTexts
                    lpv.customStatusText = null
                }
                activePreviewDialog?.findViewById<TextView>(R.id.statusPreviewEditText)?.text = "Edit Texts & Name ✏️"
                Toast.makeText(ctx, "Defaults restored", Toast.LENGTH_SHORT).show()
                onApplied?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun showChangeMusicDialog(
        onTrackSelected: (path: String, title: String, isLocalFile: Boolean) -> Unit
    ) {
        val ctx = this
        val tracks = listOf(
            Triple("🎵 Extract Audio from Reel / Video...", "", "extract"),
            Triple("📂 Pick from Phone Storage...", "", "phone"),
            Triple("Attitude Bass Drop 🔥 (130 BPM)", "music_bgm/attitude_bass_drop.m4a", "preset"),
            Triple("Energetic Reels Trap ⚡ (135 BPM)", "music_bgm/energetic_reels_trap_beat.m4a", "preset"),
            Triple("Romantic Acoustic Love 💖 (100 BPM)", "music_bgm/romantic_acoustic_love.m4a", "preset"),
            Triple("Lo-Fi Chill Vlog 🎧 (88 BPM)", "music_bgm/lofi_chill_vlog_beat.m4a", "preset"),
            Triple("Devotional Ambient Flute 🙏 (75 BPM)", "music_bgm/devotional_ambient_flute.m4a", "preset"),
            Triple("Sad Emotional Piano 🥺 (72 BPM)", "music_bgm/sad_piano_emotional.m4a", "preset")
        )

        val titles = tracks.map { it.first }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("🎵 Select Status Soundtrack")
            .setItems(titles) { _, which ->
                val selected = tracks[which]
                when (selected.third) {
                    "extract" -> {
                        onAudioSelectedCallback = { localPath, title ->
                            onTrackSelected(localPath, title, true)
                        }
                        pickVideoForAudioExtractionLauncher.launch("video/*")
                    }
                    "phone" -> {
                        onAudioSelectedCallback = { localPath, title ->
                            onTrackSelected(localPath, title, true)
                        }
                        pickAudioForPreviewLauncher.launch("audio/*")
                    }
                    else -> {
                        val cleanTitle = selected.first.split(" (")[0]
                        onTrackSelected(selected.second, cleanTitle, false)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchQuickExport(
        projectFile: File,
        template: StatusTemplate,
        photoPaths: List<String>,
        customText: String? = null,
        customAudioPath: String? = null,
        customTexts: StatusCustomTexts? = null
    ) {
        val quickExportIntent = Intent(this, StatusQuickExportActivity::class.java).apply {
            putExtra(StatusQuickExportActivity.EXTRA_PROJECT_PATH, projectFile.absolutePath)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_ID, template.id)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_TITLE, template.title)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_DURATION_MS, template.durationMs)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_GRADIENT_START, template.gradientStartHex)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_GRADIENT_END, template.gradientEndHex)
            putStringArrayListExtra(StatusQuickExportActivity.EXTRA_PHOTO_PATHS, ArrayList(photoPaths))
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_TEXT, customText)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_AUDIO_PATH, customAudioPath)

            val texts = customTexts ?: activeCustomTexts
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_TITLE, texts.titleText)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_USER_NAME, texts.userName)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_QUOTE, texts.quoteText)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_TAGLINE, texts.taglineText)
            putExtra(StatusQuickExportActivity.EXTRA_HIDE_DEFAULT_OVERLAYS, texts.hideDefaultOverlays)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_TEXT_COLOR, texts.textColorHex)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_GLOW_COLOR, texts.glowColorHex)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_FONT_NAME, texts.fontName)
            putExtra(StatusQuickExportActivity.EXTRA_CUSTOM_PARTICLE_THEME, texts.particleTheme)
        }
        startActivity(quickExportIntent)
    }

    private fun resolveUriToLocalFile(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val displayName = cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) it.getString(nameIndex) else null
                } else null
            } ?: "status_photo_${System.currentTimeMillis()}.jpg"

            val importsDir = File(context.getExternalFilesDir(null), "status_imports").also { it.mkdirs() }
            val outFile = File(importsDir, "${System.currentTimeMillis()}_$displayName")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    override fun onPause() {
        activePreviewDialog?.dismiss()
        stopPreviewAudio()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            activePreviewDialog?.dismiss()
            stopPreviewAudio()
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        activePreviewDialog?.dismiss()
        stopPreviewAudio()
        System.gc()
    }

    override fun onDestroy() {
        activePreviewDialog?.dismiss()
        stopPreviewAudio()
        StatusSampleModelCache.clear()
        super.onDestroy()
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private class StatusTemplateAdapter(
        private val templates: List<StatusTemplate>,
        private val isGrid: Boolean,
        private val onCardClick: (StatusTemplate) -> Unit,
        private val onUseTemplate: (StatusTemplate) -> Unit,
        private val onToggleSound: ((StatusTemplate, (Boolean) -> Unit) -> Unit)? = null,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val VIEW_TYPE_REELS = 1
            const val VIEW_TYPE_MAST_GRID = 2
        }

        class MastGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView? = view.findViewById(R.id.mastCardPhoto)
            val cardBg: View = view.findViewById(R.id.mastCardBg)
            val emoji: TextView = view.findViewById(R.id.mastCardEmoji)
            val lyricText: TextView = view.findViewById(R.id.mastCardLyricText)
            val photoPill: TextView = view.findViewById(R.id.mastCardPhotoPill)
            val usageBadge: TextView = view.findViewById(R.id.mastCardUsageBadge)
            val title: TextView = view.findViewById(R.id.mastCardTitle)
            val bpm: TextView = view.findViewById(R.id.mastCardBpm)
            val makeBtn: View = view.findViewById(R.id.mastCardMakeBtn)
        }

        class ReelsFeedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView? = view.findViewById(R.id.reelsCardPhoto)
            val cardBg: View = view.findViewById(R.id.reelsCardBg)
            val centerEmoji: TextView = view.findViewById(R.id.reelsCardCenterEmoji)
            val lyricText: TextView = view.findViewById(R.id.reelsCardLyricText)
            val subtitle: TextView = view.findViewById(R.id.reelsCardSubtitle)
            val communityBadge: TextView = view.findViewById(R.id.reelsCardCommunityBadge)
            val soundPill: View = view.findViewById(R.id.reelsCardSoundPill)
            val soundIcon: TextView = view.findViewById(R.id.reelsCardSoundIcon)
            val soundTitle: TextView = view.findViewById(R.id.reelsCardSoundTitle)
            val title: TextView = view.findViewById(R.id.reelsCardTitle)
            val desc: TextView = view.findViewById(R.id.reelsCardDesc)
            val useBtn: View = view.findViewById(R.id.reelsCardUseBtn)
            val useBtnText: TextView = view.findViewById(R.id.reelsCardUseBtnText)
        }

        override fun getItemViewType(position: Int): Int {
            return if (isGrid) VIEW_TYPE_MAST_GRID else VIEW_TYPE_REELS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_MAST_GRID) {
                MastGridViewHolder(inflater.inflate(R.layout.item_mast_template_card, parent, false))
            } else {
                ReelsFeedViewHolder(inflater.inflate(R.layout.item_reels_feed_card, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = templates[position]
            val density = holder.itemView.context.resources.displayMetrics.density

            if (holder is MastGridViewHolder) {
                holder.emoji.text = item.emoji
                holder.title.text = item.title
                holder.bpm.text = "${item.bpm} BPM"
                val sampleLyric = item.textOverlays.firstOrNull()?.text ?: "${item.emoji} ${item.title}"
                holder.lyricText.text = sampleLyric
                holder.photoPill.text = "📸 ${item.requiredPhotoCount} Photo${if (item.requiredPhotoCount > 1) "s" else ""}"
                holder.usageBadge.text = item.getViralUsageCount()

                // Load human model portrait preview (female, male, couple, celebration)
                val modelBmp = StatusSampleModelCache.getSampleBitmapForTemplate(
                    holder.itemView.context,
                    item,
                    targetWidth = (180 * density).toInt(),
                    targetHeight = (270 * density).toInt()
                )
                if (modelBmp != null) {
                    holder.photo?.setImageBitmap(modelBmp)
                    holder.photo?.let { it.visibility = View.VISIBLE }
                }

                try {
                    val startC = Color.parseColor(item.gradientStartHex)
                    val endC = Color.parseColor(item.gradientEndHex)
                    // Semi-transparent gradient tint overlay so human model photo is clearly visible
                    val alphaStart = Color.argb(85, Color.red(startC), Color.green(startC), Color.blue(startC))
                    val alphaEnd = Color.argb(165, Color.red(endC), Color.green(endC), Color.blue(endC))
                    val bg = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(alphaStart, alphaEnd)
                    ).apply { cornerRadius = 16f * density }
                    holder.cardBg.background = bg

                    val btnBg = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(startC, endC)
                    ).apply { cornerRadius = 10f * density }
                    holder.makeBtn.background = btnBg
                } catch (_: Exception) {}

                holder.itemView.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCardClick(item)
                }

                holder.makeBtn.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onUseTemplate(item)
                }
            } else if (holder is ReelsFeedViewHolder) {
                holder.centerEmoji.text = item.emoji
                holder.title.text = item.title
                holder.desc.text = item.description
                val sampleLyric = item.textOverlays.firstOrNull()?.text ?: "${item.emoji} ${item.title}"
                holder.lyricText.text = sampleLyric
                holder.subtitle.text = "${item.bpm} BPM • ${item.durationMs / 1000}s REEL"
                holder.useBtnText.text = "⚡ Use Template (${item.requiredPhotoCount}-Photo Ready)"

                if (item.isUserCreated) {
                    holder.communityBadge.text = "🌟 MY TEMPLATE"
                    holder.communityBadge.setTextColor(Color.parseColor("#FFE500"))
                } else {
                    holder.communityBadge.text = "✨ STORYLINE COMMUNITY"
                    holder.communityBadge.setTextColor(Color.WHITE)
                }

                val soundTrack = StatusSoundtrackRepository.getByAssetPath(item.audioAssetPath)
                val soundName = soundTrack?.title
                    ?: item.audioAssetPath.substringAfterLast("/").substringBeforeLast(".").replace("_", " ")
                holder.soundTitle.text = soundName

                // Load high-resolution human model portrait preview
                val modelBmp = StatusSampleModelCache.getSampleBitmapForTemplate(
                    holder.itemView.context,
                    item,
                    targetWidth = (360 * density).toInt(),
                    targetHeight = (440 * density).toInt()
                )
                if (modelBmp != null) {
                    holder.photo?.setImageBitmap(modelBmp)
                    holder.photo?.let { it.visibility = View.VISIBLE }
                }

                try {
                    val startC = Color.parseColor(item.gradientStartHex)
                    val endC = Color.parseColor(item.gradientEndHex)
                    // Dynamic color wash over model portrait
                    val alphaStart = Color.argb(75, Color.red(startC), Color.green(startC), Color.blue(startC))
                    val alphaEnd = Color.argb(150, Color.red(endC), Color.green(endC), Color.blue(endC))
                    val bg = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(alphaStart, alphaEnd)
                    )
                    holder.cardBg.background = bg

                    val btnBg = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(startC, endC)
                    ).apply { cornerRadius = 14f * density }
                    holder.useBtn.background = btnBg
                } catch (_: Exception) {}

                holder.soundPill.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onToggleSound?.invoke(item) { playing ->
                        holder.soundIcon.text = if (playing) "⏸️" else "🎵"
                        holder.soundTitle.setTextColor(if (playing) Color.parseColor("#FFE500") else Color.parseColor("#00F2FE"))
                    }
                }

                holder.itemView.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCardClick(item)
                }

                holder.useBtn.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onUseTemplate(item)
                }
            }
        }

        override fun getItemCount(): Int = templates.size
    }

    companion object {
        const val EXTRA_INITIAL_PHOTO_PATH = "extra_initial_photo_path"
        const val EXTRA_INITIAL_TEMPLATE_ID = "extra_initial_template_id"
    }
}
