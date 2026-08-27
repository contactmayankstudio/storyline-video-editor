package com.video.engine

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.video.engine.UiToast as Toast
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.overlay.OverlayStore
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.stickers.StickerPacks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ExportController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val videoDurationMsProvider: () -> Long,
    private val isPlayingProvider: () -> Boolean,
    private val onPausePlayback: () -> Unit,
    private val notificationManagerProvider: () -> NotificationManager?,
    private val isWatermarkUnlockedProvider: () -> Boolean,
    private val onRequestWatermarkUnlock: ((callback: (Boolean) -> Unit) -> Unit),
    private val onConsumeWatermarkUnlock: (() -> Unit)? = null,
    private val onExportStarted: ((profileLabel: String, width: Int, height: Int, fps: Int, bitrateMbps: Int, codec: String) -> Unit)? = null,
    private val onExportProgress: ((progress: Int) -> Unit)? = null,
    private val onExportCompleted: ((success: Boolean, outputPath: String?, error: String?) -> Unit)? = null,
    private val onExportSuccess: (() -> Unit)? = null,
) {
    private data class ExportComplexity(
        val visualClipCount: Int,
        val overlayLayerCount: Int,
        val editedClipCount: Int,
        val effectsClipCount: Int,
        val chromaClipCount: Int,
    ) {
        val isComplex: Boolean
            get() = visualClipCount > 1 || overlayLayerCount > 0 || editedClipCount > 0 || effectsClipCount > 0 || chromaClipCount > 0

        val isVeryComplex: Boolean
            get() {
                val stressScore =
                    visualClipCount +
                        (overlayLayerCount * 2) +
                        editedClipCount +
                        (effectsClipCount * 2) +
                        (chromaClipCount * 2)
                return stressScore >= 5 || overlayLayerCount > 1 || chromaClipCount > 0 || effectsClipCount > 1
            }
    }

    private data class ExportProgressFrame(
        val progressLabel: String,
        val etaLabel: String,
        val statusLabel: String,
        val notificationTitle: String,
        val notificationText: String,
        val notificationProgress: Int,
        val indeterminate: Boolean,
    )

    companion object {
        private const val TAG = "[UI]"
        private const val EXPORT_NOTIFICATION_CHANNEL_ID = "video_export"
        private const val MAX_EXPORT_WIDTH = 1280
        private const val MAX_EXPORT_HEIGHT = 720
        private const val GALLERY_FOLDER_NAME = "Storyline"
        private val GALLERY_RELATIVE_PATH = "${Environment.DIRECTORY_MOVIES}/$GALLERY_FOLDER_NAME"
    }

    private val exportHandler = Handler(Looper.getMainLooper())
    private var exportPollRunnable: Runnable? = null
    private var progressDialog: android.app.Dialog? = null
    private var exportProgressBar: ProgressBar? = null
    private var exportPercentText: TextView? = null
    private var exportEtaText: TextView? = null
    private var exportStatusText: TextView? = null
    private var isExporting = false
    private var exportStartTimeMs: Long = 0L
    private var lastForegroundNotificationState: String? = null

    private fun beginDirectExportSession(): Boolean {
        if (isExporting) {
            Toast.makeText(activity, "Export already running", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!DeviceDetector.hasAvailableMemory()) {
            showErrorDialog("Not enough memory", "Please close other apps and try again.")
            return false
        }
        if (isPlayingProvider()) {
            onPausePlayback()
        }
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isExporting = true
        exportStartTimeMs = System.currentTimeMillis()
        resetForegroundExportNotificationState()
        return true
    }

    private fun finishDirectExportSession() {
        isExporting = false
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resetForegroundExportNotificationState()
        exportProgressBar = null
        exportPercentText = null
        exportEtaText = null
        exportStatusText = null
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            EXPORT_NOTIFICATION_CHANNEL_ID,
            "Video Export",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManagerProvider()?.createNotificationChannel(channel)
    }

    fun showExportDialog() {
        if (!DeviceDetector.hasAvailableMemory()) {
            showErrorDialog("Not enough memory", "Please close other apps and try again.")
            return
        }

        if (isPlayingProvider()) {
            onPausePlayback()
        }

        val previewView = previewViewProvider()
        if (previewView == null) {
            Toast.makeText(activity, "Preview view not available", Toast.LENGTH_SHORT).show()
            return
        }

        ExportDialog(
            activity = activity,
            previewView = previewView,
            durationMs = videoDurationMsProvider(),
            isWatermarkUnlockedProvider = isWatermarkUnlockedProvider,
            onRequestWatermarkUnlock = onRequestWatermarkUnlock,
        ).show()
    }

    fun performExport(
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        bitrateMbps: Int = 3,
        exportTitle: String = "Storyline",
        requestedProfileLabel: String = "HD",
        includeWatermark: Boolean = true,
        videoCodec: String = "h264",
    ) {
        if (!beginDirectExportSession()) {
            return
        }
        val previewView = previewViewProvider()
        if (previewView == null) {
            finishDirectExportSession()
            Toast.makeText(activity, "Preview view not available", Toast.LENGTH_SHORT).show()
            return
        }

        val previewDurationMs = previewView.getDuration()
        val durationMs = maxOf(previewDurationMs, videoDurationMsProvider())
        if (durationMs <= 0) {
            finishDirectExportSession()
            Toast.makeText(activity, "No clips loaded", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "Export duration chosen: preview=${previewDurationMs}ms fallback=${videoDurationMsProvider()}ms final=${durationMs}ms")

        val outputDir = File(activity.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, buildExportFileName(exportTitle, requestedProfileLabel, timestamp))
        val outputPath = outputFile.absolutePath

        val (lockedWidth, lockedHeight) = clampRequestedExportSize(width, height)
        val (exportWidth, exportHeight, exportFps, exportBitrateMbps) =
            adaptExportSettingsForDevice(lockedWidth, lockedHeight, fps, bitrateMbps)

        val requiredSpaceBytes = estimateExportFileSize(durationMs, exportBitrateMbps)
        val availableSpace = outputDir.freeSpace
        if (availableSpace < requiredSpaceBytes) {
            finishDirectExportSession()
            val requiredMB = requiredSpaceBytes / (1024 * 1024)
            val availableMB = availableSpace / (1024 * 1024)
            showErrorDialog("Not Enough Storage", "Required: ${requiredMB}MB\nAvailable: ${availableMB}MB")
            return
        }

        showExportProgressDialog(previewView, videoDurationMsProvider(), outputPath)
        onExportStarted?.invoke(
            requestedProfileLabel,
            exportWidth,
            exportHeight,
            exportFps,
            exportBitrateMbps,
            videoCodec,
        )

        pushForegroundExportNotification(
            title = "Video Export",
            text = "Preparing export pipeline...",
            progress = -1,
            indeterminate = true,
        )

        Thread {
            val temporaryStickerOverlayIds = mutableListOf<Int>()
            var watermarkOverlayId: Int? = null
            try {
                previewView.flushPendingClipPreviewTransformForExport()

                // 1. Force-sync all text overlays into native state before export.
                OverlayStore.all().forEach { overlay ->
                    previewView.updateTextOverlay(
                        overlay.id,
                        overlay.x,
                        overlay.y,
                        overlay.scale,
                        overlay.rotation,
                        overlay.color,
                        overlay.fontSize,
                        overlay.startTimeMs,
                        overlay.endTimeMs,
                    )
                    previewView.updateTextOverlayOpacity(
                        overlay.id,
                        if (overlay.visible) overlay.opacity else 0f,
                        0,
                        0,
                    )
                    previewView.setTextZOrder(overlay.id, 400 + overlay.layerIndex)
                    NativeBridge.setTextOverlayBitmap(previewView, overlay)
                }

                // 2. Mirror sticker/image overlays into temporary native bitmap overlays for export.
                temporaryStickerOverlayIds += registerTemporaryStickerOverlays(previewView)
                if (includeWatermark) {
                    watermarkOverlayId = registerTemporaryWatermarkOverlay(previewView, durationMs, exportWidth, exportHeight)
                }

                // 3. Register imported audio-track clips for export mixing.
                // Native export clip specs already cover visual clips and their embedded audio.
                // AudioClipStore is the source of truth for timeline audio-track imports.
                val audioClips = com.video.engine.audio.AudioClipStore.all().filter { clip ->
                    clip.sourcePath.isNotBlank()
                }
                previewView.nativeSetExportAudioClips(
                    paths        = audioClips.map { it.sourcePath }.toTypedArray(),
                    startTimesMs = audioClips.map { it.startTimeMs }.toLongArray(),
                    durationsMs  = audioClips.map { it.durationMs  }.toLongArray(),
                    volumes      = audioClips.map { clip ->
                        if (!clip.visible || clip.muted) 0f else clip.gain.coerceIn(0f, 2f)
                    }.toFloatArray(),
                    fadeInMs     = audioClips.map { it.fadeInMs.coerceAtLeast(0) }.toIntArray(),
                    fadeOutMs    = audioClips.map { it.fadeOutMs.coerceAtLeast(0) }.toIntArray(),
                    keyframeCsvs = audioClips.map { NativeBridge.serializeAudioGainKeyframesCsv(it.gainKeyframes) }.toTypedArray(),
                )

                Log.d(
                    TAG,
                    "Starting export: $outputPath ($exportWidth x $exportHeight @ ${exportFps}fps, ${exportBitrateMbps}Mbps, codec=$videoCodec)",
                )
                val success = previewView.exportToVideo(
                    outputPath = outputPath,
                    width = exportWidth,
                    height = exportHeight,
                    fps = exportFps,
                    bitrateMbps = exportBitrateMbps,
                    videoCodec = videoCodec,
                )
                val sizeMB = String.format("%.1f", outputFile.length() / (1024.0 * 1024.0))

                activity.runOnUiThread {
                    if (success) {
                        renderExportProgressFrame(
                            buildExportProgressFrame(
                                progress = 100,
                                elapsedSec = (System.currentTimeMillis() - exportStartTimeMs) / 1000,
                                videoDurationMs = durationMs,
                                exportOutputPath = outputPath,
                            ),
                        )
                    }
                    stopExportPolling()
                    if (success) {
                        exportHandler.postDelayed(
                            {
                                if (!includeWatermark) {
                                    onConsumeWatermarkUnlock?.invoke()
                                }
                                progressDialog?.dismiss()
                                finishDirectExportSession()
                                activity.stopService(Intent(activity, ExportService::class.java))
                                Log.d(TAG, "Export successful: $outputPath")
                                showExportCompleteDialog(outputPath, sizeMB, null)
                                onExportCompleted?.invoke(true, outputPath, null)
                                onExportSuccess?.invoke()
                            },
                            280L,
                        )
                    } else {
                        progressDialog?.dismiss()
                        finishDirectExportSession()
                        activity.stopService(Intent(activity, ExportService::class.java))
                        Log.e(TAG, "Export failed")
                        val nativeReason = previewView.getLastExportError().trim()
                        onExportCompleted?.invoke(false, null, nativeReason.ifEmpty { "native_export_failed" })
                        showErrorDialog(
                            "Export Failed",
                            if (nativeReason.isNotEmpty()) {
                                "Native export failed: $nativeReason"
                            } else {
                                "Native export failed while preparing the output video. This is not a storage permission issue."
                            },
                        )
                    }
                }
                if (success) {
                    val publishedExport = publishExportToGallery(outputFile)
                    activity.runOnUiThread {
                        publishedExport?.let {
                            Toast.makeText(activity, "Gallery copy ready", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export exception", e)
                activity.runOnUiThread {
                    stopExportPolling()
                    progressDialog?.dismiss()
                    finishDirectExportSession()
                    activity.stopService(Intent(activity, ExportService::class.java))
                    onExportCompleted?.invoke(false, null, e.message ?: "export_exception")
                    showErrorDialog("Export Error", e.message ?: "Unknown error")
                }
            }
            finally {
                watermarkOverlayId?.let { overlayId ->
                    runCatching { previewView.removeTextOverlay(overlayId) }
                }
                temporaryStickerOverlayIds.forEach { overlayId ->
                    runCatching { previewView.removeTextOverlay(overlayId) }
                }
            }
        }.start()
    }

    fun dismissProgressDialog() {
        progressDialog?.dismiss()
    }

    fun cleanup() {
        stopExportPolling()
    }

    private fun stopExportPolling() {
        exportPollRunnable?.let(exportHandler::removeCallbacks)
        exportPollRunnable = null
    }

    private fun resetForegroundExportNotificationState() {
        lastForegroundNotificationState = null
    }

    private fun pushForegroundExportNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ) {
        val normalizedProgress = if (progress < 0) -1 else progress.coerceIn(0, 100)
        val stateKey = "$title|$text|$normalizedProgress|$indeterminate"
        if (stateKey == lastForegroundNotificationState) return
        lastForegroundNotificationState = stateKey
        activity.startService(
            ExportService.buildIntent(
                context = activity,
                title = title,
                text = text,
                progress = normalizedProgress,
                indeterminate = indeterminate,
            ),
        )
    }

    private fun formatExportArtifactSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
        }
    }

    private fun buildExportArtifactHint(exportOutputPath: String): String? {
        val finalFile = File(exportOutputPath)
        if (finalFile.exists() && finalFile.length() > 0L) {
            return "Output ${formatExportArtifactSize(finalFile.length())}"
        }
        val audioOnlyFile = File("$exportOutputPath.mixed_audio.m4a")
        if (audioOnlyFile.exists() && audioOnlyFile.length() > 0L) {
            return "Audio ${formatExportArtifactSize(audioOnlyFile.length())}"
        }
        val videoOnlyFile = File("$exportOutputPath.video_only.mp4")
        if (videoOnlyFile.exists() && videoOnlyFile.length() > 0L) {
            return "Video ${formatExportArtifactSize(videoOnlyFile.length())}"
        }
        return null
    }

    private fun buildExportProgressFrame(
        progress: Int,
        elapsedSec: Long,
        videoDurationMs: Long,
        exportOutputPath: String,
    ): ExportProgressFrame {
        val artifactHint = buildExportArtifactHint(exportOutputPath)
        val slowDeviceHint =
            if (DeviceDetector.isLowEndDevice() && elapsedSec >= 20) {
                "Slow device: several minutes can be normal."
            } else {
                null
            }
        return when {
            progress <= 0 -> {
                val statusParts = buildList {
                    add("Preparing sources and overlays...")
                    artifactHint?.let(::add)
                    if (videoDurationMs >= 60_000L || DeviceDetector.isLowEndDevice()) {
                        add("Large timeline warm-up expected.")
                    }
                }
                ExportProgressFrame(
                    progressLabel = "0%",
                    etaLabel = if (elapsedSec < 6) "Preparing export..." else "Building export pipeline...",
                    statusLabel = statusParts.joinToString("  •  "),
                    notificationTitle = "Video Export",
                    notificationText = statusParts.joinToString(" • "),
                    notificationProgress = -1,
                    indeterminate = true,
                )
            }
            progress < 80 -> {
                val safeProgress = progress.coerceAtLeast(1)
                val eta = ((100 - safeProgress) * elapsedSec / safeProgress).coerceAtLeast(0)
                val statusParts = buildList {
                    add("Rendering video frames...")
                    artifactHint?.let(::add)
                    slowDeviceHint?.let(::add)
                }
                ExportProgressFrame(
                    progressLabel = "$progress%",
                    etaLabel = "Elapsed: ${elapsedSec}s  •  ETA: ${eta}s",
                    statusLabel = statusParts.joinToString("  •  "),
                    notificationTitle = "Video Export $progress%",
                    notificationText = statusParts.joinToString(" • "),
                    notificationProgress = progress,
                    indeterminate = false,
                )
            }
            progress < 90 -> {
                val statusParts = buildList {
                    add("Mixing timeline audio...")
                    artifactHint?.let(::add)
                }
                ExportProgressFrame(
                    progressLabel = "$progress%",
                    etaLabel = "Elapsed: ${elapsedSec}s  •  Audio pass in progress",
                    statusLabel = statusParts.joinToString("  •  "),
                    notificationTitle = "Video Export $progress%",
                    notificationText = statusParts.joinToString(" • "),
                    notificationProgress = progress,
                    indeterminate = false,
                )
            }
            progress < 100 -> {
                val statusParts = buildList {
                    add("Muxing final MP4...")
                    artifactHint?.let(::add)
                }
                ExportProgressFrame(
                    progressLabel = "$progress%",
                    etaLabel = "Elapsed: ${elapsedSec}s  •  Final container write",
                    statusLabel = statusParts.joinToString("  •  "),
                    notificationTitle = "Video Export $progress%",
                    notificationText = statusParts.joinToString(" • "),
                    notificationProgress = progress,
                    indeterminate = false,
                )
            }
            else -> {
                val statusParts = buildList {
                    add("Export complete")
                    artifactHint?.let(::add)
                }
                ExportProgressFrame(
                    progressLabel = "100%",
                    etaLabel = "Export ready",
                    statusLabel = statusParts.joinToString("  •  "),
                    notificationTitle = "Video Export 100%",
                    notificationText = statusParts.joinToString(" • "),
                    notificationProgress = 100,
                    indeterminate = false,
                )
            }
        }
    }

    private fun renderExportProgressFrame(frame: ExportProgressFrame) {
        exportProgressBar?.let { progressBar ->
            progressBar.isIndeterminate = frame.indeterminate
            if (!frame.indeterminate) {
                progressBar.progress = frame.notificationProgress
            }
        }
        exportPercentText?.text = frame.progressLabel
        exportEtaText?.text = frame.etaLabel
        exportStatusText?.text = frame.statusLabel
        pushForegroundExportNotification(
            title = frame.notificationTitle,
            text = frame.notificationText,
            progress = frame.notificationProgress,
            indeterminate = frame.indeterminate,
        )
    }

    private fun estimateExportFileSize(durationMs: Long, bitrateMbps: Int): Long {
        val durationSec = durationMs / 1000.0
        val bitrateBitsPerSec = bitrateMbps.coerceAtLeast(1).toLong() * 1024L * 1024L
        val payloadBytes = (bitrateBitsPerSec * durationSec / 8.0).toLong()
        val containerOverheadBytes = 8L * 1024L * 1024L
        return payloadBytes + containerOverheadBytes
    }

    private fun adaptExportSettingsForDevice(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        requestedBitrateMbps: Int,
    ): ExportSettings {
        val qualityProfile = DeviceDetector.getQualityProfile()
        val lockedRequestedFps = requestedFps.coerceAtMost(qualityProfile.maxExportFps).coerceAtLeast(24)
        val lockedRequestedBitrateMbps =
            requestedBitrateMbps
                .coerceAtLeast(1)
                .coerceAtMost(maxOf(1, (qualityProfile.exportBitrate + 999) / 1000))
        val complexity = inspectExportComplexity()
        val requestedPortrait = requestedHeight > requestedWidth
        fun sizedExportSettings(width: Int, height: Int, fps: Int, bitrateMbps: Int): ExportSettings {
            val normalizedWidth: Int
            val normalizedHeight: Int
            if (requestedPortrait) {
                normalizedWidth = minOf(width, height)
                normalizedHeight = maxOf(width, height)
            } else {
                normalizedWidth = maxOf(width, height)
                normalizedHeight = minOf(width, height)
            }
            val (lockedWidth, lockedHeight) = clampRequestedExportSize(normalizedWidth, normalizedHeight)
            return ExportSettings(
                width = lockedWidth,
                height = lockedHeight,
                fps = fps,
                bitrateMbps = bitrateMbps,
            )
        }

        fun aspectSafeExportSettings(maxLongEdge: Int, maxShortEdge: Int, fps: Int, bitrateMbps: Int): ExportSettings {
            val requestedSquare = requestedWidth == requestedHeight
            val maxWidth = when {
                requestedSquare -> maxShortEdge
                requestedPortrait -> maxShortEdge
                else -> maxLongEdge
            }.toFloat()
            val maxHeight = when {
                requestedSquare -> maxShortEdge
                requestedPortrait -> maxLongEdge
                else -> maxShortEdge
            }.toFloat()
            val scale = minOf(
                maxWidth / requestedWidth.toFloat(),
                maxHeight / requestedHeight.toFloat(),
                1f,
            )
            return sizedExportSettings(
                width = (requestedWidth * scale).roundToInt().coerceAtLeast(1),
                height = (requestedHeight * scale).roundToInt().coerceAtLeast(1),
                fps = fps,
                bitrateMbps = bitrateMbps,
            )
        }

        val requestsBeyondSd =
            maxOf(requestedWidth, requestedHeight) > 854 ||
                minOf(requestedWidth, requestedHeight) > 480 ||
                lockedRequestedFps > 24 ||
                lockedRequestedBitrateMbps > 2
        val deviceTier = DeviceDetector.getDeviceTier()
        val shouldForceVerySafeMode =
            complexity.isVeryComplex && (requestsBeyondSd || deviceTier != DeviceDetector.DeviceTier.HIGH)
        val shouldForceSafeMode =
            complexity.isComplex && requestsBeyondSd

        if (shouldForceVerySafeMode) {
            val severeLayerStack =
                complexity.overlayLayerCount > 1 ||
                    complexity.visualClipCount >= 4 ||
                    complexity.chromaClipCount > 0
            val targetLongEdge = if (severeLayerStack) 960 else 854
            val targetShortEdge = if (severeLayerStack) 540 else 480
            val downgraded = aspectSafeExportSettings(
                maxLongEdge = targetLongEdge,
                maxShortEdge = targetShortEdge,
                fps = minOf(lockedRequestedFps, 24),
                bitrateMbps = minOf(lockedRequestedBitrateMbps, 2).coerceAtLeast(1),
            )
            Log.w(
                TAG,
                "Downgrading very complex export from ${requestedWidth}x${requestedHeight}@${requestedFps}/${requestedBitrateMbps}Mbps " +
                    "to ${downgraded.width}x${downgraded.height}@${downgraded.fps}/${downgraded.bitrateMbps}Mbps " +
                    "(tier=$deviceTier clips=${complexity.visualClipCount} overlays=${complexity.overlayLayerCount} chroma=${complexity.chromaClipCount})",
            )
            return downgraded
        }

        if (!shouldForceSafeMode) {
            return sizedExportSettings(
                width = requestedWidth,
                height = requestedHeight,
                fps = lockedRequestedFps,
                bitrateMbps = lockedRequestedBitrateMbps,
            )
        }

        val downgraded = aspectSafeExportSettings(
            maxLongEdge = 960,
            maxShortEdge = 540,
            fps = minOf(lockedRequestedFps, 24),
            bitrateMbps = minOf(lockedRequestedBitrateMbps, 2).coerceAtLeast(2),
        )
        Log.w(
            TAG,
            "Downgrading complex export from ${requestedWidth}x${requestedHeight}@${requestedFps}/${requestedBitrateMbps}Mbps " +
                "to ${downgraded.width}x${downgraded.height}@${downgraded.fps}/${downgraded.bitrateMbps}Mbps",
        )
        return downgraded
    }

    private data class ExportSettings(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateMbps: Int,
    )

    private data class PublishedExport(
        val uri: Uri,
        val savedLocationLabel: String,
    )

    private fun clampRequestedExportSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return MAX_EXPORT_WIDTH to MAX_EXPORT_HEIGHT
        }
        val isLandscape = width >= height
        val maxWidth = if (isLandscape) MAX_EXPORT_WIDTH.toFloat() else MAX_EXPORT_HEIGHT.toFloat()
        val maxHeight = if (isLandscape) MAX_EXPORT_HEIGHT.toFloat() else MAX_EXPORT_WIDTH.toFloat()
        val scale = minOf(maxWidth / width.toFloat(), maxHeight / height.toFloat(), 1f)
        return maxOf((width * scale).toInt(), 1) to maxOf((height * scale).toInt(), 1)
    }

    private fun inspectExportComplexity(): ExportComplexity {
        var visualClipCount = 0
        var overlayClipCount = 0
        var editedClipCount = 0
        var effectsClipCount = 0
        var chromaClipCount = 0

        val result = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
        val clips = result?.data?.optJSONArray("clips")
        if (result?.success == true && clips != null) {
            for (index in 0 until clips.length()) {
                val clip = clips.optJSONObject(index) ?: continue
                if (!clip.optBoolean("enabled", true)) {
                    continue
                }

                when (clip.optString("trackType", "VIDEO").uppercase(Locale.US)) {
                    "VIDEO" -> visualClipCount += 1
                    "OVERLAY" -> {
                        visualClipCount += 1
                        overlayClipCount += 1
                    }
                }

                val playbackSpeed = clip.optDouble("playbackSpeed", 1.0)
                val opacity = clip.optDouble("opacity", 1.0)
                val curveProfile = clip.optString("curveSpeedProfile", "linear")
                val hasEdit =
                    abs(playbackSpeed - 1.0) > 0.01 ||
                        clip.optBoolean("reversePlayback", false) ||
                        clip.optBoolean("freezeFrameEnabled", false) ||
                        !curveProfile.equals("linear", ignoreCase = true) ||
                        abs(opacity - 1.0) > 0.01
                if (hasEdit) {
                    editedClipCount += 1
                }

                val hasEffects =
                    clip.optBoolean("effectsEnabled", false) ||
                        abs(clip.optDouble("brightness", 0.0)) > 0.01 ||
                        abs(clip.optDouble("contrast", 1.0) - 1.0) > 0.01 ||
                        abs(clip.optDouble("saturation", 1.0) - 1.0) > 0.01 ||
                        clip.optBoolean("lutEnabled", false)
                if (hasEffects) {
                    effectsClipCount += 1
                }

                if (clip.optBoolean("chromaEnabled", false)) {
                    chromaClipCount += 1
                }
            }
        }

        val overlayLayerCount =
            overlayClipCount +
                OverlayStore.all().count { it.visible && it.endTimeMs > it.startTimeMs } +
                StickerClipStore.all().count { it.visible && it.durationMs > 0 }

        return ExportComplexity(
            visualClipCount = visualClipCount,
            overlayLayerCount = overlayLayerCount,
            editedClipCount = editedClipCount,
            effectsClipCount = effectsClipCount,
            chromaClipCount = chromaClipCount,
        )
    }

    private fun registerTemporaryStickerOverlays(previewView: VideoPreviewView): List<Int> {
        val temporaryOverlayIds = mutableListOf<Int>()
        StickerClipStore.all()
            .asSequence()
            .filter { it.visible && it.durationMs > 0 }
            .sortedBy { it.layerIndex }
            .forEach { clip ->
                val (pixels, width, height) = createStickerPixels(clip) ?: return@forEach
                val nativeId = previewView.addTextOverlay(
                    0,
                    "",
                    clip.x,
                    clip.y,
                    clip.scale,
                    clip.rotation,
                    Color.WHITE,
                    96f,
                    clip.startTimeMs,
                    clip.startTimeMs + clip.durationMs,
                ).toInt()
                if (nativeId <= 0) {
                    return@forEach
                }
                previewView.setTextOverlayBitmap(nativeId, pixels, width, height)
                previewView.updateTextOverlayOpacity(nativeId, clip.opacity.coerceIn(0f, 1f), 0, 0)
                previewView.setTextZOrder(nativeId, 400 + clip.layerIndex)
                temporaryOverlayIds += nativeId
            }
        return temporaryOverlayIds
    }

    private fun registerTemporaryWatermarkOverlay(
        previewView: VideoPreviewView,
        durationMs: Long,
        exportWidth: Int = 1280,
        exportHeight: Int = 720,
    ): Int? {
        // Keep export watermark subtle and ratio-safe across portrait, square, and landscape renders.
        val shortEdge = minOf(exportWidth, exportHeight)
        val targetHeightPx = (shortEdge * 0.045f).toInt().coerceIn(30, 96)
        val insetPx = (shortEdge * 0.025f).toInt().coerceIn(12, 30)
        val bitmap = createWatermarkBitmap(targetHeightPx) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = TextBitmapHelper.bitmapToPixelArray(bitmap)
        val scale = targetHeightPx.toFloat() / height.toFloat()
        val scaledWidthPx = width * scale
        val scaledHeightPx = height * scale
        bitmap.recycle()

        val watermarkDurationMs = durationMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val halfWidthNorm = scaledWidthPx / (exportWidth.toFloat() * 2f)
        val halfHeightNorm = scaledHeightPx / (exportHeight.toFloat() * 2f)
        val centerX = 1f - (insetPx.toFloat() / exportWidth.toFloat()) - halfWidthNorm
        val centerY = 1f - (insetPx.toFloat() / exportHeight.toFloat()) - halfHeightNorm
        val nativeId = previewView.addTextOverlay(
            0, "", centerX, centerY, scale, 0f,
            Color.WHITE, 72f, 0, watermarkDurationMs,
        ).toInt()
        if (nativeId <= 0) return null
        previewView.setTextOverlayBitmap(nativeId, pixels, width, height)
        previewView.updateTextOverlayOpacity(nativeId, 0.52f, 0, 0)
        previewView.setTextZOrder(nativeId, 9_000)
        return nativeId
    }

    private fun createWatermarkBitmap(sizePx: Int = 160): Bitmap? {
        val height = sizePx.coerceAtLeast(30)
        val horizontalPadding = (height * 0.20f).toInt().coerceAtLeast(8)
        val iconSize = (height * 0.72f).toInt().coerceAtLeast(22)
        val gap = (height * 0.14f).toInt().coerceAtLeast(5)
        val label = "Storyline"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = height * 0.40f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(height * 0.08f, 0f, height * 0.03f, 0x88000000.toInt())
        }
        val textWidth = textPaint.measureText(label)
        val width = (horizontalPadding * 2 + iconSize + gap + textWidth).toInt().coerceAtLeast(height * 2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x5410181F
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x24FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = (height * 0.035f).coerceAtLeast(2f)
        }
        val radius = height * 0.28f
        val plateRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(plateRect, radius, radius, platePaint)
        canvas.drawRoundRect(
            RectF(
                highlightPaint.strokeWidth * 0.5f,
                highlightPaint.strokeWidth * 0.5f,
                width - highlightPaint.strokeWidth * 0.5f,
                height - highlightPaint.strokeWidth * 0.5f,
            ),
            radius,
            radius,
            highlightPaint,
        )

        val iconLeft = horizontalPadding
        val iconTop = (height - iconSize) / 2
        AppCompatResources.getDrawable(activity, R.drawable.ic_storyline_logo)?.let { drawable ->
            drawable.alpha = 235
            drawable.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            drawable.draw(canvas)
        }

        val textX = iconLeft + iconSize + gap.toFloat()
        val textY = height * 0.5f - ((textPaint.descent() + textPaint.ascent()) * 0.5f)
        canvas.drawText(label, textX, textY, textPaint)
        return bitmap
    }

    private fun createStickerPixels(clip: StickerClip): Triple<IntArray, Int, Int>? {
        val bitmap = createStickerBitmap(clip) ?: return null
        return try {
            Triple(
                TextBitmapHelper.bitmapToPixelArray(bitmap),
                bitmap.width,
                bitmap.height,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun createStickerBitmap(clip: StickerClip): Bitmap? {
        val rawBitmap = when {
            clip.type == "image" -> loadImageStickerBitmap(clip.imagePath)
                ?: TextBitmapHelper.createTextBitmap("IMG", 52f, Color.WHITE)
            else -> TextBitmapHelper.createTextBitmap(resolveStickerLabel(clip), 96f, Color.WHITE)
        }
        val scaledBitmap = scaleStickerBitmap(rawBitmap)
        if (scaledBitmap !== rawBitmap) {
            rawBitmap.recycle()
        }
        if (!clip.mirrorX) {
            return scaledBitmap
        }
        return try {
            val matrix = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(
                scaledBitmap,
                0,
                0,
                scaledBitmap.width,
                scaledBitmap.height,
                matrix,
                true,
            )
        } finally {
            scaledBitmap.recycle()
        }
    }

    private fun loadImageStickerBitmap(imagePath: String?): Bitmap? {
        val path = imagePath?.takeIf { it.isNotBlank() && !it.startsWith("placeholder://") } ?: return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    private fun scaleStickerBitmap(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= 384) {
            return bitmap
        }
        val scale = 384f / maxSide.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun resolveStickerLabel(clip: StickerClip): String {
        if (clip.type == "sticker") {
            StickerPacks.getAllPacks().values.forEach { pack ->
                pack.firstOrNull { it.id == clip.stickerId }?.let { return it.emojiOrSymbol }
            }
        }
        return "IMG"
    }

    private fun showExportProgressDialog(
        previewView: VideoPreviewView,
        videoDurationMs: Long,
        exportOutputPath: String,
    ) {
        val dialog = BottomSheetDialog(activity)
        dialog.setCancelable(false)
        val root = buildDarkSheet()

        root.addView(darkTitle("Exporting..."))
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388BFD"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
                .also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
        }
        root.addView(progressBar)
        val percentText = darkLabel("0%").also { it.textSize = 28f; it.gravity = android.view.Gravity.CENTER; it.setTextColor(android.graphics.Color.WHITE); it.setTypeface(null, Typeface.BOLD) }
        root.addView(percentText)
        val etaText = darkLabel("Processing...").also { it.gravity = android.view.Gravity.CENTER; it.setTextColor(android.graphics.Color.parseColor("#8A99AD")) }
        root.addView(etaText)
        val statusText = darkLabel("Processing...").also {
            it.gravity = android.view.Gravity.CENTER
            it.setTextColor(android.graphics.Color.parseColor("#8A99AD"))
            it.setPadding(0, dp(8), 0, dp(10))
        }
        root.addView(statusText)
        var cancelRequested = false
        root.addView(actionBtn("Cancel") {
            if (!cancelRequested) { cancelRequested = true; NativeBridge.cancelExport(previewView) }
        })

        dialog.setContentView(root)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.30f, maxRatio = 0.46f)
        progressDialog = dialog
        exportProgressBar = progressBar
        exportPercentText = percentText
        exportEtaText = etaText
        exportStatusText = statusText
        dialog.show()

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val progress = NativeBridge.getExportProgress(previewView)
                    val elapsedSec = (System.currentTimeMillis() - exportStartTimeMs) / 1000
                    if (progress >= 0) {
                        onExportProgress?.invoke(progress)
                        renderExportProgressFrame(
                            buildExportProgressFrame(
                                progress = progress,
                                elapsedSec = elapsedSec,
                                videoDurationMs = videoDurationMs,
                                exportOutputPath = exportOutputPath,
                            ),
                        )
                    }
                } catch (_: UnsatisfiedLinkError) { exportHandler.removeCallbacks(this); return }
                exportHandler.postDelayed(this, 500)
            }
        }
        exportPollRunnable = runnable
        exportHandler.post(runnable)
    }

    private fun publishExportToGallery(sourceFile: File): PublishedExport? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScopedGalleryExport(sourceFile)
        } else {
            publishLegacyGalleryExport(sourceFile)
        }
    }

    private fun publishScopedGalleryExport(sourceFile: File): PublishedExport? {
        val resolver = activity.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            },
        ) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open gallery output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
            PublishedExport(uri, "Gallery / Movies / $GALLERY_FOLDER_NAME")
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Failed to publish export to scoped gallery", error)
            null
        }
    }

    private fun publishLegacyGalleryExport(sourceFile: File): PublishedExport? {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val exportDir = File(moviesDir, GALLERY_FOLDER_NAME).also { it.mkdirs() }
        val targetFile = uniqueGalleryFile(exportDir, sourceFile.name)
        return try {
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(activity, arrayOf(targetFile.absolutePath), arrayOf("video/mp4"), null)
            PublishedExport(
                androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    targetFile,
                ),
                "Gallery / Movies / $GALLERY_FOLDER_NAME",
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to publish export to legacy gallery", error)
            null
        }
    }

    private fun uniqueGalleryFile(directory: File, requestedName: String): File {
        val baseName = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        var candidate = File(directory, requestedName)
        var index = 1
        while (candidate.exists()) {
            val nextName = if (extension.isNotBlank()) {
                "${baseName}_$index.$extension"
            } else {
                "${baseName}_$index"
            }
            candidate = File(directory, nextName)
            index += 1
        }
        return candidate
    }

    private fun buildExportFileName(title: String, requestedProfileLabel: String, timestamp: String): String {
        val safeTitle = sanitizeExportComponent(title).ifBlank { "Storyline" }
        val safeProfile = sanitizeExportComponent(requestedProfileLabel).ifBlank { "HD" }
        return "${safeTitle}_${safeProfile}_$timestamp.mp4"
    }

    private fun sanitizeExportComponent(value: String): String {
        return value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    }

    private fun showExportCompleteDialog(outputPath: String, sizeMB: String, publishedExport: PublishedExport?) {
        val playbackUri = publishedExport?.uri ?: runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                File(outputPath),
            )
        }.getOrNull()
        val dialog = BottomSheetDialog(activity)
        val root = buildDarkSheet()
        root.addView(darkTitle("✓  Export Complete"))
        root.addView(darkLabel("Size: ${sizeMB}MB").also { it.gravity = android.view.Gravity.CENTER; it.setPadding(0, 0, 0, dp(8)) })
        root.addView(darkLabel("Saved to: ${publishedExport?.savedLocationLabel ?: "App storage"}").also {
            it.gravity = android.view.Gravity.CENTER
            it.setPadding(0, 0, 0, dp(12))
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionBtn("▶ Play") {
            try {
                val uri = playbackUri ?: throw IllegalStateException("No playable export URI")
                activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {
                Toast.makeText(activity, "No player found", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }.also { it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { m -> m.setMargins(0, 0, dp(6), 0) } })
        row.addView(actionBtn("⤴ Share") {
            try {
                val uri = playbackUri ?: throw IllegalStateException("No shareable export URI")
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share",
                    ),
                )
            } catch (_: Exception) {}
            dialog.dismiss()
        }.also { it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f) })
        root.addView(row)
        root.addView(actionBtn("← Back to Editor") { dialog.dismiss(); activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            .also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).also { m -> m.topMargin = dp(8) } })
        dialog.setContentView(root)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.34f, maxRatio = 0.52f)
        progressDialog = dialog
        dialog.show()
    }

    private fun showErrorDialog(title: String, message: String) {
        val dialog = BottomSheetDialog(activity)
        val root = buildDarkSheet()
        root.addView(darkTitle("⚠  $title"))
        root.addView(darkLabel(message).also { it.setPadding(0, 0, 0, dp(16)) })
        root.addView(actionBtn("OK") { dialog.dismiss() })
        dialog.setContentView(root)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.28f, maxRatio = 0.44f)
        dialog.show()
    }

    // ── Dark sheet helpers ────────────────────────────────────────────────────
    private fun buildDarkSheet() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                dp(24).toFloat(), dp(24).toFloat(),
                dp(24).toFloat(), dp(24).toFloat(),
                0f, 0f,
                0f, 0f,
            )
            setColor(Color.parseColor("#11151B"))
            setStroke(dp(1), Color.parseColor("#1B222C"))
        }
        setPadding(dp(20), dp(14), dp(20), dp(32))
        addView(sheetHandle())
    }
    private fun sheetHandle() = TextView(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.parseColor("#3A4452"))
        }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp(14)
        }
    }
    private fun darkTitle(text: String) = TextView(activity).apply {
        this.text = text; textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(16))
    }
    private fun darkLabel(text: String) = TextView(activity).apply {
        this.text = text; textSize = 13f
        setTextColor(Color.parseColor("#8A99AD"))
    }
    private fun actionBtn(label: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#171C23"))
            setStroke(dp(1), Color.parseColor("#222A36"))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        setOnClickListener { onClick() }
    }
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
