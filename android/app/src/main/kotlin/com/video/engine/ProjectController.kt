package com.video.engine

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val shouldDeferHeavyWorkProvider: () -> Boolean = { false },
    private val onPausePlayback: () -> Unit,
    private val onSaveUiState: (projectFile: File, projectName: String) -> Unit,
    private val onPrepareProjectLoadSurface: () -> Unit,
    private val onPrepareLoadedProject: () -> Unit,
    private val onProjectLoaded: (filePath: String, previewView: VideoPreviewView) -> Unit,
    private val onProjectLoadFinished: () -> Unit,
) {
    companion object {
        private const val TAG = "[UI]"
        private const val DEFERRED_AUTOSAVE_DELAY_MS = 2500L
        private const val BUSY_AUTOSAVE_RETRY_DELAY_MS = 9000L
        private const val BUSY_AUTOSAVE_MAX_RETRY_DELAY_MS = 30000L
        private const val BUSY_AUTOSAVE_LOG_INTERVAL_MS = 15000L
        private const val LOAD_PREVIEW_READY_RETRY_DELAY_MS = 350L
        private const val LOAD_PREVIEW_READY_MAX_ATTEMPTS = 24
        private const val AUTOSAVE_BACKUP_MIN_INTERVAL_MS = 60_000L
        private const val AUTOSAVE_BACKUP_MAX_FILES = 200
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var autoSaveInFlight = false
    private var pendingAutoSaveRunnable: Runnable? = null
    private var busyAutoSaveRetryCount = 0
    private var pendingAutoSaveRunAtElapsedMs = 0L
    private var lastBusyAutoSaveLogElapsedMs = 0L
    private var lastAutoSaveBackupElapsedMs = 0L

    private fun autoSaveFile(): File =
        File(activity.getExternalFilesDir(null), "autosave/autosave.vne")

    private fun autoSaveUiFile(): File =
        File(activity.getExternalFilesDir(null), "autosave/autosave.ui.json")

    private fun autoSaveBackupDir(): File =
        RecentProjectFiles.autoSaveBackupDir(activity).apply { mkdirs() }

    fun showSaveProjectDialog() {
        if (previewViewProvider() == null) {
            Toast.makeText(activity, "Preview view not available", Toast.LENGTH_SHORT).show()
            return
        }
        ModernSheet.show(activity, "Save Project") {
            textInput("Project Name", "Untitled Project") { }
            chips("", listOf("Save"), -1, dismissOnSelect = true) { _, _ ->
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

    fun autoSave(force: Boolean = true) {
        if (!hasProjectContentProvider()) {
            Log.i(TAG, "[Project] autosave skipped: no project content")
            return
        }
        if (!force && shouldDeferHeavyWorkProvider()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBusyAutoSaveLogElapsedMs >= BUSY_AUTOSAVE_LOG_INTERVAL_MS) {
                Log.i(TAG, "[Project] autosave deferred: heavy work active")
                lastBusyAutoSaveLogElapsedMs = now
            }
            busyAutoSaveRetryCount = (busyAutoSaveRetryCount + 1).coerceAtMost(6)
            scheduleAutoSave(resolveBusyAutoSaveRetryDelayMs())
            return
        }
        if (autoSaveInFlight) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBusyAutoSaveLogElapsedMs >= BUSY_AUTOSAVE_LOG_INTERVAL_MS) {
                Log.i(TAG, "[Project] autosave deferred: save already running")
                lastBusyAutoSaveLogElapsedMs = now
            }
            scheduleAutoSave(BUSY_AUTOSAVE_RETRY_DELAY_MS)
            return
        }
        busyAutoSaveRetryCount = 0
        lastBusyAutoSaveLogElapsedMs = 0L
        cancelPendingAutoSave()
        val projectsDir = File(activity.getExternalFilesDir(null), "autosave").also { it.mkdirs() }
        val outputFile = autoSaveFile()
        Log.i(TAG, "[Project] autosave requested path=${outputFile.absolutePath}")
        autoSaveInFlight = true
        Thread {
            try {
                backupExistingAutoSave("autosave")
                val success = previewViewProvider()?.saveProject(outputFile.absolutePath, "autosave") ?: false
                if (success) onSaveUiState(outputFile, "autosave")
                Log.i(TAG, "[Project] autosave complete success=$success path=${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Auto-save failed: ${e.message}")
            } finally {
                autoSaveInFlight = false
            }
        }.start()
    }

    fun autoSaveNow(reason: String = "manual"): Boolean {
        if (!hasProjectContentProvider()) {
            Log.i(TAG, "[Project] autosave-now skipped: no project content reason=$reason")
            return false
        }
        if (autoSaveInFlight) {
            Log.i(TAG, "[Project] autosave-now skipped: save already running reason=$reason")
            scheduleAutoSave(BUSY_AUTOSAVE_RETRY_DELAY_MS)
            return false
        }
        busyAutoSaveRetryCount = 0
        lastBusyAutoSaveLogElapsedMs = 0L
        cancelPendingAutoSave()
        File(activity.getExternalFilesDir(null), "autosave").mkdirs()
        val outputFile = autoSaveFile()
        autoSaveInFlight = true
        return try {
            Log.i(TAG, "[Project] autosave-now requested reason=$reason path=${outputFile.absolutePath}")
            backupExistingAutoSave(reason)
            val success = previewViewProvider()?.saveProject(outputFile.absolutePath, "autosave") ?: false
            if (success) onSaveUiState(outputFile, "autosave")
            Log.i(TAG, "[Project] autosave-now complete reason=$reason success=$success path=${outputFile.absolutePath}")
            success
        } catch (e: Exception) {
            Log.w(TAG, "Auto-save-now failed reason=$reason: ${e.message}")
            false
        } finally {
            autoSaveInFlight = false
        }
    }

    fun scheduleAutoSave(delayMs: Long = DEFERRED_AUTOSAVE_DELAY_MS) {
        if (!hasProjectContentProvider()) {
            return
        }
        val safeDelayMs = delayMs.coerceAtLeast(400L)
        val targetRunAt = SystemClock.elapsedRealtime() + safeDelayMs
        val existingRunAt = pendingAutoSaveRunAtElapsedMs
        if (pendingAutoSaveRunnable != null && existingRunAt > 0L && existingRunAt <= targetRunAt + 250L) {
            return
        }
        pendingAutoSaveRunnable?.let(mainHandler::removeCallbacks)
        val runnable =
            Runnable {
                pendingAutoSaveRunnable = null
                pendingAutoSaveRunAtElapsedMs = 0L
                autoSave(force = false)
            }
        pendingAutoSaveRunnable = runnable
        pendingAutoSaveRunAtElapsedMs = targetRunAt
        Log.i(TAG, "[Project] autosave scheduled delay=${safeDelayMs}ms")
        mainHandler.postDelayed(runnable, safeDelayMs)
    }

    fun cancelPendingAutoSave() {
        pendingAutoSaveRunnable?.let(mainHandler::removeCallbacks)
        pendingAutoSaveRunnable = null
        pendingAutoSaveRunAtElapsedMs = 0L
    }

    private fun resolveBusyAutoSaveRetryDelayMs(): Long {
        val steppedDelay = BUSY_AUTOSAVE_RETRY_DELAY_MS * busyAutoSaveRetryCount.coerceAtLeast(1)
        return steppedDelay.coerceAtMost(BUSY_AUTOSAVE_MAX_RETRY_DELAY_MS)
    }

    private fun backupExistingAutoSave(reason: String) {
        val sourceProject = autoSaveFile()
        if (!sourceProject.isFile || sourceProject.length() <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (lastAutoSaveBackupElapsedMs > 0L && now - lastAutoSaveBackupElapsedMs < AUTOSAVE_BACKUP_MIN_INTERVAL_MS) {
            return
        }
        val backupDir = autoSaveBackupDir()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = reason.replace(Regex("[^A-Za-z0-9_-]"), "_").take(28).ifBlank { "autosave" }
        val targetProject = File(backupDir, "autosave_${timestamp}_$suffix.vne")
        runCatching {
            sourceProject.copyTo(targetProject, overwrite = true)
            val sourceUi = autoSaveUiFile()
            if (sourceUi.isFile) {
                sourceUi.copyTo(File(backupDir, "${targetProject.nameWithoutExtension}.ui.json"), overwrite = true)
            }
            lastAutoSaveBackupElapsedMs = now
            pruneAutoSaveBackups()
            Log.i(TAG, "[Project] autosave backup created reason=$reason path=${targetProject.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "Autosave backup failed reason=$reason: ${error.message}")
        }
    }

    private fun pruneAutoSaveBackups() {
        val backups = RecentProjectFiles.autoSaveBackupFiles(activity)
        backups.drop(AUTOSAVE_BACKUP_MAX_FILES).forEach { projectFile ->
            runCatching { projectFile.delete() }
            runCatching { File(projectFile.parentFile, "${projectFile.nameWithoutExtension}.ui.json").delete() }
        }
    }

    fun restoreAutoSave(): Boolean {
        val autoSaveFile = RecentProjectFiles.latestAutoSaveCandidate(activity) ?: return false
        loadProject(autoSaveFile.absolutePath)
        return true
    }

    fun hasAutoSave(): Boolean = RecentProjectFiles.latestAutoSaveCandidate(activity) != null

    fun describeAutoSave(): String? {
        val file = RecentProjectFiles.latestAutoSaveCandidate(activity) ?: return null
        val modified = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(file.lastModified()))
        return "Last autosave: $modified"
    }

    fun discardAutoSave() {
        runCatching { autoSaveFile().delete() }
        runCatching { autoSaveUiFile().delete() }
        RecentProjectFiles.autoSaveBackupFiles(activity).forEach { projectFile ->
            runCatching { projectFile.delete() }
            runCatching { File(projectFile.parentFile, "${projectFile.nameWithoutExtension}.ui.json").delete() }
        }
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

    private fun performLoadProject(filePath: String, attempt: Int = 0) {
        if (isPlayingProvider()) onPausePlayback()
        if (attempt == 0) {
            onPrepareProjectLoadSurface()
        }
        val preview = previewViewProvider()
        if (preview == null || !preview.ensureNativeSurfaceBinding()) {
            if (attempt < LOAD_PREVIEW_READY_MAX_ATTEMPTS) {
                Log.i(TAG, "[Project] load waiting for preview attempt=${attempt + 1} path=$filePath")
                mainHandler.postDelayed(
                    { performLoadProject(filePath, attempt + 1) },
                    LOAD_PREVIEW_READY_RETRY_DELAY_MS,
                )
            } else {
                Log.w(TAG, "[Project] load failed: preview not ready path=$filePath")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Load failed", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        Thread {
            try {
                val success = preview.loadProject(filePath)
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
