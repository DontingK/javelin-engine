package com.javelin.rhi.types;

/**
 * 模板操作。
 * 对照 VkStencilOp。
 */
public enum StencilOp {
    /** 模板值保持不变 */
    KEEP,                   // VK_STENCIL_OP_KEEP
    /** 模板值置 0 */
    ZERO,                   // VK_STENCIL_OP_ZERO
    /** 模板值替换为参考值 */
    REPLACE,                // VK_STENCIL_OP_REPLACE
    /** 模板值 +1，钳制到上限（不回绕） */
    INCREMENT_AND_CLAMP,    // VK_STENCIL_OP_INCREMENT_AND_CLAMP
    /** 模板值 -1，钳制到 0（不下溢） */
    DECREMENT_AND_CLAMP,    // VK_STENCIL_OP_DECREMENT_AND_CLAMP
    /** 模板值按位取反 */
    INVERT,                 // VK_STENCIL_OP_INVERT
    /** 模板值 +1，超过上限回绕到 0 */
    INCREMENT_AND_WRAP,     // VK_STENCIL_OP_INCREMENT_AND_WRAP
    /** 模板值 -1，低于 0 回绕到上限 */
    DECREMENT_AND_WRAP;     // VK_STENCIL_OP_DECREMENT_AND_WRAP
}
