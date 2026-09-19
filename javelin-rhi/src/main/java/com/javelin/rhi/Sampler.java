package com.javelin.rhi;

import com.javelin.rhi.desc.SamplerDesc;

/**
 * 采样器接口。
 * 对照设计文档第 26.2 节。
 */
public interface Sampler extends GPUResource {

    SamplerDesc desc();

    /** Vulkan 句柄 VkSampler */
    long handle();
}
