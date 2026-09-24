#version 450
layout(set = 0, binding = 0) uniform CameraBlock { mat4 viewProj; } cam;
layout(push_constant) uniform PushBlock {
    mat4 model;
    float tpf;
} pc;
layout(location = 0) in vec3 aPos;
layout(location = 3) in vec2 aUV;
layout(location = 0) out vec2 vUV;
void main() {
    gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
    vUV = aUV;
}
