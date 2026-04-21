package com.video.engine

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.overlay.TextOverlay
import kotlin.math.roundToInt

class TextEditorPanel(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val overlay: TextOverlay,
    private val onDone: (TextOverlay) -> Unit = {},
    private val onDuplicate: (TextOverlay) -> Unit = {},
    private val onDelete: (TextOverlay) -> Unit = {},
) {
    private lateinit var dialog: BottomSheetDialog

    fun show() {
        val ov = overlay  // capture to avoid 'apply' scope shadowing
        dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(px(20), px(16), px(20), px(32))
        }

        // Drag handle
        val handle = LinearLayout(activity).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(12)) }
        handle.addView(View(activity).apply {
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(px(40), px(4)).also { it.gravity = Gravity.CENTER }
        })
        root.addView(handle)

        // Title
        root.addView(TextView(activity).apply {
            text = "Edit Text"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(16))
        })

        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // ── Text input ──
        container.addView(label("Text Content"))
        val textEdit = EditText(activity).apply {
            setText(ov.text)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            hint = "Enter text"
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(px(12), px(10), px(12), px(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(80)
            ).also { it.bottomMargin = px(12) }
        }
        container.addView(textEdit)

        // ── Font size ──
        container.addView(label("Font Size"))
        val sizeVal = TextView(activity).apply {
            text = "${ov.fontSize.toInt()}pt"
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(px(60), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(4), 0, px(12))
        }
        val sizeSeek = SeekBar(activity).apply {
            max = 60; progress = (ov.fontSize - 12).toInt().coerceIn(0, 60)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF4444"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(seekListener { v ->
                ov.fontSize = (v * 60f / 100f + 12f)
                sizeVal.text = "${ov.fontSize.toInt()}pt"
                updatePreview()
            })
        }
        sizeRow.addView(sizeSeek); sizeRow.addView(sizeVal)
        container.addView(sizeRow)

        // ── Opacity ──
        container.addView(label("Opacity"))
        val opacityVal = TextView(activity).apply {
            text = "${(ov.opacity * 100).toInt()}%"
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(px(60), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val opacityRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(4), 0, px(12))
        }
        val opacitySeek = SeekBar(activity).apply {
            max = 100; progress = (ov.opacity * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF4444"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(seekListener { v ->
                ov.opacity = v / 100f
                opacityVal.text = "$v%"
                updatePreview()
            })
        }
        opacityRow.addView(opacitySeek); opacityRow.addView(opacityVal)
        container.addView(opacityRow)

        // ── Color ──
        container.addView(label("Color"))
        val colorRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(4), 0, px(12))
        }
        listOf(
            Color.WHITE to "White", Color.BLACK to "Black",
            Color.RED to "Red", Color.YELLOW to "Yellow",
            Color.CYAN to "Cyan", 0xFF00CC44.toInt() to "Green"
        ).forEach { (color, name) ->
            colorRow.addView(TextView(activity).apply {
                text = name; textSize = 11f; gravity = Gravity.CENTER
                setBackgroundColor(color)
                setTextColor(if (ColorUtils.calculateLuminance(color) > 0.4) Color.BLACK else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, px(36), 1f)
                    .also { it.setMargins(px(2), 0, px(2), 0) }
                setOnClickListener { ov.color = color; updatePreview() }
            })
        }
        container.addView(colorRow)

        // ── Bold / Italic ──
        container.addView(label("Style"))
        val styleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(4), 0, px(12))
        }

        fun toggleBtn(text: String, active: Boolean, onToggle: (Boolean) -> Unit): TextView {
            var state = active
            return TextView(activity).apply {
                this.text = text; textSize = 14f; gravity = Gravity.CENTER
                setTypeface(null, if (text == "Bold") Typeface.BOLD else Typeface.ITALIC)
                setTextColor(if (state) Color.BLACK else Color.WHITE)
                setBackgroundColor(if (state) Color.WHITE else Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, px(40), 1f)
                    .also { it.setMargins(0, 0, px(8), 0) }
                setOnClickListener {
                    state = !state
                    setTextColor(if (state) Color.BLACK else Color.WHITE)
                    setBackgroundColor(if (state) Color.WHITE else Color.parseColor("#333333"))
                    onToggle(state)
                    updatePreview()
                }
            }
        }
        styleRow.addView(toggleBtn("Bold", ov.bold) { ov.bold = it })
        styleRow.addView(toggleBtn("Italic", ov.italic) { ov.italic = it })
        container.addView(styleRow)

        // ── Actions ──
        container.addView(divider())
        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(8), 0, 0)
        }
        fun actionBtn(text: String, bg: Int, fg: Int, action: () -> Unit) =
            TextView(activity).apply {
                this.text = text; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(fg); setBackgroundColor(bg)
                layoutParams = LinearLayout.LayoutParams(0, px(44), 1f)
                    .also { it.setMargins(px(4), 0, px(4), 0) }
                setOnClickListener { action() }
            }

        actionsRow.addView(actionBtn("Duplicate", Color.parseColor("#2A2A2A"), Color.WHITE) {
            ov.text = textEdit.text.toString().ifEmpty { "Text" }
            updatePreview(); onDuplicate(overlay); dialog.dismiss()
        })
        actionsRow.addView(actionBtn("Delete", Color.parseColor("#FF3333"), Color.WHITE) {
            onDelete(overlay); previewView.removeTextOverlay(ov.id); dialog.dismiss()
        })
        actionsRow.addView(actionBtn("Done", Color.parseColor("#2196F3"), Color.WHITE) {
            ov.text = textEdit.text.toString().ifEmpty { "Text" }
            updatePreview(); onDone(overlay); dialog.dismiss()
        })
        container.addView(actionsRow)

        scroll.addView(container)
        root.addView(scroll)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun updatePreview() {
        try {
            previewView.updateTextOverlay(
                overlay.id, overlay.x, overlay.y, overlay.scale,
                overlay.rotation, overlay.color, overlay.fontSize,
                overlay.startTimeMs, overlay.endTimeMs
            )
            previewView.updateTextOverlayOpacity(overlay.id, overlay.opacity, 0, 0)
            NativeBridge.setTextOverlayBitmap(previewView, overlay)
        } catch (e: Exception) {
            android.util.Log.w("[TextEditor]", "updatePreview: ${e.message}")
        }
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text; textSize = 12f
        setTextColor(Color.parseColor("#AAAAAA"))
        setPadding(0, px(4), 0, px(2))
    }

    private fun divider() = View(activity).apply {
        setBackgroundColor(Color.parseColor("#333333"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
            .also { it.setMargins(0, px(8), 0, px(8)) }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun px(dp: Int) = (dp * activity.resources.displayMetrics.density).roundToInt()
}
