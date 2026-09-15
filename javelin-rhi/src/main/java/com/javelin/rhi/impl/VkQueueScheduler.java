package com.javelin.rhi.impl;

/**
 * Vulkan 队列调度器，负责管理命令缓冲区的提交和队列间的同步。
 * <p>
 * 根据命令的类型选择合适的队列族（图形、计算、传输），将命令缓冲区提交到 GPU 执行，
 * 并处理跨队列的同步（如信号量和栅栏），确保命令按正确顺序执行。
 */
public class VkQueueScheduler {

    /**
     * 提交待执行的命令缓冲区到 GPU 队列。
     * 包括队列族选择、命令缓冲区提交和跨队列同步。
     */
    public void submit() {
        // TODO: Queue family selection, command buffer submission, cross-queue synchronization
    }

    /**
     * 等待所有队列上的命令执行完成。
     * 内部调用 {@code vkQueueWaitIdle} 或 {@code vkDeviceWaitIdle}。
     */
    public void waitIdle() {
        // TODO: vkQueueWaitIdle / vkDeviceWaitIdle
    }
}
