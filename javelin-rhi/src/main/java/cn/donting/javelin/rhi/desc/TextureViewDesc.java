package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.TextureType;

/**
 * 纹理视图描述。
 * 对照 VkImageViewCreateInfo。
 */
public final class TextureViewDesc {

    private final TextureType type;
    private final Format format;
    private final int baseMipLevel;
    private final int levelCount;
    private final int baseLayer;
    private final int layerCount;

    public TextureViewDesc(TextureType type, Format format,
                           int baseMipLevel, int levelCount,
                           int baseLayer, int layerCount) {
        if (type == null) {
            throw new IllegalArgumentException("TextureType must not be null");
        }
        if (format == null) {
            throw new IllegalArgumentException("Format must not be null");
        }
        if (baseMipLevel < 0) {
            throw new IllegalArgumentException("baseMipLevel must be >= 0, got: " + baseMipLevel);
        }
        if (levelCount <= 0) {
            throw new IllegalArgumentException("levelCount must be positive, got: " + levelCount);
        }
        if (baseLayer < 0) {
            throw new IllegalArgumentException("baseLayer must be >= 0, got: " + baseLayer);
        }
        if (layerCount <= 0) {
            throw new IllegalArgumentException("layerCount must be positive, got: " + layerCount);
        }
        this.type = type;
        this.format = format;
        this.baseMipLevel = baseMipLevel;
        this.levelCount = levelCount;
        this.baseLayer = baseLayer;
        this.layerCount = layerCount;
    }

    public TextureType type() { return type; }
    public Format format() { return format; }
    public int baseMipLevel() { return baseMipLevel; }
    public int levelCount() { return levelCount; }
    public int baseLayer() { return baseLayer; }
    public int layerCount() { return layerCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextureViewDesc that)) return false;
        return type == that.type && format == that.format
                && baseMipLevel == that.baseMipLevel && levelCount == that.levelCount
                && baseLayer == that.baseLayer && layerCount == that.layerCount;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + format.hashCode();
        result = 31 * result + baseMipLevel;
        result = 31 * result + levelCount;
        result = 31 * result + baseLayer;
        result = 31 * result + layerCount;
        return result;
    }

    @Override
    public String toString() {
        return "TextureViewDesc{type=" + type + ", format=" + format
                + ", mip[" + baseMipLevel + "+" + levelCount + "]"
                + ", layer[" + baseLayer + "+" + layerCount + "]}";
    }
}
