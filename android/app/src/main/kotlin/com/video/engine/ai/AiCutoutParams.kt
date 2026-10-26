package com.video.engine.ai

import android.graphics.Color

/**
 * Parameters for Storyline Pro AI Cutout, Glowing Neon Silhouette, and 3D Dynamic Backgrounds.
 */
data class AiCutoutParams(
    var clipId: Int = -1,
    var enabled: Boolean = false,
    var neonGlowColor: Int = COLOR_NEON_CYAN,
    var neonGlowPulse: Boolean = true,
    var glowWidth: Float = 14.0f,
    var backgroundType: String = BG_NONE,
) {
    companion object {
        const val COLOR_NONE = 0
        const val COLOR_NEON_CYAN = 0xFF00F2FE.toInt()
        const val COLOR_HOT_PINK = 0xFFFF007F.toInt()
        const val COLOR_GOLD_FLAME = 0xFFFFD700.toInt()

        const val BG_NONE = "NONE"
        const val BG_CYBERPUNK_GRID = "CYBERPUNK_GRID"
        const val BG_GOLDEN_HOUR = "GOLDEN_HOUR"
        const val BG_NEON_TUNNEL = "NEON_TUNNEL"
        const val BG_BLUR_BOKEH = "BLUR_BOKEH"
    }

    val hasGlow: Boolean
        get() = neonGlowColor != COLOR_NONE
}
