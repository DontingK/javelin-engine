package com.javelin.rhi.types;

/**
 * 内存使用偏好。
 * 对照 VkMemoryPropertyFlags，按位组合使用。
 * 用于 MemoryPreference 的 required / preferred 两级选择。
 */
public enum MemoryUsage {
    DEVICE_LOCAL,           // VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    HOST_VISIBLE,           // VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
    HOST_COHERENT,          // VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    HOST_CACHED;            // VK_MEMORY_PROPERTY_HOST_CACHED_BIT
}
