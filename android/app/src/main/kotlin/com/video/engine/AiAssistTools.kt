package com.video.engine

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class AiPoseKeyframe(
    val timeMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
)

data class AiCaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class AiMotionSample(
    val timelineMs: Long,
    val sourceTimeMs: Long,
)

data class AiMatteSuggestion(
    val useBlueKey: Boolean,
    val similarity: Float,
    val smoothness: Float,
    val spill: Float,
    val confidence: Float,
    val label: String,
)

object AiAssistTools {
    private data class LumaFrame(
        val width: Int,
        val height: Int,
        val luma: IntArray,
    )

    fun loadCaptionCues(mediaPath: String, durationMs: Long): List<AiCaptionCue> {
        if (mediaPath.isBlank()) return emptyList()
        sidecarCandidates(mediaPath).forEach { file ->
            val raw = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
            if (raw.isBlank()) return@forEach
            val parsed =
                when (file.extension.lowercase()) {
                    "srt" -> parseSrt(raw)
                    "vtt" -> parseVtt(raw)
                    "txt" -> parseTxt(raw, durationMs)
                    else -> emptyList()
                }
            if (parsed.isNotEmpty()) {
                return normalizeCaptionCues(parsed, durationMs)
            }
        }
        return normalizeCaptionCues(buildFallbackCaptionCues(mediaPath, durationMs), durationMs)
    }

