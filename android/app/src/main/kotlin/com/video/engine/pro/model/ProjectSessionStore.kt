package com.video.engine.pro.model

import android.content.Context
import java.io.File

class ProjectSessionStore(
    private val context: Context,
) {
    private val projectsDir: File by lazy {
        File(context.getExternalFilesDir(null), "pro_projects").apply { mkdirs() }
    }

    fun save(session: ProjectSession, fileName: String = defaultFileName(session)): File {
        val targetFile = File(projectsDir, fileName)
        targetFile.writeText(session.toJsonString())
        return targetFile
    }

    fun load(fileName: String): ProjectSession {
        val sourceFile = File(projectsDir, fileName)
        return ProjectSession.fromJsonString(sourceFile.readText())
    }

    fun listProjectFiles(): List<File> {
        return projectsDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    private fun defaultFileName(session: ProjectSession): String {
        val safeName = session.projectName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "project_session" }
        return "${safeName}_${session.projectId}.json"
    }
}
