package com.javelin.rhi;

/**
 * 描述符集接口。
 * 对照设计文档第 26.3 节。
 */
public interface DescriptorSet extends GPUResource {

    /** 更新描述符（材质创建/修改时，多帧复用） */
    void update(DescriptorUpdate... updates);

    /** Vulkan 句柄 VkDescriptorSet */
    long handle();
}
