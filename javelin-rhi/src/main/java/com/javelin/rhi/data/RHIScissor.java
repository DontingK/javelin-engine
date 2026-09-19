package com.javelin.rhi.data;

/**
 * 裁剪矩形。
 * 对照 VkRect2D。
 */
public final class RHIScissor {

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    public RHIScissor(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int x() { return x; }
    public int y() { return y; }
    public int width() { return width; }
    public int height() { return height; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RHIScissor that)) return false;
        return x == that.x && y == that.y && width == that.width && height == that.height;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + width;
        result = 31 * result + height;
        return result;
    }

    @Override
    public String toString() {
        return "RHIScissor{" + x + "," + y + " " + width + "x" + height + "}";
    }
}
