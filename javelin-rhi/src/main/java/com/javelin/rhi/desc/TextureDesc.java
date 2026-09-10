package com.javelin.rhi.desc;

import com.javelin.rhi.types.*;

/**
 * 纹理创建描述符。
 */
public record TextureDesc(
    TextureType type,
    Format format,
    int width,
    int height,
    int depth,
    int mipLevels,
    int arrayLayers,
    SampleCount samples,
    TextureUsage usage,
    TextureLayout initialLayout
) {
    public TextureDesc {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Dimensions must be > 0");
        }
        if (mipLevels <= 0) mipLevels = 1;
        if (arrayLayers <= 0) arrayLayers = 1;
        if (samples == null) samples = SampleCount.COUNT_1;
        if (initialLayout == null) initialLayout = TextureLayout.UNDEFINED;
    }

    public TextureDesc(TextureType type, Format format, int width, int height, TextureUsage usage) {
        this(type, format, width, height, 1, 1, 1, SampleCount.COUNT_1, usage, TextureLayout.UNDEFINED);
    }

    public TextureDesc(TextureType type, Format format, int width, int height, int depth, TextureUsage usage) {
        this(type, format, width, height, depth, 1, 1, SampleCount.COUNT_1, usage, TextureLayout.UNDEFINED);
    }
}
