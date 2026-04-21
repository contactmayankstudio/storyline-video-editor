package com.video.engine

import android.app.Activity
import com.video.engine.effects.EffectParams
class EffectsPanel(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val clipId: Int,
    private val initial: EffectParams = EffectParams(),
    private val onChange: ((EffectParams, Boolean) -> Unit)? = null,
) {
    private var brightness = initial.brightness
    private var contrast = initial.contrast
    private var saturation = initial.saturation

    fun show() {
        ModernSheet.show(activity, "Effects") {
            slider("Brightness", -1f, 1f, brightness, { "%.2f".format(it) }) {
                brightness = it; push(false)
            }
            slider("Contrast", 0f, 2f, contrast, { "%.2f".format(it) }) {
                contrast = it; push(false)
            }
            slider("Saturation", 0f, 2f, saturation, { "%.2f".format(it) }) {
                saturation = it; push(false)
            }
            divider()
            chips("", listOf("Reset All"), -1) { _, _ ->
                brightness = 0f; contrast = 1f; saturation = 1f
                push(true)
                dismiss()
            }
        }
    }

    private fun push(reset: Boolean) {
        previewView.setClipEffects(clipId, brightness, contrast, saturation)
        onChange?.invoke(EffectParams(brightness, contrast, saturation), reset)
    }
}
