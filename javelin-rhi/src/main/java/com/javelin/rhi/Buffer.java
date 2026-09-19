package com.javelin.rhi;

import com.javelin.rhi.desc.BufferDesc;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * 缓冲接口。
 * 对照设计文档第 26.2 节。
 */
public interface Buffer extends GPUResource {

    BufferDesc desc();

    /**
     * 对齐写入（FrameArena 用）。
     * 返回实际偏移，256B 对齐（minUniformBufferOffsetAlignment）。
     */
    int writeAligned(int offset, FloatBuffer data);

    /** 运行期更新（staging 池） */
    void update(int offset, ByteBuffer data);

    /** Vulkan 句柄 VkBuffer */
    long handle();
}
