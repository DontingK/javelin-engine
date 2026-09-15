package com.javelin.rhi.impl;

import com.javelin.rhi.RHI;
import com.javelin.rhi.RHIPipeline;

/**
 * Vulkan 管线实现，封装 Vulkan 图形/计算管线对象（{@code VkPipeline}）。
 * <p>
 * 管线对象包含着色器阶段、顶点输入布局、光栅化状态、混合状态等完整的渲染状态配置，
 * 是 GPU 执行绘制和计算操作的核心对象。
 */
public class VkPipelineImpl extends RHIPipeline {

    /**
     * 创建 Vulkan 管线实例。
     *
     * @param rhi    RHI 实例
     * @param handle Vulkan 管线句柄
     */
    public VkPipelineImpl(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
