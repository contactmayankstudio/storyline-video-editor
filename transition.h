#pragma once

#include <cstdint>
#include <string>
#include <vector>

/**
 * GPU-Accelerated Video Transition Effects
 *
 * Architecture:
 * ============
 * Transitions happen BETWEEN two clips at the GPU level:
 * - Last frame of outgoing clip (can still be decoded/rendered)
 * - First frame of incoming clip (decoded/rendered simultaneously)
 * - Shader blends during transition window
 * - No extra draw calls or passes
 *
 * Why GPU?
 * - Cross dissolve: single shader pass (mix two samples)
 * - Slide effect: UV offset in fragment shader
 * - Dither: procedural noise in shader (no CPU texture)
 * - All effects run at full resolution, no resampling
 *
 * Timing:
 * transition_start = end_of_outgoing_clip - overlap (optional)
 * transition_end = end_of_outgoing_clip + duration
 *
 * During [transition_start, transition_end):
 *   Fragment shader receives:
 *   - outColor: last frame of outgoing clip
 *   - inColor: first frame of incoming clip
 *   - t: 0.0 (outgoing) → 1.0 (incoming)
 *
 * Shader pseudocode:
 * ==================
 * FADE:
 *   outOpacity = 1.0 - t
 *   inOpacity = t
 *   color = outColor * outOpacity + inColor * inOpacity
 *
 * CROSS (dissolve):
 *   color = mix(outColor, inColor, t)
 *
 * SLIDE_LEFT:
 *   outUV.x -= t  // Slide out to left
 *   inUV.x = 1.0 - (1.0 - t)  // Slide in from right
 *   color = mix(out_tex[outUV], in_tex[inUV], t)
 *
 * DITHER (noise):
 *   noise = fract(sin(dot(uv, vec2(12.9898, 78.2, t)))) * 0.5 + 0.5
 *   threshold = noise - (1.0 - t)
 *   color = (threshold > 0.0) ? inColor : outColor
 *
 * CIRCLE (radial wipe):
 *   dist = length(uv - 0.5)
 *   threshold = 0.5 * t
 *   color = (dist < threshold) ? inColor : outColor
 */

enum class TransitionType : int32_t {
    NONE = 0,           // No transition (instant cut)
    FADE = 1,           // Fade out → fade in
    CROSS = 2,          // Cross dissolve (blend)
    SLIDE_LEFT = 3,     // Slide left then right
    SLIDE_RIGHT = 4,    // Slide right then left
    DITHER = 5,         // Dither/noise reveal
    CIRCLE = 6,         // Circular wipe
    ZIGZAG = 7          // Zigzag pattern
};

struct Transition {
    int64_t id = -1;                    // Unique identifier
    TransitionType type = TransitionType::CROSS;
    int32_t durationMs = 300;           // Duration in milliseconds (200-2000)
    int32_t outgoingClipId = -1;       // Clip ID being transitioned out
    int32_t incomingClipId = -1;       // Clip ID being transitioned in
    int64_t startTimeMs = 0;            // Timeline start of transition
    bool enabled = true;                // Is transition active?

    /**
     * Get progress of transition (0.0 to 1.0)
     * @param currentTimeMs Current timeline position
     * @return 0.0 (fully outgoing) to 1.0 (fully incoming), or -1 if outside window
     */
    float getProgress(int64_t currentTimeMs) const {
        if (!enabled) return -1.0f;
        int64_t elapsed = currentTimeMs - startTimeMs;
        if (elapsed < 0 || elapsed > durationMs) return -1.0f;
        return (float)elapsed / (float)durationMs;
    }

    /**
     * Check if transition is active at given time
     */
    bool isActive(int64_t currentTimeMs) const {
        int64_t elapsed = currentTimeMs - startTimeMs;
        return enabled && elapsed >= 0 && elapsed < durationMs;
    }

    /**
     * Get end time of transition
     */
    int64_t getEndTime() const {
        return startTimeMs + durationMs;
    }
};

/**
 * Fragment Shader Pseudo-Code
 *
 * For transitions, the render pass:
 * 1. Decode BOTH clips:
 *    - outgoing clip at (outClipId, currentTime)
 *    - incoming clip at (inClipId, max(0, currentTime - durationMs))
 *
 * 2. Sample both clips into uOutColor and uInColor
 *
 * 3. Apply transition blend based on type:
 *
 * #version 300 es
 * precision mediump float;
 *
 * uniform sampler2D uOutTexture;
 * uniform sampler2D uInTexture;
 * uniform float uTransitionProgress;  // 0.0 to 1.0
 * uniform int uTransitionType;        // 0-7
 * uniform float uWidth, uHeight;      // Texture dimensions for effects
 *
 * in vec2 vUV;
 * out vec4 fragColor;
 *
 * void main() {
 *     vec4 outColor = texture(uOutTexture, vUV);
 *     vec4 inColor = texture(uInTexture, vUV);
 *     float t = uTransitionProgress;
 *
 *     vec4 result;
 *
 *     if (uTransitionType == 1) {  // FADE
 *         result = mix(outColor * (1.0 - t), inColor * t, t);
 *     } else if (uTransitionType == 2) {  // CROSS
 *         result = mix(outColor, inColor, t);
 *     } else if (uTransitionType == 3) {  // SLIDE_LEFT
 *         vec2 outUV = vUV;
 *         outUV.x -= t;  // Slide out to left
 *         vec2 inUV = vUV;
 *         inUV.x = (1.0 - t);  // Slide in from right
 *         vec4 outSample = texture(uOutTexture, outUV);
 *         vec4 inSample = texture(uInTexture, inUV);
 *         result = mix(outSample, inSample, t);
 *     } else if (uTransitionType == 5) {  // DITHER
 *         float noise = fract(sin(dot(vUV, vec2(12.9898, 78.233)) + t * 43.14) * 43758.5453);
 *         float threshold = noise - (1.0 - t);
 *         result = (threshold > 0.0) ? inColor : outColor;
 *     } else if (uTransitionType == 6) {  // CIRCLE
 *         float dist = length(vUV - 0.5);
 *         float threshold = 0.7071 * t;  // sqrt(2)/2 for diagonal
 *         result = (dist < threshold) ? inColor : outColor;
 *     } else {  // NONE or default
 *         result = (t > 0.5) ? inColor : outColor;
 *     }
 *
 *     fragColor = result;
 * }
 */

#endif  // TRANSITION_H
