package com.video.engine

import android.app.Activity
import com.video.engine.effects.EffectParams
import com.video.engine.effects.ProFilterPresets

/**
 * Filter and Color Adjust sheet backed by the shared clip effect parameters.
 */
object LutPanel {

    fun show(activity: Activity, current: EffectParams, onChange: (EffectParams) -> Unit) {
        val initial = current
        var live = current
        var currentTab = 0 // 0 = Filter, 1 = Adjust

        ModernSheet.showModal(
            context = activity,
            title = "Filter & Adjust",
            showClose = true,
            showApply = true,
            onApply = {
                onChange(live)
            },
            onCancel = {
                onChange(initial)
            },
        ) {
            tabs(listOf("Filter", "Adjust"), selected = 0) { tabIndex ->
                currentTab = tabIndex
            }

            section("Color Adjustments")
            sliderWithBubble(
                label = "Brightness",
                min = -100f,
                max = 100f,
                value = current.brightness * 100f,
                unit = "",
                format = { "%.0f".format(it) },
            ) { v ->
                live = live.copy(brightness = v / 100f)
                onChange(live)
            }
            sliderWithBubble(
                label = "Contrast",
                min = -100f,
                max = 100f,
                value = (current.contrast - 1.0f) * 100f,
                unit = "",
                format = { "%.0f".format(it) },
            ) { v ->
                live = live.copy(contrast = (1.0f + v / 100f).coerceIn(0f, 2f))
                onChange(live)
            }
            sliderWithBubble(
                label = "Saturation",
                min = -100f,
                max = 100f,
                value = (current.saturation - 1.0f) * 100f,
                unit = "",
                format = { "%.0f".format(it) },
            ) { v ->
                live = live.copy(saturation = (1.0f + v / 100f).coerceIn(0f, 2f))
                onChange(live)
            }

            divider()
            section("Cinematic Presets")
            ProFilterPresets.sections.forEach { section ->
                chipGrid(section.title, section.presets.map { it.name }, -1, columns = 3, dismissOnSelect = false) { index, _ ->
                    val preset = section.presets[index]
                    live = preset.params
                    onChange(live)
                }
            }

            divider()
            chips("Reset", listOf("Reset to Original"), -1, dismissOnSelect = false) { _, _ ->
                live = EffectParams()
                onChange(live)
            }
        }
    }
}
