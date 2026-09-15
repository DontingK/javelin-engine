package com.javelin.rhi.impl;

import com.javelin.rhi.RHIBuffer;

/**
 * 帧内存分配器，为每帧提供临时的线性内存分配服务。
 * <p>
 * 基于环形缓冲区（ring buffer）实现 bump allocator，
 * 用于高效分配每帧 Uniform Buffer 等临时资源，避免频繁的内存分配与释放。
 */
public class FrameArena {

    /**
     * 从帧内存池中分配指定大小的缓冲区。
     *
     * @param size 需要分配的字节数
     * @return 分配好的缓冲区对象
     */
    public RHIBuffer allocate(long size) {
        // TODO: Bump allocator from ring buffers
        throw new UnsupportedOperationException("Frame arena allocation not yet implemented");
    }

    /**
     * 重置当前帧的内存分配器，回收该帧已分配的临时内存。
     *
     * @param frameIndex 当前帧索引
     */
    public void reset(int frameIndex) {
        // TODO: Reset arena for frame
    }
}
