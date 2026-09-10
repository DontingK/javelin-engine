package com.javelin.rhi.api;

import com.javelin.rhi.types.TextureLayout;

/**
 * 描述符集。
 */
public abstract class DescriptorSet extends GPUResource {

    public abstract DescriptorSetLayout layout();

    // ========== 更新描述符 ==========

    public abstract void writeBuffer(int binding, int arrayElement, Buffer buffer, long offset, long range);

    public abstract void writeBuffer(int binding, Buffer buffer, long offset, long range);

    public abstract void writeTexture(int binding, int arrayElement, TextureView view, TextureLayout layout, Sampler sampler);

    public abstract void writeTexture(int binding, TextureView view, TextureLayout layout, Sampler sampler);

    public abstract void writeDynamicBuffer(int binding, int arrayElement, Buffer buffer, long offset, long range);

    public abstract void writeDynamicBuffer(int binding, Buffer buffer, long offset, long range);
}
