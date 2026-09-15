package com.javelin.rhi;

/**
 * GPU 缓冲区抽象基类，表示一块 GPU 可读写的内存资源。
 */
public abstract class RHIBuffer extends RHIResource {

    protected RHIBuffer(RHI rhi, long handle) {
        super(rhi, handle);
    }

    /** 返回缓冲区的字节大小。 */
    public abstract long size();

    /**
     * 将数据写入缓冲区指定偏移处。
     *
     * @param offset 目标偏移（字节）
     * @param data   要写入的数据
     * @return 实际写入的字节数
     */
    public abstract int writeAligned(long offset, Object data);
}
