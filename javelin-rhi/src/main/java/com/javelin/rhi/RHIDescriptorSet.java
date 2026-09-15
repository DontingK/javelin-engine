package com.javelin.rhi;

/**
 * 描述符集接口，用于将资源（缓冲区、纹理、采样器）绑定到着色器。
 */
public interface RHIDescriptorSet {

    /** 在指定绑定槽绑定统一缓冲区。 */
    void bindUniformBuffer(int binding, RHIBuffer buffer, long offset, long range);

    /** 在指定绑定槽绑定纹理和采样器。 */
    void bindTexture(int binding, RHITexture tex, RHISampler sampler);

    /** 在指定绑定槽绑定采样器。 */
    void bindSampler(int binding, RHISampler sampler);

    /** 在指定绑定槽绑定存储缓冲区。 */
    void bindStorageBuffer(int binding, RHIBuffer buffer);

    /** 将当前所有绑定更新到 GPU 描述符集。 */
    void update();
}
