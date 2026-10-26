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
        var activePresetParams: EffectParams? = null

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
            val sectionTitles = listOf("All") + ProFilterPresets.sections.map { it.title }
            categories(sectionTitles, selected = 0) { _, category ->
                val presetToApply = if (category == "All") {
                    ProFilterPresets.sections.firstOrNull()?.presets?.firstOrNull()
                } else {
                    ProFilterPresets.sections.firstOrNull { it.title == category }?.presets?.firstOrNull()
                }
                presetToApply?.let {
                    activePresetParams = it.params
                    live = blendWithIntensity(it.params, live.filterIntensity)
                    onChange(live)
                }
            }

            section("Filter Intensity")
            sliderWithBubble(
                label = "Strength",
                min = 0f,
                max = 100f,
                value = (live.filterIntensity * 100f).coerceIn(0f, 100f),
                unit = "%",
                format = { "%.0f".format(it) },
            ) { v ->
                val newIntensity = (v / 100f).coerceIn(0f, 1f)
                val base = activePresetParams ?: live
                live = blendWithIntensity(base, newIntensity)
                onChange(live)
            }

            divider()
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
            val allFilterCards = ProFilterPresets.sections.flatMap { sec ->
                sec.presets.map { preset ->
                    val gradient = when (sec.title) {
                        "Beauty" -> Pair(android.graphics.Color.parseColor("#FF758C"), android.graphics.Color.parseColor("#FF7EB3"))
                        "Creator" -> Pair(android.graphics.Color.parseColor("#F7971E"), android.graphics.Color.parseColor("#FFD200"))
                        "Cinematic" -> Pair(android.graphics.Color.parseColor("#00B4DB"), android.graphics.Color.parseColor("#0083B0"))
                        else -> Pair(android.graphics.Color.parseColor("#232526"), android.graphics.Color.parseColor("#414345"))
                    }
                    ModernSheet.VisualCard(
                        id = preset.name,
                        label = preset.name,
                        swatchGradient = gradient,
                        subtitle = preset.mood,
                    )
                }
            }
            visualCardsGrid(
                cards = allFilterCards,
                selected = -1,
                columns = 3,
                dismissOnSelect = false,
            ) { _, card ->
                ProFilterPresets.findByName(card.id)?.let { preset ->
                    activePresetParams = preset.params
                    live = blendWithIntensity(preset.params, live.filterIntensity)
                    onChange(live)
                }
            }

            divider()
            compactActionRow(
                listOf(
                    ModernSheet.CompactAction(
                        title = "Reset to Original",
                        isDestructive = false,
                        onClick = {
                            activePresetParams = null
                            live = EffectParams()
                            onChange(live)
                        },
                    ),
                ),
            )
        }
    }

    private fun blendWithIntensity(base: EffectParams, intensity: Float): EffectParams {
        val alpha = intensity.coerceIn(0f, 1f)
        return EffectParams(
            brightness = base.brightness * alpha,
            contrast = 1.0f + (base.contrast - 1.0f) * alpha,
            saturation = 1.0f + (base.saturation - 1.0f) * alpha,
            filterIntensity = alpha
        )
    }
}
