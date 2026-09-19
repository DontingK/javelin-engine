package com.javelin.rhi.types;

/**
 * 面剔除模式。
 * 对照 VkCullModeFlags。
 */
public enum CullMode {
    NONE,                   // VK_CULL_MODE_NONE
    FRONT,                  // VK_CULL_MODE_FRONT_BIT
    BACK,                   // VK_CULL_MODE_BACK_BIT
    FRONT_AND_BACK;         // VK_CULL_MODE_FRONT_AND_BACK
}
