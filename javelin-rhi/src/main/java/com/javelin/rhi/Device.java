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

    /**
     * 从设备描述符池分配描述符集（15.8 落地简化：单池，设备销毁时整体回收）。
     * layout 的定义必须与管线创建时 GraphicsPipelineDesc.descriptorSetLayouts 对应项一致（15.7-2）。
     */
    DescriptorSet createDescriptorSet(DescriptorSetLayout layout);

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

    // --- 帧同步原语（FrameState 装配，24.2） ---
    /** 创建未置位的栅栏 */
    Fence createFence();

    /** 创建栅栏；signaled=true 时初始已置位（帧环形首帧直接通过闸门） */
    Fence createFence(boolean signaled);

    /** 创建信号量（acquire / renderFinished 衔接，24.2） */
    Semaphore createSemaphore();

    // --- 交换链 ---
    /**
     * 创建交换链。
     * 设备必须是以带 {@link com.javelin.rhi.render.RenderSurface} 的 createDevice 创建的
     * （VkSurfaceKHR 由设备持有，上层无需再经 Instance.createSurface 重复创建）。
     */
    SwapChain createSwapChain(SwapChainDesc desc);

    // --- 提交 ---
    void submit(SubmitInfo info);
    void waitIdle();

    /** Vulkan 句柄 VkDevice */
    long handle();
}
