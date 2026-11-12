#version 450
// G-Buffer 可视化顶点着色器（编辑器排障面 dbg-2，Engine_DebugTools_Plan §3）：与 core
// LightingPass 同款全屏大三角形（(-1,-1)/(3,-1)/(-1,3)，顶点缓冲直传 NDC）；实际覆盖
// 范围由 Pass 的 viewport/scissor 限定到编辑器相机视口。零声明——选择三张附件的 mode
// 走 fs 侧 push 常量。
layout(location = 0) in vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
