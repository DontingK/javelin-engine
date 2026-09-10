package com.javelin.rhi;

/**
 * Vulkan 特定异常。
 */
public class VulkanException extends RHIException {

    private final int vkResult;

    public VulkanException(int vkResult, String message) {
        super(message + " (vkResult: " + vkResult + ")");
        this.vkResult = vkResult;
    }

    public int vkResult() {
        return vkResult;
    }
}
