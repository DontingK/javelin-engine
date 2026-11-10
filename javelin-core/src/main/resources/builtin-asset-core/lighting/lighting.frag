#version 450
//@javelinLighting
// 全屏光照片元着色器（批次④-B，LightingPass）：读 G-Buffer 三张重建视空间法线/位置，
// 调引擎注入的 javelinLighting——与材质 Forward fs 是同一函数、同一 set1 cluster 数据
// （「两路光照一致」由单源保证）。标记行由 SpvShaderImporter 消费：在 #version 后注入
// set0 相机块 + set1 光照段函数 + G-Buffer 三采样（binding5/6/7）。
layout(location = 0) out vec4 outColor;

void main() {
    // G-Buffer 与帧缓冲同尺寸、按同一 viewport/scissor 栅格化（分屏时内容落在各自的
    // 绝对帧缓冲位置）——故按 gl_FragCoord 绝对坐标换算纹理 UV，1:1 取回本像素
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(gbufferWorldPos, 0));
    vec4 wp = texture(gbufferWorldPos, uv);
    if (wp.w < 0.5) {
        // 背景像素（worldPos 清屏 w=0）：保留本 Pass 的清屏色，天空盒随后覆盖
        discard;
    }
    vec3 worldPos = wp.xyz;
    vec3 worldNormal = texture(gbufferNormal, uv).xyz;
    vec3 albedo = texture(gbufferAlbedo, uv).rgb;
    // 视空间换算（与材质 vs 同式：view 变换位置、mat3(view) 变换法线）
    vec3 viewPos = (cam.view * vec4(worldPos, 1.0)).xyz;
    vec3 viewNormal = mat3(cam.view) * worldNormal;
    outColor = vec4(albedo * javelinLighting(normalize(viewNormal), viewPos, worldPos), 1.0);
}
