#version 300 es
precision mediump float;
in vec2 vTextureCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uIntensity;

void main() {
    vec2 dir = vTextureCoord - vec2(0.5);
    float dist = dot(dir, dir);
    vec2 offset = dir * (dist * 0.05 * uIntensity);
    float r = texture(uTexture, vTextureCoord + offset).r;
    float g = texture(uTexture, vTextureCoord).g;
    float b = texture(uTexture, vTextureCoord - offset).b;
    fragColor = vec4(r, g, b, 1.0);
}
