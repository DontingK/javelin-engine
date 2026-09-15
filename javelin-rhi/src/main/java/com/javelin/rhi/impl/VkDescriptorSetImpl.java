package com.javelin.rhi.impl;

import com.javelin.rhi.*;

/**
 * Vulkan 描述符集实现，封装 Vulkan 描述符集（{@code VkDescriptorSet}）的资源绑定与更新。
 * <p>
 * 描述符集用于将 Uniform Buffer、纹理、采样器、Storage Buffer 等资源绑定到着色器的对应槽位，
 * 是 GPU 资源与着色器之间的重要桥梁。
 */
public class VkDescriptorSetImpl implements RHIDescriptorSet {

    /**
     * 绑定 Uniform 缓冲区到指定绑定槽。
     *
     * @param binding 绑定槽索引
     * @param buffer  Uniform 缓冲区
     * @param offset  缓冲区偏移量
     * @param range   绑定范围
     */
    @Override
    public void bindUniformBuffer(int binding, RHIBuffer buffer, long offset, long range) {
        // TODO: Record uniform buffer binding
    }

    /**
     * 绑定纹理和采样器到指定绑定槽。
     *
     * @param binding 绑定槽索引
     * @param tex     纹理对象
     * @param sampler 采样器对象
     */
    @Override
    public void bindTexture(int binding, RHITexture tex, RHISampler sampler) {
        // TODO: Record texture binding
    }

    /**
     * 绑定采样器到指定绑定槽。
     *
     * @param binding 绑定槽索引
     * @param sampler 采样器对象
     */
    @Override
    public void bindSampler(int binding, RHISampler sampler) {
        // TODO: Record sampler binding
    }

    /**
     * 绑定 Storage 缓冲区到指定绑定槽。
     *
     * @param binding 绑定槽索引
     * @param buffer  Storage 缓冲区
     */
    @Override
    public void bindStorageBuffer(int binding, RHIBuffer buffer) {
        // TODO: Record storage buffer binding
    }

    /**
     * 更新描述符集，将所有绑定的资源信息提交到 GPU。
     */
    @Override
    public void update() {
        // TODO: vkUpdateDescriptorSets
    }
}
