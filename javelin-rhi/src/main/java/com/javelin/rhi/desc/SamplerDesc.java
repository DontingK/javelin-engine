package com.javelin.rhi.desc;

import com.javelin.rhi.api.Sampler;
import com.javelin.rhi.types.*;

/**
 * 采样器创建描述符。
 */
public record SamplerDesc(
    Filter minFilter,
    Filter magFilter,
    SamplerMipmapMode mipmapMode,
    SamplerAddressMode addressModeU,
    SamplerAddressMode addressModeV,
    SamplerAddressMode addressModeW,
    float mipLodBias,
    boolean anisotropyEnable,
    float maxAnisotropy,
    boolean compareEnable,
    CompareOp compareOp,
    float minLod,
    float maxLod,
    Sampler.BorderColor borderColor,
    boolean unnormalizedCoordinates
) {
    public SamplerDesc {
        if (minFilter == null) minFilter = Filter.LINEAR;
        if (magFilter == null) magFilter = Filter.LINEAR;
        if (mipmapMode == null) mipmapMode = SamplerMipmapMode.LINEAR;
        if (addressModeU == null) addressModeU = SamplerAddressMode.REPEAT;
        if (addressModeV == null) addressModeV = SamplerAddressMode.REPEAT;
        if (addressModeW == null) addressModeW = SamplerAddressMode.REPEAT;
        if (compareOp == null) compareOp = CompareOp.ALWAYS;
        if (borderColor == null) borderColor = Sampler.BorderColor.FLOAT_OPAQUE_BLACK;
        if (maxLod < 0) maxLod = Float.MAX_VALUE;
    }

    public SamplerDesc() {
        this(Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.LINEAR,
             SamplerAddressMode.REPEAT, SamplerAddressMode.REPEAT, SamplerAddressMode.REPEAT,
             0, false, 1, false, CompareOp.ALWAYS, 0, Float.MAX_VALUE,
             Sampler.BorderColor.FLOAT_OPAQUE_BLACK, false);
    }
}
