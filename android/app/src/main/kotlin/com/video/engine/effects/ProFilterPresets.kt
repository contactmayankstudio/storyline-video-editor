package com.video.engine.effects

data class ProFilterPreset(
    val name: String,
    val mood: String,
    val params: EffectParams,
)

data class ProFilterSection(
    val title: String,
    val presets: List<ProFilterPreset>,
)

object ProFilterPresets {

    val sections: List<ProFilterSection> = listOf(
        ProFilterSection(
            title = "Beauty",
            presets = listOf(
                ProFilterPreset("Fair Lift", "Bright fair-tone lift", EffectParams(brightness = 0.30f, contrast = 0.82f, saturation = 0.86f)),
                ProFilterPreset("Beauty Lift", "Soft glow beauty look", EffectParams(brightness = 0.24f, contrast = 0.84f, saturation = 0.88f)),
                ProFilterPreset("Bridal Glow", "Warm ivory portrait glow", EffectParams(brightness = 0.26f, contrast = 0.86f, saturation = 1.06f)),
                ProFilterPreset("Camera Makeup", "Clean selfie makeup look", EffectParams(brightness = 0.22f, contrast = 0.88f, saturation = 0.92f)),
                ProFilterPreset("Soft Skin", "Gentle smooth pastel skin", EffectParams(brightness = 0.16f, contrast = 0.88f, saturation = 0.90f)),
            ),
        ),
        ProFilterSection(
            title = "Creator",
            presets = listOf(
                ProFilterPreset("Seoul Vlog", "Airy creator vlog tone", EffectParams(brightness = 0.16f, contrast = 1.10f, saturation = 1.22f)),
                ProFilterPreset("Golden Hour", "Warm sunlight travel glow", EffectParams(brightness = 0.14f, contrast = 1.16f, saturation = 1.30f)),
                ProFilterPreset("Market Pop", "Fresh punchy product colors", EffectParams(brightness = 0.10f, contrast = 1.24f, saturation = 1.52f)),
            ),
        ),
        ProFilterSection(
            title = "Cinematic",
            presets = listOf(
                ProFilterPreset("Cine Matte", "Soft matte montage look", EffectParams(brightness = 0.06f, contrast = 0.82f, saturation = 0.72f)),
                ProFilterPreset("Teal Punch", "Cool blockbuster contrast", EffectParams(brightness = -0.08f, contrast = 1.36f, saturation = 1.18f)),
                ProFilterPreset("Night Neon", "Blue-magenta nightlife mood", EffectParams(brightness = -0.10f, contrast = 1.34f, saturation = 1.42f)),
            ),
        ),
        ProFilterSection(
            title = "Classic",
            presets = listOf(
                ProFilterPreset("Retro Print", "Dusty vintage postcard", EffectParams(brightness = 0.08f, contrast = 0.80f, saturation = 0.52f)),
                ProFilterPreset("Noir Mono", "Hard black and white", EffectParams(brightness = -0.08f, contrast = 1.40f, saturation = 0.00f)),
                ProFilterPreset("Neutral", "Reset to clean base", EffectParams()),
            ),
        ),
    )

    private val allPresets: Map<String, ProFilterPreset> = sections
        .flatMap { it.presets }
        .associateBy { it.name }

    fun findByName(name: String): ProFilterPreset? = allPresets[name]
}
