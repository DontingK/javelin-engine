package com.javelin.rhi.data;

/**
 * 整数清屏值（UINT/SINT 附件专用）。
 * 对照 VkClearColorValue 的 uint32 分量——Vulkan 规范要求整数附件清屏用整数分量，
 * float32 清 UINT 附件是验证层 mismatch。
 */
public final class RHIClearUintColor implements RHIClearValue {

    private final int r;
    private final int g;
    private final int b;
    private final int a;

    public RHIClearUintColor(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public int r() { return r; }
    public int g() { return g; }
    public int b() { return b; }
    public int a() { return a; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RHIClearUintColor that)) return false;
        return r == that.r && g == that.g && b == that.b && a == that.a;
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(r);
        result = 31 * result + Integer.hashCode(g);
        result = 31 * result + Integer.hashCode(b);
        return 31 * result + Integer.hashCode(a);
    }

    @Override
    public String toString() {
        return "RHIClearUintColor{" + r + "," + g + "," + b + "," + a + "}";
    }
}
