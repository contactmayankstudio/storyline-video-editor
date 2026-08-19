package com.video.engine.pro.timeline

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.util.TypedValue

class CoverCardView(context: Context) : FrameLayout(context) {
    init {
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt() }
        
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#1C1C1C"))
            setStroke(dp(1), Color.parseColor("#333333"))
        }
        
        val label = TextView(context).apply {
            text = "COVER"
            setTextColor(Color.parseColor("#888888"))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            setColorFilter(Color.parseColor("#555555"))
        }
        
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        
        addView(icon, LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.CENTER
        })
        
        elevation = dp(4).toFloat()
    }
}
