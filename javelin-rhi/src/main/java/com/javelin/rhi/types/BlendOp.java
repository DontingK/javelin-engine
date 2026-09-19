package com.javelin.rhi.types;

/**
 * 混合操作。
 * 对照 VkBlendOp。
 */
public enum BlendOp {
    ADD,                    // VK_BLEND_OP_ADD
    SUBTRACT,               // VK_BLEND_OP_SUBTRACT
    REVERSE_SUBTRACT,       // VK_BLEND_OP_REVERSE_SUBTRACT
    MIN,                    // VK_BLEND_OP_MIN
    MAX;                    // VK_BLEND_OP_MAX
}
