package com.video.engine

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.overlay.TextOverlay
import com.video.engine.overlay.WordCue
import kotlin.math.roundToInt

/**
 * Advanced Pro Text Studio Panel with 4 category tabs, real-time interactive live preview,
 * rich typography, gradient, glow, 3D depth, and viral caption styles.
 */
class TextEditorPanel(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    overlay: TextOverlay,
    private val onLiveUpdate: (TextOverlay, Boolean) -> Unit = { _, _ -> },
    private val onDone: (TextOverlay) -> Unit = {},
    private val onDuplicate: (TextOverlay) -> Unit = {},
    private val onDelete: (TextOverlay) -> Unit = {},
) {
    private val targetOverlay: TextOverlay = overlay
    private data class ChipSpec(val label: String, val color: Int)
    private data class FontSpec(val label: String, val fontName: String)

    private lateinit var dialog: BottomSheetDialog
    private var isApplyingPreview = false
    private val darkPanel = Color.parseColor("#11151B")
    private val chipIdle = Color.parseColor("#171C23")
    private val accentBlue = Color.parseColor("#388BFD")

    private lateinit var livePreviewText: TextView
    private lateinit var livePreviewCard: FrameLayout
    private val tabContainers = mutableListOf<LinearLayout>()
    private val tabButtons = mutableListOf<TextView>()
    private var activeTabIndex = 0

    fun show() {
        dialog = BottomSheetDialog(activity)
        dialog.setCanceledOnTouchOutside(false)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(px(24).toFloat(), px(24).toFloat(), px(24).toFloat(), px(24).toFloat(), 0f, 0f, 0f, 0f)
                setColor(darkPanel)
                setStroke(px(1), Color.parseColor("#1B222C"))
            }
            setPadding(px(18), px(12), px(18), px(24))
        }

        root.addView(createHandle())
        root.addView(createHeader())
        root.addView(createLivePreviewCard())
        root.addView(createTabBar())

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val mainContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Tab 0: Text & Font
        val tabTextFont = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val textEdit = addTextInput(tabTextFont)
        addQuickEmojis(tabTextFont, textEdit)
        addFontSizeControl(tabTextFont)
        addOpacityControl(tabTextFont)
        addFontControls(tabTextFont)
        addStyleControls(tabTextFont)
        addPresetStyles(tabTextFont)
        addCaptionStyleControls(tabTextFont)
        tabContainers.add(tabTextFont)
        mainContainer.addView(tabTextFont)

        // Tab 1: Colors & Glow
        val tabColorsGlow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        addTextColorControls(tabColorsGlow)
        addGradientControls(tabColorsGlow)
        addShadowControls(tabColorsGlow)
        tabContainers.add(tabColorsGlow)
        mainContainer.addView(tabColorsGlow)

        // Tab 2: Background & 3D
        val tabBg3d = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        addBackgroundControls(tabBg3d)
        addStrokeAndDepthControls(tabBg3d)
        tabContainers.add(tabBg3d)
        mainContainer.addView(tabBg3d)

        // Tab 3: Animations & Viral Styles
        val tabAnimation = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        addViralCaptionControls(tabAnimation)
        tabContainers.add(tabAnimation)
        mainContainer.addView(tabAnimation)

        scroll.addView(mainContainer)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addActions(root, textEdit)

        dialog.setContentView(root)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.52f, maxRatio = 0.78f)
        updateLivePreviewBox()
        dialog.show()
    }

    private fun createHeader(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(8))
        }
        val titleView = TextView(activity).apply {
            text = "✨ Text Studio Pro"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val liveBadge = TextView(activity).apply {
            text = "● LIVE SYNC"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(8).toFloat()
                setColor(Color.parseColor("#11241A"))
                setStroke(px(1), Color.parseColor("#00E676"))
            }
            setPadding(px(8), px(3), px(8), px(3))
        }
        row.addView(titleView)
        row.addView(liveBadge)
        return row
    }

    private fun createLivePreviewCard(): FrameLayout {
        livePreviewCard = FrameLayout(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(12).toFloat()
                setColor(Color.parseColor("#080B0F"))
                setStroke(px(1), Color.parseColor("#1E2632"))
            }
            setPadding(px(16), px(14), px(16), px(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(86),
            ).also { it.bottomMargin = px(10) }
        }

        livePreviewText = TextView(activity).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        livePreviewCard.addView(livePreviewText)
        return livePreviewCard
    }

    private fun updateLivePreviewBox() {
        if (!::livePreviewText.isInitialized) return
        val rawText = targetOverlay.text.ifBlank { "Text Preview" }
        livePreviewText.text = if (targetOverlay.allCaps) rawText.uppercase() else rawText
        livePreviewText.setTextColor(targetOverlay.color)
        val tf = TextBitmapHelper.resolveTypeface(targetOverlay.fontName, targetOverlay.bold, targetOverlay.italic, activity)
        livePreviewText.typeface = tf
        livePreviewText.paint.isUnderlineText = targetOverlay.underline

        // Background styling
        val bgAlpha = Color.alpha(targetOverlay.backgroundColor)
        if (bgAlpha > 0) {
            livePreviewText.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(targetOverlay.backgroundCornerRadius.toInt().coerceAtLeast(4)).toFloat()
                setColor(targetOverlay.backgroundColor)
                if (targetOverlay.strokeWidth > 0f) {
                    setStroke(px(targetOverlay.strokeWidth.toInt().coerceIn(1, 4)), targetOverlay.strokeColor)
                }
            }
            val pad = px(targetOverlay.backgroundPadding.toInt().coerceIn(4, 16))
            livePreviewText.setPadding(pad, pad / 2, pad, pad / 2)
        } else {
            livePreviewText.background = null
            livePreviewText.setPadding(px(6), px(2), px(6), px(2))
        }

        // Shadow / Glow
        if (targetOverlay.shadowEnabled && targetOverlay.shadowBlur > 0f) {
            livePreviewText.setShadowLayer(
                targetOverlay.shadowBlur.coerceIn(1f, 25f),
                targetOverlay.shadowOffsetX,
                targetOverlay.shadowOffsetY,
                targetOverlay.shadowColor,
            )
        } else {
            livePreviewText.setShadowLayer(0f, 0f, 0f, 0)
        }
        livePreviewText.alpha = targetOverlay.opacity.coerceIn(0.15f, 1.0f)
    }

    private fun createTabBar(): View {
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = px(10) }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tabLabels = listOf("✏️ Text & Font", "🎨 Color & Glow", "🔲 Background & 3D", "✨ Animations")
        tabButtons.clear()
        tabLabels.forEachIndexed { index, label ->
            val tabBtn = TextView(activity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(px(14), px(8), px(14), px(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(36),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener { selectTab(index) }
            }
            tabButtons.add(tabBtn)
            row.addView(tabBtn)
        }
        scroller.addView(row)
        refreshTabButtons()
        return scroller
    }

    private fun selectTab(index: Int) {
        activeTabIndex = index
        tabContainers.forEachIndexed { idx, container ->
            container.visibility = if (idx == index) View.VISIBLE else View.GONE
        }
        refreshTabButtons()
    }

    private fun refreshTabButtons() {
        tabButtons.forEachIndexed { idx, btn ->
            val isActive = idx == activeTabIndex
            btn.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#8E9AA8"))
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(18).toFloat()
                if (isActive) {
                    setColor(Color.parseColor("#1F6FEB"))
                    setStroke(px(1), Color.parseColor("#58A6FF"))
                } else {
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#222A36"))
                }
            }
        }
    }

    // ── Tab 0: Text & Font ──────────────────────────────────────────────────
    private fun addTextInput(container: LinearLayout): EditText {
        container.addView(label("Text Content"))
        val editText = EditText(activity).apply {
            setText(targetOverlay.text)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            hint = "Enter text or title"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(12).toFloat()
                setColor(Color.parseColor("#141920"))
                setStroke(px(1), Color.parseColor("#1E2632"))
            }
            setPadding(px(14), px(10), px(14), px(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72)).also {
                it.bottomMargin = px(8)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (isApplyingPreview) return
                    targetOverlay.text = s?.toString().orEmpty().ifEmpty { "Text" }
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            })
        }
        container.addView(editText)
        return editText
    }

    private fun addQuickEmojis(container: LinearLayout, editText: EditText) {
        val emojis = listOf("🔥", "✨", "⚡", "🚀", "🎬", "💬", "❤️", "💯", "🎯", "🏷️")
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = px(10) }
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        emojis.forEach { emoji ->
            val chip = TextView(activity).apply {
                text = emoji
                textSize = 14f
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#222A36"))
                }
                layoutParams = LinearLayout.LayoutParams(px(36), px(34)).also {
                    it.setMargins(0, 0, px(6), 0)
                }
                setOnClickListener {
                    editText.append(" $emoji")
                }
            }
            row.addView(chip)
        }
        scroller.addView(row)
        container.addView(scroller)
    }

    private fun addFontSizeControl(container: LinearLayout) {
        container.addView(
            sliderRow(
                title = "Font Size",
                min = 12,
                max = 96,
                initial = targetOverlay.fontSize.roundToInt().coerceIn(12, 96),
                formatter = { "${it}pt" },
                onChange = { value ->
                    targetOverlay.fontSize = value.toFloat()
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    private fun addOpacityControl(container: LinearLayout) {
        container.addView(
            sliderRow(
                title = "Opacity",
                min = 10,
                max = 100,
                initial = (targetOverlay.opacity * 100f).roundToInt().coerceIn(10, 100),
                formatter = { "$it%" },
                onChange = { value ->
                    targetOverlay.opacity = value / 100f
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    private fun addFontControls(container: LinearLayout) {
        container.addView(label("Typography Font"))
        val specs = listOf(
            FontSpec("Kalam (हिंदी)", "kalam"),
            FontSpec("Rozha (Royal)", "rozha"),
            FontSpec("Poppins", "poppins-bold"),
            FontSpec("Bangers", "bangers"),
            FontSpec("Anton", "anton"),
            FontSpec("Marker", "marker"),
            FontSpec("Montserrat", "montserrat"),
            FontSpec("Sans", "sans-serif"),
            FontSpec("Serif", "serif"),
            FontSpec("Mono", "monospace"),
            FontSpec("Condensed", "sans-serif-condensed"),
            FontSpec("Cursive", "pacifico"),
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<TextView>()

        fun refreshButtons() {
            val current = targetOverlay.fontName ?: "sans-serif"
            for (button in buttons) {
                val active = button.tag == current
                button.setTextColor(if (active) Color.BLACK else Color.WHITE)
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(if (active) Color.WHITE else chipIdle)
                    setStroke(px(1), if (active) Color.WHITE else Color.parseColor("#222A36"))
                }
            }
        }

        for (spec in specs) {
            val button = TextView(activity).apply {
                text = spec.label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(px(14), px(6), px(14), px(6))
                tag = spec.fontName
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(36),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    targetOverlay.fontName = spec.fontName
                    refreshButtons()
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            buttons.add(button)
            row.addView(button)
        }
        refreshButtons()
        scroller.addView(row)
        container.addView(scroller)
    }

    private fun addStyleControls(container: LinearLayout) {
        container.addView(label("Text Formatting"))
        val row = toolRow()
        row.addView(toggleButton("Bold", targetOverlay.bold, Typeface.BOLD) { active -> targetOverlay.bold = active })
        row.addView(toggleButton("Italic", targetOverlay.italic, Typeface.ITALIC) { active -> targetOverlay.italic = active })
        row.addView(toggleButton("Underline", targetOverlay.underline, Typeface.NORMAL) { active -> targetOverlay.underline = active })
        row.addView(toggleButton("ALL CAPS", targetOverlay.allCaps, Typeface.BOLD) { active -> targetOverlay.allCaps = active })
        container.addView(row)
    }

    private fun addPresetStyles(container: LinearLayout) {
        container.addView(label("Quick Presets"))
        val presets = listOf(
            "Title" to Pair(Color.WHITE, 48f),
            "Caption" to Pair(0xFFFFEB3B.toInt(), 32f),
            "Hook ⚡" to Pair(0xFF00E5FF.toInt(), 42f),
            "Neon Pop" to Pair(0xFFFF4081.toInt(), 40f),
            "Sub Black" to Pair(0xFF111111.toInt(), 28f),
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { (name, props) ->
            val btn = TextView(activity).apply {
                text = name
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#2B3442"))
                }
                setPadding(px(12), px(6), px(12), px(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(34),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    targetOverlay.color = props.first
                    targetOverlay.fontSize = props.second
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            row.addView(btn)
        }
        scroller.addView(row)
        container.addView(scroller)
    }

    private fun addCaptionStyleControls(container: LinearLayout) {
        container.addView(label("AI Animated Caption Style (Karaoke / Reels)"))
        val captionStyles = listOf(
            "None" to null,
            "⚡ Hormozi Pop" to "HORMOZI",
            "🔥 MrBeast Pop" to "MRBEAST",
            "🎵 Karaoke Beat" to "KARAOKE",
            "🤖 Cyberpunk" to "CYBERPUNK",
            "🎬 Cinema Glow" to "CINEMA",
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<TextView>()

        fun refreshCaptionButtons() {
            val current = targetOverlay.captionStyle
            for (button in buttons) {
                val active = button.tag == current
                button.setTextColor(if (active) Color.BLACK else Color.WHITE)
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(if (active) Color.parseColor("#388BFD") else chipIdle)
                    setStroke(px(1), if (active) Color.parseColor("#388BFD") else Color.parseColor("#222A36"))
                }
            }
        }

        captionStyles.forEach { (title, styleCode) ->
            val btn = TextView(activity).apply {
                text = title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(px(14), px(6), px(14), px(6))
                tag = styleCode
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(36),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    targetOverlay.captionStyle = styleCode
                    targetOverlay.interpolateWordCuesIfNeeded()
                    refreshCaptionButtons()
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            buttons.add(btn)
            row.addView(btn)
        }
        refreshCaptionButtons()
        scroller.addView(row)
        container.addView(scroller)
    }

    // ── Tab 1: Colors & Glow ────────────────────────────────────────────────
    private fun addTextColorControls(container: LinearLayout) {
        container.addView(label("Primary Color"))
        val colors = listOf(
            ChipSpec("White", Color.WHITE),
            ChipSpec("Yellow", 0xFFFFEB3B.toInt()),
            ChipSpec("Gold", 0xFFFFD700.toInt()),
            ChipSpec("Cyan", 0xFF00E5FF.toInt()),
            ChipSpec("Green", 0xFF76FF03.toInt()),
            ChipSpec("Pink", 0xFFFF4081.toInt()),
            ChipSpec("Purple", 0xFFAB47BC.toInt()),
            ChipSpec("Red", 0xFFFF5252.toInt()),
            ChipSpec("Gray", 0xFF9E9E9E.toInt()),
            ChipSpec("Black", 0xFF111111.toInt()),
        )
        addColorChipRow(container, colors) { color ->
            targetOverlay.color = color
            applyPreviewChanges(refreshTimelineLabel = false)
        }
    }

    private fun addGradientControls(container: LinearLayout) {
        container.addView(label("Gradient Blend"))
        val gradientPresets = listOf(
            "Off" to Pair(Color.TRANSPARENT, Color.TRANSPARENT),
            "🔥 Fire" to Pair(0xFFFFD166.toInt(), 0xFFFF4D6D.toInt()),
            "🌊 Sky" to Pair(Color.WHITE, 0xFF00E5FF.toInt()),
            "💜 Neon" to Pair(0xFF00F5FF.toInt(), 0xFFB967FF.toInt()),
            "✨ Gold" to Pair(0xFFFFF3B0.toInt(), 0xFFFFB000.toInt()),
            "🌅 Sunset" to Pair(0xFFFF758C.toInt(), 0xFFFF7EB3.toInt()),
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        gradientPresets.forEach { (label, colors) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#2B3442"))
                }
                setPadding(px(12), px(6), px(12), px(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(36),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    if (colors.first == Color.TRANSPARENT) {
                        targetOverlay.gradientEnabled = false
                    } else {
                        targetOverlay.gradientEnabled = true
                        targetOverlay.gradientStartColor = colors.first
                        targetOverlay.gradientEndColor = colors.second
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            row.addView(btn)
        }
        scroller.addView(row)
        container.addView(scroller)
    }

    private fun addShadowControls(container: LinearLayout) {
        container.addView(label("Shadow & Neon Glow"))
        val shadowPresets = listOf(
            "Off" to Pair(Color.TRANSPARENT, 0f),
            "Soft Blur" to Pair(0x88000000.toInt(), 8f),
            "Strong Drop" to Pair(0xDD000000.toInt(), 16f),
            "✨ Gold Glow" to Pair(0xAAFFD700.toInt(), 18f),
            "🔮 Neon Cyan" to Pair(0xAA00E5FF.toInt(), 18f),
            "🔴 Red Glow" to Pair(0xAAFF5252.toInt(), 18f),
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        shadowPresets.forEach { (label, pair) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#2B3442"))
                }
                setPadding(px(12), px(6), px(12), px(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(36),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    if (pair.first == Color.TRANSPARENT) {
                        targetOverlay.shadowEnabled = false
                        targetOverlay.shadowBlur = 0f
                    } else {
                        targetOverlay.shadowEnabled = true
                        targetOverlay.shadowColor = pair.first
                        targetOverlay.shadowBlur = pair.second
                        targetOverlay.shadowOffsetY = 3f
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            row.addView(btn)
        }
        scroller.addView(row)
        container.addView(scroller)

        container.addView(
            sliderRow(
                title = "Blur Radius",
                min = 0,
                max = 40,
                initial = targetOverlay.shadowBlur.roundToInt().coerceIn(0, 40),
                formatter = { if (it == 0) "Off" else "${it}px" },
                onChange = { value ->
                    targetOverlay.shadowBlur = value.toFloat()
                    targetOverlay.shadowEnabled = value > 0
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    // ── Tab 2: Background & 3D ──────────────────────────────────────────────
    private fun addBackgroundControls(container: LinearLayout) {
        container.addView(label("Background Pill Style"))
        val pills = listOf(
            "None" to Color.TRANSPARENT,
            "Black Pill" to 0xCC000000.toInt(),
            "Frosted White" to 0xDDFFFFFF.toInt(),
            "Yellow Tag" to 0xCCFFEB3B.toInt(),
            "Red Accent" to 0xCCB71C1C.toInt(),
            "Deep Blue" to 0xCC0D47A1.toInt(),
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        pills.forEach { (label, color) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#2B3442"))
                }
                setPadding(px(12), px(6), px(12), px(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(36),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    targetOverlay.backgroundColor = color
                    if (color != Color.TRANSPARENT) {
                        targetOverlay.backgroundPadding = targetOverlay.backgroundPadding.coerceAtLeast(14f)
                        targetOverlay.backgroundCornerRadius = targetOverlay.backgroundCornerRadius.coerceAtLeast(12f)
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            row.addView(btn)
        }
        scroller.addView(row)
        container.addView(scroller)

        container.addView(
            sliderRow(
                title = "Padding",
                min = 0,
                max = 64,
                initial = targetOverlay.backgroundPadding.roundToInt().coerceIn(0, 64),
                formatter = { "${it}px" },
                onChange = { value ->
                    targetOverlay.backgroundPadding = value.toFloat()
                    if (value > 0 && Color.alpha(targetOverlay.backgroundColor) == 0) {
                        targetOverlay.backgroundColor = 0xCC000000.toInt()
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        container.addView(
            sliderRow(
                title = "Corner Radius",
                min = 0,
                max = 48,
                initial = targetOverlay.backgroundCornerRadius.roundToInt().coerceIn(0, 48),
                formatter = { "${it}px" },
                onChange = { value ->
                    targetOverlay.backgroundCornerRadius = value.toFloat()
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    private fun addStrokeAndDepthControls(container: LinearLayout) {
        container.addView(label("Stroke Border"))
        container.addView(
            sliderRow(
                title = "Width",
                min = 0,
                max = 18,
                initial = targetOverlay.strokeWidth.roundToInt().coerceIn(0, 18),
                formatter = { "${it}px" },
                onChange = { value ->
                    targetOverlay.strokeWidth = value.toFloat()
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        val strokeColors = listOf(
            ChipSpec("Off", Color.TRANSPARENT),
            ChipSpec("Black", Color.BLACK),
            ChipSpec("White", Color.WHITE),
            ChipSpec("Gold", 0xFFFFD54F.toInt()),
            ChipSpec("Cyan", 0xFF00E5FF.toInt()),
            ChipSpec("Red", 0xFFFF5252.toInt()),
        )
        addColorChipRow(container, strokeColors) { color ->
            if (color == Color.TRANSPARENT) {
                targetOverlay.strokeWidth = 0f
            } else {
                targetOverlay.strokeColor = color
                targetOverlay.strokeWidth = targetOverlay.strokeWidth.coerceAtLeast(4f)
            }
            applyPreviewChanges(refreshTimelineLabel = false)
        }

        container.addView(label("3D Depth Extrusion"))
        container.addView(
            sliderRow(
                title = "Depth",
                min = 0,
                max = 24,
                initial = targetOverlay.depthPx.roundToInt().coerceIn(0, 24),
                formatter = { "${it}px" },
                onChange = { value ->
                    targetOverlay.depthPx = value.toFloat()
                    if (value > 0 && Color.alpha(targetOverlay.depthColor) == 0) {
                        targetOverlay.depthColor = 0xAA000000.toInt()
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    // ── Tab 3: Animations & Viral Caption Styles ────────────────────────────
    private fun addViralCaptionControls(container: LinearLayout) {
        container.addView(label("Viral Animated Caption Style"))
        val styles = listOf(
            "Static (None)" to null,
            "🔥 MrBeast Pop" to "MRBEAST",
            "⚡ Hormozi Kinetic" to "HORMOZI",
            "🔮 Cyberpunk" to "CYBERPUNK",
            "🎬 Cinema Subtitle" to "CINEMA",
            "🎤 Karaoke Live" to "KARAOKE",
        )
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        styles.forEach { (label, style) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(chipIdle)
                    setStroke(px(1), Color.parseColor("#2B3442"))
                }
                setPadding(px(12), px(8), px(12), px(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px(38),
                ).also { it.setMargins(0, 0, px(6), 0) }
                setOnClickListener {
                    targetOverlay.captionStyle = style
                    targetOverlay.wordCues = emptyList<WordCue>()
                    targetOverlay.interpolateWordCuesIfNeeded()
                    applyPreviewChanges(refreshTimelineLabel = false)
                }
            }
            row.addView(btn)
        }
        scroller.addView(row)
        container.addView(scroller)

        container.addView(label("Active Word Scale"))
        container.addView(
            sliderRow(
                title = "Scale",
                min = 100,
                max = 160,
                initial = (targetOverlay.activeWordScale * 100f).roundToInt().coerceIn(100, 160),
                formatter = { "%.2fx".format(it / 100f) },
                onChange = { value ->
                    targetOverlay.activeWordScale = value / 100f
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )

        container.addView(label("Keyword Highlight Color"))
        val highlightColors = listOf(
            ChipSpec("Yellow", 0xFFFFEB3B.toInt()),
            ChipSpec("Green", 0xFF00FF66.toInt()),
            ChipSpec("Red", 0xFFFF2A55.toInt()),
            ChipSpec("Cyan", 0xFF00E5FF.toInt()),
            ChipSpec("White", Color.WHITE),
        )
        addColorChipRow(container, highlightColors) { color ->
            targetOverlay.highlightColor = color
            targetOverlay.glowColor = color
            applyPreviewChanges(refreshTimelineLabel = false)
        }
    }

    // ── Bottom Actions ──────────────────────────────────────────────────────
    private fun addActions(root: LinearLayout, editText: EditText) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, 0)
        }
        row.addView(actionButton("📋 Duplicate", Color.parseColor("#171C23"), Color.WHITE, Color.parseColor("#222A36")) {
            targetOverlay.text = editText.text.toString().ifEmpty { "Text" }
            applyPreviewChanges(refreshTimelineLabel = true)
            onDuplicate(targetOverlay)
            dialog.dismiss()
        })
        row.addView(actionButton("🗑️ Delete", Color.parseColor("#2B1517"), Color.parseColor("#FF6B6B"), Color.parseColor("#4A1E22")) {
            onDelete(targetOverlay)
            previewView.removeTextOverlay(targetOverlay.id)
            dialog.dismiss()
        })
        row.addView(actionButton("✅ Done", Color.parseColor("#1F6FEB"), Color.WHITE, Color.parseColor("#388BFD")) {
            targetOverlay.text = editText.text.toString().ifEmpty { "Text" }
            applyPreviewChanges(refreshTimelineLabel = true)
            onDone(targetOverlay)
            dialog.dismiss()
        })
        root.addView(row)
    }

    private fun applyPreviewChanges(refreshTimelineLabel: Boolean) {
        isApplyingPreview = true
        try {
            updateLivePreviewBox()
            onLiveUpdate(targetOverlay, refreshTimelineLabel)
            previewView.updateTextOverlay(
                targetOverlay.id,
                targetOverlay.x,
                targetOverlay.y,
                targetOverlay.scale,
                targetOverlay.rotation,
                targetOverlay.color,
                targetOverlay.fontSize,
                targetOverlay.startTimeMs,
                targetOverlay.endTimeMs,
            )
            previewView.updateTextOverlayOpacity(targetOverlay.id, targetOverlay.opacity, 0, 0)
            NativeBridge.setTextOverlayBitmap(previewView, targetOverlay)
            previewView.postInvalidateOnAnimation()
        } catch (e: Exception) {
            android.util.Log.w("[TextEditor]", "updatePreview: ${e.message}")
        } finally {
            isApplyingPreview = false
        }
    }

    private fun addColorChipRow(
        container: LinearLayout,
        chips: List<ChipSpec>,
        onPick: (Int) -> Unit,
    ) {
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(4), 0, px(10))
        }
        for (chip in chips) {
            val color = chip.color
            val button = createChip(chip.label, displayChipColor(color))
            button.setTextColor(readableTextColor(color))
            button.setOnClickListener { onPick(color) }
            row.addView(button)
        }
        scroller.addView(row)
        container.addView(scroller)
    }

    private fun createChip(text: String, backgroundColor: Int): TextView {
        val view = TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(8).toFloat()
                setColor(backgroundColor)
                setStroke(px(1), Color.parseColor("#303844"))
            }
            setTextColor(readableTextColor(backgroundColor))
            setPadding(px(12), px(6), px(12), px(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                px(34),
            ).also { it.setMargins(0, 0, px(6), 0) }
            layoutParams = params
        }
        return view
    }

    private fun toggleButton(
        text: String,
        active: Boolean,
        style: Int,
        onToggle: (Boolean) -> Unit,
    ): TextView {
        val view = createChip(text, if (active) Color.WHITE else chipIdle).apply {
            textSize = 12f
            setTypeface(null, style)
        }
        var current = active
        fun refresh() {
            view.setTextColor(if (current) Color.BLACK else Color.WHITE)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(8).toFloat()
                setColor(if (current) Color.WHITE else chipIdle)
                setStroke(px(1), if (current) Color.WHITE else Color.parseColor("#222A36"))
            }
        }
        refresh()
        view.setOnClickListener {
            current = !current
            refresh()
            onToggle(current)
            applyPreviewChanges(refreshTimelineLabel = false)
        }
        return view
    }

    private fun actionButton(text: String, backgroundColor: Int, textColor: Int, strokeColor: Int = Color.TRANSPARENT, action: () -> Unit): TextView {
        val view = TextView(activity).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(10).toFloat()
                setColor(backgroundColor)
                if (strokeColor != Color.TRANSPARENT) {
                    setStroke(px(1), strokeColor)
                }
            }
            val params = LinearLayout.LayoutParams(0, px(44), 1f).also {
                it.setMargins(px(4), 0, px(4), 0)
            }
            layoutParams = params
            setOnClickListener { action() }
        }
        return view
    }

    private fun sliderRow(
        title: String,
        min: Int,
        max: Int,
        initial: Int,
        formatter: (Int) -> String,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val safeInitial = initial.coerceIn(min, max)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(10).toFloat()
                setColor(Color.parseColor("#141920"))
                setStroke(px(1), Color.parseColor("#1E2632"))
            }
            setPadding(px(12), px(8), px(12), px(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = px(8) }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(activity).apply {
            text = title
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8A99AD"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueText = TextView(activity).apply {
            text = formatter(safeInitial)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentBlue)
        }
        headerRow.addView(titleView)
        headerRow.addView(valueText)
        container.addView(headerRow)

        val seekBar = SeekBar(activity).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = safeInitial - min
            progressTintList = ColorStateList.valueOf(accentBlue)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = px(4) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val value = (p + min).coerceIn(min, max)
                    valueText.text = formatter(value)
                    onChange(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        container.addView(seekBar)
        return container
    }

    private fun createHandle(): LinearLayout {
        val handle = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(10))
        }
        val bar = View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(999).toFloat()
                setColor(Color.parseColor("#3A4452"))
            }
            val params = LinearLayout.LayoutParams(px(36), px(4)).also {
                it.gravity = Gravity.CENTER
            }
            layoutParams = params
        }
        handle.addView(bar)
        return handle
    }

    private fun label(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#7E8B9B"))
            setPadding(0, px(4), 0, px(4))
        }
    }

    private fun toolRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(2), 0, px(8))
        }
    }

    private fun displayChipColor(color: Int): Int {
        return if (Color.alpha(color) == 0) chipIdle else color
    }

    private fun readableTextColor(color: Int): Int {
        if (Color.alpha(color) == 0) return Color.WHITE
        return if (ColorUtils.calculateLuminance(color) > 0.4) Color.BLACK else Color.WHITE
    }

    private fun px(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).roundToInt()
    }
}
