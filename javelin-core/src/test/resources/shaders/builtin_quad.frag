#version 450
// set 2 = 材质（RHI 20.3/21.5）：binding0 MaterialBlock（std140），binding1 贴图槽按声明序
layout(set = 2, binding = 0) uniform MaterialBlock { vec4 _Tint; } mat;
layout(set = 2, binding = 1) uniform sampler2D _MainTex;
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = texture(_MainTex, vUV) * mat._Tint;
}
