package com.javelin.rhi.impl;

import com.javelin.rhi.*;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;

/**
 * Vulkan RHI 主实现类，是 RHI（Rendering Hardware Interface）层的核心入口。
 * <p>
 * 负责管理 Vulkan 实例、逻辑设备、资源创建、渲染图执行等全部 GPU 交互操作。
 * 通过 {@link RHI} 接口向引擎上层提供统一的 GPU 抽象，屏蔽底层 Vulkan API 的复杂性。
 */
public class VulkanRHI implements RHI {

    private static final Logger logger = LoggerFactory.getLogger(VulkanRHI.class);

    /** Vulkan 实例 */
    private final VkInstance vkInstance;
    /** 物理设备（GPU） */
    private final VkPhysicalDevice vkPhysicalDevice;
    /** 逻辑设备 */
    private final VkDevice vkDevice;
    /** 图形队列 */
    private final VkQueue graphicsQueue;
    /** 图形队列族索引 */
    private final int graphicsFamily;

    /** 渲染表面（由上层传入，RHI 内部创建 VkSurfaceKHR + Swapchain） */
    private final RHISurface surface;
    /** Vulkan Surface 句柄 */
    private final long vkSurfaceKHR;

    /** Java Cleaner，用于自动清理原生资源 */
    private final Cleaner cleaner = Cleaner.create();
    /** 资源映射表，管理所有 GPU 资源的句柄 */
    private final VkResourceImpl resourceTable = new VkResourceImpl();
    /** 延迟销毁队列，安全释放 GPU 资源 */
    private final DeferredDestroyQueue destroyQueue = new DeferredDestroyQueue();
    /** 帧内存分配器，提供临时缓冲区分配 */
    private final FrameArena frameArena = new FrameArena();
    /** 管线缓存，复用已创建的 Vulkan 管线 */
    private final PipelineCache pipelineCache = new PipelineCache();
    /** 命令池管理器，管理命令缓冲区的分配与释放 */
    private final VkCommandPoolManager commandPoolManager = new VkCommandPoolManager();
    /** 队列调度器，管理命令提交和队列同步 */
    private final VkQueueScheduler queueScheduler = new VkQueueScheduler();

    /**
     * 创建 VulkanRHI 实例，初始化完整的 Vulkan 环境。
     * <p>
     * 初始化流程：
     * <ol>
     *   <li>创建 VkInstance</li>
     *   <li>选择物理设备</li>
     *   <li>创建逻辑设备</li>
     *   <li>获取图形队列</li>
     *   <li>调用 {@code surface.createVulkanSurface(vkInstance)} 创建 VkSurfaceKHR</li>
     * </ol>
     *
     * @param surface 渲染表面（已由上层创建好窗口）
     */
    public VulkanRHI(RHISurface surface) {
        this.surface = surface;

        VulkanInit.Context ctx = VulkanInit.init(surface);
        this.vkInstance = ctx.instance;
        this.vkPhysicalDevice = ctx.physicalDevice;
        this.vkDevice = ctx.device;
        this.graphicsQueue = ctx.graphicsQueue;
        this.graphicsFamily = ctx.graphicsFamily;

        this.vkSurfaceKHR = surface.createVulkanSurface(vkInstance);
        if (vkSurfaceKHR == 0) {
            throw new RHIException("创建 VkSurfaceKHR 失败");
        }
        logger.info("VkSurfaceKHR 创建成功: {}", vkSurfaceKHR);

        // TODO: 查询 Surface 能力，创建 Swapchain

        logger.info("VulkanRHI 初始化完成");
    }

    /**
     * 开始构建一个新的渲染图。
     *
     * @return 新创建的渲染图对象
     */
    @Override
    public RHIRenderGraph beginRenderGraph() {
        return new VkRenderGraphImpl(this);
    }

    /**
     * 创建图形管线。
     *
     * @param desc 管线描述符
     * @return 创建的图形管线
     */
    @Override
    public RHIPipeline createGraphicsPipeline(RHIPipelineDesc desc) {
        return pipelineCache.getOrCreate(desc, () -> {
            // TODO: Vulkan graphics pipeline creation
            throw new UnsupportedOperationException("Graphics pipeline creation not yet implemented");
        });
    }

    /**
     * 创建计算管线。
     *
     * @param desc 管线描述符
     * @return 创建的计算管线
     */
    @Override
    public RHIPipeline createComputePipeline(RHIPipelineDesc desc) {
        return pipelineCache.getOrCreate(desc, () -> {
            // TODO: Vulkan compute pipeline creation
            throw new UnsupportedOperationException("Compute pipeline creation not yet implemented");
        });
    }

