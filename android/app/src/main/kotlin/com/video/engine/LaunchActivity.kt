package com.video.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.photo.PhotoEditorActivity
import com.video.engine.photo.PhotoProjectStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LaunchActivity : Activity() {
    private var homeScrollView: NestedScrollView? = null
    private var recentProjectsList: RecyclerView? = null
    private var emptyProjectsContainer: View? = null
    private var navCreateIcon: ImageView? = null
    private var navCreateLabel: TextView? = null
    private var navProjectsIcon: ImageView? = null
    private var navProjectsLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_shell)

        homeScrollView = findViewById(R.id.homeScrollView)
        emptyProjectsContainer = findViewById(R.id.startRecentProjectsEmptyContainer)
        recentProjectsList = findViewById<RecyclerView?>(R.id.startRecentProjectsList)?.apply {
            layoutManager = LinearLayoutManager(this@LaunchActivity)
            adapter = ProjectListAdapter(emptyList(), onProjectClick = { })
        }

        navCreateIcon = findViewById(R.id.navCreateIcon)
        navCreateLabel = findViewById(R.id.navCreateLabel)
        navProjectsIcon = findViewById(R.id.navProjectsIcon)
        navProjectsLabel = findViewById(R.id.navProjectsLabel)

        // Main Create Actions
        findViewById<View>(R.id.startNewProjectButton).setOnClickListener {
            openEditor(EditorLaunchIntents.START_ACTION_NEW)
        }
        findViewById<View>(R.id.startPhotoEditorButton).setOnClickListener {
            openPhotoEditor(resume = false)
        }

        // Bottom Navigation
        findViewById<View>(R.id.navCreate).setOnClickListener {
            updateNavSelection(isCreate = true)
            homeScrollView?.smoothScrollTo(0, 0)
        }
        findViewById<View>(R.id.navProjects).setOnClickListener {
            updateNavSelection(isCreate = false)
            val recentLabel = findViewById<View>(R.id.startRecentWorkLabel)
            if (recentLabel != null) {
                homeScrollView?.smoothScrollTo(0, recentLabel.top)
            }
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshRecentProjects()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView?>(R.id.startSubtitle)?.text = getString(R.string.start_subtitle)
        setStartActionsEnabled(true)
        updateNavSelection(isCreate = true)
        refreshRecentProjects()
    }

    private fun updateNavSelection(isCreate: Boolean) {
        val accentBlue = Color.parseColor("#388BFD")
        val mutedGray = Color.parseColor("#8A99AD")

        navCreateIcon?.setColorFilter(if (isCreate) accentBlue else mutedGray)
        navCreateLabel?.setTextColor(if (isCreate) accentBlue else mutedGray)
        navProjectsIcon?.setColorFilter(if (!isCreate) accentBlue else mutedGray)
        navProjectsLabel?.setTextColor(if (!isCreate) accentBlue else mutedGray)
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

    private fun refreshRecentProjects() {
        val recentProjects = RecentProjectFiles.all(this)
        val list = recentProjectsList ?: return
        val emptyContainer = emptyProjectsContainer

        if (recentProjects.isEmpty()) {
            emptyContainer?.visibility = View.VISIBLE
            list.visibility = View.GONE
            list.adapter = ProjectListAdapter(emptyList(), onProjectClick = { })
        } else {
            emptyContainer?.visibility = View.GONE
            list.visibility = View.VISIBLE
            list.adapter = ProjectListAdapter(
                projects = recentProjects,
                onProjectClick = { project ->
                    openEditor(EditorLaunchIntents.START_ACTION_OPEN, project.absolutePath)
                },
                onProjectDelete = { project ->
                    confirmDeleteVideoProject(project)
                },
            )
        }
    }

    private fun confirmDeleteVideoProject(project: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete video draft?")
            .setMessage("This removes the saved video draft and its editor state.")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = RecentProjectFiles.delete(this, project)
                Toast.makeText(this, if (deleted) "Video draft deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                refreshRecentProjects()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setStartActionsEnabled(enabled: Boolean) {
        findViewById<View?>(R.id.startNewProjectButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startPhotoEditorButton)?.isEnabled = enabled
    }
}
