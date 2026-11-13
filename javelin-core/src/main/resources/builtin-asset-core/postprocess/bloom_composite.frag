#version 450
// Bloom 合成（Engine_PostProcess_Design §3.7）：场景色 + Bloom 结果 × 强度，写链输出
// （Tonemap 也在链上时写其中间 RT 交 Tonemap；Tonemap 关时直写显示目标）。
// set0 binding0 = 场景色（未 tonemap 的 HDR 缓冲）、binding1 = Bloom 结果（1/2 级 RT）。
// 强度走 push 常量（运行期可由「项目设置」调）。
layout(set = 0, binding = 0) uniform sampler2D sceneColor;
layout(set = 0, binding = 1) uniform sampler2D bloomColor;

// params：z = INTENSITY（x/y/w 未用；与 prefilter/down/up 共用同一 push 块）。
layout(push_constant) uniform BloomPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec3 scene = texture(sceneColor, uv).rgb;
    vec3 bloom = texture(bloomColor, uv).rgb;
    outColor = vec4(scene + bloom * pc.params.z, 1.0);
}
