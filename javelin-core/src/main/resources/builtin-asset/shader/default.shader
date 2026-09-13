// @javelin-guid: auto-generated
Shader "Javelin/Default" {
    Properties {
        _Color ("颜色", Color) = (0.5, 0.0, 0.5, 1.0)
    }

    Pass Forward {
        renderPath  Forward
        renderLayer Opaque
        zWrite      On
        zTest       LessEqual
        cullMode    Back
        blend       Off

        vs {
            #version 450
            layout(location = 0) in vec3 a_position;

            layout(location = 0) out vec3 v_worldPos;

            void main() {
                vec4 worldPos = u_model * vec4(a_position, 1.0);
                v_worldPos = worldPos.xyz;
                gl_Position = u_proj * u_view * worldPos;
            }
        }

        fs {
            #version 450
            layout(location = 0) in vec3 v_worldPos;
            layout(location = 0) out vec4 o_color;

            layout(std140, set = 1, binding = 0) uniform MaterialParams {
                vec4 _Color;
            };

            void main() {
                o_color = _Color;
            }
        }
    }

    Pass ShadowCaster {
        renderPath  Shadow
        renderLayer Opaque
        zWrite      On
        zTest       Less
        cullMode    Back
        blend       Off

        vs {
            #version 450
            layout(location = 0) in vec3 a_position;

            void main() {
                gl_Position = u_lightProj * u_lightView * u_model * vec4(a_position, 1.0);
            }
        }

        fs {
            #version 450
            void main() {
                // 深度由硬件写入
            }
        }
    }
}
