package com.javelin.rhi;

/**
 * 描述符类型枚举，对应 Vulkan 描述符类型。
 */
public enum RHIDescriptorType {
    UNIFORM_BUFFER,
    DYNAMIC_UNIFORM_BUFFER,
    STORAGE_BUFFER,
    SAMPLED_TEXTURE,
    STORAGE_TEXTURE,
    SAMPLER
}
