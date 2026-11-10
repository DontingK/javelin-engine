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

    // G-Buffer 兜底通道（批次④-B）：HYBRID 档下缺/坏材质的物体仍以纯紫出现在
    // 不透明几何里——保住「错误显性化」语义（default.shader 仍是兜底专用，
    // 不改造成光照注入样例）。法线取常量 +Z（非法线 mesh 亦有可见受光面）。
    Pass GBuffer
    {
        Cull Back
        ZWrite On
        ZTest LEqual
        Blend Off

        vs {
            #version 450
            layout(location = 0) in vec3 aPos;
            layout(location = 0) out vec3 vWorldPos;
            void main() {
                vec4 world = pc.model * vec4(aPos, 1.0);
                gl_Position = cam.viewProj * world;
                vWorldPos = world.xyz;
            }
        }
        fs {
            #version 450
            layout(location = 0) in vec3 vWorldPos;
            layout(location = 0) out vec4 outAlbedo;
            layout(location = 1) out vec4 outNormal;
            layout(location = 2) out vec4 outWorldPos;
            void main() {
                outAlbedo = vec4(0.5, 0.0, 1.0, 1.0);
                outNormal = vec4(0.0, 0.0, 1.0, 0.0);
                outWorldPos = vec4(vWorldPos, 1.0);
            }
        }
    }
}
