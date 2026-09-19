package com.javelin.rhi.types;

/**
 * 查询类型。
 * 对照 VkQueryType。
 */
public enum QueryType {
    OCCLUSION,              // VK_QUERY_TYPE_OCCLUSION
    PIPELINE_STATISTICS,    // VK_QUERY_TYPE_PIPELINE_STATISTICS
    TIMESTAMP;              // VK_QUERY_TYPE_TIMESTAMP
}
