#version 300 es
precision mediump float;
in vec2 vTextureCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uIntensity; // default 0.6

void main() {
    vec4 color = texture(uTexture, vTextureCoord);
    vec2 pos = vTextureCoord - vec2(0.5);
    float dist = length(pos);
    float vignette = smoothstep(0.75, 0.25, dist * (0.8 + uIntensity * 0.6));
    fragColor = vec4(color.rgb * vignette, color.a);
}
