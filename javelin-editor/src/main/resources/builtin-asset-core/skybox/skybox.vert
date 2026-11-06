#version 450

// 天空盒顶点着色器（Engine_Skybox_Design §4，批次⑤ SkyboxPass）。
// 手写全声明（纯 GLSL 路径，PickPass/shadow 同链路——覆盖制不消费材质 Pass 声明）。
// set0 = 相机块（与材质 shader 注入的 CameraBlock 同布局：viewProj@0 + view@64，
// UBO 动态偏移选视口相机）；无 push。
//
// 深度语义（拍板 2026-11-02）：gl_Position.z = w 推到远平面 + ZTest LEqual +
// ZWrite Off（fs 侧 PSO 控制）——免调深度上限，不污染深度缓冲。
//
// 立方体以相机为中心（2026-11-06 修复）：平移到相机世界位置 p = -Rᵀ·t
// （view = [R|t]），旋转由 viewProj 正常施加——view·(w+p) = R·w 平移自然抵消。
// 原写法 viewProj * mat4(mat3(view)) 复合出 proj·(R²w+t)（[R|t]·[R|0]=[R²|t]）：
// 相机在原点仅天空转速×2（自旋 Demo 未暴露），离原点 |t|>1 相机跑出单位立方体
// ——视向无几何=黑区。

layout(set = 0, binding = 0) uniform CameraBlock {
    mat4 viewProj;
    mat4 view;
} cam;

layout(location = 0) in vec3 aPos;
layout(location = 0) out vec3 vDir;

void main() {
    // 相机世界位置（view 旋转的逆转 × 平移列）：立方体跟着相机走
    vec3 camPos = -transpose(mat3(cam.view)) * cam.view[3].xyz;
    gl_Position = cam.viewProj * vec4(aPos + camPos, 1.0);
    gl_Position.z = gl_Position.w;   // 推至远平面（z/w = 1.0）
    vDir = aPos;                     // 世界方向（立方体轴向即方向，与位置无关）
}
