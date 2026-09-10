package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Instance;
import com.javelin.rhi.api.PhysicalDevice;
import com.javelin.rhi.api.RHIWindow;
import com.javelin.rhi.types.Feature;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Vulkan 实例实现
 */
public class VulkanInstance extends Instance {

    private static final Logger log = LoggerFactory.getLogger(VulkanInstance.class);

    private final VkInstance instance;
    private final long debugMessenger;
    private final boolean validationEnabled;
    private final List<VulkanPhysicalDevice> physicalDevices;

    private DebugMessenger userMessenger;

    public VulkanInstance(boolean enableValidation, String appName, int appVersion, RHIWindow window) {
        // 检查验证层是否可用，不可用时自动降级
        if (enableValidation && !isValidationLayerAvailable()) {
            log.warn("Validation layer VK_LAYER_KHRONOS_validation not available, disabling validation");
            enableValidation = false;
        }
        this.validationEnabled = enableValidation;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 应用信息
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8(appName))
                .applicationVersion(appVersion)
                .pEngineName(stack.UTF8("Javelin"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK_API_VERSION_1_3);

            // 扩展
            List<String> extensions = new ArrayList<>();
            if (window != null) {
                for (String ext : window.requiredInstanceExtensions()) {
                    extensions.add(ext);
                }
            }
            if (enableValidation) {
                extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }

            PointerBuffer ppExtensions = stack.pointers(extensions.stream()
                .map(stack::UTF8)
                .toArray(ByteBuffer[]::new));

            PointerBuffer ppLayers = null;
            if (enableValidation) {
                ppLayers = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
            }

            // 实例创建信息
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(ppExtensions);

            if (ppLayers != null) {
                createInfo.ppEnabledLayerNames(ppLayers);
            }

            // 调试信使创建信息（在 vkCreateInstance vkDestroyInstance 期间也生效）
            VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = null;
            if (enableValidation) {
                debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                    .messageSeverity(
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                    .messageType(
                        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                    .pfnUserCallback(this::debugCallback);
                createInfo.pNext(debugCreateInfo.address());
            }

            PointerBuffer pInstance = stack.pointers(1);
            VulkanChecks.check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            this.instance = new VkInstance(pInstance.get(0), createInfo);

            // 创建调试信使
            if (enableValidation) {
                LongBuffer pMessenger = stack.mallocLong(1);
                VulkanChecks.check(vkCreateDebugUtilsMessengerEXT(instance, debugCreateInfo, null, pMessenger),
                    "vkCreateDebugUtilsMessengerEXT");
                this.debugMessenger = pMessenger.get(0);
            } else {
                this.debugMessenger = VK_NULL_HANDLE;
            }

            // 枚举物理设备
            this.physicalDevices = enumeratePhysicalDevices(stack);

            log.info("Vulkan instance created (validation={}, devices={})", enableValidation, physicalDevices.size());
        }
    }

    private List<VulkanPhysicalDevice> enumeratePhysicalDevices(MemoryStack stack) {
        IntBuffer pCount = stack.mallocInt(1);
        VulkanChecks.check(vkEnumeratePhysicalDevices(instance, pCount, null), "vkEnumeratePhysicalDevices");

        int count = pCount.get(0);
        if (count == 0) {
            throw new RuntimeException("No Vulkan-compatible physical devices found");
        }

        PointerBuffer pDevices = stack.mallocPointer(count);
        VulkanChecks.check(vkEnumeratePhysicalDevices(instance, pCount, pDevices), "vkEnumeratePhysicalDevices");

        List<VulkanPhysicalDevice> devices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            devices.add(new VulkanPhysicalDevice(new VkPhysicalDevice(pDevices.get(i), instance)));
        }
        return devices;
    }

    @Override
    public long handle() {
        return instance.address();
    }

