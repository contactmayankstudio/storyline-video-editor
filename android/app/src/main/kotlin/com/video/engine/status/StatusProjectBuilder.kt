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
        customText: String? = null,
        textNormX: Float = 0.5f,
        textNormY: Float = 0.74f,
        textScale: Float = 1.0f
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

            // If template has an underlying .vne file (User / Community template), adapt and clone it
            if (!template.templateFilePath.isNullOrBlank() && File(template.templateFilePath).exists()) {
                try {
                    val tplFile = File(template.templateFilePath)
                    val tplJson = JSONObject(tplFile.readText())
                    val clips = tplJson.optJSONArray("clips") ?: JSONArray()
                    var videoClipIdx = 0
                    for (i in 0 until clips.length()) {
                        val clip = clips.getJSONObject(i)
                        val trackType = clip.optString("trackType", "VIDEO")
                        if (trackType.equals("VIDEO", ignoreCase = true) || trackType.equals("MAIN", ignoreCase = true)) {
                            val newPath = normalizedPhotoPaths[videoClipIdx % normalizedPhotoPaths.size]
                            clip.put("mediaPath", newPath)
                            videoClipIdx++
                        } else if (trackType.equals("AUDIO", ignoreCase = true) && audioFile != null && audioFile.exists()) {
                            clip.put("mediaPath", audioFile.absolutePath)
                        }
                    }
                    if (!customText.isNullOrBlank()) {
                        val textArray = tplJson.optJSONArray("textOverlays") ?: JSONArray().also { tplJson.put("textOverlays", it) }
                        textArray.put(JSONObject().apply {
                            put("id", 100)
                            put("text", customText)
                            put("x", textNormX)
                            put("y", textNormY)
                            put("scale", 1.30 * textScale)
                            put("color", "0xFFFFD700")
                            put("opacity", 1.0)
                            put("fadeInMs", 300)
                            put("fadeOutMs", 300)
                            put("zOrder", 99)
                        })
                    }
                    projectFile.writeText(tplJson.toString(2))
                    return projectFile
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to adapt .vne template, falling back to dynamic generator: ${e.message}")
                }
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

                val motionStyle = template.getMotionStyle()
                val (baseContrast, baseSaturation, baseBrightness) = getColorGradingForStyle(motionStyle)

                template.slots.forEachIndexed { index, slot ->
                    val photoPath = normalizedPhotoPaths[index % normalizedPhotoPaths.size]
                    val motion = getMotionParamsForStyle(index, isSinglePhoto, motionStyle)
                    val contrast = if (isSinglePhoto && index % 2 == 1) baseContrast * 1.05 else baseContrast
                    val saturation = if (isSinglePhoto && index % 2 == 1) baseSaturation * 1.06 else baseSaturation

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
                            put("brightness", baseBrightness)
                            put("contrast", contrast)
                            put("saturation", saturation)
                            if (template.lutFilter.isNotBlank()) {
                                put("lutFilter", template.lutFilter)
                            }
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
                        put("x", textNormX)
                        put("y", textNormY)
                        put("scale", 1.30 * textScale)
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

                // Dynamic transitions tailored to template style
                val transitionsArray = JSONArray()
                val (transDurationMs, leadInMs) = when (motionStyle) {
                    TemplateMotionStyle.PHONK_ATTITUDE, TemplateMotionStyle.FLASH_BEAT_CUTS -> Pair(200L, 100L)
                    TemplateMotionStyle.THREE_D_ZOOM, TemplateMotionStyle.VELOCITY_RAMP, TemplateMotionStyle.NEON_CYBERPUNK -> Pair(300L, 150L)
                    TemplateMotionStyle.ROMANTIC_LOVE, TemplateMotionStyle.SPIRITUAL_AURA, TemplateMotionStyle.EMOTIONAL_PULLBACK -> Pair(750L, 350L)
                    TemplateMotionStyle.CELEBRATION_POP, TemplateMotionStyle.RETRO_VINTAGE -> Pair(450L, 200L)
                }

                var transStartTimeMs = 0L
                for (i in 0 until template.slots.size - 1) {
                    val slot = template.slots[i]
                    transStartTimeMs += slot.durationMs
                    val transType = when (motionStyle) {
                        TemplateMotionStyle.PHONK_ATTITUDE -> if (i % 2 == 0) "glitch" else "flash"
                        TemplateMotionStyle.THREE_D_ZOOM -> if (i % 2 == 0) "zoom_in" else "flash"
                        TemplateMotionStyle.VELOCITY_RAMP -> if (i % 2 == 0) "whip_right" else "slide"
                        TemplateMotionStyle.ROMANTIC_LOVE -> "crossfade"
                        TemplateMotionStyle.SPIRITUAL_AURA -> "crossfade"
                        TemplateMotionStyle.NEON_CYBERPUNK -> "glitch"
                        TemplateMotionStyle.FLASH_BEAT_CUTS -> "flash"
                        else -> slot.transitionOut
                    }
                    val transJson = JSONObject().apply {
                        put("id", i + 1)
                        put("type", transType)
                        put("fromClipId", i + 1)
                        put("toClipId", i + 2)
                        put("startTimeMs", transStartTimeMs - leadInMs)
                        put("durationMs", transDurationMs)
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

    private fun getColorGradingForStyle(style: TemplateMotionStyle): Triple<Double, Double, Double> {
        return when (style) {
            TemplateMotionStyle.THREE_D_ZOOM -> Triple(1.18, 1.25, 0.02)
            TemplateMotionStyle.VELOCITY_RAMP -> Triple(1.15, 1.20, 0.0)
            TemplateMotionStyle.PHONK_ATTITUDE -> Triple(1.30, 1.12, -0.02)
            TemplateMotionStyle.ROMANTIC_LOVE -> Triple(1.05, 1.22, 0.04)
            TemplateMotionStyle.CELEBRATION_POP -> Triple(1.12, 1.32, 0.06)
            TemplateMotionStyle.EMOTIONAL_PULLBACK -> Triple(1.20, 0.70, -0.04)
            TemplateMotionStyle.SPIRITUAL_AURA -> Triple(1.10, 1.26, 0.08)
            TemplateMotionStyle.NEON_CYBERPUNK -> Triple(1.35, 1.45, 0.0)
            TemplateMotionStyle.FLASH_BEAT_CUTS -> Triple(1.22, 1.20, 0.03)
            TemplateMotionStyle.RETRO_VINTAGE -> Triple(1.14, 0.85, -0.02)
        }
    }

    private fun getMotionParamsForStyle(
        index: Int,
        isSinglePhoto: Boolean,
        style: TemplateMotionStyle
    ): MotionParams {
        if (!isSinglePhoto) {
            return when (index % 3) {
                0 -> MotionParams(1.00, 1.14, 0.50, 0.50, 0.50, 0.48, 0.0, 0.0)
                1 -> MotionParams(1.15, 1.03, 0.50, 0.50, 0.48, 0.52, 0.0, 0.0)
                else -> MotionParams(1.08, 1.18, 0.48, 0.52, 0.50, 0.50, 0.0, 0.0)
            }
        }

        return when (style) {
            TemplateMotionStyle.THREE_D_ZOOM -> when (index % 4) {
                0 -> MotionParams(1.00, 1.50, 0.50, 0.50, 0.50, 0.47, 0.0, 2.5)
                1 -> MotionParams(1.55, 1.06, 0.50, 0.50, 0.47, 0.52, 2.5, -3.0)
                2 -> MotionParams(1.08, 1.52, 0.46, 0.54, 0.50, 0.48, -3.0, 3.0)
                else -> MotionParams(1.50, 1.12, 0.54, 0.48, 0.46, 0.52, 3.0, -1.5)
            }

            TemplateMotionStyle.VELOCITY_RAMP -> when (index % 4) {
                0 -> MotionParams(1.05, 1.38, 0.50, 0.65, 0.50, 0.48, 0.0, 0.0) // Acceleration curve
                1 -> MotionParams(1.35, 1.05, 0.35, 0.50, 0.48, 0.52, 0.0, 0.0) // Whip entry
                2 -> MotionParams(1.00, 1.42, 0.50, 0.50, 0.52, 0.46, 1.0, -1.0)
                else -> MotionParams(1.38, 1.08, 0.65, 0.50, 0.48, 0.50, 0.0, 0.0)
            }

            TemplateMotionStyle.PHONK_ATTITUDE -> when (index % 4) {
                0 -> MotionParams(1.12, 1.36, 0.50, 0.50, 0.52, 0.47, -5.0, 3.5)  // Dutch angle slam
                1 -> MotionParams(1.40, 1.06, 0.47, 0.53, 0.47, 0.53, 3.5, -4.0)  // Strobe glitch bounce
                2 -> MotionParams(1.06, 1.45, 0.53, 0.47, 0.53, 0.46, -4.0, 4.5)  // Heavy bass slam
                else -> MotionParams(1.18, 1.55, 0.50, 0.50, 0.50, 0.48, 0.0, 0.0) // Sigma face lock punch
            }

            TemplateMotionStyle.ROMANTIC_LOVE -> when (index % 4) {
                0 -> MotionParams(1.00, 1.10, 0.50, 0.50, 0.50, 0.47, 0.0, 0.0)   // Dreamy slow Ken Burns
                1 -> MotionParams(1.08, 1.15, 0.48, 0.52, 0.48, 0.52, 0.5, -0.5)  // Warm right drift
                2 -> MotionParams(1.12, 1.05, 0.50, 0.50, 0.52, 0.48, 0.0, 0.0)   // Heartbeat pulse
                else -> MotionParams(1.04, 1.14, 0.52, 0.48, 0.48, 0.50, -0.5, 0.5)// Golden sunset float
            }

            TemplateMotionStyle.CELEBRATION_POP -> when (index % 4) {
                0 -> MotionParams(0.94, 1.24, 0.50, 0.50, 0.52, 0.47, -2.0, 2.0)  // Pop in bounce
                1 -> MotionParams(1.26, 1.04, 0.50, 0.50, 0.47, 0.52, 2.0, -2.0)  // Celebratory bounce
                2 -> MotionParams(1.08, 1.25, 0.46, 0.54, 0.50, 0.48, -1.0, 1.5)  // Pop slide
                else -> MotionParams(1.05, 1.32, 0.54, 0.50, 0.50, 0.46, 1.0, -1.0)
            }

            TemplateMotionStyle.EMOTIONAL_PULLBACK -> when (index % 4) {
                0 -> MotionParams(1.30, 1.02, 0.50, 0.50, 0.46, 0.51, 0.0, 0.0)   // Slow melancholic pull-back
                1 -> MotionParams(1.08, 1.16, 0.52, 0.47, 0.49, 0.51, 0.0, 0.0)   // Lonely drift
                2 -> MotionParams(1.24, 1.02, 0.50, 0.50, 0.48, 0.52, 0.0, 0.0)   // Tears pull-back
                else -> MotionParams(1.05, 1.14, 0.48, 0.52, 0.51, 0.48, 0.0, 0.0)
            }

            TemplateMotionStyle.SPIRITUAL_AURA -> when (index % 4) {
                0 -> MotionParams(1.02, 1.14, 0.50, 0.50, 0.56, 0.42, 0.0, 0.0)   // Sacred upward tilt
                1 -> MotionParams(1.12, 1.24, 0.50, 0.50, 0.46, 0.50, 0.0, 0.0)   // Golden aura float
                2 -> MotionParams(1.18, 1.08, 0.50, 0.50, 0.43, 0.53, 0.0, 0.0)   // Divine sunrise reveal
                else -> MotionParams(1.06, 1.22, 0.50, 0.50, 0.50, 0.46, 0.0, 0.0) // Trance pulse
            }

            TemplateMotionStyle.NEON_CYBERPUNK -> when (index % 4) {
                0 -> MotionParams(1.10, 1.48, 0.48, 0.52, 0.50, 0.48, 0.0, 2.0)
                1 -> MotionParams(1.42, 1.08, 0.53, 0.47, 0.48, 0.52, 2.0, -2.5)
                2 -> MotionParams(1.05, 1.45, 0.50, 0.50, 0.52, 0.46, -2.5, 2.0)
                else -> MotionParams(1.35, 1.10, 0.48, 0.52, 0.48, 0.50, 1.5, -1.0)
            }

            TemplateMotionStyle.FLASH_BEAT_CUTS -> when (index % 4) {
                0 -> MotionParams(1.00, 1.36, 0.50, 0.50, 0.50, 0.47, 0.0, 0.0)
                1 -> MotionParams(1.36, 1.04, 0.50, 0.50, 0.47, 0.52, 0.0, 0.0)
                2 -> MotionParams(1.08, 1.40, 0.50, 0.50, 0.52, 0.46, 1.0, -1.0)
                else -> MotionParams(1.30, 1.05, 0.50, 0.50, 0.48, 0.50, -1.0, 1.0)
            }

            TemplateMotionStyle.RETRO_VINTAGE -> when (index % 4) {
                0 -> MotionParams(1.03, 1.12, 0.50, 0.50, 0.50, 0.48, -0.6, 0.6)
                1 -> MotionParams(1.10, 1.18, 0.48, 0.52, 0.48, 0.52, 0.6, -0.6)
                2 -> MotionParams(1.16, 1.08, 0.52, 0.48, 0.52, 0.48, -0.4, 0.4)
                else -> MotionParams(1.05, 1.15, 0.50, 0.50, 0.48, 0.50, 0.4, -0.4)
            }
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
