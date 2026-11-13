#version 450
// 后处理 Bloom 顶点着色器（Engine_PostProcess_Design §3.7）：与 Tonemap 同款全屏大三角形
// （(-1,-1)/(3,-1)/(-1,3)，顶点缓冲直传 NDC），额外输出 vUV（NDC→[0,1]，在 [-1,1] 可见区
// 恰为 0..1）——片元侧配合 push 常量 uvRect 换算源纹理坐标（跨视口区域与多级降采样通吃）。
// 四个 bloom_*.frag 共用本 stage。本 stage 零描述符集声明。
layout(location = 0) in vec2 aPos;
layout(location = 0) out vec2 vUV;

void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
