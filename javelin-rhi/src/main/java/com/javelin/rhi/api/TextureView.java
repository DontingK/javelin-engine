package com.javelin.rhi.api;

import com.javelin.rhi.types.Format;
import com.javelin.rhi.desc.TextureViewDesc.TextureSubresourceRange;

/**
 * 纹理视图（ImageView）。
 */
public abstract class TextureView extends GPUResource {

    public abstract Texture texture();

    public abstract TextureViewType viewType();

    public abstract Format format();

    public abstract TextureSubresourceRange subresourceRange();

    // ========== 内部类型 ==========

    public enum TextureViewType {
        TEXTURE_1D,
        TEXTURE_2D,
        TEXTURE_3D,
        TEXTURE_CUBE,
        TEXTURE_1D_ARRAY,
        TEXTURE_2D_ARRAY,
        TEXTURE_CUBE_ARRAY
    }
}
