package com.javelin.rhi.types;

/**
 * 缓冲用途。
 * 对照 VkBufferUsageFlags，按位组合使用。
 */
public enum BufferUsage {
    TRANSFER_SRC,           // VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    TRANSFER_DST,           // VK_BUFFER_USAGE_TRANSFER_DST_BIT
    UNIFORM_TEXEL,          // VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT
    STORAGE_TEXEL,          // VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT
    UNIFORM,                // VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
    STORAGE,                // VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    INDEX,                  // VK_BUFFER_USAGE_INDEX_BUFFER_BIT
    VERTEX,                 // VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
    INDIRECT;               // VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
}
