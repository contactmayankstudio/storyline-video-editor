#version 330 core

/**
 * GPU-Accelerated Crossfade Transition Shader
 * 
 * Handles blending between two adjacent video clips during transitions.
 * Optimized for real-time compositing with minimal CPU overhead.
 * 
 * Features:
 * - Samples two separate YUV420P clip textures
 * - Per-clip opacity and BT.709 YUV→RGB conversion
 * - Crossfade blend via linear interpolation (progress 0→1)
 * - Single draw call per transition
 * 
 * Usage:
 * - In timeline compositing: when clipA ends and clipB starts
 * - Progress interpolates from clipA (opaque) to clipB (opaque)
 * - GPU handles all pixel math simultaneously
 * 
 * Performance:
 * - 6 texture reads per fragment (3 per clip: Y, U, V)
 * - Color matrix math per fragment (BT.709 × 2)
 * - Linear blend (1 instruction per channel)
 * - Typical GPU fill rate: 10-50 Gpixels/sec (1080p @ 30fps = 62.4 Mpixels/sec)
 */

// Input from vertex shader
in VS_OUT {
    vec2 texCoord;
    vec3 worldPos;
} fs_in;

// ============ Clip A (Outgoing) ============
uniform sampler2D texA_Y;      // Clip A Y plane (texture unit 0)
uniform sampler2D texA_U;      // Clip A U plane (texture unit 1)
uniform sampler2D texA_V;      // Clip A V plane (texture unit 2)
uniform float opacityA;        // Clip A opacity [0..1]

// ============ Clip B (Incoming) ============
uniform sampler2D texB_Y;      // Clip B Y plane (texture unit 3)
uniform sampler2D texB_U;      // Clip B U plane (texture unit 4)
uniform sampler2D texB_V;      // Clip B V plane (texture unit 5)
uniform float opacityB;        // Clip B opacity [0..1]

// ============ Crossfade Control ============
uniform float progress;        // Transition progress [0..1]
                              // 0.0 = 100% Clip A, 0% Clip B
                              // 0.5 = 50% Clip A, 50% Clip B
                              // 1.0 = 0% Clip A, 100% Clip B

// Output
out vec4 FragColor;

/**
 * Convert YUV to RGB using BT.709 color matrix.
 * 
 * @param y Luminance component [0..1]
 * @param u Chroma U component [-0.5..0.5] (pre-shifted by caller)
 * @param v Chroma V component [-0.5..0.5] (pre-shifted by caller)
 * @return RGB color vector
 */
vec3 yuvToRgb(float y, float u, float v) {
    // BT.709 YUV→RGB conversion (ITU Rec. 709 standard)
    float r = y + 1.5748 * v;
    float g = y - 0.1873 * u - 0.4681 * v;
    float b = y + 1.8556 * u;
    
    // Clamp to valid range [0, 1]
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

void main() {
    // ============ Sample Clip A ============
    float yA = texture(texA_Y, fs_in.texCoord).r;
    float uA = texture(texA_U, fs_in.texCoord).r - 0.5;  // Shift to [-0.5, 0.5]
    float vA = texture(texA_V, fs_in.texCoord).r - 0.5;  // Shift to [-0.5, 0.5]
    
    vec3 rgbA = yuvToRgb(yA, uA, vA);
    
    // ============ Sample Clip B ============
    float yB = texture(texB_Y, fs_in.texCoord).r;
    float uB = texture(texB_U, fs_in.texCoord).r - 0.5;  // Shift to [-0.5, 0.5]
    float vB = texture(texB_V, fs_in.texCoord).r - 0.5;  // Shift to [-0.5, 0.5]
    
    vec3 rgbB = yuvToRgb(yB, uB, vB);
    
    // ============ Crossfade Blend ============
    // Linear interpolation based on transition progress
    // progress 0.0: 100% A, 0% B
    // progress 1.0: 0% A, 100% B
    vec3 blended = mix(rgbA, rgbB, progress);
    
    // ============ Apply Per-Clip Opacity ============
    // During crossfade:
    // - If both clips are visible: use weighted average of opacities
    // - Clip A fades out as progress increases
    // - Clip B fades in as progress increases
    float alphaA = opacityA * (1.0 - progress);    // Clip A opacity decreases
    float alphaB = opacityB * progress;            // Clip B opacity increases
    float blendedAlpha = alphaA + alphaB;
    
    // Normalize alpha (if both had full opacity at progress=0.5, alpha=1.0)
    blendedAlpha = clamp(blendedAlpha, 0.0, 1.0);
    
    // ============ Output ============
    FragColor = vec4(blended, blendedAlpha);
}
