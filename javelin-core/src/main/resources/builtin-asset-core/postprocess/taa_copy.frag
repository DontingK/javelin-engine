#version 450
// TAA 拷贝 + 去抖动（Engine_TAA_Design §3.6 第二 Pass）：历史 RT（全帧尺寸）→ 链输出（本视口区域）。
// 跨视口坐标换算走 push 常量 uvRect（与 ssao_composite.frag 同口径）；set0 binding0 = 历史色。
// 去抖：历史锚在本帧抖动栅格上（resolve 重投影只对齐上一帧），输出前把本帧抖动平移回去
// （采样 +j 像素 = 画面平移 -j）——不做这步，Halton 相位每帧变 → 整屏逐帧跳动。
// 抖动关闭时偏移恒 0（退化为 1:1 直拷）。历史 LINEAR 采样（亚像素偏移双线性插值）。
// 覆盖制、零光照注入（注释里也不写注入标记字面量——会误触导入期注入致撞名）。
layout(set = 0, binding = 0) uniform sampler2D historyColor;

layout(push_constant) uniform TaaCopyPush {
    vec4 uvRect;
    vec4 jitter;   // xy = 本帧抖动像素偏移（已减 0.5 的 Halton 值，UV 换算除以全帧宽高）
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec2 texel = vec2(1.0, 1.0) / vec2(textureSize(historyColor, 0));
    vec2 unjittered = uv + pc.jitter.xy * texel;
    // 去抖后边缘半像素落在历史内容之外（历史只写了本视口区域）——夹回 uvRect 内防漏采样
    unjittered = clamp(unjittered, pc.uvRect.xy, pc.uvRect.xy + pc.uvRect.zw);
    outColor = texture(historyColor, unjittered);
}
