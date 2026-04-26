package com.video.engine

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import com.video.engine.UiToast as Toast
import com.video.engine.effects.EffectParams
import com.video.engine.effects.ProFilterPresets
import com.video.engine.stickers.StickersPanel
import com.video.engine.timeline.TimelineManager
import com.video.engine.transition.TransitionType
import com.video.engine.NativeBridge

class UiChromeController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val editorStateProvider: () -> EditorState?,
    private val layerControllerProvider: () -> LayerController?,
    private val overlayControllerProvider: () -> OverlayController?,
    private val currentTimeMsProvider: () -> Long,
    private val isPlayingProvider: () -> Boolean,
    private val setIsPlaying: (Boolean) -> Unit,
    private val onNativePlay: () -> Unit,
    private val onNativePause: () -> Unit,
    private val onPauseRendering: () -> Unit,
    private val onShowExportDialog: () -> Unit,
    private val onShowSaveProjectDialog: () -> Unit,
    private val onShowLoadProjectDialog: () -> Unit,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
    private val onOpenVideoImportPicker: () -> Unit,
    private val onOpenOverlayImportPicker: () -> Unit,
    private val onOpenLayerImportPicker: () -> Unit,
    private val onQuickImport: () -> Boolean,
    private val onQuickOverlayImport: () -> Boolean,
    private val onQuickLayerImport: () -> Boolean,
    private val onShowAudioPicker: () -> Unit,
    private val onQuickAudioImport: () -> Boolean,
    private val onShowTextComposer: () -> Unit,
    private val onAddTextPreset: (String) -> Unit,
    private val onOpenSelectedTextStudio: () -> Unit = {},
    private val onSplitAudioAtPlayhead: () -> Boolean,
    private val onHealthAction: (String) -> Unit = {},
    private val onUiButtonTap: (String, String, String) -> Unit = { _, _, _ -> },
    private val onShowProblemReportDialog: (String) -> Unit = {},
    private val clipEffects: MutableMap<Int, EffectParams>,
    private val onRevealSelectedClipPreview: () -> Unit = {},
    private val onTransitionRequested: (Int, Int) -> Unit = { _, _ -> },
    private val onApplyTransitionPreset: (TransitionType, Int) -> Boolean = { _, _ -> false },
    private val onRemoveTransitionPreset: () -> Boolean = { false },
    private val onVoiceoverRequested: () -> Unit = {},
) {
    private data class ChromaPanelState(
        val enabled: Boolean = false,
        val similarity: Float = 0.35f,
        val smoothness: Float = 0.10f,
        val spill: Float = 0.05f,
        val isBlue: Boolean = false,
    )

    companion object {
        private const val TAG = "[UI]"
    }

    private fun pausePlaybackForPanel() {
        if (isPlayingProvider()) {
            setIsPlaying(false)
            onPauseRendering()
        }
    }

    private fun buildLayerItems(): List<LayerItem> {
        val layerItems = mutableListOf<LayerItem>()
        editorStateProvider()?.buildLayerDescriptors().orEmpty().forEach { descriptor ->
            layerControllerProvider()?.buildLayerItem(descriptor)?.let { item ->
                layerItems += item
            }
        }
        return layerItems
    }

    private fun showLayerManager() {
        LayersPanel(activity, buildLayerItems()).show()
    }

    private fun showImportSourceSheet(
        title: String,
        openedAction: String,
        browseLabel: String,
        quickLabel: String? = null,
        quickUnavailableMessage: String,
        onBrowse: () -> Unit,
        onQuick: (() -> Boolean)? = null,
        extraActions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        pausePlaybackForPanel()
        val options = mutableListOf<String>()
        options += browseLabel
        if (quickLabel != null && onQuick != null) {
            options += quickLabel
        }
        options += extraActions.map { it.first }
        onHealthAction(openedAction)
        ModernSheet.show(activity, title) {
            chips("Source", options, -1) { _, option ->
                when {
                    option == browseLabel -> onBrowse()
                    quickLabel != null && option == quickLabel -> {
                        if (onQuick?.invoke() != true) {
                            Toast.makeText(activity, quickUnavailableMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> extraActions.firstOrNull { it.first == option }?.second?.invoke()
                }
            }
        }
    }

    private fun resolveActiveClipId(previewView: VideoPreviewView? = previewViewProvider()): Int? {
        val timelineManager = timelineManagerProvider()
        val selectedClipId = timelineManager?.getSelectedClipId()
        if (selectedClipId != null && selectedClipId > 0) {
            return selectedClipId
        }
        val firstTimelineClipId = timelineManager?.getClips()?.firstOrNull()?.id
        if (firstTimelineClipId != null && firstTimelineClipId > 0) {
            timelineManager.selectClip(firstTimelineClipId)
            return firstTimelineClipId
        }
        val firstNativeClipId = previewView?.let { NativeBridge.getClipIds(it).firstOrNull() }
        if (firstNativeClipId != null && firstNativeClipId > 0) {
            timelineManager?.selectClip(firstNativeClipId)
            return firstNativeClipId
        }
        return null
    }

    private fun applyEffectPreset(params: EffectParams): Boolean {
        val previewView = previewViewProvider() ?: run {
            Toast.makeText(activity, "Preview not ready", Toast.LENGTH_SHORT).show()
            return false
        }
        val clipId = resolveActiveClipId(previewView) ?: run {
            Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
            return false
        }
        onRevealSelectedClipPreview()
        applyColorPreview(previewView, clipId, params)
        return true
    }

    private fun effectPresetByName(name: String): EffectParams {
        return ProFilterPresets.findByName(name)?.params ?: when (name) {
            "Vivid" -> EffectParams(brightness = 0.10f, contrast = 1.24f, saturation = 1.55f)
            "Matte" -> EffectParams(brightness = 0.12f, contrast = 0.78f, saturation = 0.62f)
            "Warm" -> EffectParams(brightness = 0.14f, contrast = 1.12f, saturation = 1.28f)
            "Cool" -> EffectParams(brightness = -0.08f, contrast = 1.10f, saturation = 0.74f)
            "Vintage" -> EffectParams(brightness = 0.10f, contrast = 0.82f, saturation = 0.46f)
            "B&W" -> EffectParams(brightness = -0.02f, contrast = 1.28f, saturation = 0.00f)
            "Cinematic" -> EffectParams(brightness = -0.06f, contrast = 1.34f, saturation = 0.68f)
            "Drama" -> EffectParams(brightness = -0.10f, contrast = 1.46f, saturation = 1.06f)
            "Punch" -> EffectParams(brightness = 0.06f, contrast = 1.34f, saturation = 1.36f)
            "Soft" -> EffectParams(brightness = 0.12f, contrast = 0.84f, saturation = 0.88f)
            "Neutral" -> EffectParams()
            else -> EffectParams()
        }
    }

    private fun showEffectsStudio(): Boolean {
        val previewView = previewViewProvider()
        if (previewView == null) {
            Toast.makeText(activity, "Preview view not found", Toast.LENGTH_SHORT).show()
            return false
        }
        val selectedClipId = resolveActiveClipId(previewView)
        if (selectedClipId == null || selectedClipId <= 0) {
            Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
            return false
        }
        onRevealSelectedClipPreview()
        val currentParams = clipEffects[selectedClipId] ?: EffectParams()
        EffectsPanel(activity, previewView, selectedClipId, currentParams) { ep, _ ->
            clipEffects[selectedClipId] = ep
            persistClipEffectsAsync(selectedClipId, ep)
        }.show()
        return true
    }

    private fun showLutLibrary(): Boolean {
        val previewView = previewViewProvider() ?: run {
            Toast.makeText(activity, "Preview not ready", Toast.LENGTH_SHORT).show()
            return false
        }
        val clipId = resolveActiveClipId(previewView) ?: run {
            Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
            return false
        }
        onRevealSelectedClipPreview()
        val current = clipEffects[clipId] ?: EffectParams()
        LutPanel.show(activity, current) { updated ->
            applyColorPreview(previewView, clipId, updated)
        }
        return true
    }

    private fun openChromaForActiveClip(): Boolean {
        val clipId = resolveActiveClipId() ?: run {
            Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
            return false
        }
        showChromaKeyPanel(clipId)
        return true
    }

    private fun showColorStudio(): Boolean {
        val previewView = previewViewProvider() ?: run {
            Toast.makeText(activity, "Preview not ready", Toast.LENGTH_SHORT).show()
            return false
        }
        val clipId = resolveActiveClipId(previewView) ?: run {
            Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
            return false
        }
        onRevealSelectedClipPreview()
        ModernSheet.show(activity, "Color Grading") {
            val current = clipEffects[clipId] ?: EffectParams()
            slider("Brightness", -1f, 1f, current.brightness, { "${(it * 100).toInt()}%" }) { v ->
                val updated = (clipEffects[clipId] ?: current).copy(brightness = v)
                applyColorPreview(previewView, clipId, updated)
            }
            slider("Contrast", 0f, 2f, current.contrast, { "%.1fx".format(it) }) { v ->
                val updated = (clipEffects[clipId] ?: current).copy(contrast = v)
                applyColorPreview(previewView, clipId, updated)
            }
            slider("Saturation", 0f, 2f, current.saturation, { "%.1fx".format(it) }) { v ->
                val updated = (clipEffects[clipId] ?: current).copy(saturation = v)
                applyColorPreview(previewView, clipId, updated)
            }
            chips("LUT Presets", listOf("Warm", "Cool", "Vintage", "B&W", "Cinematic"), -1, dismissOnSelect = false) { _, lut ->
                applyEffectPreset(effectPresetByName(lut))
            }
        }
        return true
    }

    private fun addQuickSticker(stickerId: Int, label: String): Boolean {
        val clip = overlayControllerProvider()?.createStickerClip(stickerId) ?: return false
        overlayControllerProvider()?.addStickerClip(clip)
        Log.d("[STICKER]", "added quick=$label id=${clip.stickerId} layer=${clip.layerIndex}")
        return true
    }

    private fun showStickerLibrary(): Boolean {
        pausePlaybackForPanel()
        StickersPanel(
            activity,
            onStickerSelected = { sticker ->
                val clip = overlayControllerProvider()?.createStickerClip(sticker.id) ?: return@StickersPanel
                overlayControllerProvider()?.addStickerClip(clip)
                Log.d("[STICKER]", "added id=${clip.stickerId} layer=${clip.layerIndex}")
            },
            onImageSelected = { imagePath ->
                val clip = overlayControllerProvider()?.createImageStickerClip(imagePath) ?: return@StickersPanel
                overlayControllerProvider()?.addStickerClip(clip)
                Log.d("[IMAGE]", "added path=$imagePath")
            },
        ).show()
        return true
    }

    private fun showTextToolSheet() {
        pausePlaybackForPanel()
        onHealthAction("text_tool_sheet_opened")
        ModernSheet.show(activity, "Text") {
            chips("Create", listOf("Composer", "Caption", "Title", "Lower 3rd", "Subtitle"), -1) { _, option ->
                when (option) {
                    "Composer" -> onShowTextComposer()
                    else -> onAddTextPreset(option)
                }
            }
            chips("Quick", listOf("Hook", "CTA", "Quote", "Label", "Basic", "Badge"), -1) { _, option ->
                when (option) {
                    else -> onAddTextPreset(option)
                }
            }
            chips("Studio", listOf("Edit Selected"), -1) { _, _ ->
                onOpenSelectedTextStudio()
            }
        }
    }

    private fun showEffectsToolSheet() {
        onHealthAction("effects_tool_sheet_opened")
        ModernSheet.show(activity, "Effects") {
            chips("Studio", listOf("Studio FX", "LUT Library", "Chroma Key", "Reset FX"), -1) { _, option ->
                when (option) {
                    "Studio FX" -> showEffectsStudio()
                    "LUT Library" -> showLutLibrary()
                    "Chroma Key" -> openChromaForActiveClip()
                    "Reset FX" -> applyEffectPreset(EffectParams())
                }
            }
            chips("Quick Looks", listOf("Beauty Lift", "Bridal Glow", "Cine Matte", "Teal Punch", "Golden Hour", "Noir Mono"), -1, dismissOnSelect = false) { _, option ->
                applyEffectPreset(effectPresetByName(option))
            }
            chips("Finish", listOf("Fair Lift", "Soft Skin", "Seoul Vlog", "Market Pop", "Night Neon", "Retro Print"), -1, dismissOnSelect = false) { _, option ->
                applyEffectPreset(effectPresetByName(option))
            }
        }
    }

    private fun showStickerToolSheet() {
        pausePlaybackForPanel()
        onHealthAction("graphics_tool_sheet_opened")
        ModernSheet.show(activity, "Graphics") {
            chips("Quick", listOf("Spark", "Flame", "Heart", "Film", "Boom", "Star"), -1) { _, option ->
                val success = when (option) {
                    "Spark" -> addQuickSticker(1, option)
                    "Flame" -> addQuickSticker(2, option)
                    "Heart" -> addQuickSticker(3, option)
                    "Film" -> addQuickSticker(4, option)
                    "Boom" -> addQuickSticker(5, option)
                    "Star" -> addQuickSticker(6, option)
                    else -> false
                }
                if (!success) {
                    Toast.makeText(activity, "Graphic add failed", Toast.LENGTH_SHORT).show()
                }
            }
            chips("Source", listOf("Sticker Pack", "Overlay Import", "Quick Overlay", "Manage Layers"), -1) { _, option ->
                when (option) {
                    "Sticker Pack" -> showStickerLibrary()
                    "Overlay Import" -> onOpenOverlayImportPicker()
                    "Quick Overlay" -> {
                        if (!onQuickOverlayImport()) {
                            Toast.makeText(activity, "No quick overlay media found", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Manage Layers" -> showLayerManager()
                }
            }
        }
    }

    private fun showTransitionToolSheet() {
        onHealthAction("transition_tool_sheet_opened")
        ModernSheet.show(activity, "Transition") {
            chips("Quick", listOf("Cross 250", "Fade 250", "Cross 500", "Fade 500", "Wipe 450", "Slide 450"), -1) { _, option ->
                val success = when (option) {
                    "Cross 250" -> onApplyTransitionPreset(TransitionType.CROSS, 250)
                    "Fade 250" -> onApplyTransitionPreset(TransitionType.FADE, 250)
                    "Cross 500" -> onApplyTransitionPreset(TransitionType.CROSS, 500)
                    "Fade 500" -> onApplyTransitionPreset(TransitionType.FADE, 500)
                    "Wipe 450" -> onApplyTransitionPreset(TransitionType.WIPE, 450)
                    "Slide 450" -> onApplyTransitionPreset(TransitionType.SLIDE, 450)
                    else -> false
                }
                if (!success) {
                    Toast.makeText(activity, "Need clips around the cut for transition", Toast.LENGTH_SHORT).show()
                }
            }
            chips("More", listOf("Cross 700", "Fade 700", "Wipe 700", "Slide 700", "Studio Panel", "Remove"), -1) { _, option ->
                val success = when (option) {
                    "Cross 700" -> onApplyTransitionPreset(TransitionType.CROSS, 700)
                    "Fade 700" -> onApplyTransitionPreset(TransitionType.FADE, 700)
                    "Wipe 700" -> onApplyTransitionPreset(TransitionType.WIPE, 700)
                    "Slide 700" -> onApplyTransitionPreset(TransitionType.SLIDE, 700)
                    "Studio Panel" -> {
                        val manager = timelineManagerProvider()
                        val clips = manager?.getClips().orEmpty()
                        if (clips.size < 2) {
                            false
                        } else {
                            val selected = manager?.getSelectedClipId()
                            val idx = clips.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
                            val outgoing = clips[idx].id
                            val incoming = clips.getOrNull(idx + 1)?.id ?: clips[maxOf(0, idx - 1)].id
                            onTransitionRequested(outgoing, incoming)
                            true
                        }
                    }
                    "Remove" -> onRemoveTransitionPreset()
                    else -> false
                }
                if (!success) {
                    Toast.makeText(activity, "No transition target available", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showVoiceToolSheet() {
        pausePlaybackForPanel()
        onHealthAction("voiceover_tool_sheet_opened")
        ModernSheet.show(activity, "Voiceover") {
            chips("Capture", listOf("Record Voice", "Punch-In"), -1) { _, _ ->
                onVoiceoverRequested()
            }
            chips("Source", listOf("Import Audio", "Quick Sample", "Split Audio"), -1) { _, option ->
                when (option) {
                    "Import Audio" -> onShowAudioPicker()
                    "Quick Sample" -> {
                        if (!onQuickAudioImport()) {
                            Toast.makeText(activity, "No quick audio found", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Split Audio" -> {
                        if (!onSplitAudioAtPlayhead()) {
                            Toast.makeText(activity, "Select an audio clip first", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun showColorToolSheet() {
        onHealthAction("color_tool_sheet_opened")
        onRevealSelectedClipPreview()
        ModernSheet.show(activity, "Color") {
            chips("Studio", listOf("Grade Controls", "LUT Library", "Chroma Key", "Reset Color"), -1) { _, option ->
                when (option) {
                    "Grade Controls" -> showColorStudio()
                    "LUT Library" -> showLutLibrary()
                    "Chroma Key" -> openChromaForActiveClip()
                    "Reset Color" -> applyEffectPreset(EffectParams())
                }
            }
            chips("Quick Looks", listOf("Fair Lift", "Beauty Lift", "Cine Matte", "Teal Punch", "Golden Hour", "Noir Mono"), -1, dismissOnSelect = false) { _, option ->
                applyEffectPreset(effectPresetByName(option))
            }
            chips("Finish", listOf("Bridal Glow", "Soft Skin", "Seoul Vlog", "Night Neon", "Retro Print", "Neutral"), -1, dismissOnSelect = false) { _, option ->
                applyEffectPreset(effectPresetByName(option))
            }
        }
    }

    fun showEffectsSheet() {
        showEffectsToolSheet()
    }

    fun showSelectedClipColorStudio(): Boolean {
        onHealthAction("selected_clip_color_studio_opened")
        return showColorStudio()
    }

    fun showGraphicsSheet() {
        showStickerToolSheet()
    }

    fun showTransitionSheet() {
        showTransitionToolSheet()
    }

    fun showLayerImportSheet() {
        showImportSourceSheet(
            title = "Layers",
            openedAction = "layers_source_sheet_opened",
            browseLabel = "Browse Layer",
            quickLabel = "Quick Sample",
            quickUnavailableMessage = "No quick layer media found",
            onBrowse = onOpenLayerImportPicker,
            onQuick = onQuickLayerImport,
            extraActions = listOf("Manage Layers" to { showLayerManager() }),
        )
    }

    fun setupPlayPauseButton() {
        val playPauseButton = activity.findViewById<ImageButton>(R.id.previewPlayPauseButton)
        fun syncPlayPauseIcon() {
            playPauseButton?.setImageResource(
                if (isPlayingProvider()) R.drawable.ic_pause_toolbar
                else R.drawable.ic_play_toolbar,
            )
        }
        syncPlayPauseIcon()
        playPauseButton?.setOnClickListener {
            onUiButtonTap("play_pause", "preview_controls", "tap")
            if (isPlayingProvider()) {
                onNativePause()
                Log.d(TAG, "Pause pressed")
            } else {
                onNativePlay()
                Log.d(TAG, "Play pressed")
            }
            syncPlayPauseIcon()
        }
        playPauseButton?.setOnLongClickListener {
            onUiButtonTap("play_pause_report", "preview_controls", "long_press")
            onShowProblemReportDialog("preview_play_button")
            true
        }
    }

    fun setupExportButton() {
        activity.findViewById<View>(R.id.exportButton)?.setOnClickListener {
            onUiButtonTap("export", "top_bar", "tap")
            onShowExportDialog()
        }
        activity.findViewById<LinearLayout?>(R.id.exportButtonContainer)?.setOnClickListener {
            onUiButtonTap("export_container", "top_bar", "tap")
            onShowExportDialog()
        }
        activity.findViewById<View>(R.id.exportButton)?.setOnLongClickListener {
            onUiButtonTap("export_report", "top_bar", "long_press")
            onShowProblemReportDialog("export_button")
            true
        }
        activity.findViewById<LinearLayout?>(R.id.exportButtonContainer)?.setOnLongClickListener {
            onUiButtonTap("export_container_report", "top_bar", "long_press")
            onShowProblemReportDialog("export_button")
            true
        }
    }

    fun setupToolbarButtons() {
        bindOptionalImageButton("saveProjectButton", {
            onUiButtonTap("save_project", "top_bar", "tap")
            onShowSaveProjectDialog()
        }, "Save project button not found")
        bindOptionalImageButton("loadProjectButton", {
            onUiButtonTap("toolbar_back", "top_bar", "tap")
            onShowLoadProjectDialog()
        }, "Load project button not found")
        bindOptionalImageButtonLongClick("saveProjectButton", {
            onUiButtonTap("save_project_report", "top_bar", "long_press")
            onShowProblemReportDialog("save_project_button")
        }, "Save project button not found")

        try {
            activity.findViewById<ImageView>(R.id.undoButton)?.setOnClickListener {
                onUiButtonTap("undo", "preview_controls", "tap")
                onUndo()
            }
            activity.findViewById<ImageView>(R.id.redoButton)?.setOnClickListener {
                onUiButtonTap("redo", "preview_controls", "tap")
                onRedo()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Undo/redo buttons not found: ${e.message}")
        }

        activity.findViewById<LinearLayout>(R.id.cutButton).setOnClickListener {
            onUiButtonTap("media", "main_toolbar", "tap")
            Log.d(TAG, "Video import button clicked")
            showImportSourceSheet(
                title = "Media",
                openedAction = "media_source_sheet_opened",
                browseLabel = "Browse Device",
                quickLabel = "Quick Sample",
                quickUnavailableMessage = "No quick media found",
                onBrowse = onOpenVideoImportPicker,
                onQuick = onQuickImport,
            )
        }
        activity.findViewById<LinearLayout>(R.id.cutButton).setOnLongClickListener {
            onUiButtonTap("media_quick", "main_toolbar", "long_press")
            Log.d(TAG, "Video import button long-pressed")
            if (!onQuickImport()) {
                Toast.makeText(activity, "No quick media found", Toast.LENGTH_SHORT).show()
            }
            true
        }

        activity.findViewById<LinearLayout>(R.id.overlayImportButton).setOnClickListener {
            onUiButtonTap("overlay", "main_toolbar", "tap")
            Log.d(TAG, "Overlay import button clicked")
            showImportSourceSheet(
                title = "Overlay",
                openedAction = "overlay_source_sheet_opened",
                browseLabel = "Browse Device",
                quickLabel = "Quick Sample",
                quickUnavailableMessage = "No quick overlay media found",
                onBrowse = onOpenOverlayImportPicker,
                onQuick = onQuickOverlayImport,
            )
        }
        activity.findViewById<LinearLayout>(R.id.overlayImportButton).setOnLongClickListener {
            onUiButtonTap("overlay_quick", "main_toolbar", "long_press")
            Log.d(TAG, "Overlay import button long-pressed")
            if (!onQuickOverlayImport()) {
                Toast.makeText(activity, "No quick overlay media found", Toast.LENGTH_SHORT).show()
            }
            true
        }

        activity.findViewById<LinearLayout>(R.id.layersButton).setOnClickListener {
            onUiButtonTap("layers", "main_toolbar", "tap")
            Log.d(TAG, "Layer import button clicked")
            showImportSourceSheet(
                title = "Layers",
                openedAction = "layers_source_sheet_opened",
                browseLabel = "Browse Layer",
                quickLabel = "Quick Sample",
                quickUnavailableMessage = "No quick layer media found",
                onBrowse = onOpenLayerImportPicker,
                onQuick = onQuickLayerImport,
                extraActions = listOf("Manage Layers" to { showLayerManager() }),
            )
        }
        activity.findViewById<LinearLayout>(R.id.layersButton).setOnLongClickListener {
            onUiButtonTap("layers_manage", "main_toolbar", "long_press")
            showLayerManager()
            true
        }

        activity.findViewById<LinearLayout>(R.id.audioButton).setOnClickListener {
            onUiButtonTap("audio", "main_toolbar", "tap")
            Log.d(TAG, "Audio button clicked")
            showImportSourceSheet(
                title = "Audio",
                openedAction = "audio_source_sheet_opened",
                browseLabel = "Browse Device",
                quickLabel = "Quick Sample",
                quickUnavailableMessage = "No quick audio found",
                onBrowse = onShowAudioPicker,
                onQuick = onQuickAudioImport,
            )
        }
        activity.findViewById<LinearLayout>(R.id.audioButton).setOnLongClickListener {
            onUiButtonTap("audio_quick", "main_toolbar", "long_press")
            Log.d(TAG, "Audio button long-pressed")
            if (!onQuickAudioImport()) {
                Toast.makeText(activity, "No quick audio found", Toast.LENGTH_SHORT).show()
            }
            true
        }

        activity.findViewById<LinearLayout>(R.id.textButton).setOnClickListener {
            onUiButtonTap("text", "main_toolbar", "tap")
            showTextToolSheet()
        }
        activity.findViewById<LinearLayout>(R.id.textButton).setOnLongClickListener {
            onUiButtonTap("text_composer", "main_toolbar", "long_press")
            onShowTextComposer()
            true
        }

        activity.findViewById<LinearLayout>(R.id.effectsButton).setOnClickListener {
            onUiButtonTap("effects", "main_toolbar", "tap")
            showEffectsToolSheet()
        }
        activity.findViewById<LinearLayout>(R.id.effectsButton).setOnLongClickListener {
            onUiButtonTap("effects_studio", "main_toolbar", "long_press")
            showEffectsStudio()
            true
        }

        bindOptionalLinearButton("stickersButton", "Stickers button not found in layout") {
            onUiButtonTap("graphics", "main_toolbar", "tap")
            showStickerToolSheet()
        }
        activity.findViewById<LinearLayout>(R.id.stickersButton).setOnLongClickListener {
            onUiButtonTap("graphics_pack", "main_toolbar", "long_press")
            showStickerLibrary()
            true
        }

        bindOptionalLinearButton("transitionButton", "Transition button not found") {
            onUiButtonTap("transition", "main_toolbar", "tap")
            showTransitionToolSheet()
        }
        activity.findViewById<LinearLayout>(R.id.transitionButton).setOnLongClickListener {
            onUiButtonTap("transition_studio", "main_toolbar", "long_press")
            val manager = timelineManagerProvider()
            val clips = manager?.getClips().orEmpty()
            if (clips.size < 2) {
                Toast.makeText(activity, "Need at least 2 clips for transition", Toast.LENGTH_SHORT).show()
            } else {
                val selected = manager?.getSelectedClipId()
                val idx = clips.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
                val outgoing = clips[idx].id
                val incoming = clips.getOrNull(idx + 1)?.id ?: clips[maxOf(0, idx - 1)].id
                onTransitionRequested(outgoing, incoming)
            }
            true
        }

        bindOptionalLinearButton("voiceoverButton", "Voiceover button not found") {
            onUiButtonTap("voiceover", "main_toolbar", "tap")
            showVoiceToolSheet()
        }
        activity.findViewById<LinearLayout>(R.id.voiceoverButton).setOnLongClickListener {
            onUiButtonTap("voiceover_record", "main_toolbar", "long_press")
            pausePlaybackForPanel()
            onVoiceoverRequested()
            true
        }

        bindOptionalLinearButton("colorGradingButton", "Color Grading button not found") {
            onUiButtonTap("color", "main_toolbar", "tap")
            showColorToolSheet()
        }
        activity.findViewById<LinearLayout>(R.id.colorGradingButton).setOnLongClickListener {
            onUiButtonTap("color_studio", "main_toolbar", "long_press")
            showColorStudio()
            true
        }
    }

    private fun applyColorPreview(previewView: VideoPreviewView, clipId: Int, params: EffectParams) {
        onRevealSelectedClipPreview()
        clipEffects[clipId] = params
        NativeBridge.setClipEffects(previewView, clipId, params.brightness, params.contrast, params.saturation)
        runCatching { NativeBridge.seekToTime(previewView, currentTimeMsProvider().coerceAtLeast(0L)) }
        persistClipEffectsAsync(clipId, params)
    }

    private fun persistClipEffectsAsync(clipId: Int, params: EffectParams) {
        Thread {
            runCatching {
                NativeBridge.executeCommand(
                    action = "SET_CLIP_EFFECTS",
                    params = mapOf(
                        "clipId" to clipId,
                        "brightness" to params.brightness,
                        "contrast" to params.contrast,
                        "saturation" to params.saturation,
                    ),
                )
            }
        }.start()
    }

    private fun bindOptionalImageButton(name: String, onClick: () -> Unit, missingMessage: String) {
        try {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) {
                activity.findViewById<ImageView>(id)?.setOnClickListener { onClick() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$missingMessage: ${e.message}")
        }
    }

    private fun bindOptionalImageButtonLongClick(name: String, onClick: () -> Unit, missingMessage: String) {
        try {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) {
                activity.findViewById<ImageView>(id)?.setOnLongClickListener {
                    onClick()
                    true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$missingMessage: ${e.message}")
        }
    }

    private fun bindOptionalLinearButton(name: String, missingMessage: String, onClick: () -> Unit) {
        try {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            activity.findViewById<LinearLayout?>(id)?.setOnClickListener { onClick() }
        } catch (e: Exception) {
            Log.d(TAG, "$missingMessage: ${e.message}")
        }
    }

    fun showChromaKeyPanel(clipId: Int) {
        val previewView = previewViewProvider() ?: return
        val initial = loadChromaPanelState(clipId)
        var enabled = initial.enabled
        var similarity = initial.similarity
        var smoothness = initial.smoothness
        var spill = initial.spill
        var isBlue = initial.isBlue

        fun apply() {
            val playheadMs = currentTimeMsProvider().coerceAtLeast(0L)
            NativeBridge.executeCommandAsync(
                "SET_CHROMA_KEY",
                mapOf(
                    "clipId" to clipId,
                    "enabled" to enabled,
                    "color" to if (isBlue) 1 else 0,
                    "similarity" to similarity,
                    "smoothness" to smoothness,
                    "spill" to spill,
                ),
            )
            activity.runOnUiThread {
                runCatching { NativeBridge.seekToTime(previewView, playheadMs) }
            }
        }

        ModernSheet.show(activity, "Chroma Key") {
            toggle("Enable Green Screen", enabled) { enabled = it; apply() }
            divider()
            chips("Key Color", listOf("Green", "Blue"), if (isBlue) 1 else 0) { _, option ->
                isBlue = option.equals("Blue", ignoreCase = true)
                apply()
            }
            slider("Similarity", 0f, 1f, similarity, { "%.0f%%".format(it * 100) }) { similarity = it; apply() }
            slider("Smoothness", 0f, 1f, smoothness, { "%.0f%%".format(it * 100) }) { smoothness = it; apply() }
            slider("Spill", 0f, 1f, spill, { "%.0f%%".format(it * 100) }) { spill = it; apply() }
        }
    }

    private fun loadChromaPanelState(clipId: Int): ChromaPanelState {
        val result = runCatching { NativeBridge.executeCommand("GET_TIMELINE_LAYOUT") }.getOrNull()
        val clips = result?.data?.optJSONArray("clips") ?: return ChromaPanelState()
        for (index in 0 until clips.length()) {
            val clip = clips.optJSONObject(index) ?: continue
            if (clip.optInt("clipId", -1) != clipId) continue
            return ChromaPanelState(
                enabled = clip.optBoolean("chromaEnabled", false),
                similarity = clip.optDouble("chromaSimilarity", 0.35).toFloat().coerceIn(0f, 1f),
                smoothness = clip.optDouble("chromaSmoothness", 0.10).toFloat().coerceIn(0f, 1f),
                spill = clip.optDouble("chromaSpill", 0.05).toFloat().coerceIn(0f, 1f),
                isBlue = clip.optBoolean("chromaIsBlue", false),
            )
        }
        return ChromaPanelState()
    }
}
