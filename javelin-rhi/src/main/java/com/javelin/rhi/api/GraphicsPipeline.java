package com.javelin.rhi.api;

/**
 * 图形管线状态对象（PSO）。
 */
public abstract class GraphicsPipeline extends GPUResource {

    public abstract PipelineLayout layout();
}
