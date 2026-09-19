package com.javelin.rhi;

import com.javelin.rhi.desc.*;

/**
 * 逻辑设备接口。
 * 对照设计文档第 26.1 节。
 */
public interface Device extends GPUResource {

    // --- 资源创建 ---
    Texture createTexture(TextureDesc desc, UploadData data);
    Buffer createBuffer(BufferDesc desc, UploadData data);
    Sampler createSampler(SamplerDesc desc);

    // --- 管线 ---
    GraphicsPipeline createGraphicsPipeline(GraphicsPipelineDesc desc);
    ComputePipeline createComputePipeline(ComputePipelineDesc desc);
    PipelineLayout createPipelineLayout(PipelineLayoutDesc desc);
    DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc);
    ShaderModule createShaderModule(byte[] spirv);

    // --- 队列 ---
    Queue graphicsQueue();
    Queue computeQueue();
    Queue transferQueue();

    // --- 命令缓冲 ---
    CommandBuffer allocateCommandBuffer(boolean secondary);

    // --- 帧同步 ---
    FrameArena frameArena();
    PipelineCache pipelineCache();

    // --- 提交 ---
    void submit(SubmitInfo info);
    void waitIdle();

    /** Vulkan 句柄 VkDevice */
    long handle();
}
