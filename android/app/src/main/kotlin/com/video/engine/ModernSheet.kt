package com.video.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

/**
 * Modern bottom sheet builder — dark themed, used for Speed, Volume, Chroma etc.
 */
object ModernSheet {

    fun show(context: Context, title: String, build: Builder.() -> Unit) {
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetRootBackground(context)
            setPadding(px(context, 20), px(context, 16), px(context, 20), px(context, 30))
        }

        // Title
        root.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(context, 16))
        })

        // Drag handle
        val handle = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(context, 12))
        }
        handle.addView(TextView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(context, 999).toFloat()
                setColor(Color.parseColor("#4A5561"))
            }
            layoutParams = LinearLayout.LayoutParams(px(context, 40), px(context, 4)).also {
                it.gravity = Gravity.CENTER
            }
        })
        root.addView(handle, 0)

        Builder(context, root, dialog).build()
        dialog.setContentView(root)
        dialog.show()
    }

    class Builder(
        private val context: Context,
        private val root: LinearLayout,
        private val dialog: BottomSheetDialog,
    ) {
        private var textInputView: EditText? = null

        fun textInput(label: String, hint: String, onChange: (String) -> Unit) {
            root.addView(TextView(context).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor("#8E99A5"))
                setPadding(0, px(context, 8), 0, px(context, 4))
            })
            val edit = EditText(context).apply {
                this.hint = hint
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#6F7E8B"))
                background = inputBackground(context)
                setPadding(px(context, 12), px(context, 10), px(context, 12), px(context, 10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = px(context, 8) }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { onChange(s.toString()) }
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                })
            }
            textInputView = edit
            root.addView(edit)
        }

        fun getTextInput(): String = textInputView?.text?.toString() ?: ""
        fun slider(
            label: String,
            min: Float, max: Float, value: Float,
            format: (Float) -> String = { "%.2f".format(it) },
            onChange: (Float) -> Unit,
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = surfaceCard(context)
                setPadding(px(context, 12), px(context, 10), px(context, 12), px(context, 10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = px(context, 6) }
            }
            val labelView = TextView(context).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor("#8E99A5"))
                layoutParams = LinearLayout.LayoutParams(px(context, 90), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val valueView = TextView(context).apply {
                text = format(value); textSize = 12f; setTextColor(Color.WHITE)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(px(context, 60), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val seek = SeekBar(context).apply {
                this.max = 100
                progress = ((value - min) / (max - min) * 100).roundToInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6FDBFF"))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                        val v = min + (max - min) * p / 100f
                        valueView.text = format(v)
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            row.addView(labelView)
            row.addView(seek)
            row.addView(valueView)
            root.addView(row)
        }

        fun chips(label: String, options: List<String>, selected: Int = -1, onSelect: (Int, String) -> Unit) {
            root.addView(TextView(context).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor("#8E99A5"))
                setPadding(0, px(context, 8), 0, px(context, 4))
            })
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            options.forEachIndexed { i, opt ->
                val chip = TextView(context).apply {
                    text = opt; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = chipBackground(context, i == selected)
                    setPadding(px(context, 16), px(context, 8), px(context, 16), px(context, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(px(context, 4), 0, px(context, 4), 0) }
                    setOnClickListener { onSelect(i, opt); dialog.dismiss() }
                }
                row.addView(chip)
            }
            root.addView(row)
        }

        fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = surfaceCard(context)
                setPadding(px(context, 12), px(context, 10), px(context, 12), px(context, 10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = px(context, 6) }
            }
            row.addView(TextView(context).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Switch(context).apply {
                isChecked = checked
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6FDBFF"))
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
            root.addView(row)
        }

        fun divider() {
            root.addView(TextView(context).apply {
                setBackgroundColor(Color.parseColor("#222A33"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(context, 1)
                ).also { it.setMargins(0, px(context, 8), 0, px(context, 8)) }
            })
        }

        fun dismiss() = dialog.dismiss()
    }

    private fun px(context: Context, dp: Int) =
        (dp * context.resources.displayMetrics.density).roundToInt()

    private fun sheetRootBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(px(context, 26).toFloat(), px(context, 26).toFloat(), px(context, 26).toFloat(), px(context, 26).toFloat(), 0f, 0f, 0f, 0f)
            setColor(Color.parseColor("#0B0F13"))
            setStroke(px(context, 1), Color.parseColor("#1B222A"))
        }

    private fun surfaceCard(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(context, 16).toFloat()
            setColor(Color.parseColor("#131920"))
            setStroke(px(context, 1), Color.parseColor("#252F38"))
        }

    private fun inputBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(context, 14).toFloat()
            setColor(Color.parseColor("#11161C"))
            setStroke(px(context, 1), Color.parseColor("#27323B"))
        }

    private fun chipBackground(context: Context, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(context, 14).toFloat()
            if (selected) {
                setColor(Color.parseColor("#152330"))
                setStroke(px(context, 1), Color.parseColor("#6FDBFF"))
            } else {
                setColor(Color.parseColor("#12171D"))
                setStroke(px(context, 1), Color.parseColor("#252F38"))
            }
        }
}
