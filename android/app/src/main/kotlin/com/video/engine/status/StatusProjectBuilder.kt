package com.video.engine.status

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatusProjectBuilder {
    private const val TAG = "StatusProjectBuilder"

    fun buildProjectFile(
        context: Context,
        template: StatusTemplate,
        photoPaths: List<String>
    ): File? {
        if (photoPaths.isEmpty()) return null

        try {
            val projectsDir = File(context.getExternalFilesDir(null), "projects").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTitle = template.title.replace(Regex("[^a-zA-Z0-9]"), "_")
            val projectFile = File(projectsDir, "Status_${safeTitle}_${ts}.vne")

            // Copy audio asset to storage so video engine can decode it
            val audioFile = extractAudioAsset(context, template.audioAssetPath)

            val rootJson = JSONObject().apply {
                put("version", "1.1")
                put("name", "Status - ${template.title}")
                put("fps", 30)
                put("width", 1080)
                put("height", 1920)

                // Clips array
                val clipsArray = JSONArray()
                var currentTimelineTimeMs = 0L

                template.slots.forEachIndexed { index, slot ->
                    val photoPath = photoPaths[index % photoPaths.size]
                    val clipJson = JSONObject().apply {
                        put("id", index + 1)
                        put("mediaPath", photoPath)
                        put("startTimeMs", currentTimelineTimeMs)
                        put("durationMs", slot.durationMs)
                        put("sourceInMs", 0L)
                        put("sourceOutMs", slot.durationMs)
                        put("trackType", "VIDEO")
                        put("trackLane", 0)
                        put("zOrder", 0)
                        put("opacity", 1.0)
                        put("volumeGain", 1.0)
                        put("enabled", true)
                        put("playbackSpeed", 1.0)
                        put("effects", JSONObject().apply {
                            put("brightness", 0.0)
                            put("contrast", 1.05)
                            put("saturation", 1.1)
                            put("enabled", true)
                        })
                    }
                    clipsArray.put(clipJson)
                    currentTimelineTimeMs += slot.durationMs
                }

                // Add BGM Audio Track if present
                if (audioFile != null && audioFile.exists()) {
                    val audioClipJson = JSONObject().apply {
                        put("id", 999)
                        put("mediaPath", audioFile.absolutePath)
                        put("startTimeMs", 0L)
                        put("durationMs", template.durationMs)
                        put("sourceInMs", 0L)
                        put("sourceOutMs", template.durationMs)
                        put("trackType", "AUDIO")
                        put("trackLane", 0)
                        put("zOrder", 0)
                        put("opacity", 1.0)
                        put("volumeGain", 1.0)
                        put("enabled", true)
                    }
                    clipsArray.put(audioClipJson)
                }

                put("clips", clipsArray)

                // Text Overlays
                val textArray = JSONArray()
                template.textOverlays.forEachIndexed { index, textOverlay ->
                    val textJson = JSONObject().apply {
                        put("id", index + 1)
                        put("text", textOverlay.text)
                        put("x", 0.5)
                        put("y", textOverlay.posYPercent.toDouble())
                        put("scale", textOverlay.scale.toDouble())
                        put("rotation", 0.0)
                        put("color", textOverlay.colorHex)
                        put("opacity", 1.0)
                        put("fadeInMs", 300)
                        put("fadeOutMs", 300)
                        put("zOrder", 10 + index)
                        put("startTime", textOverlay.startTimeMs)
                        put("endTime", textOverlay.endTimeMs)
                        put("enabled", true)
                    }
                    textArray.put(textJson)
                }
                put("textOverlays", textArray)

                // Transitions between consecutive clips
                val transitionsArray = JSONArray()
                var transStartTimeMs = 0L
                for (i in 0 until template.slots.size - 1) {
                    val slot = template.slots[i]
                    transStartTimeMs += slot.durationMs
                    val transJson = JSONObject().apply {
                        put("id", i + 1)
                        put("type", slot.transitionOut)
                        put("fromClipId", i + 1)
                        put("toClipId", i + 2)
                        put("startTimeMs", transStartTimeMs - 300L)
                        put("durationMs", 500L)
                    }
                    transitionsArray.put(transJson)
                }
                put("transitions", transitionsArray)
            }

            projectFile.writeText(rootJson.toString(2))
            Log.i(TAG, "Status project generated successfully: ${projectFile.absolutePath}")
            return projectFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build status project", e)
            return null
        }
    }

    private fun extractAudioAsset(context: Context, assetPath: String): File? {
        return try {
            val audioDir = File(context.filesDir, "status_bgm").also { it.mkdirs() }
            val fileName = File(assetPath).name
            val outFile = File(audioDir, fileName)
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract audio asset $assetPath: ${e.message}")
            null
        }
    }
}
