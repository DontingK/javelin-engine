package com.javelin.rhi;

/**
 * 栅栏接口。
 * 对照设计文档第 24.1 节 / 第 26.6 节。
 */
public interface Fence extends GPUResource {

    /** CPU 等待 GPU 完成 */
    void await();

    /** 非阻塞完成查询（true = 已 signal；GPU 落后时读回环跳帧用） */
    boolean isSignaled();

    /** 重置栅栏（复用前必须调用） */
    void reset();

    /** Vulkan 句柄 VkFence */
    long handle();
}
