#version 450

// 主光阴影深度顶点着色器（Engine_Lighting_Design §4.5，批次④ ShadowPass）。
// 手写全声明（纯 GLSL 路径，PickPass 同链路——覆盖制不消费材质 Pass 声明）。
// push 布局（ShadowPass 专属 144B，非引擎材质 80B 布局）：
//   lightViewProj@0(64B)：主光正交 世界→光裁剪（sunViewProj 同源值）
//   model@64(64B)：每物体世界矩阵
//   cutoff@128(4B)：alpha 裁剪阈值（0 = 不裁剪，Engine_Shadow_Cutout_Design）
//   hasAlbedoTex@132(4B)：1 = fs 采样 albedo 做裁剪 / 0 = 跳过（无贴图或无 UV0）
// set0 binding0：材质 _MainTex（不裁剪时绑 1×1 白图）。无颜色附件（depth-only）。

layout(push_constant) uniform ShadowPushBlock {
    mat4 lightViewProj;
    mat4 model;
    float cutoff;
    float hasAlbedoTex;
} spc;

layout(location = 0) in vec3 aPos;
layout(location = 3) in vec2 aUV;

layout(location = 0) out vec2 vUV;

void main() {
    gl_Position = spc.lightViewProj * spc.model * vec4(aPos, 1.0);
    vUV = aUV;
}
