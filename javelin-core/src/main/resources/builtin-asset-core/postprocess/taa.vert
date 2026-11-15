#version 450
// TAA 顶点着色器（Engine_TAA_Design §3.6）：与 ssao.vert/bloom.vert 同款全屏大三角形
// （(-1,-1)/(3,-1)/(-1,3)，顶点缓冲直传 NDC），输出 vUV（NDC→[0,1]，在 [-1,1] 可见区恰为 0..1）。
// 片元侧配合参数块/copy push 的 uvRect 换算全帧纹理坐标（分屏视口通吃）。
// taa.frag / taa_copy.frag 共用本 stage。本 stage 零描述符集声明。
layout(location = 0) in vec2 aPos;
layout(location = 0) out vec2 vUV;

void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
