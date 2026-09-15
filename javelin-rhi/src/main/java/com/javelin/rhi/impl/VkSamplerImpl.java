package com.javelin.rhi.impl;

import com.javelin.rhi.RHI;
import com.javelin.rhi.RHISampler;

/**
 * Vulkan 采样器实现，封装 Vulkan 采样器对象（{@code VkSampler}）。
 * <p>
 * 采样器定义了纹理采样的过滤方式、寻址模式、各向异性等参数，
 * 是着色器中纹理采样操作的核心状态对象。
 */
public class VkSamplerImpl extends RHISampler {

    /**
     * 创建 Vulkan 采样器实例。
     *
     * @param rhi   RHI 实例
     * @param handle Vulkan 采样器句柄
     */
    public VkSamplerImpl(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
