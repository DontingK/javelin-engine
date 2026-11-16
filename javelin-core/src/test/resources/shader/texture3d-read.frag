#version 450
// RHI 纹理种类底座验证·图形段：sampler3D 采样 compute 写入的体数据（z 取 0.5 切片，
// 画面 = 该切片灰度图案）。验证 GENERAL → SHADER_READ_ONLY 跨 Pass 屏障 +
// 3D 默认视图绑定 + 3D 采样。
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler3D voxelTex;

void main() {
    float v = texture(voxelTex, vec3(vUV, 0.5)).r;
    fragColor = vec4(v, v, v, 1.0);
}
