package com.javelin.rhi.data;

/**
 * 深度模板清屏值。
 * 对照 VkClearDepthStencilValue。
 */
public final class RHIClearDepthStencil implements RHIClearValue {

    private final float depth;
    private final int stencil;

    public RHIClearDepthStencil(float depth, int stencil) {
        this.depth = depth;
        this.stencil = stencil;
    }

    public float depth() { return depth; }
    public int stencil() { return stencil; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RHIClearDepthStencil that)) return false;
        return Float.compare(depth, that.depth) == 0 && stencil == that.stencil;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(depth);
        result = 31 * result + stencil;
        return result;
    }

    @Override
    public String toString() {
        return "RHIClearDepthStencil{depth=" + depth + ", stencil=" + stencil + "}";
    }
}
