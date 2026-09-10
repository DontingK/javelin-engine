package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.*;
import com.javelin.rhi.api.Queue;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Vulkan 逻辑设备实现 */
public class VulkanDevice extends Device {

    private static final Logger log = LoggerFactory.getLogger(VulkanDevice.class);

    private final VulkanInstance instance;
    private final VulkanPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VulkanQueue graphicsQueue;
    private final VulkanQueue computeQueue;
    private final VulkanQueue transferQueue;
    private final int graphicsQueueFamily;
    private final int computeQueueFamily;
    private final int transferQueueFamily;

    public VulkanDevice(VulkanInstance instance, VulkanPhysicalDevice physicalDevice,
                        Set<String> enabledExtensions) {
        this.instance = instance;
        this.physicalDevice = physicalDevice;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 查找队列族
            this.graphicsQueueFamily = findQueueFamily(physicalDevice, VK_QUEUE_GRAPHICS_BIT);
            this.computeQueueFamily = findQueueFamily(physicalDevice, VK_QUEUE_COMPUTE_BIT);
            this.transferQueueFamily = findQueueFamily(physicalDevice, VK_QUEUE_TRANSFER_BIT);

            // 去重队列族索引
            Set<Integer> uniqueFamilies = new LinkedHashSet<>();
            uniqueFamilies.add(graphicsQueueFamily);
            if (computeQueueFamily >= 0) uniqueFamilies.add(computeQueueFamily);
            if (transferQueueFamily >= 0) uniqueFamilies.add(transferQueueFamily);

            // 创建队列信息
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);
            FloatBuffer priorities = stack.floats(1.0f);

            int i = 0;
            for (int family : uniqueFamilies) {
                queueCreateInfos.get(i)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(family)
                    .pQueuePriorities(priorities);
                i++;
            }

            // 设备特性
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
                .samplerAnisotropy(physicalDevice.features().samplerAnisotropy())
                .fillModeNonSolid(physicalDevice.features().fillModeNonSolid())
                .wideLines(physicalDevice.features().wideLines());

            // Vulkan 1.3 特性
            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .dynamicRendering(physicalDevice.features13().dynamicRendering())
                .synchronization2(physicalDevice.features13().synchronization2());

            // 扩展
            List<String> extensions = new ArrayList<>(enabledExtensions);
            extensions.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);

            // 去重并转换为指针缓冲区
            Set<String> uniqueExts = new LinkedHashSet<>(extensions);
            PointerBuffer ppExtensions = stack.pointers(
                uniqueExts.stream().map(stack::UTF8).toArray(java.nio.ByteBuffer[]::new));

            // 设备创建信息
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCreateInfos)
                .pEnabledFeatures(features)
                .ppEnabledExtensionNames(ppExtensions)
                .pNext(features13);

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);
            VulkanChecks.check(vkCreateDevice(physicalDevice.vkPhysicalDevice(), createInfo, null, pDevice),
                "vkCreateDevice");
            this.device = new VkDevice(pDevice.get(0), physicalDevice.vkPhysicalDevice(), createInfo);

            // 获取队列
            this.graphicsQueue = new VulkanQueue(device, graphicsQueueFamily, 0, Queue.QueueType.GRAPHICS);
            this.computeQueue = computeQueueFamily >= 0
                ? new VulkanQueue(device, computeQueueFamily, 0, Queue.QueueType.COMPUTE)
                : null;
            this.transferQueue = transferQueueFamily >= 0
                ? new VulkanQueue(device, transferQueueFamily, 0, Queue.QueueType.TRANSFER)
                : null;

            log.info("Vulkan device created: {}", physicalDevice.deviceName());
        }
    }

    private int findQueueFamily(VulkanPhysicalDevice device, int queueFlags) {
        PhysicalDevice.QueueFamilyProperties[] families = device.queueFamilies();
        for (int i = 0; i < families.length; i++) {
            if ((families[i].queueFlags() & queueFlags) != 0) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public long handle() {
        return device.address();
    }

    @Override
    protected void doDispose() {
        vkDeviceWaitIdle(device);
        vkDestroyDevice(device, null);
        log.info("Vulkan device destroyed");
    }

    @Override
    public PhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    @Override
    public Instance instance() {
        return instance;
    }

    @Override
    public Queue graphicsQueue() {
        return graphicsQueue;
    }

    @Override
    public Queue computeQueue() {
        return computeQueue;
    }

    @Override
    public Queue transferQueue() {
        return transferQueue;
    }

    @Override
    public Buffer createBuffer(BufferDesc desc) {
        return new VulkanBuffer(this, desc);
    }

    @Override
    public Texture createTexture(TextureDesc desc) {
        return new VulkanTexture(this, desc);
    }

    @Override
    public Shader createShader(ShaderDesc desc) {
        return new VulkanShader(this, desc);
    }

    @Override
    public GraphicsPipeline createGraphicsPipeline(GraphicsPipelineDesc desc) {
        return new VulkanGraphicsPipeline(this, desc);
    }

    @Override
    public ComputePipeline createComputePipeline(ComputePipelineDesc desc) {
        throw new UnsupportedOperationException("Compute pipeline not yet implemented");
    }

    @Override
    public PipelineLayout createPipelineLayout(PipelineLayoutDesc desc) {
        return new VulkanPipelineLayout(this, desc);
    }

    @Override
    public DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc) {
        return new VulkanDescriptorSetLayout(this, desc);
    }

    @Override
    public DescriptorPool createDescriptorPool(DescriptorPoolDesc desc) {
        return new VulkanDescriptorPool(this, desc);
    }

    @Override
    public Fence createFence(boolean signaled) {
        return new VulkanFence(this, signaled);
    }

    @Override
    public Semaphore createSemaphore() {
        return new VulkanSemaphore(this);
    }

    @Override
    public CommandPool createCommandPool(Queue queue, CommandPoolFlags flags) {
        return new VulkanCommandPool(this, queue, flags);
    }

    @Override
    public CommandBuffer createCommandBuffer(CommandPool pool, boolean primary) {
        return ((VulkanCommandPool) pool).allocate(primary);
    }

    @Override
    public void waitIdle() {
        vkDeviceWaitIdle(device);
    }

    @Override
    public void waitForFences(Fence[] fences, boolean waitAll, long timeoutNanos) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFences = stack.mallocLong(fences.length);
            for (Fence fence : fences) {
                pFences.put(fence.handle());
            }
            pFences.flip();
            VulkanChecks.check(vkWaitForFences(device, pFences, waitAll, timeoutNanos), "vkWaitForFences");
        }
    }

    @Override
    public void resetFences(Fence[] fences) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFences = stack.mallocLong(fences.length);
            for (Fence fence : fences) {
                pFences.put(fence.handle());
            }
            pFences.flip();
            VulkanChecks.check(vkResetFences(device, pFences), "vkResetFences");
        }
    }

    @Override
    public SwapChain createSwapChain(RHIWindow window, SwapChainDesc desc) {
        long surface = window.createSurface(instance.vkInstance());
        if (surface == 0) {
            throw new RuntimeException("Failed to create Vulkan surface");
        }
        return new VulkanSwapChain(this, window, surface, desc);
    }

    VkDevice vkDevice() {
        return device;
    }

    int graphicsQueueFamily() {
        return graphicsQueueFamily;
    }

    int computeQueueFamily() {
        return computeQueueFamily;
    }

    int transferQueueFamily() {
        return transferQueueFamily;
    }
}
