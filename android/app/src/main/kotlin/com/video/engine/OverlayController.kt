package com.video.engine

import android.app.Activity
import com.video.engine.ModernSheet
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import com.video.engine.UiToast as Toast
import com.video.engine.overlay.OverlayStore
import com.video.engine.overlay.TextOverlay
import com.video.engine.overlay.TextOverlayView
import com.video.engine.pro.model.TrackType
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
    private val trackEndTimeMsProvider: (TrackType) -> Long = { 0L },
    private val overlayViews: MutableMap<Int, TextOverlayView>,
    private val stickerOverlayViews: MutableMap<Int, StickerOverlayView>,
    private val onRefreshOverlayStack: () -> Unit,
    private val onTimelineContentChanged: () -> Unit,
    private val onSelectClip: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "[UI]"
        private const val TEXT_PREVIEW_Z = 520
        private const val STICKER_PREVIEW_Z = 430
        private const val TEXT_NATIVE_UPDATE_INTERVAL_MS = 16L
    }

    private val textNativeOpacityById = mutableMapOf<Int, Float>()
    private val textNativeZOrderById = mutableMapOf<Int, Int>()
    private val textNativeViewIdentityById = mutableMapOf<Int, Int>()
    private val textNativeTransformUpdateMsById = mutableMapOf<Int, Long>()

    private fun appendStartFor(trackType: TrackType): Long {
        val playheadMs = currentTimeMsProvider().coerceAtLeast(0L)
        val trackEndMs = trackEndTimeMsProvider(trackType).coerceAtLeast(0L)
        return if (trackEndMs > 0L) trackEndMs else playheadMs
    }

    private fun safeTimelineInt(ms: Long): Int = ms.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun updateNativeTextTransform(overlay: TextOverlay, force: Boolean) {
        val nowMs = SystemClock.uptimeMillis()
        val lastMs = textNativeTransformUpdateMsById[overlay.id] ?: 0L
        if (!force && nowMs - lastMs < TEXT_NATIVE_UPDATE_INTERVAL_MS) return
        textNativeTransformUpdateMsById[overlay.id] = nowMs
        val previewView = previewViewProvider() ?: return
        previewView.setActiveTextOverlayId(overlay.id)
        previewView.updateTextOverlayTransform(
            overlay.id,
            overlay.x,
            overlay.y,
            overlay.scale,
            overlay.rotation,
        )
        if (force) {
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
            previewView.updateTextOverlayOpacity(overlay.id, if (overlay.visible) overlay.opacity else 0f, 0, 0)
        }
    }

    private fun clampOverlayCenterPx(
        normalizedCenter: Float,
        containerSize: Int,
        measuredSize: Int,
        scale: Float,
    ): Float {
        val safeContainer = containerSize.coerceAtLeast(1)
        val safeMeasured = measuredSize.coerceAtLeast(1)
        val renderedSize = (safeMeasured * scale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
        val minCenter = renderedSize * 0.5f
        val maxCenter = (safeContainer.toFloat() - renderedSize * 0.5f).coerceAtLeast(minCenter)
        return (normalizedCenter.coerceIn(0f, 1f) * safeContainer).coerceIn(minCenter, maxCenter)
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
        val startTimeMs = appendStartFor(TrackType.TEXT)
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
            startTimeMs = safeTimelineInt(startTimeMs),
            endTimeMs = safeTimelineInt(endTimeMs),
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
        val startTimeMs = appendStartFor(TrackType.OVERLAY)
        return StickerClip(
            id = id,
            type = "sticker",
            stickerId = stickerId,
            startTimeMs = safeTimelineInt(startTimeMs),
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
        val startTimeMs = appendStartFor(TrackType.OVERLAY)
        return StickerClip(
            id = id,
            type = "image",
            imagePath = imagePath,
            startTimeMs = safeTimelineInt(startTimeMs),
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
        overlayViews.remove(overlay.id)?.let { oldView ->
            overlayContainer.removeView(oldView)
        }

        val width = overlayContainer.width.takeIf { it > 0 } ?: 500
        val height = overlayContainer.height.takeIf { it > 0 } ?: 800
        val overlayView = TextOverlayView(activity)
        overlayView.setMaxTextWidth((width * 0.72f).toInt().coerceAtLeast(180))
        overlayView.setText(overlay.text)
        overlayView.applyStyle(overlay)
        overlayView.tag = "text-${overlay.id}"
        val left = (overlay.x * width - 100f).toInt().coerceIn(0, (width - 200).coerceAtLeast(0))
        val top = (overlay.y * height - 50f).toInt().coerceIn(0, (height - 100).coerceAtLeast(0))

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.LEFT or android.view.Gravity.TOP,
        ).apply {
            leftMargin = left
            topMargin = top
        }
        overlayContainer.visibility = android.view.View.VISIBLE
        overlayContainer.addView(overlayView, lp)
        overlayView.bringToFront()
        overlayView.post {
            val measuredWidth = overlayView.width.takeIf { it > 0 } ?: 200
            val measuredHeight = overlayView.height.takeIf { it > 0 } ?: 100
            val clampedCenterX = clampOverlayCenterPx(overlay.x, width, measuredWidth, overlay.scale)
            val clampedCenterY = clampOverlayCenterPx(overlay.y, height, measuredHeight, overlay.scale)
            val centeredLeft =
                (clampedCenterX - measuredWidth * 0.5f).toInt().coerceIn(0, (width - measuredWidth).coerceAtLeast(0))
            val centeredTop =
                (clampedCenterY - measuredHeight * 0.5f).toInt().coerceIn(0, (height - measuredHeight).coerceAtLeast(0))
            val params = (overlayView.layoutParams as? FrameLayout.LayoutParams) ?: return@post
            val normalizedCenterX = (clampedCenterX / width.toFloat()).coerceIn(0f, 1f)
            val normalizedCenterY = (clampedCenterY / height.toFloat()).coerceIn(0f, 1f)
            val positionChanged = overlay.x != normalizedCenterX || overlay.y != normalizedCenterY
            if (positionChanged) {
                overlay.x = normalizedCenterX
                overlay.y = normalizedCenterY
            }
            if (params.leftMargin != centeredLeft || params.topMargin != centeredTop) {
                params.leftMargin = centeredLeft
                params.topMargin = centeredTop
                overlayView.layoutParams = params
            }
            if (positionChanged) {
                updateNativeTextTransform(overlay, force = true)
            }
        }

        overlayView.onTransformChanged = { cx, cy, scale, rot ->
            overlay.x = cx.coerceIn(0f, 1f)
            overlay.y = cy.coerceIn(0f, 1f)
            overlay.scale = scale.coerceIn(0.1f, 10f)
            overlay.rotation = rot
            overlay.visible = true

            updateNativeTextTransform(overlay, force = false)
        }

        overlayView.onTransformCommitted = { cx, cy, scale, rot ->
            overlay.x = cx.coerceIn(0f, 1f)
            overlay.y = cy.coerceIn(0f, 1f)
            overlay.scale = scale.coerceIn(0.1f, 10f)
            overlay.rotation = rot
            overlay.visible = true
            updateNativeTextTransform(overlay, force = true)
            onTimelineContentChanged()
        }

        overlayView.onDeleteRequested = {
            previewViewProvider()?.removeTextOverlay(overlay.id)
            removeOverlayView(overlay.id)
            OverlayStore.remove(overlay.id)
            onTimelineContentChanged()
            Log.d("[TEXT UI]", "deleted via overlay view id=${overlay.id}")
        }

        overlayView.onSelected = {
            onSelectClip?.invoke("text-${overlay.id}")
        }

        overlayViews[overlay.id] = overlayView
        applyTextOverlayState(overlay)
    }

    fun removeOverlayView(overlayId: Int) {
        val view = overlayViews.remove(overlayId) ?: return
        textNativeOpacityById.remove(overlayId)
        textNativeZOrderById.remove(overlayId)
        textNativeViewIdentityById.remove(overlayId)
        textNativeTransformUpdateMsById.remove(overlayId)
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

        stickerView.onSelected = {
            onSelectClip?.invoke("sticker-${clip.id}")
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
        val viewIdentity = System.identityHashCode(view)
        if (textNativeViewIdentityById[overlay.id] != viewIdentity) {
            textNativeViewIdentityById[overlay.id] = viewIdentity
            textNativeOpacityById.remove(overlay.id)
            textNativeZOrderById.remove(overlay.id)
        }
        val currentTimeMs = currentTimeMsProvider()
        val activeAtCurrentTime =
            overlay.visible &&
                currentTimeMs >= overlay.startTimeMs.toLong() &&
                currentTimeMs < overlay.endTimeMs.toLong().coerceAtLeast(overlay.startTimeMs.toLong() + 1L)
        val isEditing = activeAtCurrentTime && !isPlayingProvider()
        view.visibility = if (activeAtCurrentTime) android.view.View.VISIBLE else android.view.View.INVISIBLE
        view.showControls(isEditing)
        view.alpha = if (activeAtCurrentTime) overlay.opacity.coerceIn(0.12f, 1f) else 0f
        view.setText(overlay.text)
        view.applyStyle(overlay)
        view.scaleX = overlay.scale
        view.scaleY = overlay.scale
        view.syncControlChromeScale()
        view.rotation = overlay.rotation
        val zOrder = TEXT_PREVIEW_Z + overlay.layerIndex.coerceAtLeast(0)
        view.z = zOrder.toFloat()
        val nativeOpacity = if (activeAtCurrentTime) overlay.opacity else 0f
        val previewView = previewViewProvider()
        if (textNativeOpacityById[overlay.id] != nativeOpacity) {
            textNativeOpacityById[overlay.id] = nativeOpacity
            previewView?.updateTextOverlayOpacity(overlay.id, nativeOpacity, 0, 0)
        }
        if (textNativeZOrderById[overlay.id] != zOrder) {
            textNativeZOrderById[overlay.id] = zOrder
            previewView?.setTextZOrder(overlay.id, zOrder)
        }
        onRefreshOverlayStack()
    }

    fun applyStickerLayerState(clip: StickerClip) {
        val view = stickerOverlayViews[clip.id] ?: return
        val currentTimeMs = currentTimeMsProvider()
        val activeAtCurrentTime =
            clip.visible &&
                currentTimeMs >= clip.startTimeMs.toLong() &&
                currentTimeMs < (clip.startTimeMs.toLong() + clip.durationMs.toLong().coerceAtLeast(1L))
        view.visibility = if (activeAtCurrentTime) android.view.View.VISIBLE else android.view.View.INVISIBLE
        view.alpha = if (activeAtCurrentTime) clip.opacity.coerceIn(0.12f, 1f) else 0f
        view.scaleX = if (clip.mirrorX) -clip.scale else clip.scale
        view.scaleY = clip.scale
        view.rotation = clip.rotation
        view.z = (STICKER_PREVIEW_Z + clip.layerIndex.coerceAtLeast(0)).toFloat()
        onRefreshOverlayStack()
    }
}
