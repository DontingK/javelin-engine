package com.javelin.rhi.api;

import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.MemoryUsage;

import java.nio.ByteBuffer;

/**
 * GPU 缓冲区（顶点、索引、 uniform、存储等）。
 */
public abstract class Buffer extends GPUResource {

    public abstract long size();

    public abstract BufferUsage usage();

    public abstract MemoryUsage memoryUsage();

    // ========== 映射（仅 CPU_VISIBLE 内存） ==========

    public abstract ByteBuffer map();

    public abstract ByteBuffer map(long offset, long size);

    public abstract void unmap();

    public abstract boolean isMapped();

    // ========== 便捷上传 ==========

    public abstract void upload(byte[] data);

    public abstract void upload(ByteBuffer data);

    public abstract void upload(long offset, ByteBuffer data);
}
