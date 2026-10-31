package cn.donting.javelin.rhi;

/**
 * 着色器模块接口。
 * 对照设计文档第 26.3 节。
 */
public interface ShaderModule extends GPUResource {

    /** Vulkan 句柄 VkShaderModule */
    long handle();
}
