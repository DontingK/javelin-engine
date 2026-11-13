#version 450
// 后处理链尾 Tonemap 片元着色器（Engine_PostProcess_Design §3.5）：读链输入（HDR 场景色，
// RGBA16F）经高光肩部压到 LDR 写显示目标。set0 = 本 Pass 专属布局 binding0 组合采样器
// （TonemapPass 自建，不占相机/光照/材质 set 契约）。
layout(set = 0, binding = 0) uniform sampler2D srcColor;

layout(location = 0) out vec4 outColor;

// 肩部起点：≤KNEE 逐像素不变（保住既有画面的对比与纯度），>KNEE 指数肩部压向 1.0 上限。
//
// 为何不用 ACES 等电影曲线（2026-11-13 实机反馈「开了发灰、不纯」后换装）：ACES 的中调
// 是抬亮的（本曲线口径下 0.18→0.27、0.5→0.62、1.0→0.80），而当前场景内容几乎全 ≤1、
// 又是未经 sRGB 线性工作流的显示态值（sRGB 真解 = AST-03 / P4-09 后置）——于是"压高光"
// 的收益拿不到，"提中调 + 逐通道去饱和"的副作用全吃下，观感即发灰、不纯。
// 换装后正常亮度区与原画面一致，仅 >0.8 的高光平滑压入 1.0（C1 连续、永不硬截白）。
const float KNEE = 0.8;

vec3 highlightShoulder(vec3 x) {
    vec3 over = max(x - vec3(KNEE), vec3(0.0));
    return min(x, vec3(KNEE))
            + (1.0 - KNEE) * (1.0 - exp(-over / (1.0 - KNEE)));
}

void main() {
    // 链输入与帧缓冲同尺寸、按同一 viewport/scissor 栅格化（分屏时内容落在各自的
    // 绝对帧缓冲位置）——故按 gl_FragCoord 绝对坐标换算纹理 UV，1:1 取回本像素
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(srcColor, 0));
    vec3 hdr = texture(srcColor, uv).rgb;
    outColor = vec4(highlightShoulder(hdr), 1.0);
}
