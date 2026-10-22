package com.video.engine.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
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
            decodedBitmap = decodeAndScaleBitmap(source)
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
