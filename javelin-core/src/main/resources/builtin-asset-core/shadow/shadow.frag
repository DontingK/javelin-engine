#version 450

// 主光阴影深度片元着色器（REND-21-A alpha 裁剪，Engine_Shadow_Cutout_Design §5.2）。
// 手写全声明（纯 GLSL 路径，覆盖制不消费材质 Pass 声明）。
// push 布局见 shadow.vert（144B，cutoff@128 / hasAlbedoTex@132）。
// 行为：cutoff > 0 且 hasAlbedoTex > 0.5 时采样材质 albedo，alpha < cutoff → discard，
// 使镂空/植被/铁丝网投出带孔洞的影子；否则（默认）零采样，与裁剪前逐像素一致。

layout(push_constant) uniform ShadowPushBlock {
    mat4 lightViewProj;
    mat4 model;
    float cutoff;
    float hasAlbedoTex;
} spc;

layout(set = 0, binding = 0) uniform sampler2D albedoTex;

layout(location = 0) in vec2 vUV;

void main() {
    if (spc.cutoff > 0.0 && spc.hasAlbedoTex > 0.5) {
        if (texture(albedoTex, vUV).a < spc.cutoff) {
            discard;
        }
    }
}
