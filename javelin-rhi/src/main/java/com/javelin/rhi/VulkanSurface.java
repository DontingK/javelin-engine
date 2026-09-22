package com.javelin.rhi;

import com.javelin.rhi.desc.SwapChainDesc;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Surface 实现。
 * 对照设计文档第 23.3 节。
 */
final class VulkanSurface implements Surface {

    private static final Logger log = LoggerFactory.getLogger(VulkanSurface.class);

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
                    // LWJGL 3.4 的 validate() 对 hinstance/hwnd 都做非空检查（Vulkan 规范中
                    // hinstance 本可选，但 LWJGL 生成代码不允许为 0）；取进程模块句柄传入
                    createInfo.hinstance(WinBase.GetModuleHandle(null, (CharSequence) null));
                    createInfo.hwnd(renderSurface.nativeHandle());
                    result = vkCreateWin32SurfaceKHR(vkInstance, createInfo, null, surfaceBuf);
                }
                default -> throw new UnsupportedOperationException(
                        "Platform not yet implemented: " + renderSurface.platform());
            }

            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create surface");
            }

            this.surfaceHandle = surfaceBuf.get(0);
            log.info("VkSurfaceKHR created: surface=0x{} hwnd=0x{}",
                    Long.toHexString(surfaceHandle),
                    Long.toHexString(renderSurface.nativeHandle()));
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
        throw new UnsupportedOperationException(
                "Surface does not own a Device context. Use Device.createSwapChain(desc) instead "
                        + "(the Device holds the VkSurfaceKHR created with it).");
    }

    /**
     * 创建交换链（需要 Device 上下文）。
     * 由 VulkanDevice 内部调用。
     */
    SwapChain createSwapChain(VulkanDevice device, SwapChainDesc desc) {
        return new VulkanSwapChain(device, this, desc);
    }

    @Override
    public Capabilities capabilities() {
        // 需要物理设备；通过 Instance 获取第一个可用设备
        // 实际使用中应在 createDevice 之后调用
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice physDevice = findPhysicalDevice();
            if (physDevice == null) {
                throw new IllegalStateException("No physical device available for surface capabilities");
            }

            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            int result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physDevice, surfaceHandle, caps);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get surface capabilities");
            }

            return new Capabilities(
                    caps.minImageCount(), caps.maxImageCount(),
                    caps.minImageExtent().width(), caps.minImageExtent().height(),
                    caps.maxImageExtent().width(), caps.maxImageExtent().height()
            );
        }
    }

    private VkPhysicalDevice findPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer countBuf = stack.mallocInt(1);
            int result = vkEnumeratePhysicalDevices(vkInstance, countBuf, null);
            if (result != VK_SUCCESS) {
                return null;
            }
            int deviceCount = countBuf.get(0);
            if (deviceCount == 0) {
                return null;
            }
            org.lwjgl.PointerBuffer devices = stack.mallocPointer(deviceCount);
            result = vkEnumeratePhysicalDevices(vkInstance, countBuf, devices);
            if (result != VK_SUCCESS) {
                return null;
            }
            return new VkPhysicalDevice(devices.get(0), vkInstance);
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        vkDestroySurfaceKHR(vkInstance, surfaceHandle, null);
    }
}
