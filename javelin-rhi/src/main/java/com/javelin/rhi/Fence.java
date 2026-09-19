package com.javelin.rhi;

/**
 * 栅栏接口。
 * 对照设计文档第 24.1 节 / 第 26.6 节。
 */
public interface Fence extends GPUResource {

    /** CPU 等待 GPU 完成 */
    void await();

    /** 重置栅栏（复用前必须调用） */
    void reset();

    /** Vulkan 句柄 VkFence */
    long handle();
}
