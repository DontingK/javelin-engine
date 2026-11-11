Shader "Lit"
{
    Properties
    {
        _Color ("Main Color", Color) = (1, 1, 1, 1)
        _MainTex ("Albedo", 2D) = "white" {}
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
            layout(location = 1) in vec3 aNormal;
            layout(location = 3) in vec2 aUV;
            layout(location = 0) out vec3 vViewPos;
            layout(location = 1) out vec3 vNormal;
            layout(location = 2) out vec2 vUV;
            layout(location = 3) out vec3 vWorldPos;
            void main() {
                vec4 world = pc.model * vec4(aPos, 1.0);
                gl_Position = cam.viewProj * world;
                // 视空间插值：受光计算全在视空间（javelinLighting 消费口径）
                vViewPos = (cam.view * world).xyz;
                // 法线世界→视空间；逆置矩阵吸收非均匀缩放
                vNormal = mat3(cam.view) * (transpose(inverse(mat3(pc.model))) * aNormal);
                vUV = aUV;
                // 世界位置插值：主光阴影采样重建（javelinLighting 第三参）
                vWorldPos = world.xyz;
            }
        }
        fs {
            #version 450
            layout(location = 0) in vec3 vViewPos;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUV;
            layout(location = 3) in vec3 vWorldPos;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 tex = texture(_MainTex, vUV);
                // Lambert + 环境项 + 点/聚光衰减 + 主光硬影（引擎注入函数，
                // 参数=视空间法线/位置 + 世界位置）
                vec3 lit = javelinLighting(normalize(vNormal), vViewPos, vWorldPos);
                fragColor = vec4(material._Color.rgb * tex.rgb * lit, material._Color.a * tex.a);
            }
        }
    }

    // G-Buffer 几何通道（HYBRID 档不透明几何，批次④-B）。写三张 MRT：
    // albedo(location 0) / 世界法线(location 1) / 世界位置+w=1(location 2)。
    // 光照不在本 Pass 算——由全屏 LightingPass 读 G-Buffer 直喂同一 cluster 着色。
    Pass GBuffer
    {
        Cull Back
        ZWrite On
        ZTest LEqual
        Blend Off

        vs {
            #version 450
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 3) in vec2 aUV;
            layout(location = 0) out vec3 vWorldNormal;
            layout(location = 1) out vec3 vWorldPos;
            layout(location = 2) out vec2 vUV;
            void main() {
                vec4 world = pc.model * vec4(aPos, 1.0);
                gl_Position = cam.viewProj * world;
                // 世界空间法线（逆置矩阵吸收非均匀缩放）；视空间换算归 LightingPass
                vWorldNormal = normalize(transpose(inverse(mat3(pc.model))) * aNormal);
                vWorldPos = world.xyz;
                vUV = aUV;
            }
        }
        fs {
            #version 450
            layout(location = 0) in vec3 vWorldNormal;
            layout(location = 1) in vec3 vWorldPos;
            layout(location = 2) in vec2 vUV;
            layout(location = 0) out vec4 outAlbedo;
            layout(location = 1) out vec4 outNormal;
            layout(location = 2) out vec4 outWorldPos;
            void main() {
                vec4 tex = texture(_MainTex, vUV);
                outAlbedo = vec4(material._Color.rgb * tex.rgb, 1.0);
                outNormal = vec4(normalize(vWorldNormal), 0.0);
                // w = 有效标志（几何 1.0 / 清屏 0.0）：LightingPass 据此跳过背景像素
                outWorldPos = vec4(vWorldPos, 1.0);
            }
        }
    }
}
