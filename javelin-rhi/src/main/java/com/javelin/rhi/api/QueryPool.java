package com.javelin.rhi.api;

import com.javelin.rhi.types.PipelineStage;

/**
 * 查询池（时间戳、遮挡、管线统计）。
 */
public abstract class QueryPool extends GPUResource {

    public abstract QueryType type();

    public abstract int count();

    // ========== 内部类型 ==========

    enum QueryType {
        OCCLUSION,
        PIPELINE_STATISTICS,
        TIMESTAMP
    }
}
