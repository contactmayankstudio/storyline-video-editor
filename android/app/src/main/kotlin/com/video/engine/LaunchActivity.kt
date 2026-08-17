package com.video.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.photo.PhotoEditorActivity
import com.video.engine.photo.PhotoProjectStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LaunchActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val homeBannerRunnable = Runnable {
        attachHomeBanner()
    }
    private var recentProjectsList: RecyclerView? = null
    private var homeBannerContainer: FrameLayout? = null
    private var adsController: AdsController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_shell)
        homeBannerContainer = findViewById(R.id.startTopBannerContainer)
        adsController = AdsController(this)
        recentProjectsList = findViewById<RecyclerView?>(R.id.startRecentProjectsList)?.apply {
            layoutManager = LinearLayoutManager(this@LaunchActivity)
            adapter = ProjectListAdapter(emptyList(), onProjectClick = { })
        }

        findViewById<View>(R.id.startNewProjectButton).setOnClickListener {
            openEditor(EditorLaunchIntents.START_ACTION_NEW)
        }
        findViewById<View>(R.id.startOpenProjectButton).setOnClickListener {
            openEditor(EditorLaunchIntents.START_ACTION_OPEN)
        }
        findViewById<View>(R.id.startImportMediaButton).setOnClickListener {
            openEditor(EditorLaunchIntents.START_ACTION_IMPORT)
        }
        findViewById<View>(R.id.startPhotoEditorButton).setOnClickListener {
            openPhotoEditor(resume = false)
        }
        refreshRecentProjectHint()
        scheduleHomeBanner(delayMs = 2_500L)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView?>(R.id.startSubtitle)?.text = getString(R.string.start_subtitle)
        setStartActionsEnabled(true)
        refreshRecentProjectHint()
        adsController?.onResume()
        scheduleHomeBanner(delayMs = 1_200L)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(homeBannerRunnable)
        adsController?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(homeBannerRunnable)
        adsController?.onDestroy()
        super.onDestroy()
    }

    private fun openEditor(action: String, projectPath: String? = null) {
        findViewById<TextView?>(R.id.startSubtitle)?.text = "Loading editor engine..."
        setStartActionsEnabled(false)
        val nextIntent = EditorLaunchIntents.markForEditorBoot(this, Intent(this, VideoEditorActivity::class.java))
            .putExtra(EditorLaunchIntents.EXTRA_START_SHELL_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (!projectPath.isNullOrBlank()) {
            nextIntent.putExtra(EditorLaunchIntents.EXTRA_START_PROJECT_PATH, projectPath)
        }
        startActivity(nextIntent)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun openPhotoEditor(resume: Boolean, projectPath: String? = null) {
        findViewById<TextView?>(R.id.startSubtitle)?.text = "Opening photo editor..."
        setStartActionsEnabled(false)
        val nextIntent = Intent(this, PhotoEditorActivity::class.java)
            .putExtra(PhotoEditorActivity.EXTRA_RESUME_PHOTO, resume)
        if (!projectPath.isNullOrBlank()) {
            nextIntent.putExtra(PhotoEditorActivity.EXTRA_PHOTO_PROJECT_PATH, projectPath)
        }
        startActivity(nextIntent)
    }

    private fun scheduleHomeBanner(delayMs: Long) {
        if (!resources.getBoolean(R.bool.storyline_runtime_startup_ads_enabled)) {
            adsController?.releaseBanner(homeBannerContainer)
            return
        }
        mainHandler.removeCallbacks(homeBannerRunnable)
        mainHandler.postDelayed(homeBannerRunnable, delayMs.coerceAtLeast(600L))
    }

    private fun attachHomeBanner() {
        if (isFinishing || isDestroyed) return
        if (!resources.getBoolean(R.bool.storyline_runtime_startup_ads_enabled)) return
        adsController?.attachTopBanner(homeBannerContainer)
    }

    private fun refreshRecentProjectHint() {
        val recentText = findViewById<TextView?>(R.id.startRecentProjectsEmptyText) ?: return
        val recentProjects = RecentProjectFiles.all(this)
        val recentPhotos = PhotoProjectStore.all(this)
        refreshRecentSummary(recentProjects.size, recentPhotos.size)
        refreshRecentVideoList(recentProjects)
        if (recentProjects.isEmpty()) {
            recentText.text = getString(R.string.recent_project_button_empty)
            recentText.isClickable = false
            recentText.isFocusable = false
            recentText.alpha = 0.62f
            recentText.setOnClickListener(null)
            refreshRecentPhotoHint(recentPhotos)
            return
        }
        recentText.text = getString(R.string.recent_project_button_resume)
        recentText.isClickable = true
        recentText.isFocusable = true
        recentText.alpha = 1f
        recentText.setOnClickListener {
            showVideoProjectList(recentProjects)
        }
        refreshRecentPhotoHint(recentPhotos)
    }

    private fun refreshRecentSummary(videoCount: Int, photoCount: Int) {
        val summaryText = findViewById<TextView?>(R.id.startRecentSummaryText) ?: return
        summaryText.text = if (videoCount == 0 && photoCount == 0) {
            getString(R.string.recent_projects_empty)
        } else {
            getString(R.string.recent_projects_summary, videoCount, photoCount)
        }
    }

    private fun refreshRecentVideoList(projects: List<File>) {
        val list = recentProjectsList ?: return
        if (projects.isEmpty()) {
            list.visibility = View.GONE
            list.adapter = ProjectListAdapter(emptyList(), onProjectClick = { })
            return
        }
        list.visibility = View.VISIBLE
        list.adapter = ProjectListAdapter(
            projects = projects,
            onProjectClick = { project ->
                openEditor(EditorLaunchIntents.START_ACTION_OPEN, project.absolutePath)
            },
            onProjectDelete = { project ->
                confirmDeleteVideoProject(project)
            },
        )
    }

    private fun refreshRecentPhotoHint(recentPhotos: List<File> = PhotoProjectStore.all(this)) {
        val recentPhotoText = findViewById<TextView?>(R.id.startRecentPhotoText) ?: return
        if (recentPhotos.isEmpty()) {
            recentPhotoText.text = getString(R.string.recent_photo_button_empty)
            recentPhotoText.isClickable = false
            recentPhotoText.isFocusable = false
            recentPhotoText.alpha = 0.62f
            recentPhotoText.setOnClickListener(null)
            return
        }
        recentPhotoText.text = getString(R.string.recent_photo_button_resume)
        recentPhotoText.isClickable = true
        recentPhotoText.isFocusable = true
        recentPhotoText.alpha = 1f
        recentPhotoText.setOnClickListener {
            showPhotoProjectList(recentPhotos)
        }
    }

    private fun showVideoProjectList(projects: List<File>) {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Recent Video Projects")
            .setItems(projects.mapIndexed { index, file -> formatVideoProjectRow(index, file) }.toTypedArray()) { _, which ->
                projects.getOrNull(which)?.let { project ->
                    openEditor(EditorLaunchIntents.START_ACTION_OPEN, project.absolutePath)
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                showDeleteVideoProjectList(projects)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPhotoProjectList(projects: List<File>) {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Recent Photo Projects")
            .setItems(projects.mapIndexed { index, file -> formatPhotoProjectRow(index, file) }.toTypedArray()) { _, which ->
                projects.getOrNull(which)?.let { project ->
                    openPhotoEditor(resume = true, projectPath = project.absolutePath)
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                showDeletePhotoProjectList(projects)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteVideoProjectList(projects: List<File>) {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete Video Project")
            .setItems(projects.mapIndexed { index, file -> formatVideoProjectRow(index, file) }.toTypedArray()) { _, which ->
                projects.getOrNull(which)?.let(::confirmDeleteVideoProject)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeletePhotoProjectList(projects: List<File>) {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete Photo Project")
            .setItems(projects.mapIndexed { index, file -> formatPhotoProjectRow(index, file) }.toTypedArray()) { _, which ->
                projects.getOrNull(which)?.let(::confirmDeletePhotoProject)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteVideoProject(project: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete video draft?")
            .setMessage("This removes the saved video draft and its editor state.")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = RecentProjectFiles.delete(this, project)
                Toast.makeText(this, if (deleted) "Video draft deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                refreshRecentProjectHint()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePhotoProject(project: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete photo project?")
            .setMessage("This removes the saved photo project.")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = PhotoProjectStore.delete(this, project)
                Toast.makeText(this, if (deleted) "Photo project deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                refreshRecentProjectHint()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatVideoProjectRow(index: Int, file: File): String {
        val name = if (file.name.equals("autosave.vne", ignoreCase = true) || file.parentFile?.name == "backups") {
            "Video Draft ${index + 1}"
        } else {
            file.nameWithoutExtension.replace(Regex("_[0-9]{8}_[0-9]{6}$"), "")
        }
        return "$name\n${formatUpdatedAt(file)}"
    }

    private fun formatPhotoProjectRow(index: Int, file: File): String {
        val name = PhotoProjectStore.displayName(file).ifBlank { "Photo Draft ${index + 1}" }
        return "$name\n${formatUpdatedAt(file)}"
    }

    private fun formatUpdatedAt(file: File): String {
        val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        return "Updated $date"
    }

    private fun setStartActionsEnabled(enabled: Boolean) {
        findViewById<View?>(R.id.startNewProjectButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startOpenProjectButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startImportMediaButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startPhotoEditorButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startRecentProjectsEmptyText)?.isEnabled = enabled
        findViewById<View?>(R.id.startRecentPhotoText)?.isEnabled = enabled
    }
}
