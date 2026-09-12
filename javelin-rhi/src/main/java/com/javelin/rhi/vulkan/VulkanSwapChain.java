package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.SwapChainDesc;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 交换链实现。
 */
public class VulkanSwapChain extends SwapChain {

    private static final Logger log = LoggerFactory.getLogger(VulkanSwapChain.class);

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final RHIWindow window;
    private final long surface;
    private long handle;
    private Format imageFormat;
    private ColorSpace colorSpace;
    private int width;
    private int height;
    private int imageCount;
    private PresentMode presentMode;
    private TextureUsage imageUsage;
    private VulkanTexture[] images;

    public VulkanSwapChain(VulkanDevice device, RHIWindow window, long surface, SwapChainDesc desc) {
        this.device = device.vkDevice();
        this.physicalDevice = ((VulkanPhysicalDevice) device.physicalDevice()).vkPhysicalDevice();
        this.window = window;
        this.surface = surface;
        this.imageFormat = desc.imageFormat();
        this.colorSpace = toRHIColorSpace(desc.colorSpace());
        this.width = desc.width();
        this.height = desc.height();
        this.presentMode = toRHIPresentMode(desc.presentMode());
        this.imageUsage = desc.imageUsage();

        createSwapChain(desc);
    }

    private void createSwapChain(SwapChainDesc desc) {
        createSwapChain(desc, VK_NULL_HANDLE);
    }

    private void createSwapChain(SwapChainDesc desc, long oldSwapchain) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 查询表面能力
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            VulkanChecks.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities),
                "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            // 查询表面格式
            IntBuffer pFormatCount = stack.mallocInt(1);
            VulkanChecks.check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFormatCount, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");

            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(pFormatCount.get(0), stack);
            VulkanChecks.check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFormatCount, formats),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");

            // 查询呈现模式
            IntBuffer pPresentModeCount = stack.mallocInt(1);
            VulkanChecks.check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");

            IntBuffer presentModes = stack.mallocInt(pPresentModeCount.get(0));
            VulkanChecks.check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, presentModes),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");

            // 选择表面格式
            int surfaceFormat = VK_FORMAT_B8G8R8A8_UNORM;
            int surfaceColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            for (int i = 0; i < pFormatCount.get(0); i++) {
                VkSurfaceFormatKHR format = formats.get(i);
                if (format.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    surfaceFormat = format.format();
                    surfaceColorSpace = format.colorSpace();
                    break;
                }
            }

            // 选择呈现模式
            int vkPresentMode = VK_PRESENT_MODE_FIFO_KHR;  // VSync
            while (presentModes.hasRemaining()) {
                int mode = presentModes.get();
                if (mode == VK_PRESENT_MODE_MAILBOX_KHR && desc.presentMode() == SwapChainDesc.PresentMode.MAILBOX) {
                    vkPresentMode = mode;
                    break;
                } else if (mode == VK_PRESENT_MODE_IMMEDIATE_KHR && desc.presentMode() == SwapChainDesc.PresentMode.IMMEDIATE) {
                    vkPresentMode = mode;
                    break;
                }
            }

            // 图像数量
            int minImageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0 && minImageCount > capabilities.maxImageCount()) {
                minImageCount = capabilities.maxImageCount();
            }
            this.imageCount = minImageCount;

            // 交换范围
            VkExtent2D extent = capabilities.currentExtent();
            if (extent.width() == 0xFFFFFFFF) {
                extent.width(Math.clamp(width, capabilities.minImageExtent().width(), capabilities.maxImageExtent().width()));
                extent.height(Math.clamp(height, capabilities.minImageExtent().height(), capabilities.maxImageExtent().height()));
            }
