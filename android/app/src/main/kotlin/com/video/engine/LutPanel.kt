package com.video.engine

import android.app.Activity
import android.graphics.Color
import android.opengl.GLES30
import com.video.engine.effects.EffectParams

/**
 * LUT Color Grading panel with cinematic filter presets.
 * Applies via SET_CLIP_EFFECTS command with LUT index.
 */
object LutPanel {

    data class LutPreset(val name: String, val emoji: String, val brightness: Float, val contrast: Float, val saturation: Float)

    val presets = listOf(
        LutPreset("Normal",    "○",  0.00f, 1.00f, 1.00f),
        LutPreset("Vivid",     "🌈", 0.05f, 1.15f, 1.40f),
        LutPreset("Matte",     "🎞", 0.08f, 0.85f, 0.75f),
        LutPreset("Warm",      "🌅", 0.06f, 1.05f, 1.10f),
        LutPreset("Cool",      "❄️", -0.04f, 1.05f, 0.90f),
        LutPreset("B&W",       "⬛", 0.00f, 1.10f, 0.00f),
        LutPreset("Fade",      "🌫", 0.12f, 0.80f, 0.70f),
        LutPreset("Cinematic", "🎬", -0.02f, 1.20f, 0.85f),
        LutPreset("Vintage",   "📷", 0.04f, 0.90f, 0.65f),
        LutPreset("Neon",      "💜", 0.00f, 1.25f, 1.60f),
        LutPreset("Golden",    "✨", 0.08f, 1.10f, 1.20f),
        LutPreset("Drama",     "🎭", -0.05f, 1.35f, 1.10f),
    )

    fun show(activity: Activity, clipId: Int, current: EffectParams, onChange: (EffectParams) -> Unit) {
        ModernSheet.show(activity, "Filters") {
            chips("Style", presets.map { "${it.emoji} ${it.name}" }, -1) { i, _ ->
                val p = presets[i]
                val params = EffectParams(p.brightness, p.contrast, p.saturation)
                val result = runCatching {
                    NativeBridge.executeCommand("SET_CLIP_EFFECTS", mapOf(
                        "clipId" to clipId,
                        "brightness" to p.brightness,
                        "contrast" to p.contrast,
                        "saturation" to p.saturation,
                    ))
                }.getOrNull()
                onChange(params)
            }
            divider()
            slider("Brightness", -1f, 1f, current.brightness, { "%.2f".format(it) }) { v ->
                val p = current.copy(brightness = v)
                NativeBridge.executeCommand("SET_CLIP_EFFECTS", mapOf("clipId" to clipId, "brightness" to v, "contrast" to p.contrast, "saturation" to p.saturation))
                onChange(p)
            }
            slider("Contrast", 0f, 2f, current.contrast, { "%.2f".format(it) }) { v ->
                val p = current.copy(contrast = v)
                NativeBridge.executeCommand("SET_CLIP_EFFECTS", mapOf("clipId" to clipId, "brightness" to p.brightness, "contrast" to v, "saturation" to p.saturation))
                onChange(p)
            }
            slider("Saturation", 0f, 2f, current.saturation, { "%.2f".format(it) }) { v ->
                val p = current.copy(saturation = v)
                NativeBridge.executeCommand("SET_CLIP_EFFECTS", mapOf("clipId" to clipId, "brightness" to p.brightness, "contrast" to p.contrast, "saturation" to v))
                onChange(p)
            }
        }
    }
}
