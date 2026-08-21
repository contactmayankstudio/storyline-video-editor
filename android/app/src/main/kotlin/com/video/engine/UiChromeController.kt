package com.video.engine

import android.app.Activity
import android.graphics.Color
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
import com.video.engine.utils.addBouncyTouchEffect
import java.util.concurrent.atomic.AtomicInteger

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
    private val onShowAudioPicker: () -> Unit,
    private val onShowTextComposer: () -> Unit,
    private val onAddTextPreset: (String) -> Unit,
    private val onOpenSelectedTextStudio: () -> Unit = {},
    private val onSplitAudioAtPlayhead: () -> Boolean,
    private val onHealthAction: (String) -> Unit = {},
    private val onUiButtonTap: (String, String, String) -> Unit = { _, _, _ -> },
    private val onShowProblemReportDialog: (String) -> Unit = {},
    private val clipEffects: MutableMap<Int, EffectParams>,
    private val onRevealSelectedClipPreview: () -> Unit = {},
    private val onResolveActiveVisualClipId: (() -> Int?) = { null },
    private val onResolveVisualClipPreviewTimeMs: ((Int) -> Long?) = { null },
    private val onResolveTransitionTargetPair: (() -> Pair<Int, Int>?) = { null },
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

    private var previewSeekScheduled = false
    private var pendingPreviewSeekMs = 0L
    private val chromaApplyGeneration = AtomicInteger(0)
    private val previewSeekRunnable = Runnable {
        previewSeekScheduled = false
        val previewView = previewViewProvider() ?: return@Runnable
        NativeBridge.seekToTime(previewView, pendingPreviewSeekMs.coerceAtLeast(0L))
    }

    private fun pausePlaybackForPanel() {
        if (isPlayingProvider()) {
            setIsPlaying(false)
            onPauseRendering()
        }
    }

    private fun buildLayerItems(): List<LayerItem> {
        val layerItems = mutableListOf<LayerItem>()
        editorStateProvider()
            ?.buildLayerDescriptors()
            .orEmpty()
            .sortedWith(compareByDescending<LayerDescriptor> { it.layerIndex }.thenBy { it.key })
            .forEach { descriptor ->
            layerControllerProvider()?.buildLayerItem(descriptor)?.let { item ->
                layerItems += item
            }
        }
        return layerItems
    }

    private fun showLayerManager() {
        pausePlaybackForPanel()
        LayersPanel(activity) { buildLayerItems() }.show()
    }

    private fun showImportSourceSheet(
        title: String,
        openedAction: String,
        browseLabel: String,
        onBrowse: () -> Unit,
        extraActions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        pausePlaybackForPanel()
        val options = mutableListOf<String>()
        options += browseLabel
        options += extraActions.map { it.first }
        onHealthAction(openedAction)
        ModernSheet.show(activity, title) {
            section("Source")
            actionTile(
                title = browseLabel,
                subtitle = sourceActionSubtitle(title, browseLabel),
                badge = sourceActionBadge(browseLabel),
                dismissOnClick = true,
            ) {
                onBrowse()
            }
            extraActions.forEach { (label, action) ->
                actionTile(
                    title = label,
                    subtitle = sourceActionSubtitle(title, label),
                    badge = sourceActionBadge(label),
                    dismissOnClick = true,
                ) {
                    action()
                }
            }
            if (options.size > 1) {
                infoRow("Actions", options.size.toString())
            }
        }
    }

    private fun sourceActionSubtitle(sheetTitle: String, action: String): String =
        when (action) {
            "Browse Device" -> when (sheetTitle) {
                "Media" -> "Video and photo clips"
                "Overlay" -> "Image or video overlay"
                "Audio" -> "Music and sound files"
                else -> "File picker"
            }
            "Import Layer" -> "Add visual overlay layer"
            "Open Saved Project" -> "Continue an existing edit"
            "Sticker Pack" -> "Graphics library"
            "Manage Layers" -> "Layer order and visibility"
            "Split Audio" -> "Cut selected audio at playhead"
            "Record Voice" -> "Voiceover capture"
            else -> "Editor action"
        }

    private fun sourceActionBadge(action: String): String =
        when (action) {
            "Browse Device", "Import Layer" -> "FILE"
            "Open Saved Project" -> "PROJECT"
            "Sticker Pack" -> "PACK"
            "Manage Layers" -> "LAYERS"
            "Split Audio" -> "CUT"
            "Record Voice" -> "REC"
            else -> "GO"
        }

    private fun resolveActiveClipId(previewView: VideoPreviewView? = previewViewProvider()): Int? {
        onResolveActiveVisualClipId()
            ?.takeIf { it > 0 }
            ?.let { clipId ->
                timelineManagerProvider()?.selectClip(clipId)
                return clipId
            }
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

    private fun applyEffectPresetByName(name: String): Boolean {
        val previewView = previewViewProvider() ?: run {
            Toast.makeText(activity, "Preview not ready", Toast.LENGTH_SHORT).show()
            return false
        }
        val clipId = resolveActiveClipId(previewView) ?: run {
            Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
            return false
        }
        onRevealSelectedClipPreview()
        val params = NativeBridge.applyProfessionalEffectPreset(clipId, name) ?: effectPresetByName(name)
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
        EffectsPanel(
            activity = activity,
            previewView = previewView,
            clipId = selectedClipId,
            initial = currentParams,
            resolvePreviewTimeMs = { resolvePreviewSeekTimeMs(selectedClipId) },
        ) { ep, _ ->
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
                applyEffectPresetByName(lut)
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
            categories(listOf("Presets", "Styles"), selected = 0) { _, _ -> }

            section("Text Presets")
            val textCards = listOf(
                ModernSheet.VisualCard("Hook", "Hook Title", emoji = "⚡"),
                ModernSheet.VisualCard("CTA", "CTA Button", emoji = "🎯"),
                ModernSheet.VisualCard("Quote", "Quote Card", emoji = "💬"),
                ModernSheet.VisualCard("Caption", "Caption", emoji = "📝"),
                ModernSheet.VisualCard("Title", "Main Title", emoji = "🎬"),
                ModernSheet.VisualCard("Lower 3rd", "Lower Third", emoji = "🏷️"),
            )
            visualCardsGrid(
                cards = textCards,
                selected = -1,
                columns = 3,
                dismissOnSelect = true,
            ) { _, card ->
                onAddTextPreset(card.id)
            }

            divider()
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Text Composer",
                        isPrimary = true,
                        onClick = { onShowTextComposer() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Edit Selected",
                        isPrimary = false,
                        onClick = { onOpenSelectedTextStudio() },
                    ),
                ),
            )
        }
    }

    private fun showEffectsToolSheet() {
        onHealthAction("effects_tool_sheet_opened")
        ModernSheet.show(activity, "Effects") {
            categories(listOf("Popular", "Finish"), selected = 0) { _, _ -> }

            section("Visual Effects")
            val effectCards = listOf(
                ModernSheet.VisualCard("Beauty Lift", "Beauty Lift", swatchGradient = Pair(Color.parseColor("#FF758C"), Color.parseColor("#FF7EB3"))),
                ModernSheet.VisualCard("Bridal Glow", "Bridal Glow", swatchGradient = Pair(Color.parseColor("#F7971E"), Color.parseColor("#FFD200"))),
                ModernSheet.VisualCard("Cine Matte", "Cine Matte", swatchGradient = Pair(Color.parseColor("#2C3E50"), Color.parseColor("#3498DB"))),
                ModernSheet.VisualCard("Teal Punch", "Teal Punch", swatchGradient = Pair(Color.parseColor("#00B4DB"), Color.parseColor("#0083B0"))),
                ModernSheet.VisualCard("Golden Hour", "Golden Hour", swatchGradient = Pair(Color.parseColor("#F7971E"), Color.parseColor("#FFD200"))),
                ModernSheet.VisualCard("Noir Mono", "Noir Mono", swatchGradient = Pair(Color.parseColor("#232526"), Color.parseColor("#414345"))),
            )
            visualCardsGrid(
                cards = effectCards,
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, card ->
                applyEffectPresetByName(card.id)
            }

            divider()
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Studio FX",
                        isPrimary = true,
                        onClick = { showEffectsStudio() },
                    ),
                    ModernSheet.CompactAction(
                        title = "LUT Library",
                        isPrimary = false,
                        onClick = { showLutLibrary() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Reset FX",
                        isDestructive = true,
                        onClick = { applyEffectPreset(EffectParams()) },
                    ),
                ),
            )
        }
    }

    private fun showStickerToolSheet() {
        pausePlaybackForPanel()
        onHealthAction("graphics_tool_sheet_opened")
        ModernSheet.show(activity, "Graphics") {
            categories(listOf("Quick", "Media"), selected = 0) { _, _ -> }

            section("Quick Graphics")
            val graphicCards = listOf(
                ModernSheet.VisualCard("Spark", "Spark", emoji = "✨"),
                ModernSheet.VisualCard("Flame", "Flame", emoji = "🔥"),
                ModernSheet.VisualCard("Heart", "Heart", emoji = "❤️"),
                ModernSheet.VisualCard("Film", "Film", emoji = "🎞️"),
                ModernSheet.VisualCard("Boom", "Boom", emoji = "💥"),
                ModernSheet.VisualCard("Star", "Star", emoji = "⭐"),
            )
            visualCardsGrid(
                cards = graphicCards,
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { index, card ->
                val success = addQuickSticker(index + 1, card.id)
                if (!success) {
                    Toast.makeText(activity, "Graphic add failed", Toast.LENGTH_SHORT).show()
                }
            }

            divider()
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Sticker Pack",
                        isPrimary = true,
                        onClick = { showStickerLibrary() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Overlay Import",
                        onClick = { onOpenOverlayImportPicker() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Manage Layers",
                        onClick = { showLayerManager() },
                    ),
                ),
            )
        }
    }

    private fun showTransitionToolSheet() {
        onHealthAction("transition_tool_sheet_opened")
        ModernSheet.show(activity, "Transition") {
            categories(listOf("Popular", "Fast", "Pro"), selected = 0) { _, _ -> }

            section("Transition Presets")
            val transitionCards = listOf(
                ModernSheet.VisualCard("Soft Cross", "Soft Cross", swatchGradient = Pair(Color.parseColor("#1E3A8A"), Color.parseColor("#3B82F6"))),
                ModernSheet.VisualCard("Quick Fade", "Quick Fade", swatchGradient = Pair(Color.parseColor("#111827"), Color.parseColor("#F97316"))),
                ModernSheet.VisualCard("Smooth Wipe", "Smooth Wipe", swatchGradient = Pair(Color.parseColor("#065F46"), Color.parseColor("#10B981"))),
                ModernSheet.VisualCard("Push Slide", "Push Slide", swatchGradient = Pair(Color.parseColor("#4C1D95"), Color.parseColor("#8B5CF6"))),
                ModernSheet.VisualCard("Long Dissolve", "Long Dissolve", swatchGradient = Pair(Color.parseColor("#1E3A8A"), Color.parseColor("#60A5FA"))),
                ModernSheet.VisualCard("Clean Cut", "Clean Cut", swatchGradient = Pair(Color.parseColor("#1C222C"), Color.parseColor("#252D3A"))),
            )
            visualCardsGrid(
                cards = transitionCards,
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, card ->
                val success = when (card.id) {
                    "Soft Cross" -> onApplyTransitionPreset(TransitionType.CROSS, 450)
                    "Quick Fade" -> onApplyTransitionPreset(TransitionType.FADE, 220)
                    "Smooth Wipe" -> onApplyTransitionPreset(TransitionType.WIPE, 500)
                    "Push Slide" -> onApplyTransitionPreset(TransitionType.SLIDE, 420)
                    "Long Dissolve" -> onApplyTransitionPreset(TransitionType.CROSS, 900)
                    "Clean Cut" -> onRemoveTransitionPreset()
                    else -> false
                }
                if (!success) {
                    Toast.makeText(activity, "Need clips around the cut for transition", Toast.LENGTH_SHORT).show()
                }
            }

            divider()
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Studio Panel",
                        isPrimary = true,
                        onClick = {
                            val transitionPair = onResolveTransitionTargetPair()
                            if (transitionPair == null) {
                                Toast.makeText(activity, "No transition target available", Toast.LENGTH_SHORT).show()
                            } else {
                                val (outgoing, incoming) = transitionPair
                                onTransitionRequested(outgoing, incoming)
                            }
                        },
                    ),
                    ModernSheet.CompactAction(
                        title = "Remove Transition",
                        isDestructive = true,
                        onClick = { onRemoveTransitionPreset() },
                    ),
                ),
            )
        }
    }

    private fun showVoiceToolSheet() {
        pausePlaybackForPanel()
        onHealthAction("voiceover_tool_sheet_opened")
        ModernSheet.show(activity, "Voiceover") {
            categories(listOf("Record", "Source"), selected = 0) { _, _ -> }

            section("Voice Recording")
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Record Voice",
                        isPrimary = true,
                        onClick = { onVoiceoverRequested() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Punch-In",
                        onClick = { onVoiceoverRequested() },
                    ),
                ),
            )

            divider()
            section("Audio Sources")
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Import Audio",
                        onClick = { onShowAudioPicker() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Split Audio",
                        onClick = {
                            val success = onSplitAudioAtPlayhead()
                            if (!success) {
                                Toast.makeText(activity, "Select an audio clip first", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                ),
            )
        }
    }

    private fun showColorToolSheet() {
        onHealthAction("color_tool_sheet_opened")
        onRevealSelectedClipPreview()
        ModernSheet.show(activity, "Color") {
            categories(listOf("Quick Looks", "Finish"), selected = 0) { _, _ -> }

            section("Color Presets")
            val colorCards = listOf(
                ModernSheet.VisualCard("Fair Lift", "Fair Lift", swatchGradient = Pair(Color.parseColor("#FF758C"), Color.parseColor("#FF7EB3"))),
                ModernSheet.VisualCard("Beauty Lift", "Beauty Lift", swatchGradient = Pair(Color.parseColor("#FF7EB3"), Color.parseColor("#FF758C"))),
                ModernSheet.VisualCard("Cine Matte", "Cine Matte", swatchGradient = Pair(Color.parseColor("#2C3E50"), Color.parseColor("#3498DB"))),
                ModernSheet.VisualCard("Teal Punch", "Teal Punch", swatchGradient = Pair(Color.parseColor("#00B4DB"), Color.parseColor("#0083B0"))),
                ModernSheet.VisualCard("Golden Hour", "Golden Hour", swatchGradient = Pair(Color.parseColor("#F7971E"), Color.parseColor("#FFD200"))),
                ModernSheet.VisualCard("Noir Mono", "Noir Mono", swatchGradient = Pair(Color.parseColor("#232526"), Color.parseColor("#414345"))),
            )
            visualCardsGrid(
                cards = colorCards,
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, card ->
                applyEffectPresetByName(card.id)
            }

            divider()
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Grade Controls",
                        isPrimary = true,
                        onClick = { showColorStudio() },
                    ),
                    ModernSheet.CompactAction(
                        title = "LUT Library",
                        onClick = { showLutLibrary() },
                    ),
                    ModernSheet.CompactAction(
                        title = "Reset Color",
                        isDestructive = true,
                        onClick = { applyEffectPreset(EffectParams()) },
                    ),
                ),
            )
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
            browseLabel = "Import Layer",
            onBrowse = onOpenLayerImportPicker,
            extraActions = listOf(
                "Manage Layers" to { showLayerManager() },
            ),
        )
    }

    fun setupPlayPauseButton() {
        val playPauseButton = activity.findViewById<ImageButton>(R.id.previewPlayPauseButton)
        playPauseButton?.addBouncyTouchEffect()
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
        val exportButton = activity.findViewById<View>(R.id.exportButton)
        exportButton?.addBouncyTouchEffect()
        exportButton?.setOnClickListener {
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

        val cutButton = activity.findViewById<LinearLayout>(R.id.cutButton)
        cutButton.addBouncyTouchEffect()
        cutButton.setOnClickListener {
            onUiButtonTap("media", "main_toolbar", "tap")
            Log.d(TAG, "Video import button clicked")
            onOpenVideoImportPicker()
        }
        activity.findViewById<LinearLayout>(R.id.cutButton).setOnLongClickListener(null)

        val overlayImportButton = activity.findViewById<LinearLayout>(R.id.overlayImportButton)
        overlayImportButton.addBouncyTouchEffect()
        overlayImportButton.setOnClickListener {
            onUiButtonTap("overlay", "main_toolbar", "tap")
            Log.d(TAG, "Overlay import button clicked")
            onOpenOverlayImportPicker()
        }
        activity.findViewById<LinearLayout>(R.id.overlayImportButton).setOnLongClickListener(null)

        val layersButton = activity.findViewById<LinearLayout>(R.id.layersButton)
        layersButton.addBouncyTouchEffect()
        layersButton.setOnClickListener {
            onUiButtonTap("layers", "main_toolbar", "tap")
            Log.d(TAG, "Layer import button clicked")
            onOpenLayerImportPicker()
        }
        activity.findViewById<LinearLayout>(R.id.layersButton).setOnLongClickListener {
            onUiButtonTap("layers_manage", "main_toolbar", "long_press")
            showLayerManager()
            true
        }

        activity.findViewById<LinearLayout>(R.id.audioButton).setOnClickListener {
            onUiButtonTap("audio", "main_toolbar", "tap")
            Log.d(TAG, "Audio button clicked")
            onShowAudioPicker()
        }
        activity.findViewById<LinearLayout>(R.id.audioButton).setOnLongClickListener(null)

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
            val transitionPair = onResolveTransitionTargetPair()
            if (transitionPair == null) {
                Toast.makeText(activity, "Need at least 2 clips for transition", Toast.LENGTH_SHORT).show()
            } else {
                val (outgoing, incoming) = transitionPair
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
        schedulePreviewSeek(previewView, clipId)
        persistClipEffectsAsync(clipId, params)
    }

    private fun resolvePreviewSeekTimeMs(clipId: Int? = null): Long {
        val currentTimeMs = currentTimeMsProvider().coerceAtLeast(0L)
        val resolvedTimeMs = clipId?.let { onResolveVisualClipPreviewTimeMs(it) }
        return resolvedTimeMs?.coerceAtLeast(0L) ?: currentTimeMs
    }

    private fun schedulePreviewSeek(previewView: VideoPreviewView, clipId: Int? = null) {
        pendingPreviewSeekMs = resolvePreviewSeekTimeMs(clipId)
        if (previewSeekScheduled) {
            return
        }
        previewSeekScheduled = true
        previewView.removeCallbacks(previewSeekRunnable)
        previewView.postOnAnimation(previewSeekRunnable)
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
        pausePlaybackForPanel()
        val initial = loadChromaPanelState(clipId)
        var enabled = initial.enabled
        var similarity = initial.similarity
        var smoothness = initial.smoothness
        var spill = initial.spill
        var isBlue = initial.isBlue

        fun apply() {
            val playheadMs = currentTimeMsProvider().coerceAtLeast(0L)
            val generation = chromaApplyGeneration.incrementAndGet()
            val params =
                mapOf(
                    "clipId" to clipId,
                    "enabled" to enabled,
                    "color" to if (isBlue) 1 else 0,
                    "similarity" to similarity,
                    "smoothness" to smoothness,
                    "spill" to spill,
                )
            Log.i(TAG, "Chroma apply queued clip=$clipId enabled=$enabled blue=$isBlue")
            NativeBridge.executeCommandAsync("SET_CHROMA_KEY", params)
            activity.runOnUiThread {
                fun refreshPreviewIfLatest() {
                    if (generation != chromaApplyGeneration.get()) {
                        return
                    }
                    runCatching { NativeBridge.seekToTime(previewView, playheadMs) }
                }
                refreshPreviewIfLatest()
                previewView.postDelayed({ refreshPreviewIfLatest() }, 80L)
                previewView.postDelayed({ refreshPreviewIfLatest() }, 180L)
            }
        }

        apply()
        ModernSheet.showModal(
            context = activity,
            title = "Chroma Key",
            showClose = true,
            showApply = true,
            onApply = {
                apply()
                com.video.engine.UiToast.makeText(activity, "Chroma key applied", android.widget.Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                enabled = initial.enabled
                similarity = initial.similarity
                smoothness = initial.smoothness
                spill = initial.spill
                isBlue = initial.isBlue
                apply()
            },
        ) {
            toggle("Enable Chroma Key", enabled) { enabled = it; apply() }
            divider()
            section("Key Color")
            val colors = listOf(
                android.graphics.Color.parseColor("#00FF00"), // Green
                android.graphics.Color.parseColor("#0088FF"), // Blue
                android.graphics.Color.parseColor("#00FFFF"), // Cyan
                android.graphics.Color.parseColor("#FF00FF"), // Magenta
                android.graphics.Color.parseColor("#000000"), // Black
                android.graphics.Color.parseColor("#FFFFFF"), // White
            )
            val selectedColor = if (isBlue) colors[1] else colors[0]
            colorPalette(colors, selectedColor) { chosenColor ->
                isBlue = (chosenColor == colors[1] || chosenColor == colors[2])
                apply()
            }
            divider()
            section("Parameters")
            sliderWithBubble(
                label = "Intensity",
                min = 0f,
                max = 100f,
                value = similarity * 100f,
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                similarity = v / 100f
                apply()
            }
            sliderWithBubble(
                label = "Offset",
                min = 0f,
                max = 100f,
                value = smoothness * 100f,
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                smoothness = v / 100f
                apply()
            }
            sliderWithBubble(
                label = "Spill Suppress",
                min = 0f,
                max = 100f,
                value = spill * 100f,
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                spill = v / 100f
                apply()
            }
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
