package com.video.engine.status

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
                prebuiltProject = StatusProjectBuilder.buildProjectFile(this, template, localPaths)
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
                activePreviewDialog?.findViewById<TextView>(R.id.statusPreviewTestPhotoText)?.text = "Change Photo 🔄"
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

        // Prominent 1-Photo Instant Hero Card: click -> pick photo -> immediate live preview!
        findViewById<View>(R.id.statusInstantHeroCard)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pendingTemplate = StatusTemplateRepository.allTemplates.first()
            pickPhotosLauncher.launch("image/*")
        }

        categoryContainer = findViewById(R.id.statusCategoryChipsContainer)
        templatesRecycler = findViewById(R.id.statusTemplatesRecyclerView)
        templatesRecycler.layoutManager = LinearLayoutManager(this)

        setupCategoryChips()
        updateTemplatesList()
    }

    private fun setupCategoryChips() {
        categoryContainer.removeAllViews()
        StatusTemplateRepository.categories.forEach { category ->
            val isSelected = category.equals(currentCategory, ignoreCase = true)
            val count = StatusTemplateRepository.getByCategory(category).size
            val label = when {
                category.equals("all", ignoreCase = true) -> "🔥 All ($count)"
                category.contains("CapCut", ignoreCase = true) -> "⚡ CapCut Pro ($count)"
                category.contains("1-Photo", ignoreCase = true) -> "📸 1-Photo Instant ($count)"
                category.equals("trending", ignoreCase = true) -> "🔥 Viral & Trending ($count)"
                category.equals("love", ignoreCase = true) -> "💖 Love & Couple ($count)"
                category.equals("attitude", ignoreCase = true) -> "😎 Attitude & Swag ($count)"
                category.equals("emotional", ignoreCase = true) -> "🥺 Emotional & Memories ($count)"
                category.equals("celebration", ignoreCase = true) -> "🎉 Celebration & Festival ($count)"
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
                        setColor(Color.parseColor("#262C36"))
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
        val list = StatusTemplateRepository.getByCategory(currentCategory)
        templatesRecycler.adapter = StatusTemplateAdapter(
            list,
            onCardClick = { template ->
                showTemplateLivePreviewDialog(template)
            },
            onUseTemplate = { template ->
                pendingTemplate = template
                pickPhotosLauncher.launch("image/*")
            }
        )
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
        val ctaBtn = dialogView.findViewById<View>(R.id.statusPreviewCtaBtn)
        val ctaText = dialogView.findViewById<TextView>(R.id.statusPreviewCtaText)
        val testPhotoBtn = dialogView.findViewById<View>(R.id.statusPreviewTestPhotoBtn)
        val testPhotoText = dialogView.findViewById<TextView>(R.id.statusPreviewTestPhotoText)
        val nextTemplateBtn = dialogView.findViewById<View>(R.id.statusPreviewNextTemplateBtn)

        fun bindTemplateState(tmpl: StatusTemplate) {
            currentActiveTemplate = tmpl
            titleTv?.text = if (activePhotoPath != null) "Preview: ${tmpl.title}" else tmpl.title
            categoryBadge?.text = "${tmpl.emoji} ${tmpl.category.uppercase()}"
            durationChip?.text = "⏱️ ${tmpl.durationMs / 1000}s"
            photosChip?.text = if (activePhotoPath != null) "📸 Photo Loaded" else "⚡ 1-Photo Ready"
            bpmChip?.text = "🎵 ${tmpl.bpm} BPM"
            descTv?.text = if (activePhotoPath != null) "⚡ Live animated preview of your photo with ${tmpl.title}! Tap Export to save." else tmpl.description
            ctaText?.text = if (activePhotoPath != null) "⚡ 1-CLICK EXPORT TO GALLERY" else "⚡ CREATE 1-PHOTO / REELS STATUS"
            testPhotoText?.text = if (activePhotoPath != null) "Change Photo 🔄" else "Test With My Photo 📷"

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
                assets.openFd(tmpl.audioAssetPath).use { afd ->
                    val mp = MediaPlayer().apply {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        isLooping = true
                        prepare()
                        start()
                    }
                    previewMediaPlayer = mp
                }
            } catch (_: Exception) {}

            livePreviewView?.bindTemplate(tmpl, previewMediaPlayer)
            if (activePhotoPath != null) {
                livePreviewView?.setCustomPhoto(activePhotoPath)
            }
        }

        bindTemplateState(initialTemplate)

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
                    currentProjectFile = StatusProjectBuilder.buildProjectFile(this@StatusMakerActivity, nextTmpl, activePhotoPaths)
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
                dialog.dismiss()
                val photosToUse = activePhotoPaths.ifEmpty { listOf(activePhotoPath) }
                if (currentProjectFile != null && currentProjectFile!!.exists()) {
                    launchQuickExport(currentProjectFile!!, currentActiveTemplate, photosToUse)
                } else {
                    Toast.makeText(this@StatusMakerActivity, "Preparing Export...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val proj = StatusProjectBuilder.buildProjectFile(this@StatusMakerActivity, currentActiveTemplate, photosToUse)
                        runOnUiThread {
                            if (proj != null && proj.exists()) {
                                launchQuickExport(proj, currentActiveTemplate, photosToUse)
                            } else {
                                Toast.makeText(this@StatusMakerActivity, "Failed to build status project", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
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

    private fun launchQuickExport(projectFile: File, template: StatusTemplate, photoPaths: List<String>) {
        val quickExportIntent = Intent(this, StatusQuickExportActivity::class.java).apply {
            putExtra(StatusQuickExportActivity.EXTRA_PROJECT_PATH, projectFile.absolutePath)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_ID, template.id)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_TITLE, template.title)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_DURATION_MS, template.durationMs)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_GRADIENT_START, template.gradientStartHex)
            putExtra(StatusQuickExportActivity.EXTRA_TEMPLATE_GRADIENT_END, template.gradientEndHex)
            putStringArrayListExtra(StatusQuickExportActivity.EXTRA_PHOTO_PATHS, ArrayList(photoPaths))
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

    override fun onDestroy() {
        activePreviewDialog?.dismiss()
        stopPreviewAudio()
        super.onDestroy()
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private class StatusTemplateAdapter(
        private val templates: List<StatusTemplate>,
        private val onCardClick: (StatusTemplate) -> Unit,
        private val onUseTemplate: (StatusTemplate) -> Unit
    ) : RecyclerView.Adapter<StatusTemplateAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val emoji: TextView = view.findViewById(R.id.templateEmoji)
            val title: TextView = view.findViewById(R.id.templateTitle)
            val durationChip: TextView = view.findViewById(R.id.templateDurationChip)
            val photosChip: TextView = view.findViewById(R.id.templatePhotosChip)
            val bpmChip: TextView = view.findViewById(R.id.templateBpmChip)
            val desc: TextView = view.findViewById(R.id.templateDesc)
            val previewBtn: View = view.findViewById(R.id.previewTemplateButton)
            val useBtn: View = view.findViewById(R.id.useTemplateButton)
            val useBtnText: TextView = view.findViewById(R.id.useTemplateButtonText)
            val previewBanner: View = view.findViewById(R.id.templatePreviewBanner)
            val bannerBg: View = view.findViewById(R.id.templateBannerBg)
            val bannerBadge: TextView = view.findViewById(R.id.templateBannerBadge)
            val bannerBpmPill: TextView = view.findViewById(R.id.templateBannerBpmPill)
            val bannerLyricText: TextView = view.findViewById(R.id.templateBannerLyricText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_status_template, parent, false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = templates[position]
            holder.emoji.text = item.emoji
            holder.title.text = item.title
            holder.durationChip.text = "${item.durationMs / 1000}s"
            holder.photosChip.text = "⚡ 1-Photo Ready"
            holder.bpmChip.text = "${item.bpm} BPM"
            holder.desc.text = item.description
            holder.useBtnText.text = "⚡ 1-Photo Create"
            holder.bannerBpmPill.text = "🎵 ${item.bpm} BPM"
            val sampleLyric = item.textOverlays.firstOrNull()?.text ?: "${item.emoji} ${item.title}"
            holder.bannerLyricText.text = sampleLyric

            val density = holder.itemView.context.resources.displayMetrics.density

            // Dynamic Gradient Badge for Emoji, Banner & CTA
            try {
                val startC = Color.parseColor(item.gradientStartHex)
                val endC = Color.parseColor(item.gradientEndHex)

                val emojiBg = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(startC, endC)
                ).apply {
                    cornerRadius = 14f * density
                }
                holder.emoji.background = emojiBg

                val bannerBgDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(startC, endC)
                ).apply {
                    cornerRadius = 14f * density
                }
                holder.bannerBg.background = bannerBgDrawable

                // Dynamic Gradient for Use Template Button
                val btnBg = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(startC, endC)
                ).apply {
                    cornerRadius = 12f * density
                }
                holder.useBtn.background = btnBg
            } catch (_: Exception) {}

            // Banner Click -> Live Preview with music
            holder.previewBanner.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onCardClick(item)
            }

            // Preview Button click -> Live Preview with music
            holder.previewBtn.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onCardClick(item)
            }

            // Card click -> Also opens Live Preview with music
            holder.itemView.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onCardClick(item)
            }

            // Direct Use button click -> Photo picker directly
            holder.useBtn.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onUseTemplate(item)
            }
        }

        override fun getItemCount(): Int = templates.size
    }
}
