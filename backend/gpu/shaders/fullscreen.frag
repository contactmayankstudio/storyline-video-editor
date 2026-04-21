#version 330 core

/**
 * Fullscreen Quad Fragment Shader
 * 
 * Samples texture and applies compositing effects:
 * - Per-clip opacity blending
 * - Simple fade-in/out effects
 * 
 * Compatible with OpenGL 3.3 core profile.
 */

// Input from vertex shader
in VS_OUT {
    vec2 texCoord;
    vec3 worldPos;
} fs_in;

// Texture and compositing uniforms
uniform sampler2D tex0;              // Color texture
uniform float opacity;               // Clip opacity [0..1]

// Fade effect uniforms
uniform int fadeMode;                // 0=none, 1=fade-in, 2=fade-out
uniform float fadeProgress;          // Fade progress [0..1]

// Output
out vec4 FragColor;

void main() {
    // Sample texture
    vec4 texColor = texture(tex0, fs_in.texCoord);
    
    // Apply base opacity
    texColor.a *= opacity;
    
    // Apply fade effects
    if (fadeMode == 1) {
        // Fade-in: start transparent, fade to opaque
        texColor.a *= fadeProgress;
    } else if (fadeMode == 2) {
        // Fade-out: start opaque, fade to transparent
        texColor.a *= (1.0 - fadeProgress);
    }
    
    // Ensure valid alpha range
    texColor.a = clamp(texColor.a, 0.0, 1.0);
    
    // Output with premultiplied alpha for proper blending
    FragColor = texColor;
}
