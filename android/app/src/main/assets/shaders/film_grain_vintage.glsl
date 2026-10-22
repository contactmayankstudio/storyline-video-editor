#version 300 es
precision mediump float;
in vec2 vTextureCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uTime;
uniform float uIntensity; // 0.0 to 1.0

float rand(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 base = texture(uTexture, vTextureCoord);
    float noise = (rand(vTextureCoord + vec2(uTime * 0.1, uTime * 0.2)) - 0.5) * 2.0;
    vec3 result = base.rgb + noise * (0.08 * uIntensity);
    fragColor = vec4(clamp(result, 0.0, 1.0), base.a);
}
