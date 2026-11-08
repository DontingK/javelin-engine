Shader "Gizmo"
{
    Properties
    {
        _Color ("Tint Color", Color) = (1.0, 1.0, 1.0, 1.0)
    }

    Pass Gizmo
    {
        // Cull Back：几何由 GizmoMeshes 程序化生成（2026-11-08，原 axisY.smesh 退役），
        // 绕序统一朝外（右手系 cross(v1-v0, v2-v0) 外向约定）
        Cull Back
        ZWrite On
        ZTest LEqual
        Blend Off

        vs {
            #version 450
            layout(location = 0) in vec3 aPos;
            void main() {
                gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
            }
        }
        fs {
            #version 450
            layout(location = 0) out vec4 fragColor;
            void main() {
                // unlit 纯色：TransformGizmoAppState 按轴经材质实例下发红/绿/蓝
                fragColor = material._Color;
            }
        }
    }
}