    /**
     * 创建 Uniform 缓冲区。
     *
     * @param size 缓冲区大小（字节）
     * @return 创建的 Uniform 缓冲区
     */
    @Override
    public RHIBuffer createUniformBuffer(long size) {
        // TODO: Vulkan buffer creation
        throw new UnsupportedOperationException("Buffer creation not yet implemented");
    }

    /**
     * 创建 Storage 缓冲区。
     *
     * @param size 缓冲区大小（字节）
     * @return 创建的 Storage 缓冲区
     */
    @Override
    public RHIBuffer createStorageBuffer(long size) {
        // TODO: Vulkan buffer creation
        throw new UnsupportedOperationException("Buffer creation not yet implemented");
    }

    /**
     * 创建描述符集。
     *
     * @param layout 描述符集布局
     * @return 创建的描述符集
     */
    @Override
    public RHIDescriptorSet createDescriptorSet(RHIDescriptorSetLayout layout) {
        // TODO: Vulkan descriptor set allocation
        throw new UnsupportedOperationException("Descriptor set creation not yet implemented");
    }

    /**
     * 创建纹理。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @param format 纹理格式
     * @return 创建的纹理
     */
    @Override
    public RHITexture createTexture(int width, int height, Object format) {
        // TODO: Vulkan image creation
        throw new UnsupportedOperationException("Texture creation not yet implemented");
    }

    /**
     * 创建采样器。
     *
     * @return 创建的采样器
     */
    @Override
    public RHISampler createSampler() {
        // TODO: Vulkan sampler creation
        throw new UnsupportedOperationException("Sampler creation not yet implemented");
    }

    /**
     * 从 SPIR-V 字节码创建着色器模块。
     *
     * @param spirv SPIR-V 字节码
     * @return 创建的着色器模块
     */
    @Override
    public RHIShaderModule createShaderModule(byte[] spirv) {
        // TODO: Vulkan shader module creation
        throw new UnsupportedOperationException("Shader module creation not yet implemented");
    }

    /**
     * 从帧内存分配器分配 Uniform 缓冲区。
     *
     * @param size 缓冲区大小（字节）
     * @return 分配的 Uniform 缓冲区
     */
    @Override
    public RHIBuffer allocateFrameUbo(long size) {
        return frameArena.allocate(size);
    }

    /**
     * 设置资源的调试名称，用于 GPU 调试工具识别。
     *
     * @param res  资源对象
     * @param name 调试名称
     */
    @Override
    public void setDebugName(RHIResource res, String name) {
        // TODO: VK_EXT_debug_utils integration
    }

    /**
     * 将资源句柄加入延迟销毁队列，安全释放 GPU 资源。
     *
     * @param handle 待销毁资源的句柄
     */
    @Override
    public void enqueueDestroy(long handle) {
        destroyQueue.enqueue(handle, 0);
    }

    /**
     * 获取 Cleaner 实例，用于注册资源自动清理。
     *
     * @return Cleaner 实例
     */
    @Override
    public Cleaner cleaner() {
        return cleaner;
    }

    // ------------------------------------------------------------------------
    // 内部访问器（供 impl 包内其他类使用）
    // ------------------------------------------------------------------------

    /** @return Vulkan 实例 */
    VkInstance vkInstance() {
        return vkInstance;
    }

    /** @return 物理设备 */
    VkPhysicalDevice vkPhysicalDevice() {
        return vkPhysicalDevice;
    }

    /** @return 逻辑设备 */
    VkDevice vkDevice() {
        return vkDevice;
    }

    /** @return 图形队列 */
    VkQueue graphicsQueue() {
        return graphicsQueue;
    }

    /** @return 图形队列族索引 */
    int graphicsFamily() {
        return graphicsFamily;
    }

    /** @return 渲染表面 */
    RHISurface surface() {
        return surface;
    }

    /** @return Vulkan Surface 句柄 */
    long vkSurfaceKHR() {
        return vkSurfaceKHR;
    }

    /** @return 资源表 */
    public VkResourceImpl resourceTable() {
        return resourceTable;
    }

    /** @return 延迟销毁队列 */
    public DeferredDestroyQueue destroyQueue() {
        return destroyQueue;
    }

    /** @return 帧内存分配器 */
    public FrameArena frameArena() {
        return frameArena;
    }

    /** @return 管线缓存 */
    public PipelineCache pipelineCache() {
        return pipelineCache;
    }

    /** @return 命令池管理器 */
    public VkCommandPoolManager commandPoolManager() {
        return commandPoolManager;
    }

    /** @return 队列调度器 */
    public VkQueueScheduler queueScheduler() {
        return queueScheduler;
    }

    /**
     * 处理当前帧的延迟销毁队列，释放已达到销毁条件的资源。
     *
     * @param currentFrame 当前帧索引
     */
    public void processDeferredDestroy(long currentFrame) {
        destroyQueue.process(currentFrame);
    }
}
