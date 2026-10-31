#version 450
// 三期批次① 流程验证·图形读回段：全屏 quad fs 直读段 B 输出（灰度 buffer）写颜色附件。
// 测试资产：PushUniformTest 形态自建 PSO（set0 自定义 SSBO 布局，不经引擎 set1 材质链），
// 验证「compute 写 → 图形 Pass 读」跨 Pass buffer 同步 + 读回数据比对。
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 fragColor;

layout(std430, set = 0, binding = 0) readonly buffer DataB {
    vec4 elements[65536];
} dataB;

void main() {
    uint x = uint(vUV.x * 256.0);
    uint y = uint(vUV.y * 256.0);
    uint i = y * 256u + x;
    fragColor = dataB.elements[i % 65536u];
}
