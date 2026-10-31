package cn.donting.javelin.rhi;

import java.nio.FloatBuffer;

/**
 * 帧内存池接口。
 * 对照设计文档第 17.3 节 / 第 26.6 节。
 *
 * 3 帧环形复用，帧末 fence 闸门后重置。
 */
public interface FrameArena {

    /** 分配指定大小的内存，返回偏移 */
    int allocate(int size);

    /** 对齐写入，返回实际偏移（256B 对齐） */
    int writeAligned(int offset, FloatBuffer data);

    /** 帧末重置（fence 闸门后） */
    void reset();
}
