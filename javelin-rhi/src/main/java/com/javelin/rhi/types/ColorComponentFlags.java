package com.javelin.rhi.types;

/**
 * 颜色通道写入掩码。
 * 对照 VkColorComponentFlags，按位组合使用。
 */
public enum ColorComponentFlags {
    R,                      // VK_COLOR_COMPONENT_R_BIT
    G,                      // VK_COLOR_COMPONENT_G_BIT
    B,                      // VK_COLOR_COMPONENT_B_BIT
    A;                      // VK_COLOR_COMPONENT_A_BIT
}
