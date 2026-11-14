#version 450
// SSAO 顶点着色器（Engine_SSAO_Design §3.3）：与 bloom.vert 同款全屏大三角形
// （(-1,-1)/(3,-1)/(-1,3)，顶点缓冲直传 NDC），输出 vUV（NDC→[0,1]，在 [-1,1] 可见区恰为 0..1）
// ——片元侧配合 push 常量 uvRect 换算 G-Buffer/场景色坐标（半分辨率 + 分屏视口通吃）。
// ssao.frag / ssao_composite.frag 共用本 stage。本 stage 零描述符集声明。
layout(location = 0) in vec2 aPos;
layout(location = 0) out vec2 vUV;

void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
