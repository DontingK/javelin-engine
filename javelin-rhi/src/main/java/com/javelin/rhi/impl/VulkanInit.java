package com.javelin.rhi.impl;

import com.javelin.rhi.RHIException;
import com.javelin.rhi.RHISurface;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Vulkan 初始化辅助类，负责创建 VkInstance、选择物理设备、创建逻辑设备。
 * <p>
 * 标准初始化流程：
 * <ol>
 *   <li>创建 VkInstance（含应用信息、验证层、扩展）</li>
 *   <li>枚举物理设备，选择第一个支持图形队列的设备</li>
 *   <li>查找支持图形操作的队列族索引</li>
 *   <li>创建 VkDevice（含队列创建信息、设备特性）</li>
 *   <li>获取图形队列句柄</li>
 * </ol>
 */
final class VulkanInit {

    private static final Logger logger = LoggerFactory.getLogger(VulkanInit.class);

    /**
     * Vulkan API 版本：1.3
     */
    private static final int API_VERSION = VK_API_VERSION_1_3;

    /**
     * 验证层名称（Debug 构建启用）
     */
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private VulkanInit() {
    }

    /**
     * 初始化 Vulkan 环境。
     *
     * @return 初始化结果上下文，包含 instance/device/physicalDevice/queue 等
     */
    static Context init(RHISurface surface) {
        VkInstance instance = createInstance(surface);
        PhysicalDeviceSelection selection = selectPhysicalDevice(instance);
        DeviceContext deviceCtx = createLogicalDevice(selection);

        logger.info("Vulkan 初始化完成：设备={}, 队列族={}",
                selection.properties.deviceNameString(), deviceCtx.graphicsFamily);

        return new Context(instance, selection.physicalDevice, deviceCtx);
    }

    /**
     * 创建 Vulkan 实例。
     */
    private static VkInstance createInstance(RHISurface surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Javelin Engine"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("Javelin"))
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(API_VERSION);

            // 实例扩展：至少需要 VK_KHR_surface + 平台特定 surface 扩展
            String[] extensions = surface.getRequiredInstanceExtensions();
            ArrayList<String> extensionList = new ArrayList<>();
            for (String extension : extensions) {
                extensionList.add(extension);
            }
            // TODO: 根据实际需要添加验证层（Debug 模式）和额外扩展
            extensionList.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            PointerBuffer extensionBuffer = stack.pointers(extensionList.stream()
                    .map(stack::UTF8)
                    .toArray(java.nio.ByteBuffer[]::new));


            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(extensionBuffer);

            PointerBuffer instancePtr = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, instancePtr);
            if (result != VK_SUCCESS) {
                throw new RHIException("vkCreateInstance 失败: " + result);
            }

            VkInstance instance = new VkInstance(instancePtr.get(0), createInfo);
            logger.info("VkInstance 创建成功");
            return instance;
        }
    }

    /**
     * 枚举物理设备并选择第一个支持图形队列的设备。
     */
    private static PhysicalDeviceSelection selectPhysicalDevice(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int result = vkEnumeratePhysicalDevices(instance, count, null);
            if (result != VK_SUCCESS) {
                throw new RHIException("vkEnumeratePhysicalDevices (count) 失败: " + result);
            }

            int deviceCount = count.get(0);
            if (deviceCount == 0) {
                throw new RHIException("未找到支持 Vulkan 的物理设备");
            }

            PointerBuffer devices = stack.mallocPointer(deviceCount);
            result = vkEnumeratePhysicalDevices(instance, count, devices);
            if (result != VK_SUCCESS) {
                throw new RHIException("vkEnumeratePhysicalDevices (enumerate) 失败: " + result);
            }

            for (int i = 0; i < deviceCount; i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(device, properties);

                int graphicsFamily = findGraphicsQueueFamily(device);
                if (graphicsFamily >= 0) {
                    logger.info("选择物理设备: {} (队列族={})",
                            properties.deviceNameString(), graphicsFamily);
                    return new PhysicalDeviceSelection(device, properties, graphicsFamily);
                }
            }

            throw new RHIException("未找到支持图形队列的物理设备");
        }
    }

    /**
     * 查找支持图形操作的队列族索引。
     *
     * @return 队列族索引，未找到返回 -1
     */
    private static int findGraphicsQueueFamily(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);

            VkQueueFamilyProperties.Buffer families =
                    VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

            for (int i = 0; i < families.capacity(); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * 创建逻辑设备并获取图形队列。
     */
    private static DeviceContext createLogicalDevice(PhysicalDeviceSelection selection) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(selection.graphicsFamily)
                    .pQueuePriorities(stack.floats(1.0f));

            // 设备扩展：需要 swapchain 支持
            PointerBuffer deviceExtensions = stack.pointers(
                    stack.UTF8("VK_KHR_swapchain")
            );

            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);

            VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfos)
                    .ppEnabledExtensionNames(deviceExtensions)
                    .pEnabledFeatures(features);

            PointerBuffer devicePtr = stack.mallocPointer(1);
            int result = vkCreateDevice(selection.physicalDevice, deviceCreateInfo, null, devicePtr);
            if (result != VK_SUCCESS) {
                throw new RHIException("vkCreateDevice 失败: " + result);
            }

            VkDevice device = new VkDevice(devicePtr.get(0), selection.physicalDevice, deviceCreateInfo);

            // 获取图形队列
            PointerBuffer queuePtr = stack.mallocPointer(1);
            vkGetDeviceQueue(device, selection.graphicsFamily, 0, queuePtr);
            VkQueue graphicsQueue = new VkQueue(queuePtr.get(0), device);

            logger.info("VkDevice 和图形队列创建成功");
            return new DeviceContext(device, graphicsQueue, selection.graphicsFamily);
        }
    }

    // ------------------------------------------------------------------------
    // 内部数据类
    // ------------------------------------------------------------------------

    /**
     * Vulkan 初始化结果上下文。
     */
    static final class Context {
        final VkInstance instance;
        final VkPhysicalDevice physicalDevice;
        final VkDevice device;
        final VkQueue graphicsQueue;
        final int graphicsFamily;

        Context(VkInstance instance, VkPhysicalDevice physicalDevice, DeviceContext deviceCtx) {
            this.instance = instance;
            this.physicalDevice = physicalDevice;
            this.device = deviceCtx.device;
            this.graphicsQueue = deviceCtx.graphicsQueue;
            this.graphicsFamily = deviceCtx.graphicsFamily;
        }
    }

    private static final class PhysicalDeviceSelection {
        final VkPhysicalDevice physicalDevice;
        final VkPhysicalDeviceProperties properties;
        final int graphicsFamily;

        PhysicalDeviceSelection(VkPhysicalDevice physicalDevice,
                                VkPhysicalDeviceProperties properties,
                                int graphicsFamily) {
            this.physicalDevice = physicalDevice;
            this.properties = properties;
            this.graphicsFamily = graphicsFamily;
        }
    }

    private static final class DeviceContext {
        final VkDevice device;
        final VkQueue graphicsQueue;
        final int graphicsFamily;

        DeviceContext(VkDevice device, VkQueue graphicsQueue, int graphicsFamily) {
            this.device = device;
            this.graphicsQueue = graphicsQueue;
            this.graphicsFamily = graphicsFamily;
        }
    }
}
