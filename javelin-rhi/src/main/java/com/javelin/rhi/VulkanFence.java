package com.javelin.rhi;

import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFenceCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Fence 实现。
 * 对照设计文档第 24.1 节 / 第 26.6 节。
 *
 * CPU ↔ GPU 握手：提交时关联（signal），复用帧状态前 await() 等待 GPU 完成。
 */
final class VulkanFence implements Fence {

    private final VulkanDevice device;
    private final long fenceHandle;
    /** Cleaner 注册句柄（dispose 触发延迟销毁） */
    private CleanerRegistry.Registration registration;
    private volatile boolean disposed;

    VulkanFence(VulkanDevice device) {
        this(device, false);
    }

    VulkanFence(VulkanDevice device, boolean signaled) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo createInfo = VkFenceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            if (signaled) {
                createInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);
            }

            LongBuffer fenceBuf = stack.mallocLong(1);
            int result = vkCreateFence(device.vkDevice(), createInfo, null, fenceBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create fence");
            }
            this.fenceHandle = fenceBuf.get(0);
        }

        registration = CleanerRegistry.register(this, device, fenceHandle, CleanerRegistry.ResourceType.FENCE);
        device.stats().onFenceCreated();
    }

    @Override
    public void await() {
        int result = vkWaitForFences(device.vkDevice(), fenceHandle, true, Long.MAX_VALUE);
        if (result != VK_SUCCESS) {
            throw new VulkanException(result, "Failed to wait for fence");
        }
    }

    @Override
    public boolean isSignaled() {
        return vkGetFenceStatus(device.vkDevice(), fenceHandle) == VK_SUCCESS;
    }

    @Override
    public void reset() {
        int result = vkResetFences(device.vkDevice(), fenceHandle);
        if (result != VK_SUCCESS) {
            throw new VulkanException(result, "Failed to reset fence");
        }
    }

    @Override
    public long handle() {
        return fenceHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        device.stats().onFenceDisposed();
        registration.dispose();
    }
}
