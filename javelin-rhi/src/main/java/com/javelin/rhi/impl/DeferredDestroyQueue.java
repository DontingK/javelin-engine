package com.javelin.rhi.impl;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 延迟销毁队列，用于安全地延迟销毁 GPU 资源。
 * <p>
 * 在帧渲染过程中，某些资源可能仍被 GPU 使用，直接销毁会导致同步问题。
 * 该类将资源的销毁操作延迟若干帧，确保 GPU 不再访问该资源后再执行真正的销毁。
 */
public class DeferredDestroyQueue {

    /** 内部队列，存储待销毁的资源条目 */
    private final ConcurrentLinkedQueue<DestroyEntry> queue = new ConcurrentLinkedQueue<>();

    /**
     * 将资源句柄加入延迟销毁队列。
     *
     * @param handle    待销毁资源的句柄
     * @param frameIndex 当前帧索引
     */
    public void enqueue(long handle, long frameIndex) {
        queue.add(new DestroyEntry(handle, frameIndex));
    }

    /**
     * 处理队列中已达到销毁条件的资源条目。
     *
     * @param currentFrame 当前帧索引
     */
    public void process(long currentFrame) {
        DestroyEntry e;
        while ((e = queue.peek()) != null && e.frameIndex + 2 <= currentFrame) {
            queue.poll();
            // TODO: vkDestroyBuffer/Image/Pipeline...
        }
    }

    /**
     * 内部记录，表示一个待销毁的资源条目。
     *
     * @param handle     资源句柄
     * @param frameIndex 加入队列时的帧索引
     */
    record DestroyEntry(long handle, long frameIndex) {
    }
}
