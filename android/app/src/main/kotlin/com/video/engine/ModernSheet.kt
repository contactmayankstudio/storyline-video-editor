package com.video.engine

import android.content.Context
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

/**
 * Modern bottom sheet builder — dark themed, used for Speed, Volume, Chroma etc.
 */
object ModernSheet {

    private const val DEFAULT_PEEK_RATIO = 0.40f
    private const val DEFAULT_MAX_RATIO = 0.56f

    fun show(context: Context, title: String, build: Builder.() -> Unit) {
        val dialog = BottomSheetDialog(context)
        dialog.setCanceledOnTouchOutside(false)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetRootBackground(context)
            setPadding(px(context, 20), px(context, 16), px(context, 20), 0)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(context, 30))
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        root.addView(FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(TextView(context).apply {
                text = title
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(px(context, 44), 0, px(context, 44), px(context, 16))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
            })
            addView(TextView(context).apply {
                text = "Close"
                textSize = 12f
                setTextColor(Color.parseColor("#8E99A5"))
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(px(context, 8), px(context, 2), px(context, 8), px(context, 12))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                )
                setOnClickListener { dialog.dismiss() }
            })
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
        root.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.weight = 1f },
        )

        Builder(context, content, dialog).build()
        dialog.setContentView(root)
        applyEditorBehavior(dialog, context)
        dialog.show()
    }

    fun applyEditorBehavior(
        dialog: BottomSheetDialog,
        context: Context,
        peekRatio: Float = DEFAULT_PEEK_RATIO,
        maxRatio: Float = DEFAULT_MAX_RATIO,
    ) {
        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val attrs = window.attributes
                    attrs.blurBehindRadius = 0
                    window.attributes = attrs
                    window.setBackgroundBlurRadius(0)
                }
            }
            val bottomSheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                    ?: return@setOnShowListener
            val screenHeight = context.resources.displayMetrics.heightPixels
            val maxHeight = (screenHeight * maxRatio.coerceIn(0.35f, 0.92f)).roundToInt()
            val peekHeight = (screenHeight * peekRatio.coerceIn(0.25f, maxRatio)).roundToInt().coerceAtMost(maxHeight)

            bottomSheet.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet.layoutParams =
                bottomSheet.layoutParams.apply {
                    height = maxHeight
                }

            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.isFitToContents = true
            behavior.skipCollapsed = false
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.peekHeight = peekHeight
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
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

        fun chips(
            label: String,
            options: List<String>,
            selected: Int = -1,
            dismissOnSelect: Boolean = false,
            onSelect: (Int, String) -> Unit,
        ) {
            root.addView(TextView(context).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor("#8E99A5"))
                setPadding(0, px(context, 8), 0, px(context, 4))
            })
            val scroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, 0, px(context, 8), 0)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
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
                    setOnClickListener {
                        onSelect(i, opt)
                        if (dismissOnSelect) dialog.dismiss()
                    }
                }
                row.addView(chip)
            }
            scroller.addView(row)
            root.addView(scroller)
        }

        fun selectableChips(
            label: String,
            options: List<String>,
            selected: Int = -1,
            dismissOnSelect: Boolean = false,
            onSelect: (Int, String) -> Unit,
        ): (Int) -> Unit {
            root.addView(TextView(context).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor("#8E99A5"))
                setPadding(0, px(context, 8), 0, px(context, 4))
            })
            val scroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, 0, px(context, 8), 0)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            val chipViews = mutableListOf<TextView>()
            fun render(selectedIndex: Int) {
                chipViews.forEachIndexed { i, chip ->
                    chip.background = chipBackground(context, i == selectedIndex)
                    chip.setTypeface(null, if (i == selectedIndex) Typeface.BOLD else Typeface.NORMAL)
                }
            }
            options.forEachIndexed { i, opt ->
                val chip = TextView(context).apply {
                    text = opt; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(px(context, 16), px(context, 8), px(context, 16), px(context, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(px(context, 4), 0, px(context, 4), 0) }
                    setOnClickListener {
                        render(i)
                        onSelect(i, opt)
                        if (dismissOnSelect) dialog.dismiss()
                    }
                }
                chipViews.add(chip)
                row.addView(chip)
            }
            render(selected)
            scroller.addView(row)
            root.addView(scroller)
            return { selectedIndex -> render(selectedIndex) }
        }

        fun chipGrid(
            label: String,
            options: List<String>,
            selected: Int = -1,
            columns: Int = 4,
            dismissOnSelect: Boolean = false,
            onSelect: (Int, String) -> Unit,
        ) {
            root.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#8E99A5"))
                setPadding(0, px(context, 8), 0, px(context, 4))
            })

            val columnCount = columns.coerceAtLeast(1)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            options.chunked(columnCount).forEachIndexed { rowIndex, chunk ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also {
                        if (rowIndex > 0) it.topMargin = px(context, 8)
                    }
                }

                repeat(columnCount) { columnIndex ->
                    val optionIndex = rowIndex * columnCount + columnIndex
                    val chip =
                        if (columnIndex < chunk.size) {
                            TextView(context).apply {
                                val opt = chunk[columnIndex]
                                text = opt
                                textSize = 13f
                                gravity = Gravity.CENTER
                                setTextColor(Color.WHITE)
                                background = chipBackground(context, optionIndex == selected)
                                minHeight = px(context, 40)
                                setPadding(px(context, 8), px(context, 8), px(context, 8), px(context, 8))
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f,
                                ).also { lp ->
                                    if (columnIndex > 0) lp.marginStart = px(context, 8)
                                }
                                setOnClickListener {
                                    onSelect(optionIndex, opt)
                                    if (dismissOnSelect) dialog.dismiss()
                                }
                            }
                        } else {
                            Space(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    0,
                                    1f,
                                ).also { lp ->
                                    if (columnIndex > 0) lp.marginStart = px(context, 8)
                                }
                            }
                        }
                    row.addView(chip)
                }
                container.addView(row)
            }

            root.addView(container)
        }

        fun selectableTileGrid(
            label: String,
            options: List<String>,
            selected: Int = -1,
            columns: Int = 3,
            dismissOnSelect: Boolean = false,
            onSelect: (Int, String) -> Unit,
        ): (Int) -> Unit {
            root.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#8E99A5"))
                setPadding(0, px(context, 8), 0, px(context, 4))
            })

            val columnCount = columns.coerceAtLeast(1)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val tiles = mutableListOf<LinearLayout>()
            fun render(selectedIndex: Int) {
                tiles.forEachIndexed { i, tile ->
                    tile.background = chipBackground(context, i == selectedIndex)
                    (tile.getChildAt(1) as? TextView)?.setTypeface(
                        null,
                        if (i == selectedIndex) Typeface.BOLD else Typeface.NORMAL,
                    )
                }
            }

            options.chunked(columnCount).forEachIndexed { rowIndex, chunk ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also {
                        if (rowIndex > 0) it.topMargin = px(context, 8)
                    }
                }

                repeat(columnCount) { columnIndex ->
                    val optionIndex = rowIndex * columnCount + columnIndex
                    val tile =
                        if (columnIndex < chunk.size) {
                            LinearLayout(context).apply {
                                val opt = chunk[columnIndex]
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                minimumHeight = px(context, 84)
                                setPadding(px(context, 8), px(context, 8), px(context, 8), px(context, 8))
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f,
                                ).also { lp ->
                                    if (columnIndex > 0) lp.marginStart = px(context, 8)
                                }
                                addView(transitionPreviewSwatch(context, opt))
                                addView(TextView(context).apply {
                                    text = opt
                                    textSize = 12f
                                    gravity = Gravity.CENTER
                                    setTextColor(Color.WHITE)
                                    maxLines = 1
                                    includeFontPadding = false
                                    setPadding(0, px(context, 6), 0, 0)
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                    )
                                })
                                setOnClickListener {
                                    render(optionIndex)
                                    onSelect(optionIndex, opt)
                                    if (dismissOnSelect) dialog.dismiss()
                                }
                            }.also(tiles::add)
                        } else {
                            Space(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    0,
                                    1f,
                                ).also { lp ->
                                    if (columnIndex > 0) lp.marginStart = px(context, 8)
                                }
                            }
                        }
                    row.addView(tile)
                }
                container.addView(row)
            }

            root.addView(container)
            render(selected)
            return { selectedIndex -> render(selectedIndex) }
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

        fun section(label: String) {
            root.addView(TextView(context).apply {
                text = label.uppercase()
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#7D8994"))
                setPadding(px(context, 2), px(context, 14), 0, px(context, 6))
                letterSpacing = 0.06f
            })
        }

        fun caption(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.parseColor("#B4BEC8"))
                setPadding(px(context, 2), 0, px(context, 2), px(context, 8))
            })
        }

        fun actionTile(
            title: String,
            subtitle: String? = null,
            badge: String? = null,
            dismissOnClick: Boolean = false,
            onClick: () -> Unit,
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = surfaceCard(context)
                minimumHeight = px(context, 58)
                setPadding(px(context, 12), px(context, 10), px(context, 12), px(context, 10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = px(context, 8) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onClick()
                    if (dismissOnClick) dialog.dismiss()
                }
            }

            row.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = px(context, 999).toFloat()
                    setColor(Color.parseColor("#6FDBFF"))
                }
                layoutParams = LinearLayout.LayoutParams(px(context, 4), px(context, 34)).also {
                    it.marginEnd = px(context, 12)
                }
            })

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textColumn.addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                includeFontPadding = false
            })
            if (!subtitle.isNullOrBlank()) {
                textColumn.addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.parseColor("#8E99A5"))
                    setPadding(0, px(context, 5), 0, 0)
                    maxLines = 2
                })
            }
            row.addView(textColumn)

            if (!badge.isNullOrBlank()) {
                row.addView(TextView(context).apply {
                    text = badge
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0B0F13"))
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = px(context, 999).toFloat()
                        setColor(Color.parseColor("#E7F7FF"))
                    }
                    setPadding(px(context, 10), px(context, 5), px(context, 10), px(context, 5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginStart = px(context, 10) }
                })
            }

            root.addView(row)
        }

        fun infoRow(label: String, value: String) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = inputBackground(context)
                setPadding(px(context, 12), px(context, 9), px(context, 12), px(context, 9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = px(context, 8) }
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#8E99A5"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(context).apply {
                text = value
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.END
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

    private fun transitionPreviewSwatch(context: Context, label: String): FrameLayout {
        val swatch = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(context, 42),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(context, 10).toFloat()
                colors = when {
                    label.contains("Fade", ignoreCase = true) ->
                        intArrayOf(Color.parseColor("#101820"), Color.parseColor("#F68B2D"))
                    label.contains("Wipe", ignoreCase = true) ->
                        intArrayOf(Color.parseColor("#1D4ED8"), Color.parseColor("#35D399"))
                    label.contains("Slide", ignoreCase = true) ->
                        intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#7C3AED"))
                    label.contains("Clean", ignoreCase = true) ->
                        intArrayOf(Color.parseColor("#111827"), Color.parseColor("#374151"))
                    else ->
                        intArrayOf(Color.parseColor("#263B72"), Color.parseColor("#F97316"))
                }
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
        }
        swatch.addView(View(context).apply {
            alpha = if (label.contains("Clean", ignoreCase = true)) 1f else 0.62f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(context, 8).toFloat()
                setColor(Color.parseColor("#DDE7F2"))
            }
            layoutParams = FrameLayout.LayoutParams(
                px(context, 28),
                px(context, 28),
                Gravity.CENTER,
            )
        })
        return swatch
    }
}
