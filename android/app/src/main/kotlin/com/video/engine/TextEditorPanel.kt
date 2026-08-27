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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.overlay.TextOverlay
import kotlin.math.roundToInt

class TextEditorPanel(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val overlay: TextOverlay,
    private val onLiveUpdate: (TextOverlay, Boolean) -> Unit = { _, _ -> },
    private val onDone: (TextOverlay) -> Unit = {},
    private val onDuplicate: (TextOverlay) -> Unit = {},
    private val onDelete: (TextOverlay) -> Unit = {},
) {
    private data class ChipSpec(val label: String, val color: Int)
    private data class FontSpec(val label: String, val fontName: String)

    private lateinit var dialog: BottomSheetDialog
    private var isApplyingPreview = false
    private val darkPanel = Color.parseColor("#11151B")
    private val chipIdle = Color.parseColor("#171C23")

    fun show() {
        dialog = BottomSheetDialog(activity)
        dialog.setCanceledOnTouchOutside(false)

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(px(24).toFloat(), px(24).toFloat(), px(24).toFloat(), px(24).toFloat(), 0f, 0f, 0f, 0f)
            setColor(darkPanel)
            setStroke(px(1), Color.parseColor("#1B222C"))
        }
        root.setPadding(px(20), px(16), px(20), px(32))
        root.addView(createHandle())
        root.addView(createTitle())

        val scroll = ScrollView(activity)
        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL

        val textEdit = addTextInput(container)
        addFontSizeControl(container)
        addOpacityControl(container)
        addFontControls(container)
        addTextColorControls(container)
        addGradientControls(container)
        addBackgroundControls(container)
        addStrokeAndDepthControls(container)
        addShadowControls(container)
        addStyleControls(container)
        addActions(container, textEdit)

        scroll.addView(container)
        root.addView(scroll)
        dialog.setContentView(root)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.44f, maxRatio = 0.62f)
        dialog.show()
    }

    private fun addTextInput(container: LinearLayout): EditText {
        container.addView(label("Text Content"))
        val editText = EditText(activity)
        editText.setText(overlay.text)
        editText.setTextColor(Color.WHITE)
        editText.setHintTextColor(Color.parseColor("#666666"))
        editText.hint = "Enter text"
        editText.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = px(12).toFloat()
            setColor(Color.parseColor("#141920"))
            setStroke(px(1), Color.parseColor("#1E2632"))
        }
        editText.setPadding(px(12), px(10), px(12), px(10))
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(80))
        params.bottomMargin = px(12)
        editText.layoutParams = params
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isApplyingPreview) return
                overlay.text = s?.toString().orEmpty().ifEmpty { "Text" }
                applyPreviewChanges(refreshTimelineLabel = false)
            }
        })
        container.addView(editText)
        return editText
    }

    private fun addFontSizeControl(container: LinearLayout) {
        container.addView(label("Font Size"))
        container.addView(
            sliderRow(
                title = "Size",
                min = 12,
                max = 72,
                initial = overlay.fontSize.roundToInt().coerceIn(12, 72),
                formatter = { value: Int -> "${value}pt" },
                onChange = { value: Int ->
                    overlay.fontSize = value.toFloat()
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    private fun addOpacityControl(container: LinearLayout) {
        container.addView(label("Opacity"))
        container.addView(
            sliderRow(
                title = "Opacity",
                min = 0,
                max = 100,
                initial = (overlay.opacity * 100f).roundToInt().coerceIn(0, 100),
                formatter = { value: Int -> "$value%" },
                onChange = { value: Int ->
                    overlay.opacity = value / 100f
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    private fun addFontControls(container: LinearLayout) {
        container.addView(label("Font"))
        val row = toolRow()
        val specs = listOf(
            FontSpec("Sans", "sans-serif"),
            FontSpec("Serif", "serif"),
            FontSpec("Mono", "monospace"),
            FontSpec("Condensed", "sans-serif-condensed"),
        )
        val buttons = mutableListOf<TextView>()
        fun refreshButtons() {
            val current = overlay.fontName ?: "sans-serif"
            for (button in buttons) {
                val active = button.tag == current
                button.setTextColor(if (active) Color.BLACK else Color.WHITE)
                button.setBackgroundColor(if (active) Color.WHITE else chipIdle)
            }
        }
        for (spec in specs) {
            val button = createChip(spec.label, chipIdle)
            button.tag = spec.fontName
            button.setOnClickListener {
                overlay.fontName = spec.fontName
                refreshButtons()
                applyPreviewChanges(refreshTimelineLabel = false)
            }
            buttons.add(button)
            row.addView(button)
        }
        refreshButtons()
        container.addView(row)
    }

    private fun addTextColorControls(container: LinearLayout) {
        container.addView(label("Color"))
        addColorChipRow(
            container = container,
            chips = listOf(
                ChipSpec("White", Color.WHITE),
                ChipSpec("Black", Color.BLACK),
                ChipSpec("Red", Color.RED),
                ChipSpec("Yellow", Color.YELLOW),
                ChipSpec("Cyan", Color.CYAN),
                ChipSpec("Green", 0xFF00CC44.toInt()),
                ChipSpec("Pink", 0xFFFF6EC7.toInt()),
                ChipSpec("Amber", 0xFFFFB74D.toInt()),
            ),
            onPick = { color: Int ->
                overlay.color = color
                applyPreviewChanges(refreshTimelineLabel = false)
            },
        )

        container.addView(label("Text RGB"))
        container.addView(colorSliderRow("Red", Color.red(overlay.color)) { value: Int ->
            overlay.color = Color.argb(Color.alpha(overlay.color), value, Color.green(overlay.color), Color.blue(overlay.color))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Green", Color.green(overlay.color)) { value: Int ->
            overlay.color = Color.argb(Color.alpha(overlay.color), Color.red(overlay.color), value, Color.blue(overlay.color))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Blue", Color.blue(overlay.color)) { value: Int ->
            overlay.color = Color.argb(Color.alpha(overlay.color), Color.red(overlay.color), Color.green(overlay.color), value)
            applyPreviewChanges(refreshTimelineLabel = false)
        })
    }

    private fun addGradientControls(container: LinearLayout) {
        container.addView(label("Gradient"))
        addColorChipRow(
            container = container,
            chips = listOf(
                ChipSpec("Off", Color.TRANSPARENT),
                ChipSpec("Sky", 0xFF35C7FF.toInt()),
                ChipSpec("Fire", 0xFFFF4D6D.toInt()),
                ChipSpec("Neon", 0xFFB967FF.toInt()),
                ChipSpec("Gold", 0xFFFFB000.toInt()),
            ),
            onPick = { color: Int ->
                if (color == Color.TRANSPARENT) {
                    overlay.gradientEnabled = false
                } else {
                    overlay.gradientEnabled = true
                    overlay.gradientStartColor = when (color) {
                        0xFFFF4D6D.toInt() -> 0xFFFFD166.toInt()
                        0xFFB967FF.toInt() -> 0xFF00F5FF.toInt()
                        0xFFFFB000.toInt() -> 0xFFFFF3B0.toInt()
                        else -> Color.WHITE
                    }
                    overlay.gradientEndColor = color
                }
                applyPreviewChanges(refreshTimelineLabel = false)
            },
        )
        container.addView(colorSliderRow("Start R", Color.red(overlay.gradientStartColor)) { value: Int ->
            overlay.gradientEnabled = true
            overlay.gradientStartColor = Color.argb(Color.alpha(overlay.gradientStartColor), value, Color.green(overlay.gradientStartColor), Color.blue(overlay.gradientStartColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Start G", Color.green(overlay.gradientStartColor)) { value: Int ->
            overlay.gradientEnabled = true
            overlay.gradientStartColor = Color.argb(Color.alpha(overlay.gradientStartColor), Color.red(overlay.gradientStartColor), value, Color.blue(overlay.gradientStartColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Start B", Color.blue(overlay.gradientStartColor)) { value: Int ->
            overlay.gradientEnabled = true
            overlay.gradientStartColor = Color.argb(Color.alpha(overlay.gradientStartColor), Color.red(overlay.gradientStartColor), Color.green(overlay.gradientStartColor), value)
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("End R", Color.red(overlay.gradientEndColor)) { value: Int ->
            overlay.gradientEnabled = true
            overlay.gradientEndColor = Color.argb(Color.alpha(overlay.gradientEndColor), value, Color.green(overlay.gradientEndColor), Color.blue(overlay.gradientEndColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("End G", Color.green(overlay.gradientEndColor)) { value: Int ->
            overlay.gradientEnabled = true
            overlay.gradientEndColor = Color.argb(Color.alpha(overlay.gradientEndColor), Color.red(overlay.gradientEndColor), value, Color.blue(overlay.gradientEndColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("End B", Color.blue(overlay.gradientEndColor)) { value: Int ->
            overlay.gradientEnabled = true
            overlay.gradientEndColor = Color.argb(Color.alpha(overlay.gradientEndColor), Color.red(overlay.gradientEndColor), Color.green(overlay.gradientEndColor), value)
            applyPreviewChanges(refreshTimelineLabel = false)
        })
    }

    private fun addBackgroundControls(container: LinearLayout) {
        container.addView(label("Background"))
        addColorChipRow(
            container = container,
            chips = listOf(
                ChipSpec("None", Color.TRANSPARENT),
                ChipSpec("Black", 0x99000000.toInt()),
                ChipSpec("White", 0xCCFFFFFF.toInt()),
                ChipSpec("Yellow", 0xCCFFEB3B.toInt()),
                ChipSpec("Blue", 0xCC0D47A1.toInt()),
                ChipSpec("Red", 0xCCB71C1C.toInt()),
            ),
            onPick = { color: Int ->
                overlay.backgroundColor = color
                applyPreviewChanges(refreshTimelineLabel = false)
            },
        )

        container.addView(label("Background RGB"))
        container.addView(colorSliderRow("Alpha", Color.alpha(overlay.backgroundColor)) { value: Int ->
            overlay.backgroundColor = Color.argb(
                value,
                Color.red(overlay.backgroundColor),
                Color.green(overlay.backgroundColor),
                Color.blue(overlay.backgroundColor),
            )
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Red", Color.red(overlay.backgroundColor)) { value: Int ->
            val alpha = nonZeroBackgroundAlpha()
            overlay.backgroundColor = Color.argb(alpha, value, Color.green(overlay.backgroundColor), Color.blue(overlay.backgroundColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Green", Color.green(overlay.backgroundColor)) { value: Int ->
            val alpha = nonZeroBackgroundAlpha()
            overlay.backgroundColor = Color.argb(alpha, Color.red(overlay.backgroundColor), value, Color.blue(overlay.backgroundColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(colorSliderRow("Blue", Color.blue(overlay.backgroundColor)) { value: Int ->
            val alpha = nonZeroBackgroundAlpha()
            overlay.backgroundColor = Color.argb(alpha, Color.red(overlay.backgroundColor), Color.green(overlay.backgroundColor), value)
            applyPreviewChanges(refreshTimelineLabel = false)
        })
        container.addView(
            sliderRow(
                title = "Padding",
                min = 0,
                max = 96,
                initial = overlay.backgroundPadding.roundToInt().coerceIn(0, 96),
                formatter = { value: Int -> if (value == 0) "Auto" else "${value}px" },
                onChange = { value: Int ->
                    overlay.backgroundPadding = value.toFloat()
                    if (value > 0 && Color.alpha(overlay.backgroundColor) == 0) {
                        overlay.backgroundColor = 0x99000000.toInt()
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        container.addView(
            sliderRow(
                title = "Round",
                min = 0,
                max = 72,
                initial = overlay.backgroundCornerRadius.roundToInt().coerceIn(0, 72),
                formatter = { value: Int -> if (value == 0) "Auto" else "${value}px" },
                onChange = { value: Int ->
                    overlay.backgroundCornerRadius = value.toFloat()
                    if (value > 0 && Color.alpha(overlay.backgroundColor) == 0) {
                        overlay.backgroundColor = 0x99000000.toInt()
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
    }

    private fun addStrokeAndDepthControls(container: LinearLayout) {
        container.addView(label("Stroke"))
        container.addView(
            sliderRow(
                title = "Width",
                min = 0,
                max = 18,
                initial = overlay.strokeWidth.roundToInt().coerceIn(0, 18),
                formatter = { value: Int -> "${value}px" },
                onChange = { value: Int ->
                    overlay.strokeWidth = value.toFloat()
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        addColorChipRow(
            container = container,
            chips = listOf(
                ChipSpec("Off", Color.TRANSPARENT),
                ChipSpec("Black", Color.BLACK),
                ChipSpec("White", Color.WHITE),
                ChipSpec("Gold", 0xFFFFD54F.toInt()),
                ChipSpec("Cyan", 0xFF00E5FF.toInt()),
            ),
            onPick = { color: Int ->
                if (color == Color.TRANSPARENT) {
                    overlay.strokeWidth = 0f
                } else {
                    overlay.strokeColor = color
                    overlay.strokeWidth = overlay.strokeWidth.coerceAtLeast(4f)
                }
                applyPreviewChanges(refreshTimelineLabel = false)
            },
        )

        container.addView(label("3D Depth"))
        container.addView(
            sliderRow(
                title = "Depth",
                min = 0,
                max = 24,
                initial = overlay.depthPx.roundToInt().coerceIn(0, 24),
                formatter = { value: Int -> "${value}px" },
                onChange = { value: Int ->
                    overlay.depthPx = value.toFloat()
                    if (value > 0 && Color.alpha(overlay.depthColor) == 0) {
                        overlay.depthColor = 0x99000000.toInt()
                    }
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        container.addView(colorSliderRow("Depth Alpha", Color.alpha(overlay.depthColor)) { value: Int ->
            overlay.depthColor = Color.argb(value, Color.red(overlay.depthColor), Color.green(overlay.depthColor), Color.blue(overlay.depthColor))
            applyPreviewChanges(refreshTimelineLabel = false)
        })
    }

    private fun addShadowControls(container: LinearLayout) {
        container.addView(label("Shadow"))
        addColorChipRow(
            container = container,
            chips = listOf(
                ChipSpec("Off", Color.TRANSPARENT),
                ChipSpec("Soft", 0x88000000.toInt()),
                ChipSpec("Strong", 0xCC000000.toInt()),
                ChipSpec("Glow", 0xAA00E5FF.toInt()),
            ),
            onPick = { color: Int ->
                if (color == Color.TRANSPARENT) {
                    overlay.shadowEnabled = false
                    overlay.shadowBlur = 0f
                } else {
                    overlay.shadowEnabled = true
                    overlay.shadowColor = color
                    overlay.shadowBlur = overlay.shadowBlur.coerceAtLeast(6f)
                    overlay.shadowOffsetY = overlay.shadowOffsetY.takeIf { it != 0f } ?: 3f
                }
                applyPreviewChanges(refreshTimelineLabel = false)
            },
        )
        container.addView(
            sliderRow(
                title = "Blur",
                min = 0,
                max = 40,
                initial = overlay.shadowBlur.roundToInt().coerceIn(0, 40),
                formatter = { value: Int -> if (value == 0) "Off" else "${value}px" },
                onChange = { value: Int ->
                    overlay.shadowBlur = value.toFloat()
                    overlay.shadowEnabled = value > 0
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        container.addView(
            sliderRow(
                title = "Offset X",
                min = -40,
                max = 40,
                initial = overlay.shadowOffsetX.roundToInt().coerceIn(-40, 40),
                formatter = { value: Int -> "${value}px" },
                onChange = { value: Int ->
                    overlay.shadowOffsetX = value.toFloat()
                    overlay.shadowEnabled = overlay.shadowBlur > 0f
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        container.addView(
            sliderRow(
                title = "Offset Y",
                min = -40,
                max = 40,
                initial = overlay.shadowOffsetY.roundToInt().coerceIn(-40, 40),
                formatter = { value: Int -> "${value}px" },
                onChange = { value: Int ->
                    overlay.shadowOffsetY = value.toFloat()
                    overlay.shadowEnabled = overlay.shadowBlur > 0f
                    applyPreviewChanges(refreshTimelineLabel = false)
                },
            ),
        )
        container.addView(colorSliderRow("Alpha", Color.alpha(overlay.shadowColor)) { value: Int ->
            overlay.shadowColor = Color.argb(value, Color.red(overlay.shadowColor), Color.green(overlay.shadowColor), Color.blue(overlay.shadowColor))
            overlay.shadowEnabled = value > 0 && overlay.shadowBlur > 0f
            applyPreviewChanges(refreshTimelineLabel = false)
        })
    }

    private fun addStyleControls(container: LinearLayout) {
        container.addView(label("Style"))
        val row = toolRow()
        row.addView(toggleButton("Bold", overlay.bold, Typeface.BOLD) { active: Boolean -> overlay.bold = active })
        row.addView(toggleButton("Italic", overlay.italic, Typeface.ITALIC) { active: Boolean -> overlay.italic = active })
        row.addView(toggleButton("Under", overlay.underline, Typeface.NORMAL) { active: Boolean -> overlay.underline = active })
        row.addView(toggleButton("Caps", overlay.allCaps, Typeface.BOLD) { active: Boolean -> overlay.allCaps = active })
        container.addView(row)
    }

    private fun addActions(container: LinearLayout, editText: EditText) {
        container.addView(divider())
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, px(8), 0, 0)
        row.addView(actionButton("Duplicate", Color.parseColor("#171C23"), Color.WHITE, Color.parseColor("#222A36")) {
            overlay.text = editText.text.toString().ifEmpty { "Text" }
            applyPreviewChanges(refreshTimelineLabel = true)
            onDuplicate(overlay)
            dialog.dismiss()
        })
        row.addView(actionButton("Delete", Color.parseColor("#1A1214"), Color.parseColor("#F85149"), Color.parseColor("#4A1E22")) {
            onDelete(overlay)
            previewView.removeTextOverlay(overlay.id)
            dialog.dismiss()
        })
        row.addView(actionButton("Done", Color.parseColor("#1F6FEB"), Color.WHITE, Color.parseColor("#388BFD")) {
            overlay.text = editText.text.toString().ifEmpty { "Text" }
            applyPreviewChanges(refreshTimelineLabel = true)
            onDone(overlay)
            dialog.dismiss()
        })
        container.addView(row)
    }

    private fun applyPreviewChanges(refreshTimelineLabel: Boolean) {
        isApplyingPreview = true
        try {
            onLiveUpdate(overlay, refreshTimelineLabel)
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
            previewView.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
            NativeBridge.setTextOverlayBitmap(previewView, overlay)
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
        val row = toolRow()
        for (chip in chips) {
            val color = chip.color
            val button = createChip(chip.label, displayChipColor(color))
            button.setTextColor(readableTextColor(color))
            button.setOnClickListener { onPick(color) }
            row.addView(button)
        }
        container.addView(row)
    }

    private fun createChip(text: String, backgroundColor: Int): TextView {
        val view = TextView(activity)
        view.text = text
        view.textSize = 11f
        view.gravity = Gravity.CENTER
        view.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = px(6).toFloat()
            setColor(backgroundColor)
        }
        view.setTextColor(readableTextColor(backgroundColor))
        val params = LinearLayout.LayoutParams(0, px(36), 1f)
        params.setMargins(px(2), 0, px(2), 0)
        view.layoutParams = params
        return view
    }

    private fun toggleButton(
        text: String,
        active: Boolean,
        style: Int,
        onToggle: (Boolean) -> Unit,
    ): TextView {
        val view = createChip(text, if (active) Color.WHITE else chipIdle)
        view.textSize = 12f
        view.setTypeface(null, style)
        var current = active
        fun refresh() {
            view.setTextColor(if (current) Color.BLACK else Color.WHITE)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(6).toFloat()
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
        val view = TextView(activity)
        view.text = text
        view.textSize = 13f
        view.gravity = Gravity.CENTER
        view.setTypeface(null, Typeface.BOLD)
        view.setTextColor(textColor)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = px(8).toFloat()
            setColor(backgroundColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(px(1), strokeColor)
            }
        }
        val params = LinearLayout.LayoutParams(0, px(44), 1f)
        params.setMargins(px(4), 0, px(4), 0)
        view.layoutParams = params
        view.setOnClickListener { action() }
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
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, px(2), 0, px(8))

        val titleView = TextView(activity)
        titleView.text = title
        titleView.textSize = 12f
        titleView.setTextColor(Color.parseColor("#8A99AD"))
        titleView.layoutParams = LinearLayout.LayoutParams(px(74), LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(titleView)

        val valueText = TextView(activity)
        valueText.text = formatter(safeInitial)
        valueText.textSize = 12f
        valueText.setTextColor(Color.WHITE)
        valueText.gravity = Gravity.END
        valueText.layoutParams = LinearLayout.LayoutParams(px(64), LinearLayout.LayoutParams.WRAP_CONTENT)

        val seekBar = SeekBar(activity)
        seekBar.max = (max - min).coerceAtLeast(1)
        seekBar.progress = safeInitial - min
        seekBar.progressTintList = ColorStateList.valueOf(Color.parseColor("#388BFD"))
        seekBar.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seekBar.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        seekBar.setOnSeekBarChangeListener(seekListener { progress: Int ->
            val value = (progress + min).coerceIn(min, max)
            valueText.text = formatter(value)
            onChange(value)
        })
        row.addView(seekBar)
        row.addView(valueText)
        return row
    }

    private fun colorSliderRow(title: String, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        return sliderRow(
            title = title,
            min = 0,
            max = 255,
            initial = initial.coerceIn(0, 255),
            formatter = { value: Int -> value.toString() },
            onChange = onChange,
        )
    }

    private fun createHandle(): LinearLayout {
        val handle = LinearLayout(activity)
        handle.gravity = Gravity.CENTER
        handle.setPadding(0, 0, 0, px(12))
        val bar = View(activity)
        bar.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = px(999).toFloat()
            setColor(Color.parseColor("#3A4452"))
        }
        val params = LinearLayout.LayoutParams(px(36), px(4))
        params.gravity = Gravity.CENTER
        bar.layoutParams = params
        handle.addView(bar)
        return handle
    }

    private fun createTitle(): TextView {
        val view = TextView(activity)
        view.text = "Edit Text"
        view.textSize = 16f
        view.setTypeface(null, Typeface.BOLD)
        view.setTextColor(Color.WHITE)
        view.gravity = Gravity.CENTER
        view.setPadding(0, 0, 0, px(16))
        return view
    }

    private fun label(text: String): TextView {
        val view = TextView(activity)
        view.text = text
        view.textSize = 12f
        view.setTextColor(Color.parseColor("#AAAAAA"))
        view.setPadding(0, px(4), 0, px(2))
        return view
    }

    private fun divider(): View {
        val view = View(activity)
        view.setBackgroundColor(Color.parseColor("#333333"))
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
        params.setMargins(0, px(8), 0, px(8))
        view.layoutParams = params
        return view
    }

    private fun toolRow(): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, px(4), 0, px(12))
        return row
    }

    private fun seekListener(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = onChange(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        }
    }

    private fun nonZeroBackgroundAlpha(): Int {
        val alpha = Color.alpha(overlay.backgroundColor)
        return if (alpha > 0) alpha else 204
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
