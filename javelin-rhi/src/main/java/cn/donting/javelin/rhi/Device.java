package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.render.RenderSurface;
import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.types.Format;
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

    /**
     * 查询某像素格式在本设备上的能力（RHI-15，2026-11-21）：
     * 数据源 = {@code vkGetPhysicalDeviceFormatProperties} 的 optimalTilingFeatures，按格式缓存。
     * 纹理/管线创建期已按 usage 自动校验，此方法供诊断与可选用例（如按机型选档）。
     */
    FormatSupport formatSupport(Format format);

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

    /**
     * push 常量最大字节数（VkPhysicalDeviceLimits::maxPushConstantsSize，规范保底 128）。
     * 管线描述 pushConstantSize 超出即 Vulkan 校验报错——调用方建管线前先用本值显性判定。
     */
    int maxPushConstantsSize();

    // --- 光追能力探针（P4-06 首范围前置；只报「驱动是否提供」，不代表已启用） ---
    /** 驱动上报的设备扩展名清单是否包含该扩展（如 {@code VK_KHR_ray_query}） */
    boolean hasDeviceExtension(String extensionName);

    /**
     * 光追查询能力：{@code VK_KHR_ray_query} 扩展在列 **且** feature 位
     * {@code VkPhysicalDeviceRayQueryFeaturesKHR.rayQuery} 为 true。
     *
     * <p>只做探测不启用——启用要等 AS 构建批次一并加进 {@code VkDeviceCreateInfo}。</p>
     */
    boolean supportsRayQuery();

    /**
     * 光追能力是否**已在逻辑设备上启用**（扩展与 feature 同时进 {@code VkDeviceCreateInfo}）。
     *
     * <p>与 {@link #supportsRayQuery()} 的区别：后者只报驱动能力，本方法报「这一台设备真的能用」。
     * B2 建 AS 前查本方法，false 就显性抛错，不做软件回退。</p>
     */
    boolean rayQueryEnabled();

    /**
     * 构建底层加速结构（BLAS，P4-06 B2）。
     *
     * <p>设备无光追能力时显性抛错（{@code IllegalStateException}），不做软件回退；调用方
     * 负责在网格缓冲变化时 dispose 后重建。</p>
     */
    AccelerationStructure createBlas(cn.donting.javelin.rhi.desc.BlasDesc desc);

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
