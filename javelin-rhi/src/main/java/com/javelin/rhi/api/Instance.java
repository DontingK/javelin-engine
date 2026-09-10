package com.javelin.rhi.api;

import com.javelin.rhi.types.Feature;

/**
 * Vulkan 实例，管理物理设备和全局状态。
 */
public abstract class Instance extends GPUResource {

    public abstract PhysicalDevice[] enumeratePhysicalDevices();

    public abstract PhysicalDevice selectPhysicalDevice(PhysicalDevicePreferences prefs);

    // ========== 调试 ==========

    public abstract void setDebugMessenger(DebugMessenger messenger);

    // ========== 内部类型 ==========

    public record PhysicalDevicePreferences(
            boolean preferDiscrete,
            boolean requireGraphicsQueue,
            boolean requireComputeQueue,
            boolean requireTransferQueue,
            boolean requirePresentQueue,
            String[] requiredExtensions,
            Feature[] requiredFeatures
    ) {
    }

    public interface DebugMessenger {
        void message(DebugSeverity severity, DebugType type, String message);
    }

    public enum DebugSeverity {
        VERBOSE, INFO, WARNING, ERROR
    }

    public enum DebugType {
        GENERAL, VALIDATION, PERFORMANCE, OTHER
    }
}