    @Override
    protected void doDispose() {
        if (debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        vkDestroyInstance(instance, null);
        log.info("Vulkan instance destroyed");
    }

    @Override
    public PhysicalDevice[] enumeratePhysicalDevices() {
        return physicalDevices.toArray(new PhysicalDevice[0]);
    }

    @Override
    public PhysicalDevice selectPhysicalDevice(PhysicalDevicePreferences prefs) {
        for (VulkanPhysicalDevice device : physicalDevices) {
            // 检查必需特性
            if (prefs.requiredFeatures() != null) {
                boolean allSupported = true;
                for (Feature feature : prefs.requiredFeatures()) {
                    if (!device.supports(feature)) {
                        allSupported = false;
                        break;
                    }
                }
                if (!allSupported) continue;
            }

            // 检查必需扩展
            if (prefs.requiredExtensions() != null) {
                boolean allSupported = true;
                for (String ext : prefs.requiredExtensions()) {
                    if (!device.supportsExtension(ext)) {
                        allSupported = false;
                        break;
                    }
                }
                if (!allSupported) continue;
            }

            // 检查队列族
            boolean hasGraphics = false;
            boolean hasCompute = false;
            boolean hasTransfer = false;
            boolean hasPresent = false;

            for (PhysicalDevice.QueueFamilyProperties family : device.queueFamilies()) {
                if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) hasGraphics = true;
                if ((family.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) hasCompute = true;
                if ((family.queueFlags() & VK_QUEUE_TRANSFER_BIT) != 0) hasTransfer = true;
                // present 需要 surface 检查，这里简化处理
                hasPresent = true;
            }

            if (prefs.requireGraphicsQueue() && !hasGraphics) continue;
            if (prefs.requireComputeQueue() && !hasCompute) continue;
            if (prefs.requireTransferQueue() && !hasTransfer) continue;
            if (prefs.requirePresentQueue() && !hasPresent) continue;

            // 优先选择独立显卡
            if (prefs.preferDiscrete() && device.deviceType() == PhysicalDevice.DeviceType.DISCRETE_GPU) {
                log.info("Selected discrete GPU: {}", device.deviceName());
                return device;
            }
        }

        // 回退：返回第一个可用设备
        if (!physicalDevices.isEmpty()) {
            PhysicalDevice device = physicalDevices.get(0);
            log.info("Selected GPU: {}", device.deviceName());
            return device;
        }

        throw new RuntimeException("No suitable physical device found");
    }

    @Override
    public void setDebugMessenger(DebugMessenger messenger) {
        this.userMessenger = messenger;
    }

    private int debugCallback(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        String message = data.pMessageString();

        DebugSeverity severity = switch (messageSeverity) {
            case VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT -> DebugSeverity.VERBOSE;
            case VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT -> DebugSeverity.INFO;
            case VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT -> DebugSeverity.WARNING;
            case VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT -> DebugSeverity.ERROR;
            default -> DebugSeverity.INFO;
        };

        DebugType type = switch (messageTypes) {
            case VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT -> DebugType.GENERAL;
            case VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT -> DebugType.VALIDATION;
            case VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT -> DebugType.PERFORMANCE;
            default -> DebugType.OTHER;
        };

        if (userMessenger != null) {
            userMessenger.message(severity, type, message);
        } else {
            switch (severity) {
                case ERROR -> log.error("[{}] {}", type, message);
                case WARNING -> log.warn("[{}] {}", type, message);
                case INFO -> log.info("[{}] {}", type, message);
                case VERBOSE -> log.debug("[{}] {}", type, message);
            }
        }

        return VK_FALSE;
    }

    VkInstance vkInstance() {
        return instance;
    }

    /**
     * 检查 VK_LAYER_KHRONOS_validation 验证层是否可用。
     */
    private static boolean isValidationLayerAvailable() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            int result = vkEnumerateInstanceLayerProperties(pCount, null);
            if (result != VK_SUCCESS) {
                return false;
            }

            int count = pCount.get(0);
            if (count == 0) {
                return false;
            }

            VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count, stack);
            result = vkEnumerateInstanceLayerProperties(pCount, layers);
            if (result != VK_SUCCESS) {
                return false;
            }

            for (int i = 0; i < count; i++) {
                if ("VK_LAYER_KHRONOS_validation".equals(layers.get(i).layerNameString())) {
                    return true;
                }
            }
            return false;
        }
    }
}
