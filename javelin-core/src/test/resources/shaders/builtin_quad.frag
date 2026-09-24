#version 450
layout(set = 1, binding = 0) uniform sampler2D uTexture;
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = texture(uTexture, vUV);
}
