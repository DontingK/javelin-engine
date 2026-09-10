package com.javelin.rhi.api;

import com.javelin.rhi.types.DescriptorType;

/**
 * 描述符池。
 */
public abstract class DescriptorPool extends GPUResource {

    public abstract DescriptorSet allocateSet(DescriptorSetLayout layout);

    public abstract DescriptorSet[] allocateSets(DescriptorSetLayout[] layouts);

    public abstract void freeSets(DescriptorSet[] sets);

    public abstract void reset();

    // ========== 内部类型 ==========

    record PoolSize(DescriptorType type, int count) {}
}
