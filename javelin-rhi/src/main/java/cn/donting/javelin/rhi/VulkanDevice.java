package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.types.TextureLayout;
import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.exception.VulkanException;
import cn.donting.javelin.rhi.render.RenderSurface;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.SampleCount;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 逻辑设备实现。
 * 对照设计文档第 23.4a 节阶段 3。
 */
final class VulkanDevice implements Device {

    private final VulkanInstance instance;
    private final VkDevice device;
    private final VulkanPhysicalDevice physicalDevice;
    private final Queue graphicsQueue;
    private final Queue computeQueue;
    private final Queue transferQueue;
    private final VulkanSurface surface;   // 设备持有的呈现 surface（createSwapChain 复用）
    private final long commandPoolHandle;
    private final long descriptorPoolHandle;
    private final RenderStats stats = new RenderStats();
    private final GPUInfo gpuInfo;
    private volatile boolean disposed;

    VulkanDevice(VulkanInstance instance, VkInstance vkInstance,
                 RHIDevicePrefs prefs, RenderSurface surface, boolean debug) {
        this.instance = instance;

        // 创建呈现 surface（若提供 RenderSurface）：VkSurfaceKHR 供 present 族检查，
        // 并由本设备持有，createSwapChain 直接复用——上层无需重复创建 VkSurfaceKHR
        this.surface = surface != null ? new VulkanSurface(instance, vkInstance, surface) : null;

        // 选择物理设备（surface 感知：检查 present 队列族支持）
        long surfaceHandle = this.surface != null ? this.surface.handle() : 0;
        this.physicalDevice = VulkanPhysicalDevice.select(vkInstance, prefs, surfaceHandle);
        this.gpuInfo = new GPUInfo(physicalDevice.properties());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 队列创建信息
            Set<Integer> uniqueFamilies = new HashSet<>();
            uniqueFamilies.add(physicalDevice.graphicsFamily());
            if (physicalDevice.presentFamily() >= 0) {
                uniqueFamilies.add(physicalDevice.presentFamily());
            }

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);

            int idx = 0;
            for (int family : uniqueFamilies) {
                VkDeviceQueueCreateInfo qci = queueCreateInfos.get(idx++);
                qci.sType$Default();
                qci.queueFamilyIndex(family);
                FloatBuffer priorities = stack.floats(1.0f);
                qci.pQueuePriorities(priorities);
            }

            // 设备特性
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            features.samplerAnisotropy(physicalDevice.features().samplerAnisotropy());

            // 设备创建信息
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(features);

