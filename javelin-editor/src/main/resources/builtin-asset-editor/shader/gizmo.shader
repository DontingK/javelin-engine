Shader "Gizmo"
{
    Properties
    {
        _Color ("Tint Color", Color) = (1.0, 1.0, 1.0, 1.0)
    }

    Pass Gizmo
    {
        // Cull Off：变换 Gizmo 网格（axisY.smesh 箭头）来自外部工具导出，
        // 绕序不保证一致（正反面混合），双面渲染绕开绕序约定
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
