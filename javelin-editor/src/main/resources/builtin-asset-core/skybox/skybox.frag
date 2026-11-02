#version 450

// 天空盒片元着色器（Engine_Skybox_Design §4，批次⑤ SkyboxPass）。
// set1 = skybox 专属布局：binding0 组合采样器（samplerCube；SkyboxPass 自建，
// 不占材质/光照 set 契约）。实例名 skyboxMap 非保留字（保留字面归
// Engine_Uniform_Keys §保留字——本 Pass 自有布局无冲突）。

layout(set = 1, binding = 0) uniform samplerCube skyboxMap;

layout(location = 0) in vec3 vDir;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(texture(skyboxMap, normalize(vDir)).rgb, 1.0);
}
