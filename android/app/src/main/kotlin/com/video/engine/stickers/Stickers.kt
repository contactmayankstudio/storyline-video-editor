package com.video.engine.stickers

import android.app.Activity
import com.video.engine.ModernSheet
import com.video.engine.AiPoseKeyframe
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import kotlin.math.atan2

data class Sticker(
    val id: Int,
    val emojiOrSymbol: String,
)

data class StickerClip(
    val id: Int,
    var type: String,
    var stickerId: Int = 0,
    var imagePath: String? = null,
    var startTimeMs: Int,
    var durationMs: Int,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var scale: Float = 1.0f,
    var rotation: Float = 0.0f,
    var opacity: Float = 1.0f,
    var mirrorX: Boolean = false,
    var layerIndex: Int = 0,
    var visible: Boolean = true,
    var aiTrackKeyframes: List<AiPoseKeyframe> = emptyList(),
)

object StickerClipStore {
    private val clips = linkedMapOf<Int, StickerClip>()
    fun add(clip: StickerClip) { clips[clip.id] = clip }
    fun remove(id: Int) { clips.remove(id) }
    fun all(): List<StickerClip> = clips.values.toList()
}

object StickerPacks {
    private val defaultPack = listOf(
        Sticker(1, "✨"),
        Sticker(2, "🔥"),
        Sticker(3, "❤️"),
        Sticker(4, "🎬"),
        Sticker(5, "💥"),
        Sticker(6, "⭐"),
    )

    fun getAllPacks(): Map<String, List<Sticker>> = mapOf("default" to defaultPack)
}

class StickerOverlayView(context: Context) : FrameLayout(context) {
    var onTransformChanged: ((Float, Float, Float, Float) -> Unit)? = null
    var onDeleteRequested: (() -> Unit)? = null
    var onSelected: (() -> Unit)? = null

    private val label = TextView(context).apply {
        textSize = 28f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setShadowLayer(4f, 0f, 2f, Color.argb(120, 0, 0, 0))
    }
    private val deleteButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
    }
    private var lastX = 0f
    private var lastY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var isDragging = false
    private var isRotating = false
    private var initialAngle = 0.0
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                scaleX = (scaleX * scaleFactor).coerceIn(0.35f, 6.0f)
                scaleY = (scaleY * scaleFactor).coerceIn(0.35f, 6.0f)
                notifyTransformChanged()
                return true
            }
        },
    )

    init {
        addView(
            label,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(
            deleteButton,
            LayoutParams(64, 64, Gravity.END or Gravity.TOP),
        )
        setPadding(20, 20, 20, 20)
        setOnLongClickListener {
            onDeleteRequested?.invoke()
            true
        }
        deleteButton.setOnClickListener { onDeleteRequested?.invoke() }
    }

    fun setSticker(text: String, durationMs: Int) {
        label.text = text
        contentDescription = "Sticker $text $durationMs"
    }

    fun showControls(show: Boolean) {
        deleteButton.visibility = if (show) View.VISIBLE else View.GONE
        alpha = if (show) 1.0f else 0.96f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                isDragging = true
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                showControls(true)
                parent.requestDisallowInterceptTouchEvent(true)
                onSelected?.invoke()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    isRotating = true
                    initialAngle = angle(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x
                lastY = event.y
                if (event.pointerCount <= 2) {
                    isRotating = false
                    // Re-anchor for the remaining finger so drag doesn't teleport
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTranslationX = translationX
                    startTranslationY = translationY
                    if (!isDragging) {
                        setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRotating && event.pointerCount >= 2) {
                    val currentAngle = angle(event)
                    val delta = Math.toDegrees(currentAngle - initialAngle).toFloat()
                    rotation += delta
                    initialAngle = currentAngle
                    notifyTransformChanged()
                } else if (isDragging) {
                    translationX = startTranslationX + (event.rawX - downRawX)
                    translationY = startTranslationY + (event.rawY - downRawY)
                    notifyTransformChanged()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isRotating = false
                setLayerType(View.LAYER_TYPE_NONE, null)
                parent.requestDisallowInterceptTouchEvent(false)
                notifyTransformChanged()
            }
        }

        return true
    }

    private fun angle(event: MotionEvent): Double {
        if (event.pointerCount < 2) return 0.0
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return atan2(dy.toDouble(), dx.toDouble())
    }

    private fun notifyTransformChanged() {
        val parentView = parent as? View ?: return
        if (parentView.width <= 0 || parentView.height <= 0) return
        val centerX = (x + width * 0.5f) / parentView.width.toFloat()
        val centerY = (y + height * 0.5f) / parentView.height.toFloat()
        onTransformChanged?.invoke(
            centerX.coerceIn(0f, 1f),
            centerY.coerceIn(0f, 1f),
            scaleY.coerceIn(0.35f, 6.0f),
            rotation,
        )
    }
}

class StickersPanel(
    private val activity: Activity,
    private val onStickerSelected: (Sticker) -> Unit,
    private val onImageSelected: (String) -> Unit,
) {
    fun show() {
        val emojis = listOf(
            "✨", "🔥", "❤️", "🎬", "💥", "⭐",
            "🚀", "👏", "🎉", "💯", "😍", "🤩",
            "😎", "🎯", "⚡", "🌟", "💡", "🎶",
            "👍", "🙌", "👑", "🏆", "💎", "🌈",
        )
        val stickers = emojis.mapIndexed { idx, emo -> Sticker(idx + 1, emo) }

        ModernSheet.showModal(
            context = activity,
            title = "Add Sticker",
            showClose = true,
            showApply = false,
        ) {
            categories(listOf("Popular", "Reactions", "Vibe", "Symbols"), selected = 0) { _, _ -> }

            section("Stickers & Emojis")
            val stickerCards = emojis.mapIndexed { idx, emo ->
                ModernSheet.VisualCard(
                    id = "$idx",
                    label = "",
                    emoji = emo,
                )
            }
            visualCardsGrid(
                cards = stickerCards,
                selected = -1,
                columns = 6,
                dismissOnSelect = true,
            ) { i, _ ->
                if (i < stickers.size) {
                    onStickerSelected(stickers[i])
                }
            }

            divider()
            section("Custom Image Overlay")
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Import Custom Image / PNG",
                        isPrimary = true,
                        dismissOnClick = true,
                        onClick = { onImageSelected("gallery://picker") },
                    ),
                ),
            )
        }
    }
}
