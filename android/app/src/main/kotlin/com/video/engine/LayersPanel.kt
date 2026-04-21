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
    val visible: Boolean = true,
    val secondaryIcon: Int? = null,
    val onSecondaryAction: (() -> Unit)? = null,
    val onToggleVisibility: (() -> Unit)? = null,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

class LayersPanel(
    private val activity: Activity,
    private val items: List<LayerItem>,
) {
    fun show() {
        val dialog = BottomSheetDialog(activity)
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
            setPadding(0, 0, 0, px(12))
        })

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
                    setPadding(0, px(10), 0, px(10))
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
                    setTextColor(Color.WHITE)
                })
                textWrap.addView(TextView(activity).apply {
                    text = item.subtitle
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
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
                        setOnClickListener { action?.invoke() }
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

                list.addView(row)
            }
            scroll.addView(list)
            root.addView(scroll)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun px(dp: Int) = (dp * activity.resources.displayMetrics.density).roundToInt()
}
