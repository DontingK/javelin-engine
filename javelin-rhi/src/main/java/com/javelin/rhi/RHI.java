package com.javelin.rhi;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

/**
 * RHI（Rendering Hardware Interface）顶层接口，提供 GPU 资源的创建与管理能力。
 * <p>
 * 所有 GPU 对象（管线、缓冲区、纹理、描述符集等）均通过此接口创建，
 * 底层由具体实现（如 VulkanRHI）完成实际的 GPU 操作。
 */
public interface RHI {

    /** 开始构建一帧的渲染图。 */
    RHIRenderGraph beginRenderGraph();

    /** 创建图形渲染管线。 */
    RHIPipeline createGraphicsPipeline(RHIPipelineDesc desc);

    /** 创建计算管线。 */
    RHIPipeline createComputePipeline(RHIPipelineDesc desc);

    /** 创建统一缓冲区（UBO）。 */
    RHIBuffer createUniformBuffer(long size);

    /** 创建存储缓冲区（SSBO）。 */
    RHIBuffer createStorageBuffer(long size);

    /** 创建描述符集。 */
    RHIDescriptorSet createDescriptorSet(RHIDescriptorSetLayout layout);

    /** 创建纹理。 */
    RHITexture createTexture(int width, int height, Object format);

    /** 创建采样器。 */
    RHISampler createSampler();

    /** 从 SPIR-V 字节码创建着色器模块。 */
    RHIShaderModule createShaderModule(byte[] spirv);

    /** 在当前帧的临时内存池中分配一块 UBO 空间。 */
    RHIBuffer allocateFrameUbo(long size);

    /** 为指定 GPU 资源设置调试名称，便于调试工具识别。 */
    void setDebugName(RHIResource res, String name);

    /** Internal: called by Cleaner callback to queue resource for deferred destruction. */
    void enqueueDestroy(long handle);

    /** Internal: accessed by RHIResource for Cleaner registration. */
    Cleaner cleaner();
}
