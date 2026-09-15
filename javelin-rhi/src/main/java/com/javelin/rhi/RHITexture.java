package com.javelin.rhi;

/**
 * GPU 纹理抽象基类，表示可用于采样、渲染或存储的图像资源。
 */
public abstract class RHITexture extends RHIResource {

    protected RHITexture(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
