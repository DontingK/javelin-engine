package com.javelin.rhi.exception;

/**
 * Vulkan 调用失败包装异常。
 * 携带 Vulkan 错误码（VkResult），便于上层定位问题。
 */
public class VulkanException extends RHIException {

    /** Vulkan VkResult 错误码 */
    private final int result;

    public VulkanException(int result, String message) {
        super(message);
        this.result = result;
    }

    public VulkanException(int result, String message, Throwable cause) {
        super(message, cause);
        this.result = result;
    }

    public int getResult() {
        return result;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " (VkResult=" + result + ")";
    }
}
