package com.javelin.rhi.api;

import com.javelin.rhi.types.*;

/**
 * 采样器。
 */
public abstract class Sampler extends GPUResource {

    public abstract Filter minFilter();

    public abstract Filter magFilter();

    public abstract SamplerMipmapMode mipmapMode();

    public abstract SamplerAddressMode addressModeU();

    public abstract SamplerAddressMode addressModeV();

    public abstract SamplerAddressMode addressModeW();

    public abstract float mipLodBias();

    public abstract boolean anisotropyEnable();

    public abstract float maxAnisotropy();

    public abstract boolean compareEnable();

    public abstract CompareOp compareOp();

    public abstract float minLod();

    public abstract float maxLod();

    public abstract BorderColor borderColor();

    public abstract boolean unnormalizedCoordinates();

    // ========== 内部类型 ==========

    public enum BorderColor {
        FLOAT_TRANSPARENT_BLACK,
        INT_TRANSPARENT_BLACK,
        FLOAT_OPAQUE_BLACK,
        INT_OPAQUE_BLACK,
        FLOAT_OPAQUE_WHITE,
        INT_OPAQUE_WHITE
    }
}
