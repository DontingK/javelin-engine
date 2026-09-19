package com.javelin.rhi.desc;

import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.SampleCount;
import com.javelin.rhi.types.TextureType;
import com.javelin.rhi.types.TextureUsage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 纹理描述。
 * 对照 VkImageCreateInfo。
 */
public final class TextureDesc {

    private final TextureType type;
    private final Format format;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int arrayLayers;
    private final SampleCount sampleCount;
    private final Set<TextureUsage> usage;
    private final MemoryPreference memory;

    public TextureDesc(TextureType type, Format format,
                       int width, int height, int depth,
                       int mipLevels, int arrayLayers,
                       SampleCount sampleCount,
                       Set<TextureUsage> usage, MemoryPreference memory) {
        if (type == null) {
            throw new IllegalArgumentException("TextureType must not be null");
        }
        if (format == null) {
            throw new IllegalArgumentException("Format must not be null");
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException(
                    "Texture dimensions must be positive, got: " + width + "x" + height + "x" + depth);
        }
        if (mipLevels <= 0) {
            throw new IllegalArgumentException("mipLevels must be positive, got: " + mipLevels);
        }
        if (arrayLayers <= 0) {
            throw new IllegalArgumentException("arrayLayers must be positive, got: " + arrayLayers);
        }
        if (sampleCount == null) {
            throw new IllegalArgumentException("SampleCount must not be null");
        }
        if (usage == null || usage.isEmpty()) {
            throw new IllegalArgumentException("Texture usage must not be empty");
        }
        if (memory == null) {
            throw new IllegalArgumentException("MemoryPreference must not be null");
        }
        this.type = type;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = mipLevels;
        this.arrayLayers = arrayLayers;
        this.sampleCount = sampleCount;
        this.usage = Collections.unmodifiableSet(EnumSet.copyOf(usage));
        this.memory = memory;
    }

    public TextureType type() { return type; }
    public Format format() { return format; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int mipLevels() { return mipLevels; }
    public int arrayLayers() { return arrayLayers; }
    public SampleCount sampleCount() { return sampleCount; }
    public Set<TextureUsage> usage() { return usage; }
    public MemoryPreference memory() { return memory; }

    /** 便捷构造：2D 纹理，1 mip，1 layer，无 MSAA */
    public static TextureDesc texture2D(Format format, int width, int height, Set<TextureUsage> usage) {
        return new TextureDesc(
                TextureType.TYPE_2D, format, width, height, 1,
                1, 1, SampleCount.COUNT_1, usage, MemoryPreference.deviceLocal()
        );
    }

    /** 便捷构造：2D 深度纹理 */
    public static TextureDesc depth2D(Format format, int width, int height, Set<TextureUsage> usage) {
        return new TextureDesc(
                TextureType.TYPE_2D, format, width, height, 1,
                1, 1, SampleCount.COUNT_1, usage, MemoryPreference.deviceLocal()
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextureDesc that)) return false;
        return type == that.type && format == that.format
                && width == that.width && height == that.height && depth == that.depth
                && mipLevels == that.mipLevels && arrayLayers == that.arrayLayers
                && sampleCount == that.sampleCount
                && usage.equals(that.usage) && memory.equals(that.memory);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + format.hashCode();
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + depth;
        result = 31 * result + mipLevels;
        result = 31 * result + arrayLayers;
        result = 31 * result + sampleCount.hashCode();
        result = 31 * result + usage.hashCode();
        result = 31 * result + memory.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "TextureDesc{type=" + type + ", format=" + format
                + ", " + width + "x" + height + "x" + depth
                + ", mipLevels=" + mipLevels + ", arrayLayers=" + arrayLayers
                + ", sampleCount=" + sampleCount + ", usage=" + usage + "}";
    }
}
