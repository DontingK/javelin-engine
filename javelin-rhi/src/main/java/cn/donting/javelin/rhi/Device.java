package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.render.RenderSurface;
import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.types.SampleCount;

import java.util.Set;

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
     * 设备必须是以带 {@link RenderSurface} 的 createDevice 创建的
     * （VkSurfaceKHR 由设备持有，上层无需再经 Instance.createSurface 重复创建）。
     */
    SwapChain createSwapChain(SwapChainDesc desc);

    // --- 提交 ---
    void submit(SubmitInfo info);
    void waitIdle();

    // --- GPU 信息与渲染统计（doc/Engine_RenderStats_Design.md v0.1） ---
    /** 设备/驱动静态信息（设备构造时从 VkPhysicalDeviceProperties 提取，只读） */
    GPUInfo gpuInfo();

    // --- MSAA 能力（VkPhysicalDeviceLimits 位掩码解码，引擎抗锯齿档位钳制用） ---
    /** 颜色附件支持的采样档位（规范保证含 COUNT_1，返回不可变集） */
    Set<SampleCount> supportedColorSampleCounts();

    /** 深度附件支持的采样档位（规范保证含 COUNT_1，返回不可变集） */
    Set<SampleCount> supportedDepthSampleCounts();

    /** 渲染统计：11 类存活资源计数 + 上一帧 draw 统计快照（公开只读 getter） */
    RenderStats renderStats();

    /**
     * 帧尾快照+清零 draw 统计：引擎每帧录制收尾后显式调用。
     * 跳帧（最小化）时 runtime 未变，重复快照使显示值保持上一实帧、不闪零。
     */
    void resetFrameStats();

    /** Vulkan 句柄 VkDevice */
    long handle();
}
