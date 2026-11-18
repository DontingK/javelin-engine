#version 450
// RHI 纹理种类底座验证·图形段：sampler3D 采样 compute 写入的体数据（z 取第 7 层体素中心，
// 画面 = 该切片灰度图案）。验证 GENERAL → SHADER_READ_ONLY 跨 Pass 屏障 +
// 3D 默认视图绑定 + 3D 采样。
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler3D voxelTex;

// z 取「第 7 层体素中心」= (7+0.5)/16，而不是 0.5：
// 0.5 恰好落在 z=7 与 z=8 两层之间，而 (x^y^7)&7 与 (x^y^8)&7 两层的值互补
// （v8 = 7 - v7，因 8 的 bit3 被 &7 掩掉后等于异或 7），三线性把两层平均 → 恒 0.5 → 整屏中灰。
const float Z_CENTER = (7.0 + 0.5) / 16.0;

void main() {
    float v = texture(voxelTex, vec3(vUV, Z_CENTER)).r;
    fragColor = vec4(v, v, v, 1.0);
}
