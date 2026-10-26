package com.video.engine.pro.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class CoverCardView(context: Context) : FrameLayout(context) {

    private val coverImageView: ImageView
    private val placeholderIcon: ImageView
    private val label: TextView

    init {
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt() }

        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#1C1C1C"))
            setStroke(dp(1), Color.parseColor("#383838"))
        }

        coverImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        addView(coverImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        placeholderIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            setColorFilter(Color.parseColor("#777777"))
        }
        addView(placeholderIcon, LayoutParams(dp(18), dp(18)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(6)
        })

        label = TextView(context).apply {
            text = "COVER"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 8.5f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        })

        elevation = dp(3).toFloat()
        isClickable = true
        isFocusable = true
    }

    fun setCoverBitmap(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            coverImageView.setImageBitmap(bitmap)
            coverImageView.visibility = View.VISIBLE
            placeholderIcon.visibility = View.GONE
            label.setBackgroundColor(Color.parseColor("#AA000000"))
        } else {
            coverImageView.setImageBitmap(null)
            coverImageView.visibility = View.GONE
            placeholderIcon.visibility = View.VISIBLE
            label.setBackgroundColor(Color.TRANSPARENT)
        }
    }
}
