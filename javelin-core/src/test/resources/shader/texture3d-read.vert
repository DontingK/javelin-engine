#version 450
// RHI 纹理种类底座验证·图形段顶点着色器：NDC 全屏 quad（与 ssbo-read.vert 同款，
// 纯 RHI 层测试、无相机、不经引擎注入）
vec2 positions[6] = vec2[](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
);
layout(location = 0) out vec2 vUV;
void main() {
    vec2 p = positions[gl_VertexIndex];
    vUV = (p + 1.0) * 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
