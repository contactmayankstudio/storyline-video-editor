package com.video.engine

import android.app.Activity
import android.util.Log
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.video.engine.effects.EffectParams
import com.video.engine.stickers.StickersPanel
import com.video.engine.timeline.TimelineManager
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
    private val onQuickImport: () -> Boolean,
    private val onQuickOverlayImport: () -> Boolean,
    private val onShowAudioPicker: () -> Unit,
    private val onQuickAudioImport: () -> Boolean,
    private val onSplitAudioAtPlayhead: () -> Boolean,
    private val clipEffects: MutableMap<Int, EffectParams>,
    private val onTransitionRequested: (Int, Int) -> Unit = { _, _ -> },
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

    fun setupPlayPauseButton() {
        val playPauseButton = activity.findViewById<ImageButton>(R.id.previewPlayPauseButton)
        fun syncPlayPauseIcon() {
            playPauseButton?.setImageResource(
                if (isPlayingProvider()) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
            )
        }
        syncPlayPauseIcon()
        playPauseButton?.setOnClickListener {
            if (isPlayingProvider()) {
                onNativePause()
                Log.d(TAG, "Pause pressed")
            } else {
                onNativePlay()
                Log.d(TAG, "Play pressed")
            }
            syncPlayPauseIcon()
        }
    }

    fun setupExportButton() {
        activity.findViewById<ImageView>(R.id.exportButton)?.setOnClickListener {
            onShowExportDialog()
        }
        activity.findViewById<LinearLayout?>(R.id.exportButtonContainer)?.setOnClickListener {
            onShowExportDialog()
        }
    }

    fun setupToolbarButtons() {
        bindOptionalImageButton("saveProjectButton", onShowSaveProjectDialog, "Save project button not found")
        bindOptionalImageButton("loadProjectButton", onShowLoadProjectDialog, "Load project button not found")

        try {
            activity.findViewById<ImageView>(R.id.undoButton)?.setOnClickListener { onUndo() }
            activity.findViewById<ImageView>(R.id.redoButton)?.setOnClickListener { onRedo() }
        } catch (e: Exception) {
            Log.d(TAG, "Undo/redo buttons not found: ${e.message}")
        }

        activity.findViewById<LinearLayout>(R.id.cutButton).setOnClickListener {
            Log.d(TAG, "Video import button clicked")
            onOpenVideoImportPicker()
        }
        activity.findViewById<LinearLayout>(R.id.cutButton).setOnLongClickListener {
            Log.d(TAG, "Video import button long-pressed")
            if (!onQuickImport()) {
                Toast.makeText(activity, "No quick media found", Toast.LENGTH_SHORT).show()
            }
            true
        }

        activity.findViewById<LinearLayout>(R.id.overlayImportButton).setOnClickListener {
            Log.d(TAG, "Overlay import button clicked")
            onOpenOverlayImportPicker()
        }
        activity.findViewById<LinearLayout>(R.id.overlayImportButton).setOnLongClickListener {
            Log.d(TAG, "Overlay import button long-pressed")
            if (!onQuickOverlayImport()) {
                Toast.makeText(activity, "No quick overlay media found", Toast.LENGTH_SHORT).show()
            }
            true
        }

        activity.findViewById<LinearLayout>(R.id.audioButton).setOnClickListener {
            Log.d(TAG, "Audio button clicked")
            onShowAudioPicker()
        }
        activity.findViewById<LinearLayout>(R.id.audioButton).setOnLongClickListener {
            Log.d(TAG, "Audio button long-pressed")
            if (!onQuickAudioImport()) {
                Toast.makeText(activity, "No quick audio found", Toast.LENGTH_SHORT).show()
            }
            true
        }

        activity.findViewById<LinearLayout>(R.id.textButton).setOnClickListener {
            overlayControllerProvider()?.showAddTextDialog()
        }

        activity.findViewById<LinearLayout>(R.id.effectsButton).setOnClickListener {
            val previewView = previewViewProvider()
            if (previewView == null) {
                Toast.makeText(activity, "Preview view not found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timelineManager = timelineManagerProvider()
            val selectedClipId = timelineManager?.getSelectedClipId() ?: run {
                val selectedFromTimeline = timelineManager?.getClips()?.firstOrNull()?.id
                if (selectedFromTimeline != null) {
                    timelineManager.selectClip(selectedFromTimeline)
                    selectedFromTimeline
                } else {
                    val ids = NativeBridge.getClipIds(previewView)
                    if (ids.isNotEmpty()) ids[0] else 0
                }
            }

            if (selectedClipId <= 0) {
                Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentParams = clipEffects[selectedClipId] ?: EffectParams()
            EffectsPanel(activity, previewView, selectedClipId, currentParams) { ep, enabled ->
                val applied = if (enabled) ep else EffectParams()
                clipEffects[selectedClipId] = applied
                persistClipEffectsAsync(selectedClipId, applied)
            }.show()
        }

        bindOptionalLinearButton("stickersButton", "Stickers button not found in layout") {
            val previewView = previewViewProvider()
            if (previewView == null) {
                Toast.makeText(activity, "Preview view not found", Toast.LENGTH_SHORT).show()
                return@bindOptionalLinearButton
            }
            if (isPlayingProvider()) {
                setIsPlaying(false)
                onPauseRendering()
            }
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
        }

        bindOptionalLinearButton("layersButton", "Layers button not found in layout") {
            val layerItems = mutableListOf<LayerItem>()
            editorStateProvider()?.buildLayerDescriptors().orEmpty().forEach { descriptor ->
                layerControllerProvider()?.buildLayerItem(descriptor)?.let { item ->
                    layerItems += item
                }
            }
            LayersPanel(activity, layerItems).show()
        }

        bindOptionalLinearButton("transitionButton", "Transition button not found") {
            val manager = timelineManagerProvider()
            val clips = manager?.getClips().orEmpty()
            if (clips.size < 2) {
                Toast.makeText(activity, "Need at least 2 clips for transition", Toast.LENGTH_SHORT).show()
                return@bindOptionalLinearButton
            }
            val selected = manager?.getSelectedClipId()
            val idx = clips.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
            val outgoing = clips[idx].id
            val incoming = clips.getOrNull(idx + 1)?.id ?: clips[maxOf(0, idx - 1)].id
            onTransitionRequested(outgoing, incoming)
        }

        bindOptionalLinearButton("voiceoverButton", "Voiceover button not found") {
            if (isPlayingProvider()) {
                setIsPlaying(false)
                onPauseRendering()
            }
            onVoiceoverRequested()
        }

        bindOptionalLinearButton("colorGradingButton", "Color Grading button not found") {
            val previewView = previewViewProvider() ?: run {
                Toast.makeText(activity, "Preview not ready", Toast.LENGTH_SHORT).show()
                return@bindOptionalLinearButton
            }
            val clipId = timelineManagerProvider()?.getSelectedClipId()
                ?: NativeBridge.getClipIds(previewView).firstOrNull()
                ?: run {
                    Toast.makeText(activity, "Select a clip first", Toast.LENGTH_SHORT).show()
                    return@bindOptionalLinearButton
                }
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
                chips("LUT Presets", listOf("None", "Warm", "Cool", "Vintage", "B&W"), -1) { _, lut ->
                    Thread {
                        runCatching { NativeBridge.executeCommand("APPLY_LUT", mapOf("clipId" to clipId, "lut" to lut.lowercase())) }
                        activity.runOnUiThread {
                            runCatching { NativeBridge.seekToTime(previewView, currentTimeMsProvider().coerceAtLeast(0L)) }
                        }
                    }.start()
                }
            }
        }
    }

    private fun applyColorPreview(previewView: VideoPreviewView, clipId: Int, params: EffectParams) {
        clipEffects[clipId] = params
        previewView.setClipEffects(clipId, params.brightness, params.contrast, params.saturation)
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
