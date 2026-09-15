package com.javelin.rhi;

/**
 * GPU 采样器抽象基类，表示纹理采样配置（过滤、寻址模式等）。
 */
public abstract class RHISampler extends RHIResource {

    protected RHISampler(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
