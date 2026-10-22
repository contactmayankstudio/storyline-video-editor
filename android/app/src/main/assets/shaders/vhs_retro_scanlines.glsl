#version 300 es
precision mediump float;
in vec2 vTextureCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uTime;
uniform float uIntensity;

void main() {
    vec2 uv = vTextureCoord;
    float scanline = sin(uv.y * 800.0 + uTime * 10.0) * 0.04 * uIntensity;
    vec4 col = texture(uTexture, uv);
    col.rgb -= scanline;
    fragColor = col;
}
