package com.javelin.rhi.impl;

/**
 * Vulkan 命令池管理器，负责命令缓冲区（{@code VkCommandBuffer}）的分配、释放和命令池的复用。
 * <p>
 * 命令池是命令缓冲区的内存分配器，按队列族隔离。该类封装了命令缓冲区的生命周期管理，
 * 支持批量重置命令池以高效回收命令缓冲区。
 */
public class VkCommandPoolManager {

    /**
     * 从命令池中分配一个命令缓冲区。
     */
    public void allocateCommandBuffer() {
        // TODO: vkAllocateCommandBuffers
    }

    /**
     * 释放指定的命令缓冲区回命令池。
     */
    public void freeCommandBuffer() {
        // TODO: vkFreeCommandBuffers
    }

    /**
     * 重置整个命令池，回收池中所有命令缓冲区。
     */
    public void resetPool() {
        // TODO: vkResetCommandPool
    }
}
