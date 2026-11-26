Shader "Standard"
{
    Properties
    {
        _Color ("Main Color", Color) = (1, 1, 1, 1)
        _MainTex ("Albedo", 2D) = "white" {}
        // alpha 裁剪阈值（REND-21-A）：0 = 不裁剪（默认）；> 0 时 albedo.a 低于阈值丢弃，
        // 主渲染与阴影 Pass 同阈值（Engine_Shadow_Cutout_Design）
        _AlphaCutoff ("Alpha Cutoff", Range(0, 1)) = 0
        // PBR 材质参数（REND-03）：粗糙度/金属度。缺省 0.5/0 即"半粗糙电介质"，
        // 与旧 Lambert 观感最接近的一档；HYBRID 档写入 G-Buffer material（r=rough、g=metal）
        _Roughness ("Roughness", Range(0, 1)) = 0.5
        _Metallic ("Metallic", Range(0, 1)) = 0.0
    }

    // 物理基线（GGX + Fresnel + 能量守恒）。Lambert 仍保留为可选值（老 shader 不声明即 Lambert）
    LightingModel PBR

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
                javelinAlphaClip(tex, material._AlphaCutoff);
                vec3 albedo = material._Color.rgb * tex.rgb;
                // PBR（REND-03）：albedo 进 BRDF（F0 = mix(0.04, albedo, metallic)、漫反射
                // 份额 = albedo×(1-metallic)）；roughness/metallic 来自材质 UBO 两个标量
                vec3 lit = javelinLightingPbr(normalize(vNormal), vViewPos, vWorldPos, 1.0,
                        albedo, vec2(material._Roughness, material._Metallic));
                fragColor = vec4(lit, material._Color.a * tex.a);
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
            layout(location = 3) out vec4 outMaterial;
            void main() {
                vec4 tex = texture(_MainTex, vUV);
                javelinAlphaClip(tex, material._AlphaCutoff);
                // albedo.a = per-object 接收阴影标志（四期 §3.6，HYBRID 档承载）：几何写
                // pc.receiveShadow（1/0），全屏 LightingPass 经 javelinReceiveShadow 取回
                outAlbedo = vec4(material._Color.rgb * tex.rgb, pc.receiveShadow);
                outNormal = vec4(normalize(vWorldNormal), 0.0);
                // w = 有效标志（几何 1.0 / 清屏 0.0）：LightingPass 据此跳过背景像素
                outWorldPos = vec4(vWorldPos, 1.0);
                // material 通道（REND-03）：r=roughness、g=metallic。不写这条的旧 shader 会保持
                // 清屏值 (1,0,0,1) = 全粗糙非金属，PBR 光照自动退化为漫反射
                outMaterial = vec4(material._Roughness, material._Metallic, 0.0, 1.0);
            }
        }
    }
}
