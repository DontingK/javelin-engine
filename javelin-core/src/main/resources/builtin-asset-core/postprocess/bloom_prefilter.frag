#version 450
// Bloom 阈值提取（Engine_PostProcess_Design §3.7）：读 HDR 场景色，软阈值留亮部，写 1/2 级
// Bloom RT（目标半分辨率 → LINEAR 采样隐式 2x2 降采样）。set0 binding0 = 链输入（场景色）。
// 参数（THRESHOLD/KNEE）为着色器内起步常量，接设置后置。
layout(set = 0, binding = 0) uniform sampler2D srcColor;

// uvRect：xy = 视口区域左上（源纹理归一化坐标），zw = 视口区域尺寸（源纹理归一化）——
// 由 vUV（视口内 0..1）映射到源纹理绝对区域，正确支撑分屏视口与多级降采样。
layout(push_constant) uniform BloomPush {
    vec4 uvRect;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

// 亮度阈值起步值（与 Tonemap 高光肩部 KNEE=0.8 同口径：肩部以上的高光才起辉）。
const float THRESHOLD = 0.8;
// 软过渡半宽：亮度在 THRESHOLD±KNEE 间平滑登场（避免硬阈值造成的边缘跳变）。
const float KNEE = 0.4;

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec3 c = texture(srcColor, uv).rgb;
    float brightness = max(c.r, max(c.g, c.b));
    // 软膝：低于 (THRESHOLD-KNEE) 不计，高于 (THRESHOLD+KNEE) 全计，中间二次平滑。
    float soft = clamp(brightness - THRESHOLD + KNEE, 0.0, 2.0 * KNEE);
    soft = soft * soft / (4.0 * KNEE + 1e-4);
    float contribution = max(soft, brightness - THRESHOLD) / max(brightness, 1e-4);
    outColor = vec4(c * contribution, 1.0);
}
