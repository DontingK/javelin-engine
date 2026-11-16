Shader "Default"
{
    // 非光照（施工序 ⑤）：错误兜底材质直读 albedo，不被 LightingPass 乘光照
    //（无灯场景也须可见——红线 15 错误显性化）
    LightingModel Unlit

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

    // G-Buffer 兜底通道（批次④-B）：仍是「坏 shader stub 声明 GBuffer」时的 PSO 回退源
    //（RenderingSystem.pipelineFor 落到 DefaultMaterial 取同名 Pass）。本 shader 自身是
    // Unlit，收集期不会把自己的 GBuffer 段入桶（collectList 对 UNLIT 跳过），
    // 真正的可见兜底由 UnlitForwardPass 画 Forward 段完成。
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
