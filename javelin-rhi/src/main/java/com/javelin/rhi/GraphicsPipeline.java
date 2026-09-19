package com.javelin.rhi;

/**
 * 图形管线接口。
 * 对照设计文档第 26.3 节。
 */
public interface GraphicsPipeline extends GPUResource {

    /** Vulkan 句柄 VkPipeline */
    long handle();
}
