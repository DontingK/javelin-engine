package com.javelin.rhi.api;

import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.desc.TextureViewDesc;
import com.javelin.rhi.types.*;

/**
 * GPU 纹理/图像。
 */
public abstract class Texture extends GPUResource {

    public abstract int width();

    public abstract int height();

    public abstract int depth();

    public abstract int mipLevels();

    public abstract int arrayLayers();

    public abstract Format format();

    public abstract TextureUsage usage();

    public abstract TextureType type();

    public abstract SampleCount samples();

    // ========== 创建视图 ==========

    public abstract TextureView createView(TextureViewDesc desc);

    // ========== 当前布局（内部追踪） ==========

    public abstract TextureLayout currentLayout();
}
