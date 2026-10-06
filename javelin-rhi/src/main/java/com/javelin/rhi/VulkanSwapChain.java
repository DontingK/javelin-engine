package com.javelin.rhi;

import com.javelin.rhi.desc.SwapChainDesc;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.TextureType;
import com.javelin.rhi.types.TextureUsage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * SwapChain 实现。
 * 对照设计文档第 23.3 节 / 第 23.4a 节阶段 5。
 *
 * 创建：查 surface formats → 选格式 → 选 present mode → 算图像数 → vkCreateSwapchainKHR。
 * 恢复：recreate() 销毁旧链重建（23.4a 恢复路径）。
 */
final class VulkanSwapChain implements SwapChain {

    private final VulkanDevice device;
    private final VulkanSurface surface;
    private final VkDevice vkDevice;

    private long swapchainHandle;
    private SwapChainDesc desc;
    /** 实际生效的呈现模式（create 期选定；请求模式不支持回退 FIFO 时此处可见真相） */
    private SwapChainDesc.PresentMode selectedPresentMode;
    private int width;
    private int height;
    private List<Texture> images;
    private volatile boolean disposed;

    VulkanSwapChain(VulkanDevice device, VulkanSurface surface, SwapChainDesc desc) {
        this.device = device;
        this.surface = surface;
        this.vkDevice = device.vkDevice();
        create(desc);
    }

