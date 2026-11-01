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
            layout(location = 2) in vec2 aUV;
            layout(location = 0) out vec3 vViewPos;
            layout(location = 1) out vec3 vNormal;
            layout(location = 2) out vec2 vUV;
            void main() {
                vec4 world = pc.model * vec4(aPos, 1.0);
                gl_Position = cam.viewProj * world;
                // 视空间插值：受光计算全在视空间（javelinLighting 消费口径）
                vViewPos = (cam.view * world).xyz;
                // 法线世界→视空间；逆置矩阵吸收非均匀缩放
                vNormal = mat3(cam.view) * (transpose(inverse(mat3(pc.model))) * aNormal);
                vUV = aUV;
            }
        }
        fs {
            #version 450
            layout(location = 0) in vec3 vViewPos;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUV;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 tex = texture(_MainTex, vUV);
                // Lambert + 环境项 + 点/聚光衰减（引擎注入函数，参数=视空间法线/位置）
                vec3 lit = javelinLighting(normalize(vNormal), vViewPos);
                fragColor = vec4(material._Color.rgb * tex.rgb * lit, material._Color.a * tex.a);
            }
        }
    }
}
