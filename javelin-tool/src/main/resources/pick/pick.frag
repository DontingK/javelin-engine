#version 450

// GPU ID-buffer 拾取片元着色器（Engine_Picking_Design）：
// 输出本物体帧内 pick ID 到 R32_UINT 附件（0 = 背景/未命中）。

layout(push_constant) uniform PushBlock { mat4 model; int pickId; } pc;

layout(location = 0) out uint oPickId;

void main() {
    oPickId = uint(pc.pickId);
}
