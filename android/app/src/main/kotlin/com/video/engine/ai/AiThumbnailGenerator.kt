package com.video.engine.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * AI Viral Thumbnail & Cover Photo Generator for Storyline Video Editor.
 * Extracts high-energy keyframes from video and renders studio-grade
 * composite thumbnails with bold typography, vignette gradients, and branding.
 */
object AiThumbnailGenerator {

    data class ThumbnailResult(
        val success: Boolean,
        val filePath: String = "",
        val fileUri: Uri? = null,
        val errorMessage: String? = null,
    )

    fun generateThumbnail(
        context: Context,
        videoPath: String,
        headlineText: String = "VIRAL HOOK",
        targetTimeMs: Long = 2000L,
    ): ThumbnailResult {
        if (videoPath.isBlank() || !File(videoPath).exists()) {
            return ThumbnailResult(success = false, errorMessage = "Source video file not found")
        }

        val retriever = MediaMetadataRetriever()
        val baseFrame: Bitmap = try {
            retriever.setDataSource(videoPath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull() ?: 5000L
            val safeTimeMs = targetTimeMs.coerceIn(500L, (durationMs - 500L).coerceAtLeast(500L))

            retriever.getFrameAtTime(safeTimeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return ThumbnailResult(success = false, errorMessage = "Failed to extract keyframe")
        } catch (e: Exception) {
            return ThumbnailResult(success = false, errorMessage = "Metadata error: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        // Determine target dimensions (maintain 9:16 or 16:9 aspect)
        val isVertical = baseFrame.height > baseFrame.width
        val outWidth = if (isVertical) 1080 else 1920
        val outHeight = if (isVertical) 1920 else 1080

        val compositeBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(compositeBitmap)

        // 1. Draw scaled background video frame (center crop)
        val srcRect = Rect(0, 0, baseFrame.width, baseFrame.height)
        val dstRect = Rect(0, 0, outWidth, outHeight)
        val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(baseFrame, srcRect, dstRect, filterPaint)

        // 2. Draw Top & Bottom Vignette Shadow Gradients for high contrast text readability
        val topGradientPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, outHeight * 0.28f,
                Color.parseColor("#B3000000"), Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, outWidth.toFloat(), outHeight * 0.28f, topGradientPaint)

        val bottomGradientPaint = Paint().apply {
            shader = LinearGradient(
                0f, outHeight * 0.55f, 0f, outHeight.toFloat(),
                Color.TRANSPARENT, Color.parseColor("#E6000000"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, outHeight * 0.55f, outWidth.toFloat(), outHeight.toFloat(), bottomGradientPaint)

        // 3. Draw Category Badge at top
        val badgeText = "🔥 VIRAL HIGHLIGHT"
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            textSize = if (isVertical) 32f else 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC0B192C")
            style = Paint.Style.FILL
        }
        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val badgeBounds = Rect()
        badgePaint.getTextBounds(badgeText, 0, badgeText.length, badgeBounds)
        val badgeX = 64f
        val badgeY = 96f
        val badgeRect = RectF(
            badgeX - 24f,
            badgeY - badgeBounds.height() - 16f,
            badgeX + badgeBounds.width() + 24f,
            badgeY + 16f,
        )
        canvas.drawRoundRect(badgeRect, 20f, 20f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 20f, 20f, badgeBorderPaint)
        canvas.drawText(badgeText, badgeX, badgeY, badgePaint)

        // 4. Draw Main Headline Text with heavy 3D stroke & drop shadow
        val cleanHeadline = if (headlineText.isNotBlank()) headlineText.uppercase() else "MUST WATCH REEL"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE600") // Hormozi/Beast Punchy Yellow
            textSize = if (isVertical) 68f else 82f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.02f
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textPaint.textSize
            typeface = textPaint.typeface
            letterSpacing = textPaint.letterSpacing
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }

        // Multiline support (up to 2 lines)
        val words = cleanHeadline.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (w in words) {
            val testLine = if (currentLine.isEmpty()) w else "$currentLine $w"
            if (textPaint.measureText(testLine) > (outWidth - 140)) {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = w
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        val startY = outHeight * 0.76f
        val lineHeight = textPaint.textSize * 1.15f
        lines.take(2).forEachIndexed { idx, line ->
            val y = startY + (idx * lineHeight)
            val x = 64f
            // Draw 3D shadow
            val shadowPaint = Paint(strokePaint).apply {
                color = Color.parseColor("#CC000000")
            }
            canvas.drawText(line, x + 6f, y + 6f, shadowPaint)
            // Draw heavy stroke
            canvas.drawText(line, x, y, strokePaint)
            // Draw vibrant fill
            canvas.drawText(line, x, y, textPaint)
        }

        // 5. Draw Bottom Branding Pill: [STORYLINE AI • 4K HD]
        val brandText = "STORYLINE AI • 4K HD"
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(brandText, 64f, outHeight - 48f, brandPaint)

        // 6. Save to Storage & Register in MediaStore
        val filename = "thumbnail_${System.currentTimeMillis()}.jpg"
        return try {
            val file = saveBitmapToGallery(context, compositeBitmap, filename)
            ThumbnailResult(
                success = true,
                filePath = file?.absolutePath.orEmpty(),
            )
        } catch (e: Exception) {
            ThumbnailResult(success = false, errorMessage = "Save failed: ${e.message}")
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, filename: String): File? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val storylineDir = File(picturesDir, "StorylineThumbnails").apply { mkdirs() }
        val destFile = File(storylineDir, filename)

        var stream: OutputStream? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StorylineThumbnails")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    stream = context.contentResolver.openOutputStream(uri)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream!!)
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            } else {
                stream = FileOutputStream(destFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
        } finally {
            stream?.close()
        }

        // Also save fallback in cache dir for fast in-app access
        val cacheFile = File(context.cacheDir, filename)
        FileOutputStream(cacheFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
        return if (destFile.exists()) destFile else cacheFile
    }
}
