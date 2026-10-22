package com.video.engine.status

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.video.engine.EditorLaunchIntents
import com.video.engine.R
import com.video.engine.VideoEditorActivity
import com.video.engine.VideoPreviewView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusQuickExportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROJECT_PATH = "extra_status_project_path"
        const val EXTRA_TEMPLATE_TITLE = "extra_status_template_title"
        const val EXTRA_TEMPLATE_DURATION_MS = "extra_status_template_duration_ms"
        const val EXTRA_TEMPLATE_GRADIENT_START = "extra_status_gradient_start"
        const val EXTRA_TEMPLATE_GRADIENT_END = "extra_status_gradient_end"
    }

    private var projectPath: String? = null
    private var templateTitle: String = "Status Video"
    private var durationMs: Long = 12000L
    private var gradientStart: String = "#FF416C"
    private var gradientEnd: String = "#8A2387"

    private lateinit var previewContainer: FrameLayout
    private lateinit var playPauseIcon: ImageView
    private lateinit var exportProgressOverlay: FrameLayout
    private lateinit var exportProgressBar: ProgressBar
    private lateinit var exportPercentText: TextView

    private var previewView: VideoPreviewView? = null
    private var isPlaying = true
    private var isExporting = false
    private val handler = Handler(Looper.getMainLooper())
    private var progressCheckRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_quick_export)

        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)
        templateTitle = intent.getStringExtra(EXTRA_TEMPLATE_TITLE) ?: "Status Video"
        durationMs = intent.getLongExtra(EXTRA_TEMPLATE_DURATION_MS, 12000L)
        gradientStart = intent.getStringExtra(EXTRA_TEMPLATE_GRADIENT_START) ?: "#FF416C"
        gradientEnd = intent.getStringExtra(EXTRA_TEMPLATE_GRADIENT_END) ?: "#8A2387"

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
                    16.dpToPx(this),
                    20.dpToPx(this),
                    systemBars.bottom + 20.dpToPx(this)
                )
                insets
            }
        }

        findViewById<TextView>(R.id.quickExportTitle)?.text = templateTitle
        findViewById<TextView>(R.id.quickExportDurationBadge)?.text = "${durationMs / 1000}s HD"
        findViewById<FrameLayout>(R.id.quickExportBackBtn)?.setOnClickListener { finish() }

        previewContainer = findViewById(R.id.previewSurfaceContainer)
        playPauseIcon = findViewById(R.id.quickExportPlayPauseIcon)
        exportProgressOverlay = findViewById(R.id.exportProgressOverlay)
        exportProgressBar = findViewById(R.id.exportProgressBar)
        exportPercentText = findViewById(R.id.exportPercentText)

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

        setupPreviewView()

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

    private fun setupPreviewView() {
        val pv = VideoPreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewView = pv
        previewContainer.addView(pv)

        val loadProjectRunnable = Runnable {
            projectPath?.let { path ->
                val loaded = pv.loadProject(path)
                if (loaded) {
                    pv.startPlayback(0)
                    isPlaying = true
                    playPauseIcon.visibility = View.GONE
                }
            }
        }

        pv.onSurfaceReady = {
            runOnUiThread(loadProjectRunnable)
        }

        // Post fallback in case surface was already bound
        pv.postDelayed(loadProjectRunnable, 300)

        // Tap on preview toggles playback
        previewContainer.setOnClickListener {
            togglePlayback()
        }
    }

    private fun togglePlayback() {
        val pv = previewView ?: return
        if (isPlaying) {
            pv.stopPlayback()
            isPlaying = false
            playPauseIcon.visibility = View.VISIBLE
        } else {
            pv.startPlayback(0)
            isPlaying = true
            playPauseIcon.visibility = View.GONE
        }
    }

    private fun startOneClickExport() {
        if (isExporting) return
        isExporting = true

        val pv = previewView ?: return
        pv.stopPlayback()
        isPlaying = false

        exportProgressOverlay.visibility = View.VISIBLE
        exportProgressBar.progress = 0
        exportPercentText.text = "Exporting Status Video... 0%"

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = templateTitle.replace(Regex("[^a-zA-Z0-9]"), "_")
        val exportFile = File(cacheDir, "Status_${safeTitle}_${timeStamp}.mp4")

        Thread {
            try {
                // Call VideoPreviewView native export
                val success = pv.exportToVideo(
                    outputPath = exportFile.absolutePath,
                    width = 1080,
                    height = 1920,
                    fps = 30,
                    bitrateMbps = 8,
                    videoCodec = "h264"
                )

                if (success && exportFile.exists() && exportFile.length() > 0) {
                    val savedFile = saveVideoToGallery(exportFile)
                    runOnUiThread {
                        exportProgressOverlay.visibility = View.GONE
                        isExporting = false
                        showExportSuccessDialog(savedFile ?: exportFile)
                    }
                } else {
                    runOnUiThread {
                        exportProgressOverlay.visibility = View.GONE
                        isExporting = false
                        Toast.makeText(this@StatusQuickExportActivity, "Export failed. You can open in Editor to export.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    exportProgressOverlay.visibility = View.GONE
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
                val p = pv.getExportProgress()
                if (p in 0..100) {
                    currentProgress = p
                } else {
                    // Simulated progress smooth increment while rendering
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
            e.printStackTrace()
            null
        }
    }

    private fun showExportSuccessDialog(videoFile: File) {
        val options = arrayOf(
            "Share to WhatsApp Status 🟢",
            "Share to Instagram Stories 🟣",
            "More Share Options 🚀",
            "Create Another Status 🔁"
        )

        AlertDialog.Builder(this)
            .setTitle("Status Video Ready! 🎉")
            .setMessage("Saved in full 1080p HD to your phone gallery (Movies/Storyline_Status).")
            .setItems(options) { _, which ->
                when (which) {
                    0, 1, 2 -> {
                        val uri: Uri = FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            videoFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (which == 0) setPackage("com.whatsapp")
                            else if (which == 1) setPackage("com.instagram.android")
                        }
                        try {
                            startActivity(shareIntent)
                        } catch (_: Exception) {
                            // Fallback to system chooser
                            startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share Status Video"
                            ))
                        }
                    }
                    3 -> finish()
                }
            }
            .setPositiveButton("Done") { _, _ -> finish() }
            .setCancelable(false)
            .show()
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

    override fun onPause() {
        previewView?.stopPlayback()
        isPlaying = false
        super.onPause()
    }

    override fun onDestroy() {
        progressCheckRunnable?.let { handler.removeCallbacks(it) }
        previewView?.stopPlayback()
        super.onDestroy()
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
