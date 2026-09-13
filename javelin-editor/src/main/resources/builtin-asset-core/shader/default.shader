// @javelin-guid: a1d2830d83ec423d94eec58c947f94fb
Shader "Javelin/Default" {
    Properties {
        _Color ("颜色", Color) = (1, 1, 1, 1)
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
            layout(location = 0) in vec3 a_pos;
            void main() {
                gl_Position = u_mvp * vec4(a_pos, 1.0);
            }
        }

        fs {
            #version 450
            layout(location = 0) out vec4 o_color;
            void main() {
                o_color = _Color;
            }
        }
    }
}
