#version 450

// DebugDrawLine 片段着色器（Editor_DebugLine_Design）：直写顶点色，
// 无混合（v0.1 线不透明，alpha 渐隐后置开标准 blend）。

layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vColor;
}
