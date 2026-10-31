package cn.donting.javelin.rhi.types;

/**
 * 查询类型。
 * 对照 VkQueryType。
 */
public enum QueryType {
    /** 遮挡查询（统计通过深度测试的样本数） */
    OCCLUSION,              // VK_QUERY_TYPE_OCCLUSION
    /** 管线统计查询（顶点/片元计数等） */
    PIPELINE_STATISTICS,    // VK_QUERY_TYPE_PIPELINE_STATISTICS
    /** 时间戳查询（GPU 计时/profiling） */
    TIMESTAMP;              // VK_QUERY_TYPE_TIMESTAMP
}
