package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Semaphore;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 信号量实现 */
public class VulkanSemaphore extends Semaphore {

    private final VkDevice device;
    private final long handle;

    public VulkanSemaphore(VulkanDevice device) {
        this.device = device.vkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer pSemaphore = stack.mallocLong(1);
            VulkanChecks.check(vkCreateSemaphore(this.device, createInfo, null, pSemaphore), "vkCreateSemaphore");
            this.handle = pSemaphore.get(0);
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroySemaphore(device, handle, null);
    }
}
