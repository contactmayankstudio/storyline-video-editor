package com.video.engine

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

data class LayerItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean = false,
    val visible: Boolean = true,
    val onSelect: (() -> Unit)? = null,
    val secondaryIcon: Int? = null,
    val onSecondaryAction: (() -> Unit)? = null,
    val onToggleVisibility: (() -> Unit)? = null,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

class LayersPanel(
    private val activity: Activity,
    private val itemsProvider: () -> List<LayerItem>,
) {
    fun show() {
        val dialog = BottomSheetDialog(activity)
        dialog.setCanceledOnTouchOutside(true)
        renderContent(dialog)
        ModernSheet.applyEditorBehavior(dialog, activity, peekRatio = 0.44f, maxRatio = 0.62f)
        dialog.show()
    }

    private fun renderContent(dialog: BottomSheetDialog) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(px(20), px(16), px(20), px(32))
        }

        // Drag handle
        val handle = LinearLayout(activity).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(12)) }
        handle.addView(TextView(activity).apply {
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(px(40), px(4)).also { it.gravity = Gravity.CENTER }
        })
        root.addView(handle)

        // Title
        root.addView(TextView(activity).apply {
            text = "Layers"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(4))
        })
        root.addView(TextView(activity).apply {
            text = "Tap a row to select it"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(12))
        })

        val items = itemsProvider()
        if (items.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "No layers yet."
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding(0, px(24), 0, px(24))
            })
        } else {
            val scroll = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

            items.forEach { item ->
                // Divider
                list.addView(TextView(activity).apply {
                    setBackgroundColor(Color.parseColor("#2A2A2A"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                        .also { it.setMargins(0, px(4), 0, px(4)) }
                })

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(10), px(10), px(10), px(10))
                    if (item.selected) {
                        setBackgroundColor(Color.parseColor("#243D68"))
                    }
                    isClickable = item.onSelect != null
                    isFocusable = item.onSelect != null
                    setOnClickListener {
                        item.onSelect?.invoke()
                        dialog.dismiss()
                    }
                }

                // Text
                val textWrap = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textWrap.addView(TextView(activity).apply {
                    text = item.title
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (item.selected) Color.parseColor("#D8EAFF") else Color.WHITE)
                })
                textWrap.addView(TextView(activity).apply {
                    text = item.subtitle
                    textSize = 11f
                    setTextColor(if (item.selected) Color.parseColor("#A9D7FF") else Color.parseColor("#888888"))
                })
                row.addView(textWrap)

                fun iconBtn(icon: Int, tint: Int = Color.WHITE, action: (() -> Unit)?): ImageButton {
                    return ImageButton(activity).apply {
                        setImageResource(icon)
                        setColorFilter(tint)
                        setBackgroundColor(Color.TRANSPARENT)
                        layoutParams = LinearLayout.LayoutParams(px(36), px(36))
                            .also { it.setMargins(px(2), 0, px(2), 0) }
                        alpha = if (action != null) 1f else 0.3f
                        isEnabled = action != null
                        setOnClickListener {
                            action?.invoke()
                            renderContent(dialog)
                        }
                    }
                }

                row.addView(iconBtn(
                    if (item.visible) android.R.drawable.presence_online
                    else android.R.drawable.presence_invisible,
                    if (item.visible) Color.parseColor("#4CAF50") else Color.parseColor("#888888"),
                    item.onToggleVisibility
                ))
                item.secondaryIcon?.let { row.addView(iconBtn(it, Color.parseColor("#A9D7FF"), item.onSecondaryAction)) }
                row.addView(iconBtn(android.R.drawable.arrow_up_float, Color.WHITE, item.onMoveUp))
                row.addView(iconBtn(android.R.drawable.arrow_down_float, Color.WHITE, item.onMoveDown))
                row.addView(iconBtn(android.R.drawable.ic_menu_delete, Color.parseColor("#FF5555"), item.onDelete))
                row.addView(ImageView(activity).apply {
                    setImageResource(android.R.drawable.ic_media_next)
                    setColorFilter(if (item.selected) Color.parseColor("#D8EAFF") else Color.parseColor("#666666"))
                    layoutParams = LinearLayout.LayoutParams(px(18), px(18))
                        .also { it.setMargins(px(4), 0, 0, 0) }
                    alpha = if (item.onSelect != null) 1f else 0f
                })

                list.addView(row)
            }
            scroll.addView(list)
            root.addView(scroll)
        }

        root.addView(TextView(activity).apply {
            text = "Done"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2B2B2F"))
            setPadding(0, px(12), 0, px(12))
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.setMargins(0, px(16), 0, 0) }
        })

        dialog.setContentView(root)
    }

    private fun px(dp: Int) = (dp * activity.resources.displayMetrics.density).roundToInt()
}
