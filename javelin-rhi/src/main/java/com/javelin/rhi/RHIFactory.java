package com.javelin.rhi;

import com.javelin.rhi.api.Device;
import com.javelin.rhi.api.Instance;
import com.javelin.rhi.api.RHIWindow;
import com.javelin.rhi.vulkan.VulkanDevice;
import com.javelin.rhi.vulkan.VulkanInstance;
import com.javelin.rhi.vulkan.VulkanPhysicalDevice;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * RHI 工厂类，简化 Vulkan 初始化流程。
 */
public class RHIFactory {

    private static final Logger log = LoggerFactory.getLogger(RHIFactory.class);

    /**
     * 创建 Vulkan 实例（根据窗口平台自动加载所需扩展）。
     */
    public static Instance createInstance(boolean enableValidation, String appName, RHIWindow window) {
        return new VulkanInstance(enableValidation, appName, VK10.VK_MAKE_VERSION(1, 0, 0), window);
    }

    /**
     * 创建 Vulkan 实例（无窗口，用于离屏渲染）。
     */
    public static Instance createInstance(boolean enableValidation, String appName) {
        return new VulkanInstance(enableValidation, appName, VK10.VK_MAKE_VERSION(1, 0, 0), null);
    }

    /**
     * 创建 Vulkan 设备（使用默认物理设备选择）。
     */
    public static Device createDevice(Instance instance, boolean enableValidation) {
        return createDevice(instance, new Instance.PhysicalDevicePreferences(
            true, true, true, true, true,
            new String[]{"VK_KHR_swapchain"},
            null
        ), enableValidation);
    }

    /**
     * 创建 Vulkan 设备（自定义物理设备偏好）。
     */
    public static Device createDevice(Instance instance, Instance.PhysicalDevicePreferences prefs, boolean enableValidation) {
        VulkanInstance vkInstance = (VulkanInstance) instance;
        VulkanPhysicalDevice physicalDevice = (VulkanPhysicalDevice) instance.selectPhysicalDevice(prefs);

        Set<String> extensions = new HashSet<>();
        extensions.add("VK_KHR_swapchain");
        if (enableValidation) {
            // 可以添加更多调试扩展
        }

        return new VulkanDevice(vkInstance, physicalDevice, extensions);
    }

    /**
     * 完整的初始化流程：Instance + Device（带窗口）。
     */
    public static RHIContext initialize(RHIConfig config, RHIWindow window) {
        log.info("Initializing RHI with config: {}", config);

        Instance instance = createInstance(config.validation(), config.appName(), window);
        Device device = createDevice(instance, config.validation());

        log.info("RHI initialized successfully");
        return new RHIContext(instance, device);
    }

    /**
     * 完整的初始化流程：Instance + Device（无窗口，离屏渲染）。
     */
    public static RHIContext initialize(RHIConfig config) {
        log.info("Initializing RHI with config: {}", config);

        Instance instance = createInstance(config.validation(), config.appName());
        Device device = createDevice(instance, config.validation());

        log.info("RHI initialized successfully");
        return new RHIContext(instance, device);
    }

    /**
     * RHI 配置。
     */
    public record RHIConfig(
        String appName,
        boolean validation,
        int framesInFlight,
        boolean vsync
    ) {
        public RHIConfig {
            if (appName == null || appName.isEmpty()) appName = "Javelin";
            if (framesInFlight <= 0) framesInFlight = 2;
        }

        public RHIConfig() {
            this("Javelin", true, 2, true);
        }
    }

    /**
     * RHI 上下文（Instance + Device）。
     */
    public record RHIContext(Instance instance, Device device) {

        public void dispose() {
            device.dispose();
            instance.dispose();
        }
    }
}
