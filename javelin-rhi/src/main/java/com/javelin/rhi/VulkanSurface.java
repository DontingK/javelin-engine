package com.javelin.rhi;

import com.javelin.rhi.desc.SwapChainDesc;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;

/**
 * Surface 实现。
 * 对照设计文档第 23.3 节。
 */
final class VulkanSurface implements Surface {

    private final VulkanInstance instance;
    private final VkInstance vkInstance;
    private final RenderSurface renderSurface;
    private final long surfaceHandle;
    private volatile boolean disposed;

    VulkanSurface(VulkanInstance instance, VkInstance vkInstance, RenderSurface renderSurface) {
        this.instance = instance;
        this.vkInstance = vkInstance;
        this.renderSurface = renderSurface;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer surfaceBuf = stack.mallocLong(1);
            int result;

            switch (renderSurface.platform()) {
                case WINDOWS -> {
                    VkWin32SurfaceCreateInfoKHR createInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack);
                    createInfo.sType$Default();
                    createInfo.hwnd(renderSurface.nativeHandle());
                    result = vkCreateWin32SurfaceKHR(vkInstance, createInfo, null, surfaceBuf);
                }
                default -> throw new UnsupportedOperationException(
                        "Platform not yet implemented: " + renderSurface.platform());
            }

            if (result != VK10.VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create surface");
            }

            this.surfaceHandle = surfaceBuf.get(0);
        }
    }

    @Override
    public long handle() {
        return surfaceHandle;
    }

    @Override
    public RenderSurface renderSurface() {
        return renderSurface;
    }

    @Override
    public SwapChain createSwapChain(SwapChainDesc desc) {
        throw new UnsupportedOperationException("Not yet implemented (Stage 5)");
    }

    @Override
    public Capabilities capabilities() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            int result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                    physicalDevice(), surfaceHandle, caps);
            if (result != VK10.VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get surface capabilities");
            }

            return new Capabilities(
                    caps.minImageCount(), caps.maxImageCount(),
                    caps.minImageExtent().width(), caps.minImageExtent().height(),
                    caps.maxImageExtent().width(), caps.maxImageExtent().height()
            );
        }
    }

    private VkPhysicalDevice physicalDevice() {
        // 简化：通过 instance 获取第一个物理设备
        // 实际应该从 Device 获取，但 Surface 可能在 Device 之前创建
        // 这里暂时返回 null，Stage 5 再完善
        return null;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        vkDestroySurfaceKHR(vkInstance, surfaceHandle, null);
    }
}
