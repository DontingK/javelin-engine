#version 450
// DebugText 顶点着色器——顶点直接 NDC（自建 PSO，不走 set0 cam / set2 material / push 通道）
layout(location = 0) in vec2 aPos;   // NDC xy（CPU 侧按格子算好）
layout(location = 1) in vec2 aUv;    // 字库图集 uv
layout(location = 2) in vec4 aFg;    // 前景色（RGBA8 归一化，CPU 查 16 色调色板打包）
layout(location = 3) in vec4 aBg;   // 背景色
layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vFg;
layout(location = 2) out vec4 vBg;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aUv;
    vFg = aFg;
    vBg = aBg;
}
