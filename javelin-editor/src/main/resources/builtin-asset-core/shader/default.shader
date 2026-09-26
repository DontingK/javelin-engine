// @javelin-guid: 5e7b3f0c9a2d4e6b8f1c3d5a7e9b2f4d
Shader "Default"
{
    Properties
    {
    }

    Pass Forward
    {
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
                fragColor = vec4(0.5, 0.0, 1.0, 1.0);
            }
        }
    }
}
