package com.javelin.rhi;

import com.javelin.rhi.desc.*;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
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
    private volatile boolean disposed;

    VulkanDevice(VulkanInstance instance, VkInstance vkInstance,
                 RHIDevicePrefs prefs, RenderSurface surface, boolean debug) {
        this.instance = instance;

        // 选择物理设备
        long surfaceHandle = 0;
        // 注意：surface 可能还未创建，这里用 0 表示离屏模式
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
                // queueCount 由 pQueuePriorities.remaining() 自动设置
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
        }
    }

    @Override
    public Texture createTexture(TextureDesc desc, UploadData data) {
        checkDisposed();
        VulkanTexture texture = new VulkanTexture(this, desc);
        // TODO: Stage 5 处理 data 上传
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
        throw new UnsupportedOperationException("Not yet implemented (Stage 4)");
    }

    @Override
    public FrameArena frameArena() {
        throw new UnsupportedOperationException("Not yet implemented (Stage 3)");
    }

    @Override
    public PipelineCache pipelineCache() {
        throw new UnsupportedOperationException("Not yet implemented (Stage 3)");
    }

    @Override
    public void submit(SubmitInfo info) {
        throw new UnsupportedOperationException("Not yet implemented (Stage 4)");
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

    boolean isDisposed() { return disposed; }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        vkDestroyDevice(device, null);
    }

    private void checkDisposed() {
        if (disposed) {
            throw new IllegalStateException("Device has been disposed");
        }
    }
}
