// Встроенный ShaderToy-совместимый шейдер.
void mainImage(out vec4 fragColor, in vec2 fragCoord)
{
    vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
    float radius = length(uv);
    float pulse = 0.5 + 0.5 * sin(iTime * 1.4 - radius * 9.0);

    vec3 background = mix(
        vec3(0.035, 0.045, 0.075),
        vec3(0.08, 0.17, 0.28),
        0.5 + 0.5 * uv.y
    );

    vec3 glow = vec3(0.18, 0.72, 1.0) * exp(-5.0 * abs(radius - 0.42)) * pulse;
    vec3 core = vec3(0.82, 0.95, 1.0) * exp(-14.0 * radius * radius);

    fragColor = vec4(background + glow + core, 1.0);
}
