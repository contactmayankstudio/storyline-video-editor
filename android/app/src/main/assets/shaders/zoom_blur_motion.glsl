#version 300 es
precision mediump float;
in vec2 vTextureCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uIntensity; // 0.0 to 1.0

void main() {
    vec2 center = vec2(0.5, 0.5);
    vec2 dir = vTextureCoord - center;
    vec4 color = vec4(0.0);
    const int SAMPLES = 8;
    for (int i = 0; i < SAMPLES; i++) {
        float scale = 1.0 - uIntensity * 0.04 * float(i);
        color += texture(uTexture, center + dir * scale);
    }
    fragColor = color / float(SAMPLES);
}
