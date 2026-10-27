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

    /**
     * Resolves and normalizes an image Uri into an optimized local PNG file for the C++ video engine.
     * Automatically fixes EXIF rotation, scales down high-res camera photos, and converts to 
     * fast-decoding PNG format.
     */
    fun normalizeImageUriForStatus(context: Context, uri: Uri): String? {
        val displayName = queryDisplayName(context, uri) ?: "photo_${System.currentTimeMillis()}.jpg"
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
            val normalizedPath = normalizeImageFileForStatus(context, tempRawFile)
            normalizedPath ?: tempRawFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving image uri: $uri", e)
            null
        } finally {
            if (tempRawFile.exists()) {
                runCatching { tempRawFile.delete() }
            }
        }
    }

    /**
     * Normalizes a local image file: rotates according to EXIF and resizes so long edge <= 1920px.
     * Caches the output in filesDir/status_photos/ to ensure stability during preview and export.
     */
    fun normalizeImageFileForStatus(context: Context, source: File): String? {
        if (!source.exists() || !source.isFile || source.length() <= 0L) {
            return null
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
