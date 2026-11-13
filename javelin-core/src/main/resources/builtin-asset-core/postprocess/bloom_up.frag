#version 450
// Bloom 上采样累加（Engine_PostProcess_Design §3.7）：9-tap 帐篷核上采样，配加性混合
// （dst = dst + src，由 BloomPass 的 up PSO 设定）把上一级（更小）结果叠加进本级。
// set0 binding0 = 上一级 Bloom RT。
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
    // 9-tap 帐篷核（中心权重 4、边 2、角 1，总 16）——上采样时平滑过渡、无块状。
    vec3 sum = texture(srcColor, uv + t * vec2(-1.0, -1.0)).rgb;
    sum += texture(srcColor, uv + t * vec2(0.0, -1.0)).rgb * 2.0;
    sum += texture(srcColor, uv + t * vec2(1.0, -1.0)).rgb;
    sum += texture(srcColor, uv + t * vec2(-1.0, 0.0)).rgb * 2.0;
    sum += texture(srcColor, uv).rgb * 4.0;
    sum += texture(srcColor, uv + t * vec2(1.0, 0.0)).rgb * 2.0;
    sum += texture(srcColor, uv + t * vec2(-1.0, 1.0)).rgb;
    sum += texture(srcColor, uv + t * vec2(0.0, 1.0)).rgb * 2.0;
    sum += texture(srcColor, uv + t * vec2(1.0, 1.0)).rgb;
    outColor = vec4(sum / 16.0, 1.0);
}
