package com.javelin.rhi.impl;

import com.javelin.rhi.RHI;
import com.javelin.rhi.RHIShaderModule;

/**
 * Vulkan 着色器模块实现，封装 Vulkan 着色器模块对象（{@code VkShaderModule}）。
 * <p>
 * 着色器模块包含编译后的 SPIR-V 字节码，是构成图形/计算管线的基本单元。
 */
public class VkShaderModuleImpl extends RHIShaderModule {

    /**
     * 创建 Vulkan 着色器模块实例。
     *
     * @param rhi   RHI 实例
     * @param handle Vulkan 着色器模块句柄
     */
    public VkShaderModuleImpl(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
