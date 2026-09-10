package com.javelin.rhi.desc;

import com.javelin.rhi.types.*;

/**
 * 交换链创建描述符。
 */
public record SwapChainDesc(
    int width,
    int height,
    int minImageCount,
    Format imageFormat,
    ColorSpace colorSpace,
    PresentMode presentMode,
    TextureUsage imageUsage,
    boolean clipped
) {
    public SwapChainDesc {
        if (imageFormat == null) imageFormat = Format.B8G8R8A8_UNORM;
        if (colorSpace == null) colorSpace = ColorSpace.SRGB_NONLINEAR;
        if (presentMode == null) presentMode = PresentMode.FIFO;
        if (imageUsage == null) imageUsage = TextureUsage.COLOR_ATTACHMENT;
    }

    public SwapChainDesc(int width, int height) {
        this(width, height, 3, Format.B8G8R8A8_UNORM, ColorSpace.SRGB_NONLINEAR, PresentMode.FIFO, TextureUsage.COLOR_ATTACHMENT, true);
    }

    // ========== 内部类型 ==========

    public enum ColorSpace {
        SRGB_NONLINEAR
    }

    public enum PresentMode {
        IMMEDIATE,
        MAILBOX,
        FIFO,
        FIFO_RELAXED
    }
}
