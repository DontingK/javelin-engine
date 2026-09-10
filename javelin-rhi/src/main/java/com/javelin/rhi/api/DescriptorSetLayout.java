package com.javelin.rhi.api;

import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.ShaderStage;

/**
 * 描述符集布局。
 */
public abstract class DescriptorSetLayout extends GPUResource {

    public abstract Binding[] bindings();

    // ========== 内部类型 ==========

    public record Binding(
            int binding,
            DescriptorType descriptorType,
            int descriptorCount,
            ShaderStage stageFlags,
            Sampler[] immutableSamplers  // nullable
    ) {
    }
}
