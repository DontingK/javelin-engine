#version 450

// 主光阴影深度顶点着色器（Engine_Lighting_Design §4.5，批次④ ShadowPass）。
// 手写全声明（纯 GLSL 路径，PickPass 同链路——覆盖制不消费材质 Pass 声明）。
// push 布局（ShadowPass 专属 128B，非引擎材质 80B 布局）：
//   lightViewProj@0(64B)：主光正交 世界→光裁剪（sunViewProj 同源值）
//   model@64(64B)：每物体世界矩阵
// 无颜色附件（depth-only），无描述符集。

layout(push_constant) uniform ShadowPushBlock {
    mat4 lightViewProj;
    mat4 model;
} spc;

layout(location = 0) in vec3 aPos;

void main() {
    gl_Position = spc.lightViewProj * spc.model * vec4(aPos, 1.0);
}
