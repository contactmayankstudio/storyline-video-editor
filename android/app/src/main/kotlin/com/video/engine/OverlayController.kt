package com.video.engine

import android.app.Activity
import com.video.engine.ModernSheet
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import com.video.engine.overlay.OverlayStore
import com.video.engine.overlay.TextOverlay
import com.video.engine.overlay.TextOverlayView
import com.video.engine.stickers.StickerClip
import com.video.engine.stickers.StickerClipStore
import com.video.engine.stickers.StickerOverlayView

class OverlayController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val overlayContainerProvider: () -> FrameLayout?,
    private val isPlayingProvider: () -> Boolean,
    private val onPausePlayback: () -> Unit,
    private val currentTimeMsProvider: () -> Long,
    private val nextTextOverlayIdProvider: () -> Int,
    private val setNextTextOverlayId: (Int) -> Unit,
    private val nextStickerIdProvider: () -> Int,
    private val setNextStickerId: (Int) -> Unit,
    private val nextCompositeLayerIndexProvider: () -> Int,
    private val overlayViews: MutableMap<Int, TextOverlayView>,
    private val stickerOverlayViews: MutableMap<Int, StickerOverlayView>,
    private val onRefreshOverlayStack: () -> Unit,
    private val onTimelineContentChanged: () -> Unit,
) {
    companion object {
        private const val TAG = "[UI]"
    }

    fun showAddTextDialog() {
        ModernSheet.show(activity, "Add Text") {
            textInput("Text", "Enter text...") { }
            chips("", listOf("Add"), -1) { _, _ ->
                val text = getTextInput().trim().ifEmpty { "Hello World" }
                addNewTextOverlay(text)
            }
        }
    }

    fun addNewTextOverlay(text: String) {
        val id = nextTextOverlayIdProvider()
        setNextTextOverlayId(id + 1)
        val startTimeMs = currentTimeMsProvider()
        val durationMs = 3000
        val endTimeMs = startTimeMs + durationMs

        val overlay = TextOverlay(
            id = id,
            text = text,
            x = 0.5f,
            y = 0.3f,
            scale = 1.0f,
            rotation = 0.0f,
            color = 0xFFFFFFFFu.toInt(),
            fontSize = 36f,
            startTimeMs = startTimeMs.toInt(),
            endTimeMs = endTimeMs.toInt(),
            opacity = 1.0f,
            layerIndex = nextCompositeLayerIndexProvider(),
            visible = true,
        )
        OverlayStore.put(overlay)

        if (isPlayingProvider()) {
            onPausePlayback()
        }

        val previewView = previewViewProvider()
        if (previewView == null) {
            Log.e(TAG, "PreviewView not available")
            Toast.makeText(activity, "Preview view not available", Toast.LENGTH_SHORT).show()
            return
        }

        val nativeId = previewView.addTextOverlay(
            id,
            overlay.text,
            overlay.x,
            overlay.y,
            overlay.scale,
            overlay.rotation,
            overlay.color,
            overlay.fontSize,
            overlay.startTimeMs,
            overlay.endTimeMs,
        )

        if (nativeId > 0) {
            overlay.id = nativeId.toInt()
            try {
                val (pixels, width, height) = TextBitmapHelper.createTextPixels(overlay)
                previewView.setTextOverlayBitmap(overlay.id, pixels, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create text bitmap: ${e.message}")
            }

            addOverlayView(overlay)
            previewView.setActiveTextOverlayId(overlay.id)
            onTimelineContentChanged()
            Log.d("[TEXT ADD]", "Added overlay id=${overlay.id} text='$text' duration=${durationMs}ms")
            Toast.makeText(activity, "Text added. Drag to move, pinch to scale.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "Native overlay creation failed")
            Toast.makeText(activity, "Failed to add text", Toast.LENGTH_SHORT).show()
        }
    }

    fun addStickerClip(clip: StickerClip) {
        StickerClipStore.add(clip)
        addStickerOverlayView(clip)
        onTimelineContentChanged()
    }

    fun createStickerClip(stickerId: Int): StickerClip {
        val id = nextStickerIdProvider()
        setNextStickerId(id + 1)
        return StickerClip(
            id = id,
            type = "sticker",
            stickerId = stickerId,
            startTimeMs = currentTimeMsProvider().toInt(),
            durationMs = 3000,
            x = 0.5f,
            y = 0.5f,
            layerIndex = nextCompositeLayerIndexProvider(),
            visible = true,
        )
    }

    fun createImageStickerClip(imagePath: String): StickerClip {
        val id = nextStickerIdProvider()
        setNextStickerId(id + 1)
        return StickerClip(
            id = id,
            type = "image",
            imagePath = imagePath,
            startTimeMs = currentTimeMsProvider().toInt(),
            durationMs = 3000,
            layerIndex = nextCompositeLayerIndexProvider(),
            visible = true,
        )
    }

    fun addOverlayView(overlay: TextOverlay) {
        val overlayContainer = overlayContainerProvider()
        if (overlayContainer == null) {
            Log.w(TAG, "overlayContainer not ready")
            return
        }

        val overlayView = TextOverlayView(activity)
        overlayView.setText(overlay.text)
        overlayView.tag = "text-${overlay.id}"

        val width = overlayContainer.width.takeIf { it > 0 } ?: 500
        val height = overlayContainer.height.takeIf { it > 0 } ?: 800
        val left = (overlay.x * width - 100f).toInt().coerceAtLeast(0)
        val top = (overlay.y * height - 50f).toInt().coerceAtLeast(0)

        val lp = FrameLayout.LayoutParams(
            200,
            100,
            android.view.Gravity.LEFT or android.view.Gravity.TOP,
        ).apply {
            leftMargin = left
            topMargin = top
        }
        overlayContainer.addView(overlayView, lp)

        overlayView.onTransformChanged = { cx, cy, scale, rot ->
            overlay.x = cx.coerceIn(0f, 1f)
            overlay.y = cy.coerceIn(0f, 1f)
            overlay.scale = scale.coerceIn(0.1f, 10f)
            overlay.rotation = rot

            previewViewProvider()?.updateTextOverlay(
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
        }

        overlayView.onDeleteRequested = {
            previewViewProvider()?.removeTextOverlay(overlay.id)
            removeOverlayView(overlay.id)
            OverlayStore.remove(overlay.id)
            onTimelineContentChanged()
            Log.d("[TEXT UI]", "deleted via overlay view id=${overlay.id}")
        }

        overlayViews[overlay.id] = overlayView
        applyTextOverlayState(overlay)
    }

    fun removeOverlayView(overlayId: Int) {
        val view = overlayViews.remove(overlayId) ?: return
        overlayContainerProvider()?.removeView(view)
        onRefreshOverlayStack()
        onTimelineContentChanged()
        Log.d(TAG, "Removed overlay view id=$overlayId")
    }

    fun addStickerOverlayView(clip: StickerClip) {
        val overlayContainer = overlayContainerProvider()
        if (overlayContainer == null) {
            Log.w(TAG, "overlayContainer not ready for sticker")
            return
        }

        val stickerView = StickerOverlayView(activity)
        stickerView.tag = "sticker-${clip.id}"
        val displayText = if (clip.type == "sticker") {
            val allPacks = com.video.engine.stickers.StickerPacks.getAllPacks()
            var text = "✨"
            for ((_, pack) in allPacks) {
                val sticker = pack.find { it.id == clip.stickerId }
                if (sticker != null) {
                    text = sticker.emojiOrSymbol
                    break
                }
            }
            text
        } else {
            "📷"
        }

        stickerView.setSticker(displayText, clip.durationMs)

        val width = overlayContainer.width.takeIf { it > 0 } ?: 500
        val height = overlayContainer.height.takeIf { it > 0 } ?: 800
        val left = (clip.x * width - 50f).toInt().coerceAtLeast(0)
        val top = (clip.y * height - 50f).toInt().coerceAtLeast(0)

        val lp = FrameLayout.LayoutParams(
            100,
            100,
            android.view.Gravity.LEFT or android.view.Gravity.TOP,
        ).apply {
            leftMargin = left
            topMargin = top
        }
        overlayContainer.addView(stickerView, lp)

        stickerView.onTransformChanged = { cx, cy, scale, rot ->
            clip.x = cx.coerceIn(0f, 1f)
            clip.y = cy.coerceIn(0f, 1f)
            clip.scale = scale.coerceIn(0.1f, 10f)
            clip.rotation = rot
        }

        stickerView.onDeleteRequested = {
            StickerClipStore.remove(clip.id)
            removeStickerOverlayView(clip.id)
            onTimelineContentChanged()
            Log.d("[STICKER]", "deleted id=${clip.id}")
        }

        stickerOverlayViews[clip.id] = stickerView
        applyStickerLayerState(clip)
        onTimelineContentChanged()
    }

    fun removeStickerOverlayView(clipId: Int) {
        val view = stickerOverlayViews.remove(clipId) ?: return
        overlayContainerProvider()?.removeView(view)
        onRefreshOverlayStack()
        onTimelineContentChanged()
        Log.d(TAG, "Removed sticker overlay view id=$clipId")
    }

    fun applyTextOverlayState(overlay: TextOverlay) {
        val view = overlayViews[overlay.id] ?: return
        view.visibility = if (overlay.visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        view.alpha = if (overlay.visible) overlay.opacity.coerceIn(0.12f, 1f) else 0.35f
        view.scaleX = overlay.scale
        view.scaleY = overlay.scale
        view.rotation = overlay.rotation
        val zOrder = 400 + overlay.layerIndex
        view.z = zOrder.toFloat()
        previewViewProvider()?.updateTextOverlayOpacity(overlay.id, if (overlay.visible) overlay.opacity else 0f, 0, 0)
        previewViewProvider()?.setTextZOrder(overlay.id, zOrder)
        onRefreshOverlayStack()
    }

    fun applyStickerLayerState(clip: StickerClip) {
        val view = stickerOverlayViews[clip.id] ?: return
        view.visibility = if (clip.visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        view.alpha = if (clip.visible) clip.opacity.coerceIn(0.12f, 1f) else 0.35f
        view.scaleX = if (clip.mirrorX) -clip.scale else clip.scale
        view.scaleY = clip.scale
        view.rotation = clip.rotation
        view.z = (400 + clip.layerIndex).toFloat()
        onRefreshOverlayStack()
    }
}
