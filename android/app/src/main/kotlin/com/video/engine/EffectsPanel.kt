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
        ModernSheet.showModal(
            context = activity,
            title = "Video Effects",
            showClose = true,
            showApply = true,
            onApply = {
                push(reset = true)
            },
            onCancel = {
                applyParams(initial, reset = true)
            },
        ) {
            tabs(listOf("Hot", "Motion", "Glitch", "Love", "Nature"), selected = 0) { tabIdx ->
                val effectPreset = when (tabIdx) {
                    0 -> "Beauty Lift"
                    1 -> "Seoul Vlog"
                    2 -> "Night Neon"
                    3 -> "Golden Hour"
                    4 -> "Teal Punch"
                    else -> "Beauty Lift"
                }
                applyPreset(effectPreset)
            }

            section("Trending Effects")
            chipGrid(
                "Popular",
                listOf("Beauty Lift", "Golden Hour", "Cine Matte", "Teal Punch", "Night Neon", "Noir Mono"),
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, option ->
                applyPreset(option)
            }

            divider()
            section("Fine Tune")
            sliderWithBubble(
                label = "Brightness",
                min = -100f,
                max = 100f,
                value = brightness * 100f,
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                brightness = v / 100f
                push(false)
            }
            sliderWithBubble(
                label = "Contrast",
                min = -100f,
                max = 100f,
                value = (contrast - 1.0f) * 100f,
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                contrast = (1.0f + v / 100f).coerceIn(0f, 2f)
                push(false)
            }
            sliderWithBubble(
                label = "Saturation",
                min = -100f,
                max = 100f,
                value = (saturation - 1.0f) * 100f,
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                saturation = (1.0f + v / 100f).coerceIn(0f, 2f)
                push(false)
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
