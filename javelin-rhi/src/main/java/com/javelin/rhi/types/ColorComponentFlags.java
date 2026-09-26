package com.javelin.rhi.types;

/**
 * 颜色通道写入掩码。
 * 对照 VkColorComponentFlags，按位组合使用。
 */
public enum ColorComponentFlags {
    /** 允许写入 R 通道 */
    R,                      // VK_COLOR_COMPONENT_R_BIT
    /** 允许写入 G 通道 */
    G,                      // VK_COLOR_COMPONENT_G_BIT
    /** 允许写入 B 通道 */
    B,                      // VK_COLOR_COMPONENT_B_BIT
    /** 允许写入 A 通道 */
    A;                      // VK_COLOR_COMPONENT_A_BIT
}
