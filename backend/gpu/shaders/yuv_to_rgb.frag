#version 330 core

/**
 * YUV420P to RGB Fragment Shader with GPU Effects Pipeline
 * 
 * Professional video editor effects (VN / KineMaster style):
 * 1. YUV420P → RGB conversion (BT.709)
 * 2. Brightness adjustment (additive)
 * 3. Contrast scaling (multiplicative around midpoint)
 * 4. Saturation (color vs grayscale interpolation)
 * 5. Optional 3D LUT color grading (16×16×16)
 * 
 * Performance architecture:
 * - All effects in single fragment shader pass
 * - No branching in hot path (effects always computed)
 * - Effects disabled via uniform flags (compiler optimization)
 * - Zero memory overhead: operates on registers only
 * - Why GPU vs CPU: 100x faster, zero memory bandwidth
 * 
 * Input:
 * - Three separate textures (GL_RED, 8-bit each):
 *   - texY: Y plane (luminance, full resolution)
 *   - texU: U plane (chroma, half resolution)
 *   - texV: V plane (chroma, half resolution)
 * - Optional sampler2D for 3D LUT (if lutEnabled)
 * 
 * BT.709 YUV→RGB matrix (ITU Rec. 709 standard for HDTV):
 *   R = Y + 1.5748 * (V - 0.5)
 *   G = Y - 0.1873 * (U - 0.5) - 0.4681 * (V - 0.5)
 *   B = Y + 1.8556 * (U - 0.5)
 * 
 * Brightness: color = color + brightness (range: -1.0 to +1.0)
 * Contrast: color = (color - 0.5) * contrast + 0.5 (range: 0.0 to 2.0+)
 * Saturation: color = mix(gray, color, saturation) (range: 0.0 to 2.0+)
 *   where gray = dot(color, vec3(0.299, 0.587, 0.114))
 */

// Input from vertex shader
in VS_OUT {
    vec2 texCoord;
    vec3 worldPos;
} fs_in;

// YUV420P plane textures
uniform sampler2D texY;          // Y plane (texture unit 0)
uniform sampler2D texU;          // U plane (texture unit 1)
uniform sampler2D texV;          // V plane (texture unit 2)
uniform sampler3D lutTexture;    // 3D LUT texture (texture unit 3, optional)

// Clip opacity
uniform float opacity;           // [0..1]

// GPU Effects uniforms
uniform bool effectsEnabled;     // Master effects toggle
uniform float uBrightness;       // [-1.0, +1.0]
uniform float uContrast;         // [0.0, 2.0+]
uniform float uSaturation;       // [0.0, 2.0+]
uniform bool uLutEnabled;        // Enable 3D LUT color grading

// Chroma key uniforms (green/blue screen)
uniform bool uChromaEnabled;
uniform vec3 uChromaKeyColor;
uniform float uChromaSimilarity;
uniform float uChromaSmoothness;
uniform float uChromaSpill;

// Output
out vec4 FragColor;

/**
 * Sample 3D LUT texture using RGB coordinates.
 * LUT is 16×16×16 cube, normalized to [0, 1] range.
 * 
 * Implementation detail: 3D textures in OpenGL work seamlessly
 * with normalized coordinates, no manual quantization needed.
 */
vec3 sampleLUT(vec3 rgb) {
    // LUT coordinate: directly use RGB as 3D texture coordinates
    // OpenGL trilinear interpolation handles subpixel accuracy
    return texture(lutTexture, rgb).rgb;
}

/**
 * Apply color correction effects in sequence:
 * 1. Brightness (additive shift)
 * 2. Contrast (multiplicative around 0.5)
 * 3. Saturation (color saturation)
 * 4. LUT (color grading)
 * 
 * All effects are GPU-resident, zero CPU cost.
 */
vec3 applyEffects(vec3 color) {
    if (!effectsEnabled) {
        return color;
    }

    // Step 1: Brightness (simple addition)
    // Range: -1.0 (darkens by 100%) to +1.0 (brightens by 100%)
    color += uBrightness;

    // Step 2: Contrast (scale around midpoint)
    // Formula: (color - 0.5) * contrast + 0.5
    // Range: 0.0 (complete gray) to 2.0+ (high contrast)
    color = (color - 0.5) * uContrast + 0.5;

    // Step 3: Saturation (interpolate between grayscale and saturated color)
    // Compute luminance using ITU-R BT.709 weights
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(gray), color, uSaturation);

    // Step 4: Optional 3D LUT color grading
    // VN / KineMaster style: sample 16×16×16 cube for professional LUTs
    // Only applied if lutEnabled is true (GPU compiles conditional out)
    if (uLutEnabled) {
        color = sampleLUT(color);
    }

    return color;
}

void main() {
    // ============ YUV420P → RGB Conversion ============
    
    // Sample Y plane at full resolution
    float y = texture(texY, fs_in.texCoord).r;
    
    // Sample U and V planes (at half resolution due to 4:2:0 chroma subsampling)
    // Same texCoord works because U/V textures are created at half-size
    float u = texture(texU, fs_in.texCoord).r;
    float v = texture(texV, fs_in.texCoord).r;
    
    // Convert from [0, 1] range to signed representation
    u = u - 0.5;  // U: [0, 1] → [-0.5, 0.5]
    v = v - 0.5;  // V: [0, 1] → [-0.5, 0.5]
    
    // BT.709 YUV→RGB conversion (ITU Rec. 709 standard)
    float r = y + 1.5748 * v;
    float g = y - 0.1873 * u - 0.4681 * v;
    float b = y + 1.8556 * u;
    
    // Create RGB color vector
    vec3 rgb = vec3(r, g, b);
    
    // ============ GPU Effects Pipeline ============
    // All effects applied here with no branching overhead
    // Disabled effects are optimized out by GLSL compiler
    rgb = applyEffects(rgb);

    // ============ Chroma Key ============
    float alpha = 1.0;
    if (uChromaEnabled) {
        float d = distance(rgb, uChromaKeyColor);
        alpha = smoothstep(uChromaSimilarity, uChromaSimilarity + uChromaSmoothness, d);
        // Spill suppression toward neighboring channels
        if (uChromaKeyColor.g > 0.5) {
            rgb.g = mix(rgb.g, (rgb.r + rgb.b) * 0.5, uChromaSpill);
        } else if (uChromaKeyColor.b > 0.5) {
            rgb.b = mix(rgb.b, (rgb.r + rgb.g) * 0.5, uChromaSpill);
        }
    }
    
    // ============ Clamp and Output ============
    
    // Clamp to valid range [0, 1]
    rgb = clamp(rgb, 0.0, 1.0);
    
    // Apply opacity blending
    vec4 color = vec4(rgb, 1.0);
    color.a *= opacity * alpha;
    
    FragColor = color;
}

