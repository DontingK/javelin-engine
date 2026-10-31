#version 450
// 三期批次① 流程验证·图形读回段顶点着色器：NDC 全屏 quad（PushUniformTest 同款，
// 不用引擎 cam/pc 注入——纯 RHI 层测试，无相机）
vec2 positions[6] = vec2[](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
);
layout(location = 0) out vec2 vUV;
void main() {
    vec2 p = positions[gl_VertexIndex];
    vUV = (p + 1.0) * 0.5;
    // Vulkan NDC Y 朝下：翻转 UV 使 gl_VertexIndex 三角形方向与 UV 一致
    gl_Position = vec4(p, 0.0, 1.0);
}
