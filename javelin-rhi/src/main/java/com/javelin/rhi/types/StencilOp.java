package com.javelin.rhi.types;

/**
 * 模板操作。
 * 对照 VkStencilOp。
 */
public enum StencilOp {
    KEEP,                   // VK_STENCIL_OP_KEEP
    ZERO,                   // VK_STENCIL_OP_ZERO
    REPLACE,                // VK_STENCIL_OP_REPLACE
    INCREMENT_AND_CLAMP,    // VK_STENCIL_OP_INCREMENT_AND_CLAMP
    DECREMENT_AND_CLAMP,    // VK_STENCIL_OP_DECREMENT_AND_CLAMP
    INVERT,                 // VK_STENCIL_OP_INVERT
    INCREMENT_AND_WRAP,     // VK_STENCIL_OP_INCREMENT_AND_WRAP
    DECREMENT_AND_WRAP;     // VK_STENCIL_OP_DECREMENT_AND_WRAP
}
