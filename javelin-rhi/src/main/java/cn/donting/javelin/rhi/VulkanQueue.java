package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 队列实现。
 * 对照设计文档第 24.4 节（提交模型）。
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
        if (cmd == null) {
            throw new IllegalArgumentException("CommandBuffer must not be null");
        }
        if (!(cmd instanceof VulkanCommandBuffer vkCmd)) {
            throw new IllegalArgumentException("CommandBuffer is not a VulkanCommandBuffer");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 等待阶段：acquire semaphore 等 COLOR_ATTACHMENT_OUTPUT，其余 ALL_COMMANDS
            List<Semaphore> waits = info.waitSemaphores();
            IntBuffer waitStages = null;
            LongBuffer waitSemaphores = null;
            if (!waits.isEmpty()) {
                waitStages = stack.mallocInt(waits.size());
                waitSemaphores = stack.mallocLong(waits.size());
                for (int i = 0; i < waits.size(); i++) {
                    waitSemaphores.put(i, waits.get(i).handle());
                    waitStages.put(i, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                }
            }

            List<Semaphore> signals = info.signalSemaphores();
            LongBuffer signalSemaphores = null;
            if (!signals.isEmpty()) {
                signalSemaphores = stack.mallocLong(signals.size());
                for (int i = 0; i < signals.size(); i++) {
                    signalSemaphores.put(i, signals.get(i).handle());
                }
            }

            PointerBuffer cmdBuffers = stack.mallocPointer(1);
            cmdBuffers.put(0, vkCmd.vkCommandBuffer().address());

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType$Default();
            if (waitSemaphores != null) {
                // **waitSemaphoreCount 必须显式设**：LWJGL 3.4.2 的
                // `VkSubmitInfo.pWaitSemaphores` 只写指针不写 count（该 count 与
                // pWaitDstStageMask 共用，生成器留给调用方；npWaitSemaphores 无 count 赋值，
                // 而 npCommandBuffers/npSignalSemaphores 都自动带）。calloc 后恒为 0
                // → 驱动整条忽略 wait 列表：acquire 信号量从未被消费（VVL
                // non-acquired-swapchain-image-used / MissingAcquireWait / 01286；
                // 无校验层时该图像缺 presentation 引擎读完的同步，坑 #38）
                submitInfo.waitSemaphoreCount(waits.size());
                submitInfo.pWaitSemaphores(waitSemaphores);
                submitInfo.pWaitDstStageMask(waitStages);
            }
            submitInfo.pCommandBuffers(cmdBuffers);
            if (signalSemaphores != null) {
                submitInfo.pSignalSemaphores(signalSemaphores);
            }

            long fenceHandle = info.fence() != null ? info.fence().handle() : VK_NULL_HANDLE;
            int result = vkQueueSubmit(queue, submitInfo, fenceHandle);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to submit command buffer to queue");
            }
        }
    }

    @Override
    public void waitIdle() {
        vkQueueWaitIdle(queue);
    }
}
