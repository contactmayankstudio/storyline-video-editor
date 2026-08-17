package com.video.engine

import android.app.Activity
import com.video.engine.effects.EffectParams
import com.video.engine.effects.ProFilterPresets

class EffectsPanel(
    private val activity: Activity,
    private val previewView: VideoPreviewView,
    private val clipId: Int,
    private val initial: EffectParams = EffectParams(),
    private val resolvePreviewTimeMs: (() -> Long)? = null,
    private val onChange: ((EffectParams, Boolean) -> Unit)? = null,
) {
    private var brightness = initial.brightness
    private var contrast = initial.contrast
    private var saturation = initial.saturation

    fun show() {
        ModernSheet.show(activity, "Effects") {
            chipGrid(
                "Quick Looks",
                listOf("Beauty Lift", "Golden Hour", "Cine Matte", "Teal Punch", "Noir Mono", "Neutral"),
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, option ->
                applyPreset(option)
            }
            ProFilterPresets.sections.forEach { section ->
                chipGrid(
                    section.title,
                    section.presets.map { it.name },
                    selected = -1,
                    columns = 3,
                    dismissOnSelect = false,
                ) { index, _ ->
                    val preset = section.presets[index]
                    applyPreset(preset.name, preset.params)
                }
            }
            divider()
            slider("Brightness", -1f, 1f, brightness, { "%.2f".format(it) }) {
                brightness = it; push(false)
            }
            slider("Contrast", 0f, 2f, contrast, { "%.2f".format(it) }) {
                contrast = it; push(false)
            }
            slider("Saturation", 0f, 2f, saturation, { "%.2f".format(it) }) {
                saturation = it; push(false)
            }
            chips("Quick Tune", listOf("Bright +", "Bright -", "Punch +", "Soft", "Sat +", "Sat -"), -1, dismissOnSelect = false) { _, option ->
                when (option) {
                    "Bright +" -> applyParams(EffectParams((brightness + 0.08f).coerceIn(-1f, 1f), contrast, saturation), reset = false)
                    "Bright -" -> applyParams(EffectParams((brightness - 0.08f).coerceIn(-1f, 1f), contrast, saturation), reset = false)
                    "Punch +" -> applyParams(EffectParams(brightness, (contrast + 0.12f).coerceIn(0f, 2f), (saturation + 0.10f).coerceIn(0f, 2f)), reset = false)
                    "Soft" -> applyParams(EffectParams((brightness + 0.06f).coerceIn(-1f, 1f), (contrast - 0.12f).coerceIn(0f, 2f), (saturation - 0.06f).coerceIn(0f, 2f)), reset = false)
                    "Sat +" -> applyParams(EffectParams(brightness, contrast, (saturation + 0.12f).coerceIn(0f, 2f)), reset = false)
                    "Sat -" -> applyParams(EffectParams(brightness, contrast, (saturation - 0.12f).coerceIn(0f, 2f)), reset = false)
                }
            }
            divider()
            chips("Reset", listOf("Reset All"), -1, dismissOnSelect = false) { _, _ ->
                applyParams(EffectParams(), reset = true)
            }
        }
    }

    private var lastPushTime = 0L
    private val PUSH_THROTTLE_MS = 32L // ~30fps update rate for sliders

    private fun applyPreset(name: String, fallback: EffectParams? = ProFilterPresets.findByName(name)?.params) {
        val params =
            if (name == "Neutral") {
                EffectParams()
            } else {
                runCatching { NativeBridge.applyProfessionalEffectPreset(clipId, name) }.getOrNull()
                    ?: fallback
                    ?: EffectParams()
            }
        applyParams(params, reset = params == EffectParams())
    }

    private fun applyParams(params: EffectParams, reset: Boolean) {
        brightness = params.brightness.coerceIn(-1f, 1f)
        contrast = params.contrast.coerceIn(0f, 2f)
        saturation = params.saturation.coerceIn(0f, 2f)
        push(reset)
    }

    private fun push(reset: Boolean) {
        val now = System.currentTimeMillis()
        if (reset || (now - lastPushTime) > PUSH_THROTTLE_MS) {
            NativeBridge.setClipEffects(previewView, clipId, brightness, contrast, saturation)
            resolvePreviewTimeMs?.invoke()?.let { previewTimeMs ->
                runCatching { NativeBridge.seekToTime(previewView, previewTimeMs.coerceAtLeast(0L)) }
            }
            onChange?.invoke(EffectParams(brightness, contrast, saturation), reset)
            lastPushTime = now
        }
    }
}
