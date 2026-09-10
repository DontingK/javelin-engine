package com.javelin.rhi.desc;

import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.MemoryUsage;

/**
 * 缓冲区创建描述符。
 */
public record BufferDesc(
    long size,
    BufferUsage usage,
    MemoryUsage memoryUsage,
    boolean mappedAtCreation  // 创建后立即映射（仅 CPU 可见内存）
) {
    public BufferDesc {
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be > 0");
    }

    public BufferDesc(long size, BufferUsage usage, MemoryUsage memoryUsage) {
        this(size, usage, memoryUsage, false);
    }
}
