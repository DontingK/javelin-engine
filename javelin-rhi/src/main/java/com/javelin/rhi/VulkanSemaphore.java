package com.javelin.rhi;

import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Semaphore 实现。
 * 对照设计文档第 24.2 节 / 第 26.6 节。
 *
 * GPU ↔ GPU 同步：acquire 信号、图完成信号、跨队列衔接（P1）。
 */
final class VulkanSemaphore implements Semaphore {

    private final VulkanDevice device;
    private final long semaphoreHandle;
    private volatile boolean disposed;

    VulkanSemaphore(VulkanDevice device) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack);
            createInfo.sType$Default();

            LongBuffer semBuf = stack.mallocLong(1);
            int result = vkCreateSemaphore(device.vkDevice(), createInfo, null, semBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create semaphore");
            }
            this.semaphoreHandle = semBuf.get(0);
        }

        CleanerRegistry.register(this, device, semaphoreHandle, CleanerRegistry.ResourceType.SEMAPHORE);
        device.stats().onSemaphoreCreated();
    }

    @Override
    public long handle() {
        return semaphoreHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        device.stats().onSemaphoreDisposed();
        CleanerRegistry.markDisposed(semaphoreHandle);
        vkDestroySemaphore(device.vkDevice(), semaphoreHandle, null);
    }
}
