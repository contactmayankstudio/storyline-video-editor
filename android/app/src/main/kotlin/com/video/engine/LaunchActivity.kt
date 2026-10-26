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
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.photo.PhotoEditorActivity
import com.video.engine.photo.PhotoProjectStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LaunchActivity : ComponentActivity() {
    private var homeScrollView: NestedScrollView? = null
    private var recentProjectsList: RecyclerView? = null
    private var emptyProjectsContainer: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_shell)

        homeScrollView = findViewById(R.id.homeScrollView)
        homeScrollView?.clipToPadding = false

        findViewById<View>(R.id.startScreenOverlay)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                findViewById<View>(R.id.startHeaderRow)?.let { header ->
                    header.setPadding(header.paddingLeft, systemBars.top, header.paddingRight, header.paddingBottom)
                }
                homeScrollView?.let { scroll ->
                    scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, systemBars.bottom)
                }
                insets
            }
        }

        emptyProjectsContainer = findViewById(R.id.startRecentProjectsEmptyContainer)
        recentProjectsList = findViewById<RecyclerView?>(R.id.startRecentProjectsList)?.apply {
            layoutManager = LinearLayoutManager(this@LaunchActivity)
            adapter = ProjectListAdapter(emptyList(), onProjectClick = { })
        }

        // Main Create Actions
        findViewById<View>(R.id.startNewProjectButton).setOnClickListener {
            openEditor(EditorLaunchIntents.START_ACTION_NEW)
        }
        findViewById<View>(R.id.startStatusMakerButton)?.setOnClickListener {
            startActivity(Intent(this, com.video.engine.status.StatusMakerActivity::class.java))
        }
        findViewById<View>(R.id.startFunCameraButton)?.setOnClickListener {
            startActivity(Intent(this, com.video.engine.camera.FunCameraActivity::class.java))
        }
        findViewById<View>(R.id.startPhotoEditorButton).setOnClickListener {
            openPhotoEditor(resume = false)
        }
        emptyProjectsContainer?.setOnClickListener {
            openEditor(EditorLaunchIntents.START_ACTION_NEW)
        }

        refreshRecentProjects()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView?>(R.id.startSubtitle)?.text = getString(R.string.start_subtitle)
        setStartActionsEnabled(true)
        refreshRecentProjects()
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
                onProjectMore = { project, _ ->
                    showProjectOptions(project)
                },
                onProjectDelete = { project ->
                    confirmDeleteVideoProject(project)
                },
            )
        }
    }

    private fun showProjectOptions(project: File) {
        val displayName = getProjectDisplayName(project)
        val options = arrayOf("Rename", "Duplicate", "Delete")
        AlertDialog.Builder(this)
            .setTitle(displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRenameProject(project)
                    1 -> duplicateProject(project)
                    2 -> confirmDeleteVideoProject(project)
                }
            }
            .show()
    }

    private fun promptRenameProject(project: File) {
        val currentName = getProjectDisplayName(project)
        val input = android.widget.EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            setPadding(48, 32, 48, 32)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Project")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != currentName) {
                    val renamed = RecentProjectFiles.rename(this, project, newName)
                    if (renamed != null) {
                        Toast.makeText(this, "Project renamed", Toast.LENGTH_SHORT).show()
                        refreshRecentProjects()
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun duplicateProject(project: File) {
        val duplicated = RecentProjectFiles.duplicate(this, project)
        if (duplicated != null) {
            Toast.makeText(this, "Project duplicated", Toast.LENGTH_SHORT).show()
            refreshRecentProjects()
        } else {
            Toast.makeText(this, "Duplicate failed", Toast.LENGTH_SHORT).show()
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

    private fun getProjectDisplayName(project: File): String {
        if (project.isAutoSaveEntry()) {
            return "Video Draft"
        }
        val raw = project.nameWithoutExtension
        return raw.replace(Regex("_[0-9]{8}_[0-9]{6}$"), "")
    }

    private fun File.isAutoSaveEntry(): Boolean {
        return name.equals("autosave.vne", ignoreCase = true) ||
            name.startsWith("autosave_", ignoreCase = true) ||
            parentFile?.name == "autosave" ||
            parentFile?.name == "backups"
    }

    private fun setStartActionsEnabled(enabled: Boolean) {
        findViewById<View?>(R.id.startNewProjectButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startStatusMakerButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startFunCameraButton)?.isEnabled = enabled
        findViewById<View?>(R.id.startPhotoEditorButton)?.isEnabled = enabled
    }
}
