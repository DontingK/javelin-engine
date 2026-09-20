package com.javelin.rhi;

import com.javelin.rhi.desc.*;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.render.RenderSurface;
import com.javelin.rhi.types.BufferUsage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
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
    private final long commandPoolHandle;
    private volatile boolean disposed;

    VulkanDevice(VulkanInstance instance, VkInstance vkInstance,
                 RHIDevicePrefs prefs, RenderSurface surface, boolean debug) {
        this.instance = instance;

        // 选择物理设备（surface 感知：若 surface 已创建则检查 present 支持）
        long surfaceHandle = surface != null ? surface.nativeHandle() : 0;
        this.physicalDevice = VulkanPhysicalDevice.select(vkInstance, prefs, surfaceHandle);

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
        }

        CleanerRegistry.register(this, commandPoolHandle, CleanerRegistry.ResourceType.COMMAND_POOL);
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
    public PipelineLayout createPipelineLayout(PipelineLayoutDesc desc) {
        checkDisposed();
        return new VulkanPipelineLayout(this, desc);
    }

    @Override
    public DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc) {
        checkDisposed();
        return new VulkanDescriptorSetLayout(this, desc);
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
    public long handle() {
        return device.address();
    }

    VkDevice vkDevice() { return device; }
    VulkanPhysicalDevice physicalDevice() { return physicalDevice; }
    long commandPoolHandle() { return commandPoolHandle; }

    boolean isDisposed() { return disposed; }

    // --- 工厂方法（包私有，FrameState / 上传路径使用） ---

    Fence createFence() {
        checkDisposed();
        return new VulkanFence(this);
    }

    Fence createFence(boolean signaled) {
        checkDisposed();
        return new VulkanFence(this, signaled);
    }

    Semaphore createSemaphore() {
        checkDisposed();
        return new VulkanSemaphore(this);
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
                com.javelin.rhi.types.TextureLayout.UNDEFINED,
                com.javelin.rhi.types.TextureLayout.TRANSFER_DST);
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
        disposed = true;
        vkDestroyCommandPool(device, commandPoolHandle, null);
        vkDestroyDevice(device, null);
    }

    private void checkDisposed() {
        if (disposed) {
            throw new IllegalStateException("Device has been disposed");
        }
    }
}
