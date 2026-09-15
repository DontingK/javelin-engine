package com.javelin.rhi;

/**
 * GPU 管线抽象基类，表示已编译的图形或计算管线对象。
 */
public abstract class RHIPipeline extends RHIResource {

    protected RHIPipeline(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
