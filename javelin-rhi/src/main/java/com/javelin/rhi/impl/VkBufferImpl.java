package com.javelin.rhi.impl;

import com.javelin.rhi.RHI;
import com.javelin.rhi.RHIBuffer;

/**
 * Vulkan 缓冲区实现，封装 Vulkan 缓冲对象（{@code VkBuffer}）及其内存。
 * <p>
 * 提供缓冲区大小查询和数据写入能力，是 Uniform Buffer、Storage Buffer 等 GPU 缓冲资源的底层实现。
 */
public class VkBufferImpl extends RHIBuffer {

    /** 缓冲区的总字节大小 */
    private final long size;

    /**
     * 创建 Vulkan 缓冲区实例。
     *
     * @param rhi  RHI 实例
     * @param handle Vulkan 缓冲区句柄
     * @param size 缓冲区大小（字节）
     */
    public VkBufferImpl(RHI rhi, long handle, long size) {
        super(rhi, handle);
        this.size = size;
    }

    /**
     * 获取缓冲区的总大小。
     *
     * @return 缓冲区大小（字节）
     */
    @Override
    public long size() {
        return size;
    }

    /**
     * 将数据写入缓冲区的指定偏移位置。
     *
     * @param offset 写入偏移量（字节）
     * @param data   待写入的数据
     * @return 实际写入的字节数
     */
    @Override
    public int writeAligned(long offset, Object data) {
        // TODO: Write data to mapped buffer memory
        throw new UnsupportedOperationException("Buffer write not yet implemented");
    }
}
