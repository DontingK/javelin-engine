// @javelin-guid: 8f3a2c1e-9b47-4d21-a5f6-2c8e1d0b7a34
Shader "Default" {
    Properties {
        _Color    ("Tint",       Color)      = (1, 1, 1, 1)
        _Metallic ("Metallic",   Range(0, 1)) = 0.0
        _MainTex  ("Base Map",   Texture2D)   = "white"
        _BumpScale("Bump Scale", Float)       = 1.0
    }
    GlobalUniforms {
        JavelinPerFrame { mat4 u_view; mat4 u_proj; vec3 u_cameraPos; float u_time; }
    }
    Pass Forward {
        renderPath   Forward
        renderLayer  Opaque
        zWrite       On
        zTest        LessEqual
        cullMode     Back
        blend        Off
        vs {
            #version 450
            layout(location = 0) in vec3 a_pos;
            void main() { gl_Position = u_mvp * vec4(a_pos, 1.0); }
        }
        fs {
            #version 450
            layout(location = 0) out vec4 o_color;
            void main() { o_color = texture(_MainTex, vec2(0.5)) * _Color; }
        }
    }
}
