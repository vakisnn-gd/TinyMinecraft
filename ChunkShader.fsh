#version 330 core

in vec4 vColor;
in float v_AO;
in float vFogDistance;

uniform int uTransparentPass;
uniform float uDaylight;
uniform vec3 uFogColor;
uniform float uFogDensity;
uniform float uBrightness;
uniform float uReveal;

out vec4 fragColor;

void main() {
    vec4 color = vColor;
    if (uTransparentPass != 0 && color.a > 0.45 && color.a < 0.75) {
        color.rgb = mix(color.rgb * 0.78, vec3(0.28, 0.50, 0.88), 0.22);
        color.a *= mix(0.72, 1.0, clamp(uDaylight, 0.0, 1.0));
    }
    float aoScale = clamp((v_AO / 3.0) * 0.5 + 0.5, 0.5, 1.0);
    color.rgb *= aoScale * uBrightness;
    float fogAmount = 1.0 - exp(-vFogDistance * uFogDensity);
    fogAmount = max(fogAmount, 1.0 - clamp(uReveal, 0.0, 1.0));
    color.rgb = mix(color.rgb, uFogColor, clamp(fogAmount, 0.0, 1.0));
    fragColor = color;
}
