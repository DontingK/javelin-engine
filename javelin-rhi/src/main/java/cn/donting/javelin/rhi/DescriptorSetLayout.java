package cn.donting.javelin.rhi;

/**
 * 描述符集布局接口。
 * 对照设计文档第 26.3 节。
 */
public interface DescriptorSetLayout extends GPUResource {

    /** Vulkan 句柄 VkDescriptorSetLayout */
    long handle();
}
