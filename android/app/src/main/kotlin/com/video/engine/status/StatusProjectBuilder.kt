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
        photoPaths: List<String>,
        customAudioPath: String? = null,
        customText: String? = null
    ): File? {
        if (photoPaths.isEmpty()) return null

        try {
            val projectsDir = File(context.getExternalFilesDir(null), "projects").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTitle = template.title.replace(Regex("[^a-zA-Z0-9]"), "_")
            val projectFile = File(projectsDir, "Status_${safeTitle}_${ts}.vne")

            // Ensure photo paths are normalized for optimal C++ decoding and 1080x1920 layout
            val normalizedPhotoPaths = photoPaths.map { path ->
                val f = File(path)
                if (f.extension.equals("png", ignoreCase = true) && f.parentFile?.name == "status_photos") {
                    path
                } else {
                    StatusMediaHelper.normalizeImageFileForStatus(context, f) ?: path
                }
            }

            // Copy audio asset to storage so video engine can decode it, or use custom audio file
            val audioFile = if (!customAudioPath.isNullOrBlank() && File(customAudioPath).exists()) {
                File(customAudioPath)
            } else {
                StatusMediaHelper.extractAudioAssetToLocal(context, template.audioAssetPath)
                    ?: extractAudioAsset(context, template.audioAssetPath)
            }

            val rootJson = JSONObject().apply {
                put("version", "1.1")
                put("name", "Status - ${template.title}")
                put("fps", 30)
                put("width", 1080)
                put("height", 1920)

                // Clips array
                val clipsArray = JSONArray()
                var currentTimelineTimeMs = 0L
                val isSinglePhoto = normalizedPhotoPaths.size == 1

                template.slots.forEachIndexed { index, slot ->
                    val photoPath = normalizedPhotoPaths[index % normalizedPhotoPaths.size]
                    val motion = getMotionParams(index, isSinglePhoto)
                    val contrast = if (isSinglePhoto && index % 2 == 1) 1.12 else 1.06
                    val saturation = if (isSinglePhoto && index % 2 == 1) 1.18 else 1.10

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
                            put("contrast", contrast)
                            put("saturation", saturation)
                            put("enabled", true)
                        })

                        // Spatial transform (initial)
                        put("transform", JSONObject().apply {
                            put("posX", motion.startX)
                            put("posY", motion.startY)
                            put("scaleX", motion.startScale)
                            put("scaleY", motion.startScale)
                            put("rotation", motion.startRot)
                        })

                        // Transform keyframes for dynamic Ken Burns zoom & beat motion
                        val keyframesArray = JSONArray().apply {
                            put(JSONObject().apply {
                                put("timeMs", 0L)
                                put("posX", motion.startX)
                                put("posY", motion.startY)
                                put("scale", motion.startScale)
                                put("rotation", motion.startRot)
                                put("opacity", 1.0)
                            })
                            put(JSONObject().apply {
                                put("timeMs", slot.durationMs)
                                put("posX", motion.endX)
                                put("posY", motion.endY)
                                put("scale", motion.endScale)
                                put("rotation", motion.endRot)
                                put("opacity", 1.0)
                            })
                        }
                        put("transformKeyframes", keyframesArray)
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
                        // If user has custom text at bottom, position template lyrics higher (0.22)
                        val adjustedY = if (!customText.isNullOrBlank()) 0.22 else textOverlay.posYPercent.toDouble()
                        put("y", adjustedY)
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

                // Add prominent custom name / lyrical text overlay
                if (!customText.isNullOrBlank()) {
                    val customJson = JSONObject().apply {
                        put("id", 100)
                        put("text", customText)
                        put("x", 0.5)
                        put("y", 0.76)
                        put("scale", 1.30)
                        put("rotation", 0.0)
                        put("color", "0xFFFFD700")
                        put("opacity", 1.0)
                        put("fadeInMs", 300)
                        put("fadeOutMs", 300)
                        put("zOrder", 99)
                        put("startTime", 0L)
                        put("endTime", template.durationMs)
                        put("enabled", true)
                    }
                    textArray.put(customJson)
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

            val cleanJson = rootJson.toString(2).replace("\\/", "/")
            projectFile.writeText(cleanJson)
            Log.i(TAG, "Status project generated successfully: ${projectFile.absolutePath}")
            return projectFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build status project", e)
            return null
        }
    }

    private data class MotionParams(
        val startScale: Double,
        val endScale: Double,
        val startX: Double,
        val endX: Double,
        val startY: Double,
        val endY: Double,
        val startRot: Double,
        val endRot: Double
    )

    private fun getMotionParams(index: Int, isSinglePhoto: Boolean): MotionParams {
        if (!isSinglePhoto) {
            return when (index % 3) {
                0 -> MotionParams(1.00, 1.14, 0.50, 0.50, 0.50, 0.48, 0.0, 0.0)
                1 -> MotionParams(1.15, 1.03, 0.50, 0.50, 0.48, 0.52, 0.0, 0.0)
                else -> MotionParams(1.08, 1.18, 0.48, 0.52, 0.50, 0.50, 0.0, 0.0)
            }
        }
        // Dynamic, high-energy cinematic camera choreographies for 1-photo status
        return when (index % 5) {
            0 -> MotionParams(1.00, 1.20, 0.50, 0.50, 0.50, 0.47, 0.0, 0.0)      // Cinematic Zoom-In
            1 -> MotionParams(1.24, 1.05, 0.50, 0.50, 0.47, 0.52, -1.5, 1.0)   // Beat Drop Zoom-Out + Dutch tilt
            2 -> MotionParams(1.16, 1.16, 0.43, 0.57, 0.50, 0.50, 0.0, 0.0)    // Cinematic Pan Left-to-Right
            3 -> MotionParams(1.08, 1.26, 0.50, 0.50, 0.52, 0.46, 1.0, -1.0)   // Punch Zoom-In with Beat Bounce
            else -> MotionParams(1.15, 1.15, 0.57, 0.43, 0.49, 0.51, 0.0, 0.0) // Pan Right-to-Left Drift
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
