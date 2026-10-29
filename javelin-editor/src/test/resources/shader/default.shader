Shader "TestDefault"
{
    Properties
    {
        _Color    ("Tint",       Color)      = (1, 1, 1, 1)
        _Metallic ("Metallic",   Range(0, 1)) = 0.0
        _MainTex  ("Base Map",   2D)         = "white" {}
        _BumpMap  ("Normal Map", 2D)         = "bump" {}
        _BumpScale("Bump Scale", Float)      = 1.0
    }

    // 不透明 Pass：默认状态（Cull Back / ZWrite On / ZTest LEqual / Blend Off）
    Pass Forward
    {
        Cull Back
        ZWrite On
        ZTest LEqual
        Blend Off

        vs {
            #version 450
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 0) out vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
            }
        }

        fs {
            #version 450
            layout(location = 0) in vec2 vUV;
            layout(location = 0) out vec4 o_color;
            void main() {
                o_color = texture(_MainTex, vUV) * material._Color;
            }
        }
    }

    // 透明 Pass：混合分桶 = Blend 态
    Pass Transparent
    {
        Blend SrcAlpha OneMinusSrcAlpha
        ZWrite Off

        vs {
            #version 450
            layout(location = 0) in vec3 aPos;
            void main() {
                gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
            }
        }

        fs {
            #version 450
            layout(location = 0) out vec4 o_color;
            void main() {
                o_color = material._Color;
            }
        }
    }
}
