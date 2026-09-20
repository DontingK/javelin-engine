package com.javelin.rhi;

import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.types.BufferUsage;

import java.nio.FloatBuffer;
import java.util.EnumSet;

/**
 * FrameArena 实现（每帧临时数据的环形缓冲）。
 * 对照设计文档第 17.3 节 / 第 24.5 节。
 *
 * 简化实现：单块 HOST_VISIBLE + COHERENT 缓冲，帧末 fence 闸门后 reset。
 * 多帧环形（3 帧）在 Stage 5 完整实现，当前为单帧版本。
 */
final class VulkanFrameArena implements FrameArena {

    private static final int DEFAULT_CAPACITY = 1024 * 1024; // 1MB

    private final VulkanDevice device;
    private final VulkanBuffer buffer;
    private int offset;

    VulkanFrameArena(VulkanDevice device) {
        this.device = device;
        BufferDesc desc = new BufferDesc(DEFAULT_CAPACITY,
                EnumSet.of(BufferUsage.UNIFORM, BufferUsage.STORAGE, BufferUsage.VERTEX),
                MemoryPreference.hostVisibleCoherent());
        this.buffer = new VulkanBuffer(device, desc);
        this.offset = 0;
    }

    @Override
    public int allocate(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive, got: " + size);
        }
        int aligned = (offset + 255) & ~255;
        if (aligned + size > buffer.desc().size()) {
            throw new IllegalStateException("FrameArena exhausted: need " + size
                    + " bytes, remaining " + (buffer.desc().size() - aligned));
        }
        offset = aligned + size;
        return aligned;
    }

    @Override
    public int writeAligned(int offset, FloatBuffer data) {
        return buffer.writeAligned(offset, data);
    }

    @Override
    public void reset() {
        offset = 0;
    }

    /** 底层缓冲（绑定描述符用） */
    Buffer buffer() {
        return buffer;
    }
}
