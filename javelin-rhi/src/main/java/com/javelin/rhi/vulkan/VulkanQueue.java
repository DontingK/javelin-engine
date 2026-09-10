package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 队列实现 */
public class VulkanQueue extends Queue {

    private final VkDevice device;
    private final int familyIndex;
    private final int queueIndex;
    private final QueueType type;
    private final VkQueue queue;

    public VulkanQueue(VkDevice device, int familyIndex, int queueIndex, QueueType type) {
        this.device = device;
        this.familyIndex = familyIndex;
        this.queueIndex = queueIndex;
        this.type = type;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pQueue = stack.pointers(VK_NULL_HANDLE);
            vkGetDeviceQueue(device, familyIndex, queueIndex, pQueue);
            this.queue = new VkQueue(pQueue.get(0), device);
        }
    }

    @Override
    public long handle() {
        return queue.address();
    }

    @Override
    protected void doDispose() {
        // 队列随设备销毁，无需单独释放
    }

    @Override
    public int familyIndex() {
        return familyIndex;
    }

    @Override
    public int queueIndex() {
        return queueIndex;
    }

    @Override
    public QueueType type() {
        return type;
    }

    @Override
    public void submit(SubmitInfo submitInfo, Fence signalFence) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo vkSubmitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            // 等待信号
            if (submitInfo.waitSemaphores() != null && submitInfo.waitSemaphores().length > 0) {
                LongBuffer pWaitSemaphores = stack.mallocLong(submitInfo.waitSemaphores().length);
                IntBuffer pWaitDstStageMask = stack.mallocInt(submitInfo.waitSemaphores().length);
                for (int i = 0; i < submitInfo.waitSemaphores().length; i++) {
                    pWaitSemaphores.put(submitInfo.waitSemaphores()[i].handle());
                    pWaitDstStageMask.put(submitInfo.waitDstStageMask()[i]);
                }
                pWaitSemaphores.flip();
                pWaitDstStageMask.flip();
                vkSubmitInfo.pWaitSemaphores(pWaitSemaphores)
                    .pWaitDstStageMask(pWaitDstStageMask);
            }

            // 命令缓冲
            if (submitInfo.commandBuffers() != null && submitInfo.commandBuffers().length > 0) {
                PointerBuffer pCommandBuffers = stack.mallocPointer(submitInfo.commandBuffers().length);
                for (CommandBuffer cmd : submitInfo.commandBuffers()) {
                    pCommandBuffers.put(cmd.handle());
                }
                pCommandBuffers.flip();
                vkSubmitInfo.pCommandBuffers(pCommandBuffers);
            }

            // 信号
            if (submitInfo.signalSemaphores() != null && submitInfo.signalSemaphores().length > 0) {
                LongBuffer pSignalSemaphores = stack.mallocLong(submitInfo.signalSemaphores().length);
                for (Semaphore sem : submitInfo.signalSemaphores()) {
                    pSignalSemaphores.put(sem.handle());
                }
                pSignalSemaphores.flip();
                vkSubmitInfo.pSignalSemaphores(pSignalSemaphores);
            }

            long fenceHandle = signalFence != null ? signalFence.handle() : VK_NULL_HANDLE;
            VulkanChecks.check(vkQueueSubmit(queue, vkSubmitInfo, fenceHandle), "vkQueueSubmit");
        }
    }

    @Override
    public void present(PresentInfo presentInfo) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(stack.longs(
                    java.util.Arrays.stream(presentInfo.waitSemaphores())
                        .mapToLong(Semaphore::handle)
                        .toArray()))
                .swapchainCount(1)
                .pSwapchains(stack.longs(presentInfo.swapChain().handle()))
                .pImageIndices(stack.ints(presentInfo.imageIndex()));

            int result = vkQueuePresentKHR(queue, present);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                // 交换链需要重建，不抛异常
                return;
            }
            VulkanChecks.check(result, "vkQueuePresentKHR");
        }
    }

    @Override
    public void waitIdle() {
        vkQueueWaitIdle(queue);
    }

    VkQueue vkQueue() {
        return queue;
    }
}
