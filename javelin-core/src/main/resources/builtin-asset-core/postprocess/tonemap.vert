#version 450
// 后处理链尾 Tonemap 顶点着色器（Engine_PostProcess_Design §3.5）：三个顶点覆盖全帧
// 缓冲的大三角形（(-1,-1)/(3,-1)/(-1,3)，顶点缓冲直传 NDC）；实际覆盖范围由 Pass 的
// viewport/scissor 限定到本相机视口。本 stage 零声明（无描述符集、无 push）。
layout(location = 0) in vec2 aPos;

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
