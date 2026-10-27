package com.video.engine.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

object StatusMediaHelper {
    private const val TAG = "StatusMediaHelper"
    private const val MAX_STATUS_IMAGE_LONG_EDGE_PX = 1920

    fun isVideoFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val ext = File(path).extension.lowercase()
        return ext in setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "ts", "m4v")
    }

    fun isVideoUri(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)
        if (type != null && type.startsWith("video/")) return true
        val name = queryDisplayName(context, uri).orEmpty().lowercase()
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov") ||
               name.endsWith(".3gp") || name.endsWith(".webm") || name.endsWith(".avi")
    }

    fun extractPosterFrameForVideo(context: Context, videoFile: File): String? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val rawBmp = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (rawBmp != null) {
                val photoDir = File(context.filesDir, "status_photos").apply { mkdirs() }
                val posterFile = File(photoDir, "${videoFile.nameWithoutExtension}_poster.png")
                val smartBmp = smartFitToPortrait916(rawBmp)
                FileOutputStream(posterFile).use { out ->
                    smartBmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                    out.flush()
                }
                if (smartBmp != rawBmp && !smartBmp.isRecycled) {
                    smartBmp.recycle()
                }
                posterFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract poster for ${videoFile.name}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Resolves and normalizes an image or video Uri into an optimized local file for the video engine.
     * For photos: automatically fixes EXIF rotation, scales down, and converts to fast-decoding PNG.
     * For videos: copies to local status_videos directory and generates poster frame for instant preview.
     */
    fun normalizeImageUriForStatus(context: Context, uri: Uri): String? {
        val isVideo = isVideoUri(context, uri)
        val displayName = queryDisplayName(context, uri) ?: if (isVideo) "video_${System.currentTimeMillis()}.mp4" else "photo_${System.currentTimeMillis()}.jpg"
        val tempRawFile = File(context.cacheDir, "temp_status_raw_${System.currentTimeMillis()}_$displayName")

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempRawFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempRawFile.exists() || tempRawFile.length() <= 0L) {
                return null
            }
            if (isVideo || isVideoFile(tempRawFile.absolutePath)) {
                val videoDir = File(context.filesDir, "status_videos").apply { mkdirs() }
                val safeName = "video_${System.currentTimeMillis()}_${displayName.replace(Regex("[^a-zA-Z0-9._]"), "_")}"
                val targetVideo = File(videoDir, safeName)
                tempRawFile.copyTo(targetVideo, overwrite = true)
                extractPosterFrameForVideo(context, targetVideo)
                targetVideo.absolutePath
            } else {
                val normalizedPath = normalizeImageFileForStatus(context, tempRawFile)
                normalizedPath ?: tempRawFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving media uri: $uri", e)
            null
        } finally {
            if (tempRawFile.exists()) {
                runCatching { tempRawFile.delete() }
            }
        }
    }

    /**
     * Normalizes a local media file:
     * - If video, ensures poster exists and returns path
     * - If photo, rotates according to EXIF and fits to 9:16 canvas
     */
    fun normalizeImageFileForStatus(context: Context, source: File): String? {
        if (!source.exists() || !source.isFile || source.length() <= 0L) {
            return null
        }
        if (isVideoFile(source.absolutePath)) {
            extractPosterFrameForVideo(context, source)
            return source.absolutePath
        }

        val outputDir = File(context.filesDir, "status_photos").apply { mkdirs() }
        val baseName = source.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9]"), "_").take(32).ifBlank { "photo" }
        val fingerprint = Integer.toHexString("${source.absolutePath}:${source.length()}:${source.lastModified()}".hashCode())
        val target = File(outputDir, "${baseName}_${fingerprint}.png")

        if (target.exists() && target.length() > 0L) {
            return target.absolutePath
        }

        var decodedBitmap: Bitmap? = null
        return try {
            decodedBitmap = decodeAndScaleBitmap(source)?.let { bmp ->
                smartFitToPortrait916(bmp)
            }
            val bmp = decodedBitmap ?: return null

            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }

            if (target.exists() && target.length() > 0L) {
                Log.i(TAG, "Successfully normalized photo for status: ${source.name} -> ${target.name} (${bmp.width}x${bmp.height})")
                target.absolutePath
            } else {
                runCatching { target.delete() }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed normalizing image file: ${source.absolutePath}", e)
            runCatching { target.delete() }
            null
        } finally {
            decodedBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun decodeAndScaleBitmap(source: File): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val longEdge = maxOf(width, height)
                if (longEdge > MAX_STATUS_IMAGE_LONG_EDGE_PX) {
                    val scale = MAX_STATUS_IMAGE_LONG_EDGE_PX.toFloat() / longEdge.toFloat()
                    val targetW = (width * scale).roundToInt().coerceAtLeast(1)
                    val targetH = (height * scale).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetW, targetH)
                }
            }
        } else {
            // Older Android: decode bounds, compute sample size, then rotate if needed
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val longEdge = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            var sampleSize = 1
            while (longEdge / sampleSize > MAX_STATUS_IMAGE_LONG_EDGE_PX) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
            val rawBmp = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return null

            // Read EXIF orientation
            val exif = runCatching { ExifInterface(source.absolutePath) }.getOrNull()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL

            val rotationAngle = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotationAngle != 0f) {
                val matrix = Matrix().apply { postRotate(rotationAngle) }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                if (rotated != rawBmp) {
                    rawBmp.recycle()
                }
                rotated
            } else {
                rawBmp
            }
        }
    }

    /**
     * Smart Non-Cropping 9:16 Portrait Canvas:
     * Guarantees 100% of the user's photo is completely preserved and NEVER CUT OFF.
     * If the photo is already ~9:16 (within 4%), it fills directly without distortion.
     * If the photo is landscape (16:9, 4:3) or square (1:1), it fits the COMPLETE photo centered
     * over a stylized ambient blurred & darkened background of the photo to fill the 9:16 frame.
     */
    fun smartFitToPortrait916(sourceBmp: Bitmap): Bitmap {
        val w = sourceBmp.width
        val h = sourceBmp.height
        if (w <= 0 || h <= 0) return sourceBmp

        val targetRatio = 9f / 16f
        val currentRatio = w.toFloat() / h.toFloat()

        // Within 4% margin of 9:16 -> perfectly fills without cropping or distortion
        if (kotlin.math.abs(currentRatio - targetRatio) < 0.04f) {
            return sourceBmp
        }

        // Target canvas resolution (1080 x 1920)
        val targetCanvasW = 1080
        val targetCanvasH = 1920
        val output = Bitmap.createBitmap(targetCanvasW, targetCanvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Draw ambient blurred/darkened background to fill 9:16 canvas seamlessly
        val bgScale = maxOf(targetCanvasW.toFloat() / w.toFloat(), targetCanvasH.toFloat() / h.toFloat())
        val bgScaledW = w * bgScale
        val bgScaledH = h * bgScale
        val bgMatrix = Matrix().apply {
            postScale(bgScale, bgScale)
            postTranslate((targetCanvasW - bgScaledW) / 2f, (targetCanvasH - bgScaledH) / 2f)
        }
        paint.alpha = 130
        canvas.drawBitmap(sourceBmp, bgMatrix, paint)

        // Subtle dark scrim over background so center photo pops
        val scrimPaint = Paint().apply {
            color = Color.parseColor("#990B0F19")
        }
        canvas.drawRect(0f, 0f, targetCanvasW.toFloat(), targetCanvasH.toFloat(), scrimPaint)

        // 2. Draw FULL original photo centered (fitCenter) so NOT A SINGLE PIXEL IS CUT OFF
        val fgScale = minOf(targetCanvasW.toFloat() / w.toFloat(), targetCanvasH.toFloat() / h.toFloat())
        val fgScaledW = w * fgScale
        val fgScaledH = h * fgScale
        val fgMatrix = Matrix().apply {
            postScale(fgScale, fgScale)
            postTranslate((targetCanvasW - fgScaledW) / 2f, (targetCanvasH - fgScaledH) / 2f)
        }
        paint.alpha = 255
        canvas.drawBitmap(sourceBmp, fgMatrix, paint)

        if (sourceBmp != output && !sourceBmp.isRecycled) {
            sourceBmp.recycle()
        }
        return output
    }

    fun smartCropToPortrait916(sourceBmp: Bitmap): Bitmap = smartFitToPortrait916(sourceBmp)

    /**
     * Resolves an audio Uri (from device storage picker) to a local file in filesDir/status_bgm/
     * and returns Pair(absolutePath, cleanTitle).
     */
    fun copyAudioUriToLocal(context: Context, uri: Uri): Pair<String, String>? {
        return try {
            val rawName = queryDisplayName(context, uri) ?: "custom_audio_${System.currentTimeMillis()}.mp3"
            val cleanTitle = rawName
                .substringBeforeLast('.')
                .replace(Regex("[_\\-]+"), " ")
                .trim()
                .ifBlank { "Custom Song" }

            val safeFileName = "custom_${System.currentTimeMillis()}_" +
                rawName.replace(Regex("[^a-zA-Z0-9._]"), "_")

            val audioDir = File(context.filesDir, "status_bgm").apply { mkdirs() }
            val outFile = File(audioDir, safeFileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (outFile.exists() && outFile.length() > 0L) {
                Log.i(TAG, "Copied custom audio to local: ${outFile.absolutePath} ($cleanTitle)")
                Pair(outFile.absolutePath, cleanTitle)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying custom audio: $uri", e)
            null
        }
    }

    /**
     * Extracts an asset audio file into filesDir/status_bgm/ so C++ engine can decode it.
     */
    fun extractAudioAssetToLocal(context: Context, assetPath: String): File? {
        return try {
            val audioDir = File(context.filesDir, "status_bgm").apply { mkdirs() }
            var resolvedAssetPath = assetPath
            if (resolvedAssetPath.endsWith(".wav", ignoreCase = true)) {
                val candidateM4a = resolvedAssetPath.substringBeforeLast(".") + ".m4a"
                try {
                    context.assets.open(candidateM4a).close()
                    resolvedAssetPath = candidateM4a
                } catch (_: Exception) {}
            }
            val fileName = File(resolvedAssetPath).name
            val outFile = File(audioDir, fileName)
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(resolvedAssetPath).use { input ->
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

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (e: Exception) {
            null
        }
    }
}
