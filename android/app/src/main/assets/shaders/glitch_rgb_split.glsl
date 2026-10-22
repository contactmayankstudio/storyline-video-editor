#version 300 es
precision mediump float;
in vec2 vTextureCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uTime;
uniform float uIntensity; // 0.0 to 1.0

void main() {
    float shift = sin(uTime * 35.0) * 0.015 * uIntensity;
    float r = texture(uTexture, vTextureCoord + vec2(shift, 0.0)).r;
    float g = texture(uTexture, vTextureCoord).g;
    float b = texture(uTexture, vTextureCoord - vec2(shift, 0.0)).b;
    fragColor = vec4(r, g, b, 1.0);
}
