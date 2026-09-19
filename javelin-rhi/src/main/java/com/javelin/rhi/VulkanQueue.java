package com.javelin.rhi;

import org.lwjgl.vulkan.VkQueue;

import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;

/**
 * 队列实现。
 */
final class VulkanQueue implements Queue {

    private final VkQueue queue;
    private final int familyIndex;

    VulkanQueue(VkQueue queue, int familyIndex) {
        this.queue = queue;
        this.familyIndex = familyIndex;
    }

    VkQueue vkQueue() { return queue; }
    int familyIndex() { return familyIndex; }

    @Override
    public void submit(CommandBuffer cmd, SubmitInfo info) {
        throw new UnsupportedOperationException("Not yet implemented (Stage 4)");
    }

    @Override
    public void waitIdle() {
        vkQueueWaitIdle(queue);
    }
}
