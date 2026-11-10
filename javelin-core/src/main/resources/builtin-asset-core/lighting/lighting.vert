#version 450
// 全屏光照顶点着色器（批次④-B，LightingPass）：三个顶点覆盖全帧缓冲的大三角形
// （(-1,-1)/(3,-1)/(-1,3)，顶点缓冲直传 NDC）；实际覆盖范围由 Pass 的
// viewport/scissor 限定到本相机视口。set0/set1 声明由 fs 侧注入，本 stage 零声明。
layout(location = 0) in vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
