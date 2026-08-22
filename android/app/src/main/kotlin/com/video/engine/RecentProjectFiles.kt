package com.video.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object RecentProjectFiles {
    private const val TAG = "RecentProjectFiles"

    fun all(context: Context): List<File> {
        val baseDir = baseDir(context)
        val projectFiles = File(baseDir, "projects")
            .listFiles { file -> file.isFile && file.extension == "vne" }
            ?.toList()
            .orEmpty()
        return buildList {
            addAll(projectFiles)
            autoSaveFile(context).takeIf { it.isFile }?.let(::add)
            addAll(autoSaveBackupFiles(context))
        }
            .distinctBy { it.absolutePath }
            .filter { isProjectUsable(context, it) }
            .sortedByDescending { it.lastModified() }
    }

    fun newest(context: Context): File? = all(context).firstOrNull()

    fun delete(context: Context, projectFile: File): Boolean {
        val candidates = linkedSetOf(projectFile)
        candidates.addAll(sidecarCandidates(projectFile))
        val currentAutoSave = autoSaveFile(context)
        if (projectFile.absolutePath == currentAutoSave.absolutePath) {
            currentAutoSave.parentFile?.let { parent ->
                candidates.add(File(parent, "autosave.ui.json"))
            }
        }
        candidates.forEach { file ->
            if (file.exists()) {
                val deleted = runCatching {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }.getOrDefault(false)
                if (!deleted && file.exists()) {
                    Log.w(TAG, "Failed to delete recent project file=${file.absolutePath}")
                }
            }
        }
        return !projectFile.exists()
    }

    fun rename(context: Context, projectFile: File, newName: String): File? {
        val sanitized = newName.trim().replace(Regex("[^a-zA-Z0-9_\\- ]"), "").takeIf { it.isNotBlank() } ?: return null
        val parent = projectFile.parentFile ?: return null
        val targetFile = File(parent, "$sanitized.vne")
        if (targetFile.absolutePath == projectFile.absolutePath) return projectFile
        if (targetFile.exists()) targetFile.delete()
        val success = projectFile.renameTo(targetFile)
        if (success) {
            sidecarFile(projectFile)?.let { sidecar ->
                val targetSidecar = File(parent, "$sanitized.ui.json")
                if (targetSidecar.exists()) targetSidecar.delete()
                sidecar.renameTo(targetSidecar)
            }
            return targetFile
        }
        return null
    }

    fun duplicate(context: Context, projectFile: File): File? {
        if (!projectFile.isFile) return null
        val parent = projectFile.parentFile ?: return null
        val baseName = projectFile.nameWithoutExtension
        var copyIndex = 1
        var targetFile = File(parent, "$baseName Copy.vne")
        while (targetFile.exists()) {
            copyIndex++
            targetFile = File(parent, "$baseName Copy $copyIndex.vne")
        }
        val copied = runCatching {
            projectFile.copyTo(targetFile, overwrite = true)
        }.isSuccess
        if (copied) {
            sidecarFile(projectFile)?.let { sidecar ->
                val targetSidecar = File(parent, "${targetFile.nameWithoutExtension}.ui.json")
                runCatching { sidecar.copyTo(targetSidecar, overwrite = true) }
            }
            return targetFile
        }
        return null
    }

    fun autoSaveFile(context: Context): File =
        File(baseDir(context), "autosave/autosave.vne")

    fun autoSaveBackupDir(context: Context): File =
        File(baseDir(context), "autosave/backups")

    fun autoSaveBackupFiles(context: Context): List<File> =
        autoSaveBackupDir(context)
            .listFiles { file -> file.isFile && file.extension == "vne" }
            ?.toList()
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    fun latestAutoSaveCandidate(context: Context): File? =
        buildList {
            autoSaveFile(context).takeIf { it.isFile }?.let(::add)
            addAll(autoSaveBackupFiles(context))
        }
            .filter { isProjectUsable(context, it) }
            .maxByOrNull { it.lastModified() }

    fun isProjectUsable(context: Context, projectFile: File): Boolean {
        if (!projectFile.isFile) return false
        val missingMedia = missingMediaPaths(context, projectFile)
        if (missingMedia.isNotEmpty()) {
            Log.w(
                TAG,
                "Skipping recent project with missing media path=${projectFile.absolutePath} " +
                    "missingCount=${missingMedia.size} firstMissing=${missingMedia.first()}",
            )
            return false
        }
        return true
    }

    private fun baseDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun missingMediaPaths(context: Context, projectFile: File): List<String> {
        val paths = linkedSetOf<String>()
        collectMediaPaths(projectFile, paths)
        sidecarFile(projectFile)?.let { collectMediaPaths(it, paths) }
        return paths.filterNot { isMediaPathResolvable(context, it) }
    }

    private fun sidecarFile(projectFile: File): File? {
        val parent = projectFile.parentFile ?: return null
        return File(parent, "${projectFile.nameWithoutExtension}.ui.json").takeIf { it.isFile }
    }

    private fun sidecarCandidates(projectFile: File): List<File> {
        val parent = projectFile.parentFile ?: return emptyList()
        return listOf(
            File(parent, "${projectFile.nameWithoutExtension}.ui.json"),
            File(parent, "${projectFile.name}.ui.json"),
        )
    }

    private fun collectMediaPaths(file: File, output: MutableSet<String>) {
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        collectClipMediaPaths(root.optJSONArray("clips"), output)
        collectClipMediaPaths(root.optJSONArray("nativeClips"), output)
    }

    private fun collectClipMediaPaths(clips: JSONArray?, output: MutableSet<String>) {
        if (clips == null) return
        for (index in 0 until clips.length()) {
            val item = clips.optJSONObject(index) ?: continue
            addPath(item.optString("mediaPath"), output)
            addPath(item.optString("sourcePath"), output)
        }
    }

    private fun addPath(path: String?, output: MutableSet<String>) {
        path?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(output::add)
    }

    private fun isMediaPathResolvable(context: Context, rawPath: String): Boolean {
        val path = rawPath.trim()
        if (path.isBlank()) return true
        if (path.startsWith("content://")) return true
        if (path.contains("://") && !path.startsWith("file://")) return true

        val filePath = if (path.startsWith("file://")) {
            Uri.parse(path).path.orEmpty()
        } else {
            path
        }
        if (filePath.isBlank()) return false
        if (!filePath.startsWith("/")) return true

        val sourceFile = File(filePath)
        if (sourceFile.isFile) return true

        val fileName = sourceFile.name.takeIf { it.isNotBlank() } ?: return false
        return candidateImportDirs(context).any { dir -> File(dir, fileName).isFile }
    }

    private fun candidateImportDirs(context: Context): List<File> {
        val externalBase = context.getExternalFilesDir(null)
        return buildList {
            add(File(context.filesDir, "imports"))
            add(File(context.filesDir, "native_imports"))
            add(File(context.filesDir, "native_image_imports"))
            add(File(context.filesDir, "audio_imports"))
            externalBase?.let {
                add(File(it, "imports"))
                add(File(it, "native_imports"))
                add(File(it, "native_image_imports"))
                add(File(it, "audio_imports"))
            }
            add(File(context.cacheDir, "imports"))
            add(File(context.cacheDir, "native_imports"))
            add(File(context.cacheDir, "native_image_imports"))
            add(File(context.cacheDir, "audio_imports"))
        }
    }
}
