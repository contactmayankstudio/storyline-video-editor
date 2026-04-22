package com.video.engine

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.overlay.OverlayStore
import com.video.engine.stickers.StickerClipStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ExportDialog(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val durationMs: Long = 0L,
    private val isWatermarkUnlockedProvider: () -> Boolean = { false },
    private val onRequestWatermarkUnlock: ((callback: (Boolean) -> Unit) -> Unit) = {},
) {
    private data class ExportProfile(val label: String, val width: Int, val height: Int)
    private data class EffectiveExportSettings(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateMbps: Int,
    )

    private data class ProjectExportComplexity(
        val visualClipCount: Int,
        val overlayClipCount: Int,
        val audioClipCount: Int,
        val textOverlayCount: Int,
        val stickerOverlayCount: Int,
        val editedClipCount: Int,
        val effectsClipCount: Int,
        val chromaClipCount: Int,
    ) {
        val totalOverlayLayers: Int
            get() = overlayClipCount + textOverlayCount + stickerOverlayCount

        val isComplex: Boolean
            get() = visualClipCount > 1 || totalOverlayLayers > 0 || editedClipCount > 0 || effectsClipCount > 0 || chromaClipCount > 0
    }

    private data class ExportRecommendation(
        val profileIndex: Int,
        val fps: Int,
        val qualityIndex: Int,
        val note: String?,
    )

    private data class AspectRatio(val label: String, val w: Int, val h: Int)
    private val aspectRatios = listOf(
        AspectRatio("16:9", 16, 9),
        AspectRatio("1:1",  1,  1),
        AspectRatio("9:16", 9, 16),
        AspectRatio("4:5",  4,  5),
    )

    private val profiles = listOf(
        ExportProfile("HD", 1280, 720),
        ExportProfile("2K", 2560, 1440),
    )
    private val qualityLabels = listOf("Fast", "Balanced", "Clean")

    fun show() {
        val complexity = inspectProjectComplexity()
        val recommendation = recommendExportSettings(complexity)
        val dialog = BottomSheetDialog(activity)
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetRootBackground()
            setPadding(dp(20), dp(14), dp(20), dp(28))
        }
        scroll.addView(
            root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(dragHandle())
        root.addView(titleView("Export Video"))
        root.addView(
            subtitleView(
                "${describeDuration(durationMs)}  •  ${complexity.visualClipCount} visual  •  " +
                    "${complexity.audioClipCount} audio  •  ${complexity.totalOverlayLayers} overlays",
            ),
        )
        recommendation.note?.let { root.addView(recommendationBanner(it)) }

        var selectedProfileIndex = recommendation.profileIndex
        var selectedFps = recommendation.fps
        var selectedQualityIndex = recommendation.qualityIndex
        var watermarkUnlocked = isWatermarkUnlockedProvider()
        var selectedAspectRatioIndex = if (activity is MainActivity) activity.getSelectedAspectRatioIndex() else 0

        val ratioButtons = mutableListOf<TextView>()
        val resolutionButtons = mutableListOf<TextView>()
        val fpsButtons = mutableListOf<TextView>()
        val qualityLabelsRow = mutableListOf<TextView>()
        lateinit var summaryValue: TextView
        lateinit var summaryMeta: TextView
        lateinit var exportButton: TextView
        lateinit var watermarkStatus: TextView
        lateinit var watermarkAction: TextView

        root.addView(sectionLabel("Resolution"))
        val resolutionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        profiles.forEachIndexed { index, profile ->
            val button = choiceButton(profile.label)
            button.setOnClickListener {
                selectedProfileIndex = index
                refreshExportSummary(
                    resolutionButtons,
                    ratioButtons,
                    fpsButtons,
                    qualityLabelsRow,
                    selectedProfileIndex,
                    selectedAspectRatioIndex,
                    selectedFps,
                    selectedQualityIndex,
                    watermarkUnlocked,
                    summaryValue,
                    summaryMeta,
                    exportButton,
                )
            }
            resolutionButtons += button
            resolutionRow.addView(button)
        }
        root.addView(resolutionRow)

        // ── Aspect Ratio ──────────────────────────────────────────────────────
        root.addView(sectionLabel("Aspect Ratio"))
        val ratioRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        aspectRatios.forEachIndexed { index, ratio ->
            val button = choiceButton(ratio.label)
            button.setOnClickListener {
                selectedAspectRatioIndex = index
                refreshExportSummary(
                    resolutionButtons, ratioButtons, fpsButtons, qualityLabelsRow,
                    selectedProfileIndex, selectedAspectRatioIndex, selectedFps, selectedQualityIndex,
                    watermarkUnlocked, summaryValue, summaryMeta, exportButton,
                )
            }
            ratioButtons += button
            ratioRow.addView(button)
        }
        root.addView(ratioRow)
        root.addView(sectionLabel("Frame Rate"))
        val fpsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(24, 30).forEach { fps ->
            val button = choiceButton("${fps}fps")
            button.setOnClickListener {
                selectedFps = fps
                refreshExportSummary(
                    resolutionButtons,
                    ratioButtons,
                    fpsButtons,
                    qualityLabelsRow,
                    selectedProfileIndex,
                    selectedAspectRatioIndex,
                    selectedFps,
                    selectedQualityIndex,
                    watermarkUnlocked,
                    summaryValue,
                    summaryMeta,
                    exportButton,
                )
            }
            fpsButtons += button
            fpsRow.addView(button)
        }
        root.addView(fpsRow)

        root.addView(sectionLabel("Title"))
        val titleInput = EditText(activity).apply {
            setText("Storyline")
            setSelection(text.length)
            hint = "Storyline"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6F7E8B"))
            background = inputBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(titleInput)

        root.addView(sectionLabel("Quality"))
        val qualityTitleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val qualityCurrent = TextView(activity).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bitrateCurrent = TextView(activity).apply {
            textSize = 14f
            setTextColor(accentWarm())
        }
        qualityTitleRow.addView(qualityCurrent)
        qualityTitleRow.addView(bitrateCurrent)
        root.addView(qualityTitleRow)

        val qualitySeek = SeekBar(activity).apply {
            max = qualityLabels.lastIndex
            progress = selectedQualityIndex
            progressTintList = android.content.res.ColorStateList.valueOf(accentBlue())
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(qualitySeek)

        val qualityHintRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        qualityLabels.forEach { label ->
            val chip = TextView(activity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#7C7C7C"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            qualityLabelsRow += chip
            qualityHintRow.addView(chip)
        }
        root.addView(qualityHintRow)

        root.addView(sectionLabel("Watermark"))
        val watermarkCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = surfaceCard()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(10) }
        }
        val watermarkIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_storyline_logo)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(12) }
        }
        val watermarkInfo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val watermarkTitle = TextView(activity).apply {
            text = "Storyline logo watermark"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        watermarkStatus = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C8C8C8"))
            setPadding(0, dp(4), 0, 0)
        }
        watermarkInfo.addView(watermarkTitle)
        watermarkInfo.addView(watermarkStatus)
        watermarkCard.addView(watermarkIcon)
        watermarkCard.addView(watermarkInfo)
        root.addView(watermarkCard)

        watermarkAction = actionButton("Watch Ad Remove Watermark", "#2A2A2A", "#FFFFFF").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(10) }
        }
        watermarkAction.setOnClickListener {
            if (watermarkUnlocked) {
                return@setOnClickListener
            }
            watermarkAction.isEnabled = false
            watermarkAction.alpha = 0.75f
            watermarkAction.text = "Loading Ad..."
            onRequestWatermarkUnlock { unlocked ->
                activity.runOnUiThread {
                    watermarkUnlocked = watermarkUnlocked || unlocked
                    refreshWatermarkState(watermarkUnlocked, watermarkStatus, watermarkAction)
                    refreshExportSummary(
                        resolutionButtons,
                        ratioButtons,
                        fpsButtons,
                        qualityLabelsRow,
                        selectedProfileIndex,
                        selectedAspectRatioIndex,
                        selectedFps,
                        selectedQualityIndex,
                        watermarkUnlocked,
                        summaryValue,
                        summaryMeta,
                        exportButton,
                    )
                }
            }
        }
        root.addView(watermarkAction)

        val summaryCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = surfaceCard()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(18) }
        }
        val summaryCaption = TextView(activity).apply {
            text = "Export Summary"
            textSize = 12f
            setTextColor(Color.parseColor("#8E9AA6"))
        }
        summaryValue = TextView(activity).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        }
        summaryMeta = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#C8C8C8"))
        }
        summaryCard.addView(summaryCaption)
        summaryCard.addView(summaryValue)
        summaryCard.addView(summaryMeta)
        root.addView(summaryCard)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(18) }
        }
        val cancelButton = actionButton("Cancel", "#2A2A2A", "#FFFFFF").apply {
            setOnClickListener { dialog.dismiss() }
        }
        exportButton = actionButton("Export", "#FF5A52", "#FFFFFF")
        actions.addView(cancelButton)
        actions.addView(exportButton)
        root.addView(actions)

        fun launchExport() {
            val profile = profiles[selectedProfileIndex]
            val ratio = aspectRatios[selectedAspectRatioIndex]
            val effectiveSettings = resolveEffectiveExportSettings(profile, selectedFps, selectedQualityIndex, ratio.w, ratio.h)
            val exportTitle = titleInput.text?.toString()?.trim().orEmpty().ifBlank { "Storyline" }
            if (activity is MainActivity) {
                activity.performExport(
                    effectiveSettings.width,
                    effectiveSettings.height,
                    effectiveSettings.fps,
                    effectiveSettings.bitrateMbps,
                    exportTitle,
                    profile.label,
                    includeWatermark = !watermarkUnlocked,
                )
            } else {
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(movies, "Storyline").also { it.mkdirs() }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                previewView.startExport(
                    File(appDir, buildRequestedExportFileName(exportTitle, profile.label, ts)).absolutePath,
                    effectiveSettings.width,
                    effectiveSettings.height,
                    effectiveSettings.fps,
                )
            }
            dialog.dismiss()
        }

        exportButton.setOnClickListener { launchExport() }
        qualitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedQualityIndex = progress.coerceIn(0, qualityLabels.lastIndex)
                refreshExportSummary(
                    resolutionButtons,
                    ratioButtons,
                    fpsButtons,
                    qualityLabelsRow,
                    selectedProfileIndex,
                    selectedAspectRatioIndex,
                    selectedFps,
                    selectedQualityIndex,
                    watermarkUnlocked,
                    summaryValue,
                    summaryMeta,
                    exportButton,
                )
                val profile = profiles[selectedProfileIndex]
                val bitrateMbps = resolveEffectiveExportSettings(profile, selectedFps, selectedQualityIndex).bitrateMbps
                qualityCurrent.text = qualityLabels[selectedQualityIndex]
                bitrateCurrent.text = "${bitrateMbps} Mbps"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        refreshExportSummary(
            resolutionButtons,
            ratioButtons,
            fpsButtons,
            qualityLabelsRow,
            selectedProfileIndex,
            selectedAspectRatioIndex,
            selectedFps,
            selectedQualityIndex,
            watermarkUnlocked,
            summaryValue,
            summaryMeta,
            exportButton,
        )
        val initialProfile = profiles[selectedProfileIndex]
        qualityCurrent.text = qualityLabels[selectedQualityIndex]
        bitrateCurrent.text = "${resolveEffectiveExportSettings(initialProfile, selectedFps, selectedQualityIndex).bitrateMbps} Mbps"
        refreshWatermarkState(watermarkUnlocked, watermarkStatus, watermarkAction)

        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun refreshExportSummary(
        resolutionButtons: List<TextView>,
        ratioButtons: List<TextView>,
        fpsButtons: List<TextView>,
        qualityLabelsRow: List<TextView>,
        selectedProfileIndex: Int,
        selectedAspectRatioIndex: Int,
        selectedFps: Int,
        selectedQualityIndex: Int,
        watermarkUnlocked: Boolean,
        summaryValue: TextView,
        summaryMeta: TextView,
        exportButton: TextView,
    ) {
        resolutionButtons.forEachIndexed { index, button ->
            styleChoiceButton(button, index == selectedProfileIndex)
        }
        ratioButtons.forEachIndexed { index, button ->
            styleChoiceButton(button, index == selectedAspectRatioIndex)
        }
        fpsButtons.forEachIndexed { index, button ->
            styleChoiceButton(button, if (index == 0) selectedFps == 24 else selectedFps == 30)
        }
        qualityLabelsRow.forEachIndexed { index, label ->
            label.setTextColor(
                when {
                    index == selectedQualityIndex -> accentBlue()
                    else -> Color.parseColor("#74808C")
                }
            )
            label.setTypeface(null, if (index == selectedQualityIndex) Typeface.BOLD else Typeface.NORMAL)
        }

        val profile = profiles[selectedProfileIndex]
        val ratio = aspectRatios[selectedAspectRatioIndex]
        val effectiveSettings = resolveEffectiveExportSettings(profile, selectedFps, selectedQualityIndex, ratio.w, ratio.h)
        val estimatedSize = estimateOutputSize(durationMs, effectiveSettings.bitrateMbps)
        summaryValue.text = "${profile.label} • ${ratio.label} • ${qualityLabels[selectedQualityIndex]}"
        summaryMeta.text =
            "${effectiveSettings.fps}fps  •  ${effectiveSettings.bitrateMbps} Mbps  •  " +
                "${if (watermarkUnlocked) "No watermark" else "Watermark ON"}  •  ${formatSize(estimatedSize)}"
        exportButton.text = "Start Export"
    }

    private fun refreshWatermarkState(
        watermarkUnlocked: Boolean,
        watermarkStatus: TextView,
        watermarkAction: TextView,
    ) {
        watermarkStatus.text = if (watermarkUnlocked) {
            "Reward unlocked. Current export runs without watermark."
        } else {
            "Logo watermark stays on export until ad unlock."
        }
        watermarkAction.text = if (watermarkUnlocked) {
            "Watermark Removed"
        } else {
            "Watch Ad Remove Watermark"
        }
        watermarkAction.isEnabled = !watermarkUnlocked
        watermarkAction.alpha = if (watermarkUnlocked) 0.7f else 1f
    }

    private fun resolveEffectiveExportSettings(
        profile: ExportProfile,
        requestedFps: Int,
        selectedQualityIndex: Int,
        ratioW: Int = 16,
        ratioH: Int = 9,
    ): EffectiveExportSettings {
        val qualityProfile = DeviceDetector.getQualityProfile()
        // Derive dimensions from profile's long edge + ratio
        val longEdge = maxOf(profile.width, profile.height)
        val (baseW, baseH) = if (ratioW >= ratioH) {
            longEdge to (longEdge * ratioH / ratioW)
        } else {
            (longEdge * ratioW / ratioH) to longEdge
        }
        val (lockedWidth, lockedHeight) = clampExportSize(baseW, baseH)
        val maxBitrateMbps = maxOf(1, (qualityProfile.exportBitrate + 999) / 1000)
        return EffectiveExportSettings(
            width = lockedWidth,
            height = lockedHeight,
            fps = requestedFps.coerceAtMost(qualityProfile.maxExportFps).coerceAtLeast(24),
            bitrateMbps = suggestedBitrateMbps(profile, requestedFps, selectedQualityIndex).coerceAtMost(maxBitrateMbps),
        )
    }

    private fun clampExportSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return 1280 to 720
        }
        val isLandscape = width >= height
        val maxWidth = if (isLandscape) 1280f else 720f
        val maxHeight = if (isLandscape) 720f else 1280f
        val scale = minOf(maxWidth / width.toFloat(), maxHeight / height.toFloat(), 1f)
        return maxOf((width * scale).toInt(), 1) to maxOf((height * scale).toInt(), 1)
    }

    private fun suggestedBitrateMbps(profile: ExportProfile, fps: Int, qualityIndex: Int): Int {
        val q = qualityIndex.coerceIn(0, qualityLabels.lastIndex)
        return when (profile.height) {
            720 -> if (fps >= 30) intArrayOf(2, 3, 4)[q] else intArrayOf(2, 2, 3)[q]
            1080 -> if (fps >= 30) intArrayOf(4, 6, 8)[q] else intArrayOf(3, 5, 6)[q]
            else -> if (fps >= 30) intArrayOf(12, 16, 24)[q] else intArrayOf(8, 12, 18)[q]
        }
    }

    private fun buildRequestedExportFileName(title: String, profileLabel: String, timestamp: String): String {
        val safeTitle = sanitizeFileComponent(title).ifBlank { "Storyline" }
        val safeProfile = sanitizeFileComponent(profileLabel).ifBlank { "HD" }
        return "${safeTitle}_${safeProfile}_$timestamp.mp4"
    }

    private fun sanitizeFileComponent(value: String): String {
        return value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    }

    private fun inspectProjectComplexity(): ProjectExportComplexity {
        var visualClipCount = 0
        var overlayClipCount = 0
        var audioClipCount = 0
        var editedClipCount = 0
        var effectsClipCount = 0
        var chromaClipCount = 0

        val layoutResult = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
        val clips = layoutResult?.data?.optJSONArray("clips")
        if (layoutResult?.success == true && clips != null) {
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
                    "AUDIO" -> audioClipCount += 1
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

        val textOverlayCount = OverlayStore.all().count { it.visible && it.endTimeMs > it.startTimeMs }
        val stickerOverlayCount = StickerClipStore.all().count { it.visible && it.durationMs > 0 }

        return ProjectExportComplexity(
            visualClipCount = visualClipCount,
            overlayClipCount = overlayClipCount,
            audioClipCount = audioClipCount,
            textOverlayCount = textOverlayCount,
            stickerOverlayCount = stickerOverlayCount,
            editedClipCount = editedClipCount,
            effectsClipCount = effectsClipCount,
            chromaClipCount = chromaClipCount,
        )
    }

    private fun recommendExportSettings(complexity: ProjectExportComplexity): ExportRecommendation {
        val isLowEnd = DeviceDetector.isLowEndDevice()
        val isHighEnd = DeviceDetector.isHighEndDevice()
        val layeredProject = complexity.totalOverlayLayers > 0 || complexity.visualClipCount > 1
        val featureHeavy = complexity.editedClipCount > 0 || complexity.effectsClipCount > 0 || complexity.chromaClipCount > 0
        val shouldUseSafeDefault = isLowEnd || layeredProject || featureHeavy

        val profileIndex = 0
        val fps = when {
            shouldUseSafeDefault -> 24
            isHighEnd -> 30
            else -> 30
        }
        val qualityIndex = when {
            shouldUseSafeDefault -> 0
            isHighEnd -> 1
            else -> 1
        }
        val note = when {
            isLowEnd && complexity.isComplex ->
                "Complex layered project on this device: Fast export is recommended."
            complexity.isComplex ->
                "Complex layered project detected: Fast export is recommended so overlays, text, chroma key, and effects render faster."
            isLowEnd ->
                "This device is memory-limited: Fast export is recommended for smoother render."
            else -> null
        }

        return ExportRecommendation(
            profileIndex = profileIndex,
            fps = fps,
            qualityIndex = qualityIndex,
            note = note,
        )
    }

    private fun estimateOutputSize(durationMs: Long, bitrateMbps: Int): Long {
        if (durationMs <= 0L) {
            return 0L
        }
        val bitrateBytesPerSec = bitrateMbps * 1024L * 1024L / 8L
        val payloadBytes = bitrateBytesPerSec * durationMs / 1000L
        val overheadBytes = 4L * 1024L * 1024L
        return payloadBytes + overheadBytes
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) {
            return "small file"
        }
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun describeDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "Quick export"
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        return if (minutes > 0) {
            String.format(Locale.US, "%d:%02d timeline", minutes, seconds)
        } else {
            String.format(Locale.US, "%ds timeline", seconds)
        }
    }

    private fun dragHandle(): View =
        View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#4A5561"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(14)
            }
        }

    private fun titleView(textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

    private fun subtitleView(textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 12f
            setTextColor(Color.parseColor("#8E99A5"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }

    private fun sectionLabel(textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8E99A5"))
            setPadding(0, dp(12), 0, dp(8))
        }

    private fun recommendationBanner(textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 12f
            setTextColor(Color.parseColor("#FFD4A5"))
            background = warningCard()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(14) }
        }

    private fun choiceButton(label: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(4)
                it.marginEnd = dp(4)
            }
            styleChoiceButton(this, false)
        }

    private fun styleChoiceButton(button: TextView, selected: Boolean) {
        button.background = chipBackground(selected)
        button.setTextColor(if (selected) Color.WHITE else Color.parseColor("#D8D8D8"))
        button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun actionButton(label: String, backgroundColor: String, textColor: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textColor))
            background = if (backgroundColor.equals("#FF5A52", ignoreCase = true)) {
                primaryButtonBackground()
            } else {
                secondaryButtonBackground()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(4)
                it.marginEnd = dp(4)
            }
        }

    private fun accentBlue(): Int = Color.parseColor("#6FDBFF")

    private fun accentWarm(): Int = Color.parseColor("#FFB257")

    private fun sheetRootBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
            setColor(Color.parseColor("#0B0F13"))
            setStroke(dp(1), Color.parseColor("#1B222A"))
        }

    private fun surfaceCard(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#131920"))
            setStroke(dp(1), Color.parseColor("#252F38"))
        }

    private fun inputBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#11161C"))
            setStroke(dp(1), Color.parseColor("#27323B"))
        }

    private fun chipBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(15).toFloat()
            if (selected) {
                setColor(Color.parseColor("#152330"))
                setStroke(dp(1), accentBlue())
            } else {
                setColor(Color.parseColor("#12171D"))
                setStroke(dp(1), Color.parseColor("#252F38"))
            }
        }

    private fun primaryButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#12171D"))
            setStroke(dp(1), accentWarm())
        }

    private fun secondaryButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#11161C"))
            setStroke(dp(1), Color.parseColor("#27323B"))
        }

    private fun warningCard(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#17120E"))
            setStroke(dp(1), Color.parseColor("#5A4121"))
        }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
