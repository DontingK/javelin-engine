package com.javelin.rhi.data;

/**
 * 视口。
 * 对照 VkViewport。
 */
public final class RHIViewport {

    private final float x;
    private final float y;
    private final float width;
    private final float height;
    private final float minDepth;
    private final float maxDepth;

    public RHIViewport(float x, float y, float width, float height,
                       float minDepth, float maxDepth) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
    }

    public float x() { return x; }
    public float y() { return y; }
    public float width() { return width; }
    public float height() { return height; }
    public float minDepth() { return minDepth; }
    public float maxDepth() { return maxDepth; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RHIViewport that)) return false;
        return Float.compare(x, that.x) == 0
                && Float.compare(y, that.y) == 0
                && Float.compare(width, that.width) == 0
                && Float.compare(height, that.height) == 0
                && Float.compare(minDepth, that.minDepth) == 0
                && Float.compare(maxDepth, that.maxDepth) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(x);
        result = 31 * result + Float.hashCode(y);
        result = 31 * result + Float.hashCode(width);
        result = 31 * result + Float.hashCode(height);
        result = 31 * result + Float.hashCode(minDepth);
        result = 31 * result + Float.hashCode(maxDepth);
        return result;
    }

    @Override
    public String toString() {
        return "RHIViewport{" + x + "," + y + " " + width + "x" + height
                + " depth[" + minDepth + "," + maxDepth + "]}";
    }
}
