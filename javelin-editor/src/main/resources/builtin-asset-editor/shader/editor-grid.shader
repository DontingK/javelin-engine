Shader "EditorGrid"
{
    Properties
    {
        _GridColor ("Grid Color", Color) = (0.5, 0.5, 0.5, 1.0)
        _FadeStart ("Fade Start Distance", Float) = 50.0
        _FadeEnd ("Fade End Distance", Float) = 100.0
        _CamPos ("Camera World Position", Vector) = (0.0, 0.0, 0.0, 0.0)
    }

    Pass Forward
    {
        Cull Off
        ZWrite Off
        ZTest LEqual
        Blend SrcAlpha OneMinusSrcAlpha
        ColorMask RGBA

        vs {
            #version 450
            layout(location = 0) in vec3 aPos;
            layout(location = 0) out vec3 v_worldPos;
            void main() {
                v_worldPos = (pc.model * vec4(aPos, 1.0)).xyz;
                gl_Position = cam.viewProj * vec4(v_worldPos, 1.0);
            }
        }
        fs {
            #version 450
            layout(location = 0) in vec3 v_worldPos;
            layout(location = 0) out vec4 fragColor;
            void main() {
                // 距相机 XZ 平面径向距离淡出：近处实线、远处渐隐（防远处网格噪点）
                // 相机世界位置与淡出半径由 EditGridAppState 每帧经材质数值属性下发
                float dist = length(v_worldPos.xz - material._CamPos.xz);
                float fade = 1.0 - smoothstep(material._FadeStart, material._FadeEnd, dist);
                vec4 color = material._GridColor;
                color.a *= fade;
                fragColor = color;
            }
        }
    }
}
