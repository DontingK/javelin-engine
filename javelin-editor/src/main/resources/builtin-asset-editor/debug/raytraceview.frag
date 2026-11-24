#version 450
// 光追求交直显片元着色器（P4-06 B3）：把半分辨率命中距离铺满视口。
// 命中 = 近蓝远橙的渐变（看清远近层次），miss = 深蓝背景。
layout(set = 0, binding = 0) uniform sampler2D hitDistance;

layout(push_constant) uniform PushBlock {
    ivec2 targetSize;   // 视口像素尺寸（帧缓冲尺寸，非源图尺寸）
} pc;

layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(pc.targetSize);
    float v = texture(hitDistance, uv).r;
    vec3 color = v < 0.0
            ? vec3(0.05, 0.08, 0.16)
            : mix(vec3(0.15, 0.65, 1.00), vec3(1.00, 0.35, 0.08), clamp(v, 0.0, 1.0));
    outColor = vec4(color, 1.0);
}
