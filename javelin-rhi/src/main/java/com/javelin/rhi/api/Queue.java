package com.javelin.rhi.api;

import com.javelin.rhi.types.PipelineBindPoint;

/**
 * 提交队列（图形/计算/传输）。
 */
public abstract class Queue extends GPUResource {

    public abstract int familyIndex();

    public abstract int queueIndex();

    public abstract QueueType type();

    public abstract void submit(SubmitInfo submitInfo, Fence signalFence);

    public abstract void present(PresentInfo presentInfo);

    public abstract void waitIdle();

    // ========== 内部类型 ==========

    public enum QueueType {
        GRAPHICS, COMPUTE, TRANSFER, SPARSE_BINDING, PROTECTED
    }

    public record SubmitInfo(
            CommandBuffer[] commandBuffers,
            Semaphore[] waitSemaphores,
            int[] waitDstStageMask,
            Semaphore[] signalSemaphores
    ) {
    }

    public record PresentInfo(
            SwapChain swapChain,
            int imageIndex,
            Semaphore[] waitSemaphores
    ) {
    }
}
