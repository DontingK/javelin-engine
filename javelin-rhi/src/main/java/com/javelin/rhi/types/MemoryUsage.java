package com.javelin.rhi.types;

/**
 * 内存分配策略。
 */
public enum MemoryUsage {
    /**
     * GPU 专用内存（DEVICE_LOCAL）。
     * 用于：顶点/索引缓冲、纹理、GPU 只读数据。
     */
    GPU_ONLY,

    /**
     * CPU 写入 GPU 读取（HOST_VISIBLE + DEVICE_LOCAL）。
     * 用于：每帧更新的 UBO、动态顶点数据。
     */
    CPU_TO_GPU,

    /**
     * GPU 写入 CPU 读取（HOST_VISIBLE）。
     * 用于：回读缓冲区、查询结果。
     */
    GPU_TO_CPU,

    /**
     * CPU 专用（HOST_VISIBLE + HOST_COHERENT）。
     * 用于：暂存缓冲区（staging）。
     */
    CPU_ONLY
}
