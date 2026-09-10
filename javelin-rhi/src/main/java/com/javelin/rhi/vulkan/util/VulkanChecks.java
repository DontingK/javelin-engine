package com.javelin.rhi.vulkan.util;

import com.javelin.rhi.VulkanException;
import org.lwjgl.vulkan.VK10;

/**
 * Vulkan 错误检查工具。
 */
public final class VulkanChecks {

    private VulkanChecks() {}

    public static void check(int vkResult, String operation) {
        if (vkResult < 0) {
            throw new VulkanException(vkResult, operation + " failed");
        }
    }

    public static void check(int vkResult) {
        check(vkResult, "Vulkan operation");
    }

    public static String resultToString(int vkResult) {
        return switch (vkResult) {
            case VK10.VK_SUCCESS -> "SUCCESS";
            case VK10.VK_NOT_READY -> "NOT_READY";
            case VK10.VK_TIMEOUT -> "TIMEOUT";
            case VK10.VK_EVENT_SET -> "EVENT_SET";
            case VK10.VK_EVENT_RESET -> "EVENT_RESET";
            case VK10.VK_INCOMPLETE -> "INCOMPLETE";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_DEVICE_LOST -> "ERROR_DEVICE_LOST";
            case VK10.VK_ERROR_MEMORY_MAP_FAILED -> "ERROR_MEMORY_MAP_FAILED";
            case VK10.VK_ERROR_LAYER_NOT_PRESENT -> "ERROR_LAYER_NOT_PRESENT";
            case VK10.VK_ERROR_EXTENSION_NOT_PRESENT -> "ERROR_EXTENSION_NOT_PRESENT";
            case VK10.VK_ERROR_FEATURE_NOT_PRESENT -> "ERROR_FEATURE_NOT_PRESENT";
            case VK10.VK_ERROR_INCOMPATIBLE_DRIVER -> "ERROR_INCOMPATIBLE_DRIVER";
            case VK10.VK_ERROR_TOO_MANY_OBJECTS -> "ERROR_TOO_MANY_OBJECTS";
            case VK10.VK_ERROR_FORMAT_NOT_SUPPORTED -> "ERROR_FORMAT_NOT_SUPPORTED";
            case VK10.VK_ERROR_FRAGMENTED_POOL -> "ERROR_FRAGMENTED_POOL";
            case VK10.VK_ERROR_UNKNOWN -> "ERROR_UNKNOWN";
            default -> "UNKNOWN(" + vkResult + ")";
        };
    }
}
