#version 450
// SSAO 合成（Engine_SSAO_Design §3.3）：场景色 × mix(1, AO, intensity)，写链输出
// （Tonemap 也在链上时写其中间 RT 交 Tonemap；Tonemap 关时直写显示目标）。
// 背景处 AO=1（ssao.frag 对 worldPos.w<0.5 输出 1）→ 天空盒不受影响。
// set0 binding0 = 场景色（链输入，HDR）、binding1 = AO（半分辨率，LINEAR 上采样）。
layout(set = 0, binding = 0) uniform sampler2D sceneColor;
layout(set = 0, binding = 1) uniform sampler2D aoTexture;

// params：z = intensity（x/y/w 未用；与 ssao.frag 共用同一 push 块）
layout(push_constant) uniform SsaoPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec3 scene = texture(sceneColor, uv).rgb;
    float ao = texture(aoTexture, uv).r;
    outColor = vec4(scene * mix(1.0, ao, pc.params.z), 1.0);
}
