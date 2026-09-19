package com.javelin.rhi.data;

/**
 * 颜色清屏值。
 * 对照 VkClearColorValue。
 */
public final class RHIClearColor implements RHIClearValue {

    private final float r;
    private final float g;
    private final float b;
    private final float a;

    public RHIClearColor(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public float r() { return r; }
    public float g() { return g; }
    public float b() { return b; }
    public float a() { return a; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RHIClearColor that)) return false;
        return Float.compare(r, that.r) == 0
                && Float.compare(g, that.g) == 0
                && Float.compare(b, that.b) == 0
                && Float.compare(a, that.a) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(r);
        result = 31 * result + Float.hashCode(g);
        result = 31 * result + Float.hashCode(b);
        result = 31 * result + Float.hashCode(a);
        return result;
    }

    @Override
    public String toString() {
        return "RHIClearColor{" + r + "," + g + "," + b + "," + a + "}";
    }
}
