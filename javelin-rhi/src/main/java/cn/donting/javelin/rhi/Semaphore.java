package cn.donting.javelin.rhi;

/**
 * 信号量接口。
 * 对照设计文档第 24.2 节 / 第 26.6 节。
 */
public interface Semaphore extends GPUResource {

    /** Vulkan 句柄 VkSemaphore */
    long handle();
}
