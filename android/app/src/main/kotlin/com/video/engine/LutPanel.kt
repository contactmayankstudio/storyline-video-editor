package com.video.engine

import android.app.Activity
import com.video.engine.effects.EffectParams
import com.video.engine.effects.ProFilterPresets

/**
 * Filter preset sheet backed by the shared clip effect parameters.
 */
object LutPanel {

    fun show(activity: Activity, current: EffectParams, onChange: (EffectParams) -> Unit) {
        var live = current
        ModernSheet.show(activity, "Pro Filters") {
            ProFilterPresets.sections.forEach { section ->
                chipGrid(section.title, section.presets.map { it.name }, -1, columns = 3) { index, _ ->
                    val preset = section.presets[index]
                    live = preset.params
                    onChange(live)
                }
            }
            divider()
            chips("Utility", listOf("Reset"), -1) { _, _ ->
                live = EffectParams()
                onChange(live)
            }
            slider("Brightness", -1f, 1f, current.brightness, { "%.2f".format(it) }) { v ->
                live = live.copy(brightness = v)
                onChange(live)
            }
            slider("Contrast", 0f, 2f, current.contrast, { "%.2f".format(it) }) { v ->
                live = live.copy(contrast = v)
                onChange(live)
            }
            slider("Saturation", 0f, 2f, current.saturation, { "%.2f".format(it) }) { v ->
                live = live.copy(saturation = v)
                onChange(live)
            }
        }
    }
}
