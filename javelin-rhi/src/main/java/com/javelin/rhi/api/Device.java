package com.javelin.rhi.api;

import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;

/**
 * Vulkan 逻辑设备，所有 GPU 资源的工厂。
 */
public abstract class Device extends GPUResource {

    // ========== 查询 ==========

    public abstract PhysicalDevice physicalDevice();

    public abstract Instance instance();

    // ========== 队列 ==========

    public abstract Queue graphicsQueue();

    public abstract Queue computeQueue();

    public abstract Queue transferQueue();

    // ========== 内存资源 ==========

    public abstract Buffer createBuffer(BufferDesc desc);

    public abstract Texture createTexture(TextureDesc desc);

    // ========== 管线对象 ==========

    public abstract Shader createShader(ShaderDesc desc);

    public abstract GraphicsPipeline createGraphicsPipeline(GraphicsPipelineDesc desc);

    public abstract ComputePipeline createComputePipeline(ComputePipelineDesc desc);

    public abstract PipelineLayout createPipelineLayout(PipelineLayoutDesc desc);

    // ========== 描述符 ==========

    public abstract DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc);

    public abstract DescriptorPool createDescriptorPool(DescriptorPoolDesc desc);

    // ========== 同步 ==========

    public abstract Fence createFence(boolean signaled);

    public abstract Semaphore createSemaphore();

    // ========== 命令 ==========

    public abstract CommandPool createCommandPool(Queue queue, CommandPoolFlags flags);

    public abstract CommandBuffer createCommandBuffer(CommandPool pool, boolean primary);

    // ========== 交换链 ==========

    public abstract SwapChain createSwapChain(RHIWindow window, SwapChainDesc desc);

    public abstract void waitIdle();

    public abstract void waitForFences(Fence[] fences, boolean waitAll, long timeoutNanos);

    public abstract void resetFences(Fence[] fences);
}
