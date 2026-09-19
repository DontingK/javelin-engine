package com.javelin.rhi.desc;

/**
 * 交换链描述。
 * 对照 VkSwapchainCreateInfoKHR。
 */
public final class SwapChainDesc {

    private final int width;
    private final int height;
    private final PresentMode presentMode;
    private final ColorSpace colorSpace;
    private final int minImageCount;
    private final boolean vsync;

    public SwapChainDesc(int width, int height,
                         PresentMode presentMode, ColorSpace colorSpace,
                         int minImageCount, boolean vsync) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "SwapChain dimensions must be positive, got: " + width + "x" + height);
        }
        if (presentMode == null) {
            throw new IllegalArgumentException("PresentMode must not be null");
        }
        if (colorSpace == null) {
            throw new IllegalArgumentException("ColorSpace must not be null");
        }
        this.width = width;
        this.height = height;
        this.presentMode = presentMode;
        this.colorSpace = colorSpace;
        this.minImageCount = minImageCount;
        this.vsync = vsync;
    }

    public int width() { return width; }
    public int height() { return height; }
    public PresentMode presentMode() { return presentMode; }
    public ColorSpace colorSpace() { return colorSpace; }
    public int minImageCount() { return minImageCount; }
    public boolean vsync() { return vsync; }

    public enum PresentMode {
        FIFO,       // VK_PRESENT_MODE_FIFO_KHR（默认，vsync）
        MAILBOX,    // VK_PRESENT_MODE_MAILBOX_KHR
        IMMEDIATE   // VK_PRESENT_MODE_IMMEDIATE_KHR
    }

    public enum ColorSpace {
        SRGB,       // VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        HDR10       // VK_COLOR_SPACE_HDR10_ST2084_EXT
    }

    public static SwapChainDesc defaults(int w, int h) {
        return new SwapChainDesc(w, h, PresentMode.FIFO, ColorSpace.SRGB, 0, true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SwapChainDesc that)) return false;
        return width == that.width && height == that.height
                && minImageCount == that.minImageCount && vsync == that.vsync
                && presentMode == that.presentMode && colorSpace == that.colorSpace;
    }

    @Override
    public int hashCode() {
        int result = width;
        result = 31 * result + height;
        result = 31 * result + presentMode.hashCode();
        result = 31 * result + colorSpace.hashCode();
        result = 31 * result + minImageCount;
        result = 31 * result + Boolean.hashCode(vsync);
        return result;
    }

    @Override
    public String toString() {
        return "SwapChainDesc{" + width + "x" + height
                + ", mode=" + presentMode + ", colorSpace=" + colorSpace
                + ", minImages=" + minImageCount + ", vsync=" + vsync + "}";
    }
}
