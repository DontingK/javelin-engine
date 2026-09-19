package com.javelin.rhi;

/**
 * 管线布局接口。
 * 对照设计文档第 26.3 节。
 */
public interface PipelineLayout extends GPUResource {

    /** Vulkan 句柄 VkPipelineLayout */
    long handle();
}
