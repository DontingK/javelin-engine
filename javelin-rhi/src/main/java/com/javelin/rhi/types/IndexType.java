package com.javelin.rhi.types;

/**
 * 索引类型。
 * 对照 VkIndexType。
 */
public enum IndexType {
    /** 16 位索引（顶点数 &lt; 65536 时用） */
    UINT16,                 // VK_INDEX_TYPE_UINT16
    /** 32 位索引 */
    UINT32,                 // VK_INDEX_TYPE_UINT32
    /** 8 位索引（KHR 扩展，超小网格） */
    UINT8;                  // VK_INDEX_TYPE_UINT8_KHR
}