    private void create(SwapChainDesc desc) {
        this.desc = desc;
        this.width = desc.width();
        this.height = desc.height();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice physDevice = device.physicalDevice().device();
            long surfaceHandle = surface.handle();

            // 查询 surface 格式
            IntBuffer countBuf = stack.mallocInt(1);
            int result = vkGetPhysicalDeviceSurfaceFormatsKHR(physDevice, surfaceHandle, countBuf, null);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get surface formats count");
            }
            int formatCount = countBuf.get(0);
            if (formatCount == 0) {
                throw new IllegalStateException("No surface formats available");
            }
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(formatCount, stack);
            result = vkGetPhysicalDeviceSurfaceFormatsKHR(physDevice, surfaceHandle, countBuf, formats);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get surface formats");
            }

            // 选择格式：SRGB 优先 R8G8B8A8_UNORM，其次 B8G8R8A8_UNORM
            int selectedFormat = VK_FORMAT_B8G8R8A8_UNORM;
            int selectedColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            for (int i = 0; i < formatCount; i++) {
                VkSurfaceFormatKHR fmt = formats.get(i);
                if (fmt.format() == VK_FORMAT_R8G8B8A8_UNORM
                        && fmt.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    selectedFormat = fmt.format();
                    selectedColorSpace = fmt.colorSpace();
                    break;
                }
                if (fmt.format() == VK_FORMAT_B8G8R8A8_UNORM
                        && fmt.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    selectedFormat = fmt.format();
                    selectedColorSpace = fmt.colorSpace();
                }
            }

            // 查询 present modes
            result = vkGetPhysicalDeviceSurfacePresentModesKHR(physDevice, surfaceHandle, countBuf, null);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get present modes count");
            }
            int modeCount = countBuf.get(0);
            IntBuffer modes = stack.mallocInt(modeCount);
            result = vkGetPhysicalDeviceSurfacePresentModesKHR(physDevice, surfaceHandle, countBuf, modes);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get present modes");
            }

            // 选择 present mode
            int desiredMode = switch (desc.presentMode()) {
                case FIFO -> VK_PRESENT_MODE_FIFO_KHR;
                case MAILBOX -> VK_PRESENT_MODE_MAILBOX_KHR;
                case IMMEDIATE -> VK_PRESENT_MODE_IMMEDIATE_KHR;
            };
            int selectedMode = VK_PRESENT_MODE_FIFO_KHR; // Vulkan 保证 FIFO 必有
            for (int i = 0; i < modeCount; i++) {
                if (modes.get(i) == desiredMode) {
                    selectedMode = desiredMode;
                    break;
                }
            }
            selectedPresentMode = selectedMode == VK_PRESENT_MODE_MAILBOX_KHR
                    ? SwapChainDesc.PresentMode.MAILBOX
                    : selectedMode == VK_PRESENT_MODE_IMMEDIATE_KHR
                            ? SwapChainDesc.PresentMode.IMMEDIATE
                            : SwapChainDesc.PresentMode.FIFO;

            // 查询 capabilities
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physDevice, surfaceHandle, caps);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get surface capabilities");
            }

            // 图像数：min + 2（2026-10-06 vsync 卡顿诊断实验）：min+1 时 FIFO 呈现队列
            // 深度仅 ~1，DWM 合成占用再吃一格后 CPU 距 vblank 近乎零余量——唤醒抖动
            // ±1.5ms 即可在某刷新边界队列排空、上一帧画面重复显示一个周期（感知为
            // 轻微回退/卡顿）。+2 多一格缓冲吸收抖动；实验证实后再拍正式形态。
            // 不超过 max（0 = 无上限）
            int imageCount = caps.minImageCount() + 2;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }
            if (desc.minImageCount() > 0 && imageCount < desc.minImageCount()) {
                imageCount = desc.minImageCount();
            }

            // 尺寸：clamp 到 surface 支持范围
            int w = Math.max(caps.minImageExtent().width(), Math.min(desc.width(), caps.maxImageExtent().width()));
            int h = Math.max(caps.minImageExtent().height(), Math.min(desc.height(), caps.maxImageExtent().height()));
            this.width = w;
            this.height = h;

            // 创建交换链
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);
            createInfo.sType$Default();
            createInfo.surface(surfaceHandle);
            createInfo.minImageCount(imageCount);
            createInfo.imageFormat(selectedFormat);
            createInfo.imageColorSpace(selectedColorSpace);
            createInfo.imageExtent(VkExtent2D.calloc(stack).set(w, h));
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.preTransform(caps.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(selectedMode);
            createInfo.clipped(true);
            createInfo.oldSwapchain(swapchainHandle != 0 ? swapchainHandle : VK_NULL_HANDLE);

            LongBuffer swapchainBuf = stack.mallocLong(1);
            result = vkCreateSwapchainKHR(vkDevice, createInfo, null, swapchainBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create swapchain");
            }

            // 销毁旧链（如果 recreate）
            if (swapchainHandle != 0) {
                vkDestroySwapchainKHR(vkDevice, swapchainHandle, null);
            }
            this.swapchainHandle = swapchainBuf.get(0);

            // 获取图像
            result = vkGetSwapchainImagesKHR(vkDevice, swapchainHandle, countBuf, null);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get swapchain images count");
            }
            int actualCount = countBuf.get(0);
            LongBuffer imageBuf = stack.mallocLong(actualCount);
            result = vkGetSwapchainImagesKHR(vkDevice, swapchainHandle, countBuf, imageBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to get swapchain images");
            }

            // 包装为 Texture
            Format rhiFormat = selectedFormat == VK_FORMAT_R8G8B8A8_UNORM
                    ? Format.R8G8B8A8_UNORM
                    : Format.B8G8R8A8_UNORM;
            List<Texture> textureList = new ArrayList<>(actualCount);
            for (int i = 0; i < actualCount; i++) {
                textureList.add(new SwapChainTexture(device, rhiFormat, w, h, imageBuf.get(i)));
            }
            this.images = List.copyOf(textureList);
        }
    }

    @Override
    public int acquireNextImage(long timeoutNanos, Semaphore signalSemaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndexBuf = stack.mallocInt(1);
            int result = vkAcquireNextImageKHR(vkDevice, swapchainHandle, timeoutNanos,
                    signalSemaphore != null ? signalSemaphore.handle() : VK_NULL_HANDLE,
                    VK_NULL_HANDLE, imageIndexBuf);
            if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
                return imageIndexBuf.get(0);
            }
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                return -1; // 需重建
            }
            throw new VulkanException(result, "Failed to acquire next image");
        }
    }

    @Override
    public Texture image(int index) {
        return images.get(index);
    }

    @Override
    public int imageCount() {
        return images.size();
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
    public SwapChainDesc.PresentMode presentMode() {
        return selectedPresentMode;
    }

    @Override
    public void present(int imageIndex, Semaphore waitSemaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer swapchains = stack.longs(swapchainHandle);
            IntBuffer indices = stack.ints(imageIndex);
            LongBuffer waits = waitSemaphore != null
                    ? stack.longs(waitSemaphore.handle())
                    : null;

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType$Default();
            if (waits != null) {
                presentInfo.pWaitSemaphores(waits);
            }
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(swapchains);
            presentInfo.pImageIndices(indices);

            int result = vkQueuePresentKHR(
                    ((VulkanQueue) device.graphicsQueue()).vkQueue(), presentInfo);
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
                if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                    return; // 上层下一帧 acquire 会返回 -1，触发 recreate
                }
                throw new VulkanException(result, "Failed to present");
            }
        }
    }

    @Override
    public void recreate(SwapChainDesc desc) {
        // 等待设备空闲，确保旧链图像不再被使用
        vkDeviceWaitIdle(vkDevice);
        create(desc);
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (swapchainHandle != 0) {
            vkDestroySwapchainKHR(vkDevice, swapchainHandle, null);
        }
    }

    /**
     * 交换链图像的 Texture 包装。
     * 不拥有 VkImage（由交换链管理），dispose 只清理 view。
     */
    static final class SwapChainTexture implements Texture {
        private final VulkanDevice device;
        private final Format format;
        private final int width;
        private final int height;
        private final long imageHandle;
        private volatile long imageViewHandle = VK_NULL_HANDLE;
        private volatile boolean disposed;

        SwapChainTexture(VulkanDevice device, Format format, int width, int height, long imageHandle) {
            this.device = device;
            this.format = format;
            this.width = width;
            this.height = height;
            this.imageHandle = imageHandle;
        }

        @Override
        public com.javelin.rhi.desc.TextureDesc desc() {
            return com.javelin.rhi.desc.TextureDesc.texture2D(
                    format, width, height,
                    EnumSet.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.TRANSFER_DST));
        }

        @Override
        public void update(TextureRegion region, java.nio.ByteBuffer data) {
            throw new UnsupportedOperationException("Cannot update swapchain image");
        }

        @Override
        public java.nio.ByteBuffer readback() {
            throw new UnsupportedOperationException("Cannot readback swapchain image");
        }

        @Override
        public long handle() {
            return imageHandle;
        }

        long ensureView() {
            if (imageViewHandle == VK_NULL_HANDLE) {
                synchronized (this) {
                    if (imageViewHandle == VK_NULL_HANDLE) {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
                            viewInfo.sType$Default();
                            viewInfo.image(imageHandle);
                            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
                            viewInfo.format(VulkanTexture.toVkFormat(format));
                            viewInfo.subresourceRange(VkImageSubresourceRange.calloc(stack)
                                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                    .baseMipLevel(0)
                                    .levelCount(1)
                                    .baseArrayLayer(0)
                                    .layerCount(1));

                            LongBuffer viewBuf = stack.mallocLong(1);
                            int result = vkCreateImageView(device.vkDevice(), viewInfo, null, viewBuf);
                            if (result != VK_SUCCESS) {
                                throw new VulkanException(result, "Failed to create swapchain image view");
                            }
                            imageViewHandle = viewBuf.get(0);
                        }
                        CleanerRegistry.register(this, device, imageViewHandle, CleanerRegistry.ResourceType.IMAGE_VIEW);
                    }
                }
            }
            return imageViewHandle;
        }

        @Override
        public void dispose() {
            if (disposed) return;
            disposed = true;
            if (imageViewHandle != VK_NULL_HANDLE) {
                CleanerRegistry.markDisposed(imageViewHandle);
                vkDestroyImageView(device.vkDevice(), imageViewHandle, null);
            }
        }
    }
}
