package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.types.TextureLayout;
import cn.donting.javelin.rhi.desc.*;
import cn.donting.javelin.rhi.exception.VulkanException;
import cn.donting.javelin.rhi.render.RenderSurface;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.TextureType;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

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
    /** 光追能力是否**已启用**（扩展 + 特性都进 device；P4-06 B1）。B2 建 AS 前必须查 */
    private final boolean rayQueryEnabled;
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

            // Dynamic Rendering（Vulkan 1.3 core）：引擎全程走 dynamic rendering
            // （renderPass=NULL + VkPipelineRenderingCreateInfo + vkCmdBeginRendering），
            // 不启用即非法用法；设备不支持时 vkCreateDevice 抛 FEATURE_NOT_PRESENT（下方统一处理）
            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack);
            features13.sType$Default();
            features13.dynamicRendering(true);
            createInfo.pNext(features13);

            // ── 光追能力（P4-06 B1）：扩展只在该驱动真的会上报时才加进启用列表，
            // 特性链同理——**能力可选不阻断引擎启动**，但真正要建 AS 的批次
            // （B2）必须先查 Device.rayQueryEnabled()，缺失就显性抛错（红线 15）
            VkPhysicalDeviceFeatures2 probe = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructureFeaturesKHR asProbe =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayQueryFeaturesKHR rqProbe =
                    VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceBufferDeviceAddressFeatures bdaProbe =
                    VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
            probe.pNext(asProbe);
            asProbe.pNext(rqProbe.address());
            rqProbe.pNext(bdaProbe.address());
            vkGetPhysicalDeviceFeatures2(physicalDevice.device(), probe);

            Set<String> enabledExtensions = new LinkedHashSet<>();
            enabledExtensions.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            boolean accelSupported = physicalDevice.hasExtension(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                    && asProbe.accelerationStructure();
            boolean rayQuerySupported = physicalDevice.hasExtension(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME)
                    && rqProbe.rayQuery();
            boolean bdaSupported = physicalDevice.hasExtension(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME)
                    && bdaProbe.bufferDeviceAddress();
            // VK_KHR_acceleration_structure 规范依赖 deferred_host_operations（同步构建也用得上）
            boolean deferredSupported = physicalDevice.hasExtension(
                    KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
            if (accelSupported && deferredSupported) {
                enabledExtensions.add(KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
                enabledExtensions.add(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
            } else {
                accelSupported = false;
            }
            if (rayQuerySupported && accelSupported) {
                enabledExtensions.add(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME);
            } else {
                rayQuerySupported = false;
            }
            if (bdaSupported && accelSupported) {
                enabledExtensions.add(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
            } else {
                bdaSupported = false;
            }

            // 特性链：另建一套结构（探测那套用完即弃，避免 pNext 复用出错）
            if (accelSupported) {
                VkPhysicalDeviceAccelerationStructureFeaturesKHR asEnable =
                        VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
                asEnable.accelerationStructure(true);
                VkPhysicalDeviceBufferDeviceAddressFeatures bdaEnable = null;
                if (bdaSupported) {
                    bdaEnable = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
                    bdaEnable.bufferDeviceAddress(true);
                }
                if (rayQuerySupported) {
                    VkPhysicalDeviceRayQueryFeaturesKHR rqEnable =
                            VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
                    rqEnable.rayQuery(true);
                    if (bdaEnable != null) {
                        rqEnable.pNext(bdaEnable.address());
                    }
                    asEnable.pNext(rqEnable.address());
                } else if (bdaEnable != null) {
                    asEnable.pNext(bdaEnable.address());
                }
                features13.pNext(asEnable.address());
            }

            this.rayQueryEnabled = rayQuerySupported;

            // 扩展启用列表（按实际可用拼装）
            PointerBuffer extBuf = stack.mallocPointer(enabledExtensions.size());
            for (String name : enabledExtensions) {
                extBuf.put(stack.UTF8(name));
            }
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
                    // 光追：TLAS 描述符（P4-06 B3）
                    {KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 64},
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

    /** 所属实例（包内：CommandBuffer 取 debug_utils 门控用） */
    VulkanInstance instance() {
        return instance;
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

    /** 格式能力查询（RHI-15）：转物理设备的缓存表 */
    @Override
    public FormatSupport formatSupport(Format format) {
        checkDisposed();
        return physicalDevice.formatSupport(format);
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

    @Override
    public int maxPushConstantsSize() {
        return physicalDevice.properties().limits().maxPushConstantsSize();
    }

    @Override
    public boolean hasDeviceExtension(String extensionName) {
        return extensionName != null && physicalDevice.hasExtension(extensionName);
    }

    @Override
    public boolean supportsRayQuery() {
        if (!hasDeviceExtension(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME)) {
            return false;
        }
        // 扩展在列只是必要条件：feature 位还得是 true（部分驱动上报扩展但把 rayQuery 关掉）
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceRayQueryFeaturesKHR rayQuery =
                    VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            features2.pNext(rayQuery);
            vkGetPhysicalDeviceFeatures2(physicalDevice.device(), features2);
            return rayQuery.rayQuery();
        }
    }

    @Override
    public boolean rayQueryEnabled() {
        return rayQueryEnabled;
    }

    @Override
    public AccelerationStructure createBlas(cn.donting.javelin.rhi.desc.BlasDesc desc) {
        return new VulkanAccelerationStructure(this, desc);
    }

    @Override
    public Tlas createTlas(int capacity) {
        return new VulkanTlas(this, capacity);
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

    /** 描述符池句柄（包内：VulkanDescriptorSet.dispose 归还池用） */
    long descriptorPoolHandle() { return descriptorPoolHandle; }
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
     *
     * <p>数据布局按纹理种类分流：TYPE_2D / TYPE_1D = 整段单拷贝（whole2D）；CUBE = 六面连续紧密
     * 打包（每面 faceSize² 像素、layer 顺序 = Vulkan cubemap 面 +X/-X/+Y/-Y/+Z/-Z）；
     * TYPE_3D = z 切片顺序整拷（extent.depth 表达第三维）；TYPE_2D_ARRAY / CUBE_ARRAY = 逐层整拷
     * （批次⑤ Skybox_Design §3 / RHI_Texture_Types_Design）。</p>
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
        if (texture.desc().type() == TextureType.CUBE) {
            // cubemap：单命令六层整拷（buffer 六面连续紧密打包、layer 顺序自动取址——
            // VkBufferImageCopy 按 layerCount 逐层从 buffer 顺序取址，无需逐面循环）
            cmd.copyBufferToTexture(staging, texture,
                    TextureRegion.cubeFaces(texture.desc().width()));
        } else {
            // 其余按种类取区域：3D 用 extent.depth 表达第三维、2D 数组/cube 数组按层整拷
            cmd.copyBufferToTexture(staging, texture, uploadRegionOf(texture.desc()));
        }
        // 拷贝后转回 SHADER_READ_ONLY：采样侧描述符恒声明 SHADER_READ_ONLY_OPTIMAL
        // （VulkanDescriptorSet），停在 TRANSFER_DST 直接采样即布局不匹配——校验层报
        // 09600、无校验层时是未定义行为（2026-11-12 实测，Pitfalls #37）。上传 fence
        // 已担保拷贝写入可见，此处只需布局转换本身
        cmd.pipelineBarrier(texture,
                TextureLayout.TRANSFER_DST,
                TextureLayout.SHADER_READ_ONLY);
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

    /**
     * 上传区域按纹理种类取（buffer 侧一律紧密打包）：TYPE_3D 的第三维由 extent.depth 表达；
     * TYPE_2D_ARRAY / CUBE_ARRAY 按 arrayLayers 逐层整拷（VkBufferImageCopy 按层顺序取址）；
     * TYPE_1D / TYPE_2D 走 {@link TextureRegion#whole2D}。
     */
    private static TextureRegion uploadRegionOf(TextureDesc desc) {
        return switch (desc.type()) {
            case TYPE_2D_ARRAY, CUBE_ARRAY ->
                    TextureRegion.whole2DArray(desc.width(), desc.height(), desc.arrayLayers());
            case TYPE_3D -> TextureRegion.whole3D(desc.width(), desc.height(), desc.depth());
            default -> TextureRegion.whole2D(desc.width(), desc.height());
        };
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
