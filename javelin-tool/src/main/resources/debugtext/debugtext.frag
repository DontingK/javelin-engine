#version 450
// DebugText 片元着色器——按字形覆盖度做前景/背景混合（PSO 固定态无 blending、无深度）
layout(set = 0, binding = 0) uniform sampler2D fontTex;   // 字库图集（R8_UNORM，nearest 点采样）
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vFg;
layout(location = 2) in vec4 vBg;
layout(location = 0) out vec4 outColor;
void main() {
    float cov = texture(fontTex, vUv).r;
    outColor = mix(vBg, vFg, cov);
}
