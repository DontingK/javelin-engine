package com.javelin.rhi.types;

/**
 * 依赖标志。
 * 对照 VkDependencyFlags。
 */
public enum DependencyFlags {
    BY_REGION,              // VK_DEPENDENCY_BY_REGION_BIT
    DEVICE_GROUP,           // VK_DEPENDENCY_DEVICE_GROUP_BIT
    VIEW_LOCAL;             // VK_DEPENDENCY_VIEW_LOCAL_BIT
}
