package com.javelin.rhi.types;

/**
 * 内存使用偏好。
 * 对照 VkMemoryPropertyFlags，按位组合使用。
 * 用于 MemoryPreference 的 required / preferred 两级选择。
 */
public enum MemoryUsage {
    /** 设备本地显存（GPU 专用，带宽最高） */
    DEVICE_LOCAL,           // VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    /** CPU 可映射写入（上传/回读必选） */
    HOST_VISIBLE,           // VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
    /** 主机写入对 GPU 立即可见（不需显式 flush） */
    HOST_COHERENT,          // VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    /** 主机侧缓存访问（回读性能更好） */
    HOST_CACHED;            // VK_MEMORY_PROPERTY_HOST_CACHED_BIT
}
