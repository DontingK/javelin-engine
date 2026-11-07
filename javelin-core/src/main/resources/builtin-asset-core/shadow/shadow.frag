#version 450

// 主光阴影深度片元着色器（Engine_Lighting_Design §4.5，批次④ ShadowPass）。
// depth-only Pass：无颜色附件、无输出（空体仅为满足 PSO 阶段非空约束——
// Vulkan 允许无 fs，但引擎 GraphicsPipelineDesc 校验 fs 非空，空 main 同效果零成本）。

void main() {
}
