package com.javelin.rhi.types;

/**
 * 依赖标志。
 * 对照 VkDependencyFlags。
 */
public enum DependencyFlags {
    /** 依赖按像素区域生效（瓦片内同步，多附件渲染常用） */
    BY_REGION,              // VK_DEPENDENCY_BY_REGION_BIT
    /** 跨设备组的依赖 */
    DEVICE_GROUP,           // VK_DEPENDENCY_DEVICE_GROUP_BIT
    /** 视图局部依赖（多视图渲染） */
    VIEW_LOCAL;             // VK_DEPENDENCY_VIEW_LOCAL_BIT
}
