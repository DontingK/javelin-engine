package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Fence;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 栅栏实现 */
public class VulkanFence extends Fence {

    private final VkDevice device;
    private final long handle;

    public VulkanFence(VulkanDevice device, boolean signaled) {
        this.device = device.vkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo createInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(signaled ? VK_FENCE_CREATE_SIGNALED_BIT : 0);

            LongBuffer pFence = stack.mallocLong(1);
            VulkanChecks.check(vkCreateFence(this.device, createInfo, null, pFence), "vkCreateFence");
            this.handle = pFence.get(0);
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyFence(device, handle, null);
    }

    @Override
    public void await(long timeoutNanos) {
        VulkanChecks.check(vkWaitForFences(device, handle, true, timeoutNanos), "vkWaitForFences");
    }

    @Override
    public void reset() {
        VulkanChecks.check(vkResetFences(device, handle), "vkResetFences");
    }

    @Override
    public boolean isSignaled() {
        int result = vkGetFenceStatus(device, handle);
        if (result == VK_SUCCESS) return true;
        if (result == VK_NOT_READY) return false;
        VulkanChecks.check(result, "vkGetFenceStatus");
        return false;
    }
}
