package cn.donting.javelin.rhi.types;

/**
 * 混合操作。
 * 对照 VkBlendOp。
 */
public enum BlendOp {
    /** 相加：src * srcFactor + dst * dstFactor（常规透明混合） */
    ADD,                    // VK_BLEND_OP_ADD
    /** 相减：src * srcFactor - dst * dstFactor */
    SUBTRACT,               // VK_BLEND_OP_SUBTRACT
    /** 反向相减：dst * dstFactor - src * srcFactor */
    REVERSE_SUBTRACT,       // VK_BLEND_OP_REVERSE_SUBTRACT
    /** 逐通道取较小值 */
    MIN,                    // VK_BLEND_OP_MIN
    /** 逐通道取较大值 */
    MAX;                    // VK_BLEND_OP_MAX
}