    fun inferAiMatte(mediaPath: String, sourceTimeMs: Long): AiMatteSuggestion? {
        val frame = extractBitmapFrame(mediaPath, sourceTimeMs) ?: return null
        val scaled = Bitmap.createScaledBitmap(frame, 180, 180, true)
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        var greenScore = 0f
        var blueScore = 0f
        var borderSamples = 0
        val borderX = max(10, width / 7)
        val borderY = max(10, height / 7)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isBorder = x < borderX || x >= (width - borderX) || y < borderY || y >= (height - borderY)
                if (!isBorder) continue
                val pixel = pixels[(y * width) + x]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val maxChannel = max(r, max(g, b))
                val minChannel = minOf(r, g, b)
                val saturation = maxChannel - minChannel
                if (saturation < 0.08f) continue
                greenScore += (g - max(r, b)).coerceAtLeast(0f) * (0.35f + saturation)
                blueScore += (b - max(r, g)).coerceAtLeast(0f) * (0.35f + saturation)
                borderSamples += 1
            }
        }
        if (borderSamples <= 0) return null
        val dominantGreen = greenScore >= blueScore
        val dominant = if (dominantGreen) greenScore else blueScore
        val alternate = if (dominantGreen) blueScore else greenScore
        val relativeConfidence = ((dominant - alternate) / borderSamples.toFloat()).coerceIn(0f, 1f)
        val colorLabel = if (dominantGreen) "Green" else "Blue"
        return AiMatteSuggestion(
            useBlueKey = !dominantGreen,
            similarity = (0.24f + ((1f - relativeConfidence) * 0.14f)).coerceIn(0.18f, 0.46f),
            smoothness = (0.08f + ((1f - relativeConfidence) * 0.16f)).coerceIn(0.06f, 0.28f),
            spill = (0.03f + ((1f - relativeConfidence) * 0.10f)).coerceIn(0.02f, 0.18f),
            confidence = relativeConfidence,
            label = "$colorLabel matte",
        )
    }

    fun buildMotionTrack(
        mediaPath: String,
        samples: List<AiMotionSample>,
        anchorX: Float,
        anchorY: Float,
        baseScale: Float,
        baseRotation: Float,
        opacity: Float,
    ): List<AiPoseKeyframe> {
        if (mediaPath.isBlank() || samples.isEmpty()) return emptyList()
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(mediaPath)
            val ordered = samples.sortedBy { it.sourceTimeMs }.distinctBy { it.timelineMs }
            val frameWidth = 160
            val frameHeight = 90
            val templateRadius = 10
            val searchRadius = 18
            val firstFrame = extractLumaFrame(retriever, ordered.first().sourceTimeMs, frameWidth, frameHeight)
                ?: return ordered.map {
                    AiPoseKeyframe(
                        timeMs = it.timelineMs,
                        x = anchorX.coerceIn(0f, 1f),
                        y = anchorY.coerceIn(0f, 1f),
                        scale = baseScale,
                        rotation = baseRotation,
                        opacity = opacity,
                    )
                }
            var centerX = (anchorX.coerceIn(0f, 1f) * (frameWidth - 1)).roundToInt().coerceIn(0, frameWidth - 1)
            var centerY = (anchorY.coerceIn(0f, 1f) * (frameHeight - 1)).roundToInt().coerceIn(0, frameHeight - 1)
            var template = extractPatch(firstFrame, centerX, centerY, templateRadius)
            val results = mutableListOf<AiPoseKeyframe>()
            ordered.forEachIndexed { index, sample ->
                val currentFrame = if (index == 0) firstFrame else extractLumaFrame(retriever, sample.sourceTimeMs, frameWidth, frameHeight)
                val currentTemplate = template
                if (currentFrame != null && currentTemplate != null) {
                    findBestMatch(currentFrame, currentTemplate, centerX, centerY, searchRadius, templateRadius)?.let { match ->
                        centerX = match.first
                        centerY = match.second
                        extractPatch(currentFrame, centerX, centerY, templateRadius)?.let { fresh ->
                            template = blendPatch(currentTemplate, fresh)
                        }
                    }
                }
                results += AiPoseKeyframe(
                    timeMs = sample.timelineMs,
                    x = (centerX / frameWidth.toFloat()).coerceIn(0f, 1f),
                    y = (centerY / frameHeight.toFloat()).coerceIn(0f, 1f),
                    scale = baseScale,
                    rotation = baseRotation,
                    opacity = opacity,
                )
            }
            smoothTrack(results)
        }.getOrElse {
            emptyList()
        }.also {
            runCatching { retriever.release() }
        }
    }

    private fun sidecarCandidates(mediaPath: String): List<File> {
        val mediaFile = File(mediaPath)
        val parent = mediaFile.parentFile ?: return emptyList()
        val baseName = mediaFile.nameWithoutExtension
        val exact = listOf("srt", "vtt", "txt").map { ext -> File(parent, "$baseName.$ext") }
        val fuzzy =
            parent.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                        file.extension.lowercase() in setOf("srt", "vtt", "txt")
                }.orEmpty()
        return (exact + fuzzy).distinctBy { it.absolutePath }.filter { it.exists() }
    }

    private fun normalizeCaptionCues(cues: List<AiCaptionCue>, durationMs: Long): List<AiCaptionCue> {
        if (cues.isEmpty()) return emptyList()
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        return cues
            .mapNotNull { cue ->
                val text = cue.text.trim().replace(Regex("\\s+"), " ")
                if (text.isBlank()) return@mapNotNull null
                val startMs = cue.startMs.coerceIn(0L, safeDurationMs - 1L)
                val endMs = cue.endMs.coerceAtLeast(startMs + 1L).coerceAtMost(safeDurationMs)
                AiCaptionCue(startMs, endMs, text)
            }
            .sortedBy { it.startMs }
            .distinctBy { "${it.startMs}:${it.endMs}:${it.text}" }
    }

    private fun parseSrt(raw: String): List<AiCaptionCue> {
        return raw
            .replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .mapNotNull { block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
                if (timeLineIndex == -1) return@mapNotNull null
                val parts = lines[timeLineIndex].split("-->")
                if (parts.size != 2) return@mapNotNull null
                val startMs = parseCaptionTimestamp(parts[0]) ?: return@mapNotNull null
                val endMs = parseCaptionTimestamp(parts[1]) ?: return@mapNotNull null
                val text = lines.drop(timeLineIndex + 1).joinToString(" ").trim()
                if (text.isBlank()) null else AiCaptionCue(startMs, endMs, text)
            }
    }

    private fun parseVtt(raw: String): List<AiCaptionCue> {
        return raw
            .replace("\r\n", "\n")
            .lines()
            .filterNot { it.trim().equals("WEBVTT", ignoreCase = true) }
            .joinToString("\n")
            .let(::parseSrt)
    }

    private fun parseTxt(raw: String, durationMs: Long): List<AiCaptionCue> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val safeDurationMs = durationMs.coerceAtLeast(lines.size.toLong() * 1200L)
        val slice = (safeDurationMs / lines.size).coerceAtLeast(1200L)
        return lines.mapIndexed { index, line ->
            val startMs = index * slice
            val endMs = if (index == lines.lastIndex) safeDurationMs else (startMs + slice)
            AiCaptionCue(startMs, endMs, line)
        }
    }

    private fun buildFallbackCaptionCues(mediaPath: String, durationMs: Long): List<AiCaptionCue> {
        val name = File(mediaPath).nameWithoutExtension.replace(Regex("[_\\-.]+"), " ").trim()
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() && it.length > 1 }
        if (words.isEmpty()) return emptyList()
        val chunks = words.chunked(4).take(6)
        val safeDurationMs = durationMs.coerceAtLeast(chunks.size.toLong() * 1400L)
        val step = (safeDurationMs / chunks.size).coerceAtLeast(1400L)
        return chunks.mapIndexed { index, chunk ->
            val startMs = index * step
            val endMs = if (index == chunks.lastIndex) safeDurationMs else (startMs + step)
            AiCaptionCue(startMs, endMs, chunk.joinToString(" "))
        }
    }

    private fun parseCaptionTimestamp(raw: String): Long? {
        val clean = raw.trim().replace('.', ',')
        val parts = clean.split(':', ',').map { it.trim() }
        if (parts.size != 4) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        val millis = parts[3].toLongOrNull() ?: return null
        return (hours * 3_600_000L) + (minutes * 60_000L) + (seconds * 1_000L) + millis
    }

    private fun extractBitmapFrame(mediaPath: String, sourceTimeMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(mediaPath)
            retriever.getFrameAtTime(sourceTimeMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    private fun extractLumaFrame(
        retriever: MediaMetadataRetriever,
        sourceTimeMs: Long,
        width: Int,
        height: Int,
    ): LumaFrame? {
        val bitmap =
            runCatching {
                retriever.getFrameAtTime(sourceTimeMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull() ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(pixels.size)
        pixels.forEachIndexed { index, pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            luma[index] = ((r * 30) + (g * 59) + (b * 11)) / 100
        }
        return LumaFrame(width, height, luma)
    }

    private fun extractPatch(frame: LumaFrame, centerX: Int, centerY: Int, radius: Int): IntArray? {
        if (centerX - radius < 0 || centerY - radius < 0 || centerX + radius >= frame.width || centerY + radius >= frame.height) {
            return null
        }
        val size = (radius * 2) + 1
        val patch = IntArray(size * size)
        var patchIndex = 0
        for (y in (centerY - radius)..(centerY + radius)) {
            val rowOffset = y * frame.width
            for (x in (centerX - radius)..(centerX + radius)) {
                patch[patchIndex++] = frame.luma[rowOffset + x]
            }
        }
        return patch
    }

    private fun findBestMatch(
        frame: LumaFrame,
        template: IntArray,
        previousCenterX: Int,
        previousCenterY: Int,
        searchRadius: Int,
        templateRadius: Int,
    ): Pair<Int, Int>? {
        var bestScore = Long.MAX_VALUE
        var bestCenter: Pair<Int, Int>? = null
        val step = 2
        for (dy in -searchRadius..searchRadius step step) {
            for (dx in -searchRadius..searchRadius step step) {
                val centerX = (previousCenterX + dx).coerceIn(templateRadius, frame.width - templateRadius - 1)
                val centerY = (previousCenterY + dy).coerceIn(templateRadius, frame.height - templateRadius - 1)
                val patch = extractPatch(frame, centerX, centerY, templateRadius) ?: continue
                var score = 0L
                for (index in template.indices) {
                    score += abs(template[index] - patch[index]).toLong()
                }
                if (score < bestScore) {
                    bestScore = score
                    bestCenter = centerX to centerY
                }
            }
        }
        return bestCenter
    }

    private fun blendPatch(base: IntArray, fresh: IntArray): IntArray {
        val blended = IntArray(base.size)
        for (index in base.indices) {
            blended[index] = ((base[index] * 3) + fresh[index]) / 4
        }
        return blended
    }

    private fun smoothTrack(input: List<AiPoseKeyframe>): List<AiPoseKeyframe> {
        if (input.size < 3) return input
        return input.mapIndexed { index, keyframe ->
            val window = input.subList(max(0, index - 1), minOf(input.size, index + 2))
            AiPoseKeyframe(
                timeMs = keyframe.timeMs,
                x = window.map { it.x }.average().toFloat(),
                y = window.map { it.y }.average().toFloat(),
                scale = window.map { it.scale }.average().toFloat(),
                rotation = window.map { it.rotation }.average().toFloat(),
                opacity = window.map { it.opacity }.average().toFloat(),
            )
        }
    }
}
