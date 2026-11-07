#version 450

// GPU ID-buffer 拾取顶点着色器（Engine_Picking_Design）。
// 手写全声明（纯 GLSL 路径，实例名与引擎注入模板一致：cam/pc——write-shader 契约）。
// push 布局：model@0(64B) + pickId@64(int)——复用引擎 80B 声明，tpf 槽位让渡（拾取无时间语义）。

layout(set=0, binding=0) uniform CameraBlock { mat4 viewProj; } cam;
layout(push_constant) uniform PushBlock { mat4 model; int pickId; } pc;

layout(location = 0) in vec3 aPos;

void main() {
    gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
}