            // 扩展：swapchain
            PointerBuffer extBuf = stack.mallocPointer(1);
            extBuf.put(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            extBuf.flip();
            createInfo.ppEnabledExtensionNames(extBuf);

            // 创建设备
            PointerBuffer deviceBuf = stack.mallocPointer(1);
            int result = vkCreateDevice(physicalDevice.device(), createInfo, null, deviceBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create logical device");
            }

            this.device = new VkDevice(deviceBuf.get(0), physicalDevice.device(), createInfo);

            // 获取队列
            PointerBuffer queueBuf = stack.mallocPointer(1);

            vkGetDeviceQueue(this.device, physicalDevice.graphicsFamily(), 0, queueBuf);
            VkQueue vkGraphicsQueue = new VkQueue(queueBuf.get(0), this.device);
            this.graphicsQueue = new VulkanQueue(vkGraphicsQueue, physicalDevice.graphicsFamily());

            // compute 和 transfer 队列（暂时复用 graphics 族，P1 再分离）
            this.computeQueue = this.graphicsQueue;
            this.transferQueue = this.graphicsQueue;

            // 创建命令池
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType$Default();
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            poolInfo.queueFamilyIndex(physicalDevice.graphicsFamily());

            LongBuffer poolBuf = stack.mallocLong(1);
            result = vkCreateCommandPool(this.device, poolInfo, null, poolBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create command pool");
            }
            this.commandPoolHandle = poolBuf.get(0);

            // 描述符池（15 节落地简化：单池 + FREE 标志，设备销毁时整体回收；
            // 15.8 的按「布局 × 帧数」环形池留待材质/多 Pass 阶段）
            int[][] poolTypeCounts = {
                    {VK_DESCRIPTOR_TYPE_SAMPLER, 512},
                    {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1024},
                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 512},
                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 512},
                    {VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, 256},
                    {VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER, 256},
                    {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1024},
                    {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1024},
                    {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 256},
                    {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC, 256},
                    {VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT, 256},
            };
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(poolTypeCounts.length, stack);
            for (int i = 0; i < poolTypeCounts.length; i++) {
                poolSizes.get(i)
                        .type(poolTypeCounts[i][0])
                        .descriptorCount(poolTypeCounts[i][1]);
            }

            VkDescriptorPoolCreateInfo descriptorPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            descriptorPoolInfo.sType$Default();
            descriptorPoolInfo.flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);
            descriptorPoolInfo.maxSets(2048);
            descriptorPoolInfo.pPoolSizes(poolSizes);

            LongBuffer descriptorPoolBuf = stack.mallocLong(1);
            result = vkCreateDescriptorPool(device, descriptorPoolInfo, null, descriptorPoolBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create descriptor pool");
            }
            this.descriptorPoolHandle = descriptorPoolBuf.get(0);
        }

        CleanerRegistry.register(this, this, commandPoolHandle, CleanerRegistry.ResourceType.COMMAND_POOL);
        CleanerRegistry.register(this, this, descriptorPoolHandle, CleanerRegistry.ResourceType.DESCRIPTOR_POOL);
    }

    @Override
    public Texture createTexture(TextureDesc desc, UploadData data) {
        checkDisposed();
        VulkanTexture texture = new VulkanTexture(this, desc);
        if (data != null && data.data() != null) {
            uploadTexture(texture, data);
        }
        return texture;
    }

    @Override
    public Buffer createBuffer(BufferDesc desc, UploadData data) {
        checkDisposed();
        VulkanBuffer buffer = new VulkanBuffer(this, desc);
        if (data != null && data.data() != null) {
            buffer.update(0, data.data());
        }
        return buffer;
    }

    @Override
    public Sampler createSampler(SamplerDesc desc) {
        checkDisposed();
        return new VulkanSampler(this, desc);
    }

    @Override
    public GraphicsPipeline createGraphicsPipeline(GraphicsPipelineDesc desc) {
        checkDisposed();
        return new VulkanGraphicsPipeline(this, desc, VK_NULL_HANDLE);
    }

    @Override
    public ComputePipeline createComputePipeline(ComputePipelineDesc desc) {
        checkDisposed();
        return new VulkanComputePipeline(this, desc, VK_NULL_HANDLE);
    }

    @Override
    public DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc) {
        checkDisposed();
        return new VulkanDescriptorSetLayout(this, desc);
    }

    @Override
    public DescriptorSet createDescriptorSet(DescriptorSetLayout layout) {
        checkDisposed();
        if (layout == null) {
            throw new IllegalArgumentException("DescriptorSetLayout must not be null");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType$Default();
            allocInfo.descriptorPool(descriptorPoolHandle);
            allocInfo.pSetLayouts(stack.longs(layout.handle()));

            LongBuffer setBuf = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, allocInfo, setBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to allocate descriptor set");
            }
            return new VulkanDescriptorSet(this, setBuf.get(0));
        }
    }

    @Override
    public ShaderModule createShaderModule(byte[] spirv) {
        checkDisposed();
        return new VulkanShaderModule(this, spirv);
    }

    @Override
    public Queue graphicsQueue() { return graphicsQueue; }

    @Override
    public Queue computeQueue() { return computeQueue; }

    @Override
    public Queue transferQueue() { return transferQueue; }

    @Override
    public CommandBuffer allocateCommandBuffer(boolean secondary) {
        checkDisposed();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType$Default();
            allocInfo.commandPool(commandPoolHandle);
            allocInfo.level(secondary ? VK_COMMAND_BUFFER_LEVEL_SECONDARY : VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(1);

            PointerBuffer cmdBuf = stack.mallocPointer(1);
            int result = vkAllocateCommandBuffers(device, allocInfo, cmdBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to allocate command buffer");
            }
            return new VulkanCommandBuffer(this, new VkCommandBuffer(cmdBuf.get(0), device));
        }
    }

    @Override
    public FrameArena frameArena() {
        checkDisposed();
        return new VulkanFrameArena(this);
    }

    @Override
    public PipelineCache pipelineCache() {
        checkDisposed();
        // Stage 5 简化：返回空缓存句柄
        return new PipelineCache() {
            @Override
            public void saveToFile(java.nio.file.Path path) {}

            @Override
            public void loadFromFile(java.nio.file.Path path) {}

            @Override
            public long handle() { return VK_NULL_HANDLE; }

            @Override
            public void dispose() {}
        };
    }

    @Override
    public void submit(SubmitInfo info) {
        checkDisposed();
        // 单命令缓冲提交：从 info 中提取命令缓冲并提交到 graphics queue
        // 实际由 RenderGraph 执行器调用 queue.submit(cmd, info)
        throw new UnsupportedOperationException(
                "Use Queue.submit(cmd, info) directly. Device.submit is reserved for future batching.");
    }

    @Override
    public void waitIdle() {
        vkDeviceWaitIdle(device);
    }

    @Override
    public GPUInfo gpuInfo() {
        return gpuInfo;
    }

    // --- MSAA 能力（VkPhysicalDeviceLimits 位掩码解码，引擎抗锯齿档位钳制用） ---

    @Override
    public Set<SampleCount> supportedColorSampleCounts() {
        return decodeSampleCounts(physicalDevice.properties().limits().framebufferColorSampleCounts());
    }

    @Override
    public Set<SampleCount> supportedDepthSampleCounts() {
        return decodeSampleCounts(physicalDevice.properties().limits().framebufferDepthSampleCounts());
    }

    /** VkSampleCountFlags 位掩码 → 不可变 SampleCount 集（COUNT_1 规范恒支持） */
    private static Set<SampleCount> decodeSampleCounts(int vkBits) {
        EnumSet<SampleCount> out = EnumSet.of(SampleCount.COUNT_1);
        if ((vkBits & VK_SAMPLE_COUNT_2_BIT) != 0) out.add(SampleCount.COUNT_2);
        if ((vkBits & VK_SAMPLE_COUNT_4_BIT) != 0) out.add(SampleCount.COUNT_4);
        if ((vkBits & VK_SAMPLE_COUNT_8_BIT) != 0) out.add(SampleCount.COUNT_8);
        if ((vkBits & VK_SAMPLE_COUNT_16_BIT) != 0) out.add(SampleCount.COUNT_16);
        if ((vkBits & VK_SAMPLE_COUNT_32_BIT) != 0) out.add(SampleCount.COUNT_32);
        if ((vkBits & VK_SAMPLE_COUNT_64_BIT) != 0) out.add(SampleCount.COUNT_64);
        return Collections.unmodifiableSet(out);
    }

    @Override
    public RenderStats renderStats() {
        return stats;
    }

    @Override
    public void resetFrameStats() {
        stats.snapshotFrame();
    }

    /** 资源类/命令缓冲打点入口（构造器尾 ++ / dispose guard 后 -- / draw 录制累计） */
    RenderStats stats() {
        return stats;
    }

    @Override
    public long handle() {
        return device.address();
    }

    VkDevice vkDevice() { return device; }
    VulkanPhysicalDevice physicalDevice() { return physicalDevice; }
    long commandPoolHandle() { return commandPoolHandle; }

    boolean isDisposed() { return disposed; }

    // --- 帧同步原语 ---

    @Override
    public Fence createFence() {
        checkDisposed();
        return new VulkanFence(this);
    }

    @Override
    public Fence createFence(boolean signaled) {
        checkDisposed();
        return new VulkanFence(this, signaled);
    }

    @Override
    public Semaphore createSemaphore() {
        checkDisposed();
        return new VulkanSemaphore(this);
    }

    // --- 交换链 ---

    @Override
    public SwapChain createSwapChain(SwapChainDesc desc) {
        checkDisposed();
        if (desc == null) {
            throw new IllegalArgumentException("SwapChainDesc must not be null");
        }
        if (surface == null) {
            throw new IllegalStateException(
                    "Device was created without a RenderSurface; cannot create a swapchain");
        }
        return new VulkanSwapChain(this, surface, desc);
    }

    /**
     * 纹理上传（staging 路径，25 节）。
     * 创建 staging buffer → 拷贝数据 → 命令缓冲转换布局 + 拷贝 → fence 等待。
     */
    private void uploadTexture(VulkanTexture texture, UploadData data) {
        java.nio.ByteBuffer pixels = data.data();
        long size = pixels.remaining();

        // 创建 staging buffer（HOST_VISIBLE + COHERENT）
        BufferDesc stagingDesc = new BufferDesc(size,
                Set.of(BufferUsage.TRANSFER_SRC),
                MemoryPreference.hostVisibleCoherent());
        VulkanBuffer staging = new VulkanBuffer(this, stagingDesc);
        staging.update(0, pixels);

        // 录制：转换到 TRANSFER_DST → 拷贝
        CommandBuffer cmd = allocateCommandBuffer(false);
        cmd.begin();
        cmd.pipelineBarrier(texture,
                TextureLayout.UNDEFINED,
                TextureLayout.TRANSFER_DST);
        cmd.copyBufferToTexture(staging, texture,
                TextureRegion.whole2D(texture.desc().width(), texture.desc().height()));
        cmd.end();

        // 提交并等待
        Fence fence = createFence();
        graphicsQueue.submit(cmd, SubmitInfo.of(fence));
        fence.await();

        // 清理
        fence.dispose();
        cmd.dispose();
        staging.dispose();
    }

    @Override
    public void dispose() {
        if (disposed) return;
        // 冲刷延迟销毁队列（帧循环已停、无在飞资源）：排队的叶子资源在 vkDestroyDevice 之前显式释放，
        // 否则随设备销毁被隐式回收、显式销毁落空
        CleanerRegistry.flush();
        disposed = true;
        vkDestroyCommandPool(device, commandPoolHandle, null);
        vkDestroyDescriptorPool(device, descriptorPoolHandle, null);
        vkDestroyDevice(device, null);
        // VkSurfaceKHR 由实例层创建，须在设备销毁后释放
        if (surface != null) {
            surface.dispose();
        }
    }

    private void checkDisposed() {
        if (disposed) {
            throw new IllegalStateException("Device has been disposed");
        }
    }
}
