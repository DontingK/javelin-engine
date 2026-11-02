#version 450

// 天空盒顶点着色器（Engine_Skybox_Design §4，批次⑤ SkyboxPass）。
// 手写全声明（纯 GLSL 路径，PickPass/shadow 同链路——覆盖制不消费材质 Pass 声明）。
// set0 = 相机块（与材质 shader 注入的 CameraBlock 同布局：viewProj@0 + view@64，
// UBO 动态偏移选视口相机）；无 push（立方体以相机为中心 = view 去平移）。
//
// 深度语义（拍板 2026-11-02）：gl_Position.z = w 推到远平面 + ZTest LEqual +
// ZWrite Off（fs 侧 PSO 控制）——免调深度上限，不污染深度缓冲。

layout(set = 0, binding = 0) uniform CameraBlock {
    mat4 viewProj;
    mat4 view;
} cam;

layout(location = 0) in vec3 aPos;
layout(location = 0) out vec3 vDir;

void main() {
    // 去平移视图（mat4(mat3) 保旋转、丢平移）：立方体恒以相机为中心，无视锥剔除
    mat4 rotationOnlyView = mat4(mat3(cam.view));
    gl_Position = cam.viewProj * rotationOnlyView * vec4(aPos, 1.0);
    gl_Position.z = gl_Position.w;   // 推至远平面（z/w = 1.0）
    vDir = aPos;                     // 世界方向（无模型变换，单位立方体轴向即方向）
}