//            this.width = extent.width();
//            this.height = extent.height();

            // 创建交换链
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface)
                .minImageCount(minImageCount)
                .imageFormat(surfaceFormat)
                .imageColorSpace(surfaceColorSpace)
                .imageExtent(extent)
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(capabilities.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(vkPresentMode)
                .clipped(desc.clipped())
                .oldSwapchain(oldSwapchain);

            LongBuffer pSwapChain = stack.mallocLong(1);
            VulkanChecks.check(vkCreateSwapchainKHR(device, createInfo, null, pSwapChain), "vkCreateSwapchainKHR");
            this.handle = pSwapChain.get(0);

            // 获取交换链图像
            IntBuffer pImageCount = stack.mallocInt(1);
            VulkanChecks.check(vkGetSwapchainImagesKHR(device, handle, pImageCount, null), "vkGetSwapchainImagesKHR");

            LongBuffer pImages = stack.mallocLong(pImageCount.get(0));
            VulkanChecks.check(vkGetSwapchainImagesKHR(device, handle, pImageCount, pImages), "vkGetSwapchainImagesKHR");

            this.images = new VulkanTexture[pImageCount.get(0)];
            for (int i = 0; i < pImageCount.get(0); i++) {
                images[i] = new VulkanTexture(device, pImages.get(i), width, height, imageFormat);
            }
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroySwapchainKHR(device, handle, null);
    }

    @Override
    public Format imageFormat() {
        return imageFormat;
    }

    @Override
    public ColorSpace colorSpace() {
        return colorSpace;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int imageCount() {
        return imageCount;
    }

    @Override
    public PresentMode presentMode() {
        return presentMode;
    }

    @Override
    public TextureUsage imageUsage() {
        return imageUsage;
    }

    @Override
    public int acquireNextImage(long timeoutNanos, Semaphore signalSemaphore, Fence signalFence) {
        // 检查窗口大小是否与交换链不一致，不一致则重建
        int windowWidth = window.width();
        int windowHeight = window.height();
        if (windowWidth != width || windowHeight != height) {
            log.info("recreate:{}x{} != {}x{}",windowWidth,windowHeight,width,height);
            recreate(windowWidth, windowHeight);
            return -1;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            long semaphoreHandle = signalSemaphore != null ? signalSemaphore.handle() : VK_NULL_HANDLE;
            long fenceHandle = signalFence != null ? signalFence.handle() : VK_NULL_HANDLE;
            int result = vkAcquireNextImageKHR(device, handle, timeoutNanos, semaphoreHandle, fenceHandle, pImageIndex);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                recreate(windowWidth, windowHeight);
                return -1;
            }
            VulkanChecks.check(result, "vkAcquireNextImageKHR");
            return pImageIndex.get(0);
        }
    }

    @Override
    public Texture getImage(int index) {
        return images[index];
    }

    @Override
    public void recreate(int width, int height) {
        log.info("[SwapChain] recreating: {}x{}", width, height);

        this.width = width;
        this.height = height;

        // 保存旧交换链句柄，用于创建时传入 oldSwapchain 实现平滑过渡
        long oldSwapchain = this.handle;

        // 创建新交换链（使用 oldSwapchain 参数，驱动会自动处理资源过渡）
        createSwapChain(new SwapChainDesc(width, height), oldSwapchain);

        // 销毁旧交换链图像包装对象
        for (VulkanTexture image : images) {
            image.dispose();
        }

        // 销毁旧交换链
        if (oldSwapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, oldSwapchain, null);
        }

        log.info("[SwapChain] recreate done: {}x{} images={}", width, height, images.length);
    }

    @Override
    public void present(Queue queue, Semaphore[] waitSemaphores) {
        // 由 Queue 实现
    }

    // ========== 类型转换 ==========

    private ColorSpace toRHIColorSpace(SwapChainDesc.ColorSpace colorSpace) {
        return ColorSpace.SRGB_NONLINEAR;  // 目前只支持这一种
    }

    private PresentMode toRHIPresentMode(SwapChainDesc.PresentMode mode) {
        return switch (mode) {
            case IMMEDIATE -> PresentMode.IMMEDIATE;
            case MAILBOX -> PresentMode.MAILBOX;
            case FIFO -> PresentMode.FIFO;
            case FIFO_RELAXED -> PresentMode.FIFO_RELAXED;
        };
    }
}
