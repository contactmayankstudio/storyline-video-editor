package com.video.engine

import android.app.Activity
import android.util.Log
import com.video.engine.UiToast as Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val hasProjectContentProvider: () -> Boolean,
    private val isPlayingProvider: () -> Boolean,
    private val onPausePlayback: () -> Unit,
    private val onSaveUiState: (projectFile: File, projectName: String) -> Unit,
    private val onPrepareLoadedProject: () -> Unit,
    private val onProjectLoaded: (filePath: String, previewView: VideoPreviewView) -> Unit,
    private val onProjectLoadFinished: () -> Unit,
) {
    companion object { private const val TAG = "[UI]" }

    private fun autoSaveFile(): File =
        File(activity.getExternalFilesDir(null), "autosave/autosave.vne")

    private fun autoSaveUiFile(): File =
        File(activity.getExternalFilesDir(null), "autosave/autosave.ui.json")

    fun showSaveProjectDialog() {
        if (previewViewProvider() == null) {
            Toast.makeText(activity, "Preview view not available", Toast.LENGTH_SHORT).show()
            return
        }
        ModernSheet.show(activity, "Save Project") {
            textInput("Project Name", "Untitled Project") { }
            chips("", listOf("Save"), -1) { _, _ ->
                val name = getTextInput().ifEmpty { "Untitled Project" }
                performSaveProject(name)
            }
        }
    }

    fun showLoadProjectDialog() {
        if (previewViewProvider() == null) {
            Toast.makeText(activity, "Preview view not available", Toast.LENGTH_SHORT).show()
            return
        }
        val projectsDir = File(activity.getExternalFilesDir(null), "projects")
        val projectFiles = projectsDir.listFiles { f -> f.isFile && f.extension == "vne" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (projectFiles.isEmpty()) {
            Toast.makeText(activity, "No saved projects", Toast.LENGTH_SHORT).show()
            return
        }
        val displayNames = projectFiles.map { file ->
            file.nameWithoutExtension.replace(Regex("_[0-9]{8}_[0-9]{6}$"), "")
        }
        ModernSheet.show(activity, "Load Project") {
            chips("Select", displayNames, -1) { i, _ ->
                performLoadProject(projectFiles[i].absolutePath)
            }
        }
    }

    fun autoSave() {
        if (!hasProjectContentProvider()) {
            Log.i(TAG, "[Project] autosave skipped: no project content")
            return
        }
        val projectsDir = File(activity.getExternalFilesDir(null), "autosave").also { it.mkdirs() }
        val outputFile = autoSaveFile()
        Log.i(TAG, "[Project] autosave requested path=${outputFile.absolutePath}")
        Thread {
            try {
                val success = previewViewProvider()?.saveProject(outputFile.absolutePath, "autosave") ?: false
                if (success) onSaveUiState(outputFile, "autosave")
                Log.i(TAG, "[Project] autosave complete success=$success path=${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Auto-save failed: ${e.message}")
            }
        }.start()
    }

    fun restoreAutoSave(): Boolean {
        val autoSaveFile = autoSaveFile()
        if (!autoSaveFile.exists()) return false
        loadProject(autoSaveFile.absolutePath)
        return true
    }

    fun hasAutoSave(): Boolean = autoSaveFile().exists()

    fun describeAutoSave(): String? {
        val file = autoSaveFile()
        if (!file.exists()) return null
        val modified = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(file.lastModified()))
        return "Last autosave: $modified"
    }

    fun discardAutoSave() {
        runCatching { autoSaveFile().delete() }
        runCatching { autoSaveUiFile().delete() }
        Log.i(TAG, "[Project] autosave discarded")
    }

    fun loadProject(filePath: String) {
        performLoadProject(filePath)
    }

    private fun performSaveProject(projectName: String) {
        val projectsDir = File(activity.getExternalFilesDir(null), "projects").also { it.mkdirs() }
        val sanitized = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(projectsDir, "${sanitized}_${ts}.vne")
        Thread {
            try {
                val success = previewViewProvider()?.saveProject(outputFile.absolutePath, projectName) ?: false
                if (success) onSaveUiState(outputFile, projectName)
                Log.i(TAG, "[Project] save complete success=$success path=${outputFile.absolutePath}")
                activity.runOnUiThread {
                    Toast.makeText(activity, if (success) "Saved: ${outputFile.name}" else "Save failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                activity.runOnUiThread { Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun performLoadProject(filePath: String) {
        if (isPlayingProvider()) onPausePlayback()
        Thread {
            try {
                val success = previewViewProvider()?.loadProject(filePath) ?: false
                Log.i(TAG, "[Project] load complete success=$success path=$filePath")
                activity.runOnUiThread {
                    if (success) {
                        onPrepareLoadedProject()
                        previewViewProvider()?.let { onProjectLoaded(filePath, it) }
                        Toast.makeText(activity, "Loaded: ${File(filePath).name}", Toast.LENGTH_SHORT).show()
                        onProjectLoadFinished()
                    } else {
                        Toast.makeText(activity, "Load failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load failed", e)
                activity.runOnUiThread { Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
