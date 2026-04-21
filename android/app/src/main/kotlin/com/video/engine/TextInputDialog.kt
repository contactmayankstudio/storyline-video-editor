package com.video.engine

import android.app.Activity
import android.graphics.Color

class TextInputDialog(private val activity: Activity, private val onConfirm: (String, Int, Int) -> Unit) {
    fun show() {
        var fontSize = 36
        var color = Color.WHITE
        ModernSheet.show(activity, "Add Text") {
            textInput("Text", "Enter text...") { }
            slider("Font Size", 12f, 72f, fontSize.toFloat(), { "${it.toInt()}pt" }) { fontSize = it.toInt() }
            chips("Color", listOf("White", "Black", "Red", "Yellow", "Cyan"), 0) { i, _ ->
                color = when (i) {
                    1 -> Color.BLACK; 2 -> Color.RED; 3 -> Color.YELLOW; 4 -> Color.CYAN
                    else -> Color.WHITE
                }
            }
            chips("", listOf("Add"), -1) { _, _ ->
                val text = getTextInput().ifEmpty { "Text" }
                onConfirm(text, fontSize, color)
            }
        }
    }
}
