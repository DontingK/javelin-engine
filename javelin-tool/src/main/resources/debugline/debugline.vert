#version 450

// DebugDrawLine 顶点着色器（Editor_DebugLine_Design，编辑器专属调试线）。
// 手写全声明（纯 GLSL 路径，实例名与引擎注入模板一致：cam——write-shader 契约）。
// 顶点属性：loc0 世界坐标 pos / loc1 RGBA8 顶点色（UNORM 解包 vec4）。

layout(set=0, binding=0) uniform CameraBlock { mat4 viewProj; } cam;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

void main() {
    vColor = aColor;
    gl_Position = cam.viewProj * vec4(aPos, 1.0);
}
