package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.BufferDesc;

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

    /**
     * 读回（GPU 已完成后调用）：map → 拷出 → unmap。
     * 须与 fence await/isSignaled 配合保证 GPU 写入已可见，本方法不做同步。
     */
    void read(int offset, int length, ByteBuffer out);

    /** Vulkan 句柄 VkBuffer */
    long handle();
}
