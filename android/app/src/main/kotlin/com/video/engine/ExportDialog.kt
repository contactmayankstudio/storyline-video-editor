package com.video.engine

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ExportDialog(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val durationMs: Long = 0L,
    private val isWatermarkUnlockedProvider: () -> Boolean = { false },
    private val onRequestWatermarkUnlock: ((callback: (Boolean) -> Unit) -> Unit) = {},
) {
    private data class ExportProfile(val label: String, val longEdge: Int)
    private data class AspectRatio(val label: String, val w: Int, val h: Int)

    private val aspectRatios = listOf(
        AspectRatio("16:9", 16, 9),
        AspectRatio("9:16", 9, 16),
        AspectRatio("1:1", 1, 1),
        AspectRatio("4:5", 4, 5),
        AspectRatio("4:3", 4, 3),
        AspectRatio("21:9", 21, 9),
    )

    private val profiles = listOf(
        ExportProfile("720p", 1280),
        ExportProfile("1080p", 1920),
        ExportProfile("2K", 2560),
        ExportProfile("4K", 3840),
    )

    private val frameRates = listOf(24, 30, 60)
    private val qualityLevels = listOf("Standard", "High", "Best")

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetRootBackground()
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(
            root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 1. Drag Handle
        root.addView(dragHandle())

        // 2. Header Row (Title & Close)
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(4) }
        }

        val titleView = TextView(activity).apply {
            text = "Export Video"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(activity).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#8A99AD"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dialog.dismiss() }
        }

        headerRow.addView(titleView)
        headerRow.addView(closeButton)
        root.addView(headerRow)

        val durationLabel = describeDuration(durationMs)
        val subtitleView = TextView(activity).apply {
            text = "$durationLabel • Ready to export"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8A99AD"))
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(subtitleView)

        // Initial State
        val defaultProfileIndex = if (DeviceDetector.isLowEndDevice()) 0 else 1 // 720p for low-end, 1080p default
        var selectedProfileIndex = defaultProfileIndex
        var selectedFpsIndex = if (DeviceDetector.isLowEndDevice()) 0 else 1 // 24 for low-end, 30 default
        var selectedQualityIndex = 1 // High default
        var selectedFormatIndex = 0 // 0 = Video
        var watermarkUnlocked = isWatermarkUnlockedProvider()
        val selectedAspectRatioIndex = if (activity is VideoEditorActivity) activity.getSelectedAspectRatioIndex() else 0

        // Buttons tracking
        val formatButtons = mutableListOf<TextView>()
        val resolutionButtons = mutableListOf<TextView>()
        val fpsButtons = mutableListOf<TextView>()
        val qualityButtons = mutableListOf<TextView>()

        // 3. FORMAT SELECTOR
        root.addView(sectionLabel("FORMAT"))
        val formatRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("MP4 Video").forEachIndexed { index, formatName ->
            val btn = choiceButton(formatName)
            btn.setOnClickListener {
                selectedFormatIndex = index
                updateUi()
            }
            formatButtons += btn
            formatRow.addView(btn)
        }
        root.addView(formatRow)

        // 4. RESOLUTION SELECTOR
        root.addView(sectionLabel("RESOLUTION"))
        val resolutionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        profiles.forEachIndexed { index, profile ->
            val btn = choiceButton(profile.label)
            btn.setOnClickListener {
                selectedProfileIndex = index
                updateUi()
            }
            resolutionButtons += btn
            resolutionRow.addView(btn)
        }
        root.addView(resolutionRow)

        // 5. FRAME RATE SELECTOR
        root.addView(sectionLabel("FRAME RATE"))
        val fpsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        frameRates.forEachIndexed { index, fpsVal ->
            val btn = choiceButton("${fpsVal} FPS")
            btn.setOnClickListener {
                selectedFpsIndex = index
                updateUi()
            }
            fpsButtons += btn
            fpsRow.addView(btn)
        }
        root.addView(fpsRow)

        // 6. QUALITY SELECTOR
        root.addView(sectionLabel("QUALITY"))
        val qualityRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        qualityLevels.forEachIndexed { index, qName ->
            val btn = choiceButton(qName)
            btn.setOnClickListener {
                selectedQualityIndex = index
                updateUi()
            }
            qualityButtons += btn
            qualityRow.addView(btn)
        }
        root.addView(qualityRow)

        // 7. EXPORT SUMMARY CARD
        val summaryCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = surfaceCard()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(20) }
        }

        val summaryCaption = TextView(activity).apply {
            text = "EXPORT SUMMARY"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8A99AD"))
            letterSpacing = 0.06f
        }
        val summaryTitle = TextView(activity).apply {
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(3))
        }
        val summaryMeta = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#8A99AD"))
        }
        summaryCard.addView(summaryCaption)
        summaryCard.addView(summaryTitle)
        summaryCard.addView(summaryMeta)
        root.addView(summaryCard)

        // 8. WATERMARK SECTION
        val watermarkCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = surfaceCard()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(14) }
        }

        val watermarkHeader = TextView(activity).apply {
            text = "Watermark"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val watermarkSubtitle = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#8A99AD"))
            setPadding(0, dp(3), 0, dp(12))
        }
        val watermarkButton = TextView(activity).apply {
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        watermarkButton.setOnClickListener {
            if (!watermarkUnlocked) {
                watermarkButton.text = "Loading Ad..."
                watermarkButton.isEnabled = false
                onRequestWatermarkUnlock { success ->
                    activity.runOnUiThread {
                        if (success) {
                            watermarkUnlocked = true
                        }
                        updateUi()
                    }
                }
            }
        }

        watermarkCard.addView(watermarkHeader)
        watermarkCard.addView(watermarkSubtitle)
        watermarkCard.addView(watermarkButton)
        root.addView(watermarkCard)

        // 9. PRIMARY EXPORT BUTTON
        val exportButton = TextView(activity).apply {
            text = "Export Video"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = primaryExportButtonBackground()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            ).also { it.topMargin = dp(20) }
        }
        root.addView(exportButton)

        fun resolveEffectiveSettings(): Triple<Int, Int, Int> {
            val profile = profiles[selectedProfileIndex]
            val fps = frameRates[selectedFpsIndex]
            val ratio = aspectRatios.getOrNull(selectedAspectRatioIndex) ?: aspectRatios[0]

            val longEdge = profile.longEdge
            val (w, h) = if (ratio.w >= ratio.h) {
                longEdge to (longEdge * ratio.h / ratio.w)
            } else {
                (longEdge * ratio.w / ratio.h) to longEdge
            }

            // Bitrate in Mbps based on resolution, fps, quality
            val baseBitrate = when {
                longEdge <= 1280 -> intArrayOf(3, 5, 7)[selectedQualityIndex]
                longEdge <= 1920 -> intArrayOf(6, 10, 14)[selectedQualityIndex]
                longEdge <= 2560 -> intArrayOf(12, 18, 26)[selectedQualityIndex]
                else -> intArrayOf(20, 30, 45)[selectedQualityIndex]
            }
            val fpsFactor = if (fps == 60) 1.35f else 1.0f
            val bitrateMbps = (baseBitrate * fpsFactor).roundToInt().coerceAtLeast(2)

            return Triple(w, h, bitrateMbps)
        }

        fun updateUi() {
            // Update button styles
            formatButtons.forEachIndexed { i, btn -> styleChoiceButton(btn, i == selectedFormatIndex) }
            resolutionButtons.forEachIndexed { i, btn -> styleChoiceButton(btn, i == selectedProfileIndex) }
            fpsButtons.forEachIndexed { i, btn -> styleChoiceButton(btn, i == selectedFpsIndex) }
            qualityButtons.forEachIndexed { i, btn -> styleChoiceButton(btn, i == selectedQualityIndex) }

            val (width, height, bitrateMbps) = resolveEffectiveSettings()
            val fps = frameRates[selectedFpsIndex]
            val qualityLabel = qualityLevels[selectedQualityIndex]
            val profileLabel = profiles[selectedProfileIndex].label

            // Update Summary Card
            summaryTitle.text = "$profileLabel • $fps FPS • $qualityLabel"
            val estimatedBytes = estimateOutputSize(durationMs, bitrateMbps)
            val sizeStr = formatSize(estimatedBytes)
            val wmText = if (watermarkUnlocked) "No Watermark" else "Watermark Included"
            summaryMeta.text = "$sizeStr estimated • $width x $height • $wmText"

            // Update Watermark Section
            if (watermarkUnlocked) {
                watermarkSubtitle.text = "✓ Watermark will be removed for this export."
                watermarkSubtitle.setTextColor(Color.parseColor("#3FB950"))
                watermarkButton.text = "✓ Watermark Removed"
                watermarkButton.background = unlockedBadgeBackground()
                watermarkButton.setTextColor(Color.parseColor("#3FB950"))
                watermarkButton.isEnabled = false
                watermarkButton.alpha = 0.85f
            } else {
                watermarkSubtitle.text = "Your video will include a Storyline watermark."
                watermarkSubtitle.setTextColor(Color.parseColor("#8A99AD"))
                watermarkButton.text = "🎁 Watch Ad to Remove Watermark"
                watermarkButton.background = rewardButtonBackground()
                watermarkButton.setTextColor(Color.parseColor("#58A6FF"))
                watermarkButton.isEnabled = true
                watermarkButton.alpha = 1f
            }
        }

        exportButton.setOnClickListener {
            val (width, height, bitrateMbps) = resolveEffectiveSettings()
            val fps = frameRates[selectedFpsIndex]
            val profileLabel = profiles[selectedProfileIndex].label
            val exportTitle = "Storyline"

            if (activity is VideoEditorActivity) {
                activity.performExport(
                    width = width,
                    height = height,
                    fps = fps,
                    bitrateMbps = bitrateMbps,
                    exportTitle = exportTitle,
                    requestedProfileLabel = profileLabel,
                    includeWatermark = !watermarkUnlocked,
                    videoCodec = "h264",
                )
            } else {
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(movies, "Storyline").also { it.mkdirs() }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outFile = File(appDir, "Storyline_${profileLabel}_$ts.mp4")
                previewView.startExport(
                    outFile.absolutePath,
                    width,
                    height,
                    fps,
                )
            }
            dialog.dismiss()
        }

        updateUi()
        dialog.setContentView(scroll)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.72f, maxRatio = 0.95f)
        dialog.show()
    }

    private fun sectionLabel(title: String): TextView =
        TextView(activity).apply {
            text = title
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8A99AD"))
            letterSpacing = 0.06f
            setPadding(0, dp(16), 0, dp(8))
        }

    private fun choiceButton(label: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(3)
                it.marginEnd = dp(3)
            }
            styleChoiceButton(this, false)
        }

    private fun styleChoiceButton(button: TextView, selected: Boolean) {
        button.background = chipBackground(selected)
        button.setTextColor(if (selected) Color.WHITE else Color.parseColor("#8A99AD"))
        button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun dragHandle(): View =
        View(activity).apply {
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

    private fun sheetRootBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            setColor(Color.parseColor("#11151B"))
            setStroke(dp(1), Color.parseColor("#1B222C"))
        }

    private fun surfaceCard(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#171C23"))
            setStroke(dp(1), Color.parseColor("#222A36"))
        }

    private fun chipBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            if (selected) {
                setColor(Color.parseColor("#16263D"))
                setStroke(dp(1), Color.parseColor("#388BFD"))
            } else {
                setColor(Color.parseColor("#171C23"))
                setStroke(dp(1), Color.parseColor("#222A36"))
            }
        }

    private fun rewardButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#142033"))
            setStroke(dp(1), Color.parseColor("#2B5282"))
        }

    private fun unlockedBadgeBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#12231A"))
            setStroke(dp(1), Color.parseColor("#235432"))
        }

    private fun primaryExportButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#1F6FEB"))
            setStroke(dp(1), Color.parseColor("#388BFD"))
        }

    private fun estimateOutputSize(durationMs: Long, bitrateMbps: Int): Long {
        if (durationMs <= 0L) return 0L
        val bitrateBytesPerSec = bitrateMbps * 1024L * 1024L / 8L
        val payloadBytes = bitrateBytesPerSec * (durationMs / 1000L)
        val overheadBytes = 2L * 1024L * 1024L
        return payloadBytes + overheadBytes
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "File size varies"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun describeDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0s"
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        return if (minutes > 0) {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        } else {
            String.format(Locale.US, "%ds", seconds)
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
