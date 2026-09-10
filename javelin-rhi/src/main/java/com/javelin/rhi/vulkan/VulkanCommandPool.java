package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.CommandBuffer;
import com.javelin.rhi.api.CommandPool;
import com.javelin.rhi.api.Queue;
import com.javelin.rhi.types.CommandPoolFlags;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 命令池实现 */
public class VulkanCommandPool extends CommandPool {

    private final VkDevice device;
    private final Queue queue;
    private final long handle;

    public VulkanCommandPool(VulkanDevice device, Queue queue, CommandPoolFlags flags) {
        this.device = device.vkDevice();
        this.queue = queue;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(queue.familyIndex())
                .flags(flags != null ? flags.value() : 0);

            LongBuffer pPool = stack.mallocLong(1);
            VulkanChecks.check(vkCreateCommandPool(this.device, createInfo, null, pPool), "vkCreateCommandPool");
            this.handle = pPool.get(0);
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyCommandPool(device, handle, null);
    }

    @Override
    public Queue queue() {
        return queue;
    }

    @Override
    public void reset() {
        VulkanChecks.check(vkResetCommandPool(device, handle, 0), "vkResetCommandPool");
    }

    CommandBuffer allocate(boolean primary) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(handle)
                .level(primary ? VK_COMMAND_BUFFER_LEVEL_PRIMARY : VK_COMMAND_BUFFER_LEVEL_SECONDARY)
                .commandBufferCount(1);

            PointerBuffer pBuffer = stack.mallocPointer(1);
            VulkanChecks.check(vkAllocateCommandBuffers(device, allocInfo, pBuffer), "vkAllocateCommandBuffers");
            return new VulkanCommandBuffer(device, pBuffer.get(0), this, primary);
        }
    }
}
