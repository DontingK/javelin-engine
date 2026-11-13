#version 450
// Bloom 阈值提取（Engine_PostProcess_Design §3.7）：读 HDR 场景色，软阈值留亮部，写 1/2 级
// Bloom RT（目标半分辨率 → LINEAR 采样隐式 2x2 降采样）。set0 binding0 = 链输入（场景色）。
// 参数走 push 常量（运行期可由「项目设置」调，2026-11-14 常量改 push）。
layout(set = 0, binding = 0) uniform sampler2D srcColor;

// uvRect：xy = 视口区域左上（源纹理归一化坐标），zw = 视口区域尺寸（源纹理归一化）——
// 由 vUV（视口内 0..1）映射到源纹理绝对区域，正确支撑分屏视口与多级降采样。
// params：x = THRESHOLD（亮度阈值）、y = KNEE（软过渡半宽）；z/w 未用（四 kind 共用块）。
layout(push_constant) uniform BloomPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec3 c = texture(srcColor, uv).rgb;
    float threshold = pc.params.x;
    float knee = pc.params.y;
    float brightness = max(c.r, max(c.g, c.b));
    // 软膝：低于 (threshold-knee) 不计，高于 (threshold+knee) 全计，中间二次平滑（knee=0 → 硬阈值）。
    float soft = clamp(brightness - threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float contribution = max(soft, brightness - threshold) / max(brightness, 1e-4);
    outColor = vec4(c * contribution, 1.0);
}
