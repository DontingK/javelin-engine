#version 450
// Bloom 降采样 + 模糊（Engine_PostProcess_Design §3.7）：4-tap 帐篷核降采样（半纹素偏移取样，
// LINEAR 采样器自带双线性），逐级 1/2 缩小时隐式高斯近似模糊。set0 binding0 = 上一级 Bloom RT。
layout(set = 0, binding = 0) uniform sampler2D srcColor;

// params 为四 kind 共用块的一部分（本 stage 未用，声明以对齐 push 块尺寸）。
layout(push_constant) uniform BloomPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec2 t = 1.0 / vec2(textureSize(srcColor, 0));
    // 目标为源的一半分辨率：四点在源上半纹素偏移，配双线性即 2x2 盒式低通（模糊）。
    vec3 sum = texture(srcColor, uv + t * vec2(-0.5, -0.5)).rgb;
    sum += texture(srcColor, uv + t * vec2(0.5, -0.5)).rgb;
    sum += texture(srcColor, uv + t * vec2(-0.5, 0.5)).rgb;
    sum += texture(srcColor, uv + t * vec2(0.5, 0.5)).rgb;
    outColor = vec4(sum * 0.25, 1.0);
}
