package com.javelin.rhi.api;

import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.TextureUsage;

/**
 * 交换链。
 */
public abstract class SwapChain extends GPUResource {

    public abstract Format imageFormat();

    public abstract ColorSpace colorSpace();

    public abstract int width();

    public abstract int height();

    public abstract int imageCount();

    public abstract PresentMode presentMode();

    public abstract TextureUsage imageUsage();

    // ========== 获取图像 ==========

    /**
     * 获取下一帧图像索引。
     * <p>
     * 如果窗口大小与交换链不一致，内部自动重建交换链并返回 -1。
     *
     * @return 图像索引，或 -1 表示交换链已重建需要跳过当前帧
     */
    public abstract int acquireNextImage(long timeoutNanos, Semaphore signalSemaphore, Fence signalFence);

    public abstract Texture getImage(int index);

    // ========== 重新创建 ==========

    public abstract void recreate(int width, int height);

    // ========== 呈现 ==========

    public abstract void present(Queue queue, Semaphore[] waitSemaphores);

    // ========== 内部类型 ==========

    public enum ColorSpace {
        SRGB_NONLINEAR,
        DISPLAY_P3_NONLINEAR_EXT,
        EXTENDED_SRGB_LINEAR_EXT,
        DISPLAY_P3_LINEAR_EXT,
        DCI_P3_NONLINEAR_EXT,
        BT709_LINEAR_EXT,
        BT709_NONLINEAR_EXT,
        BT2020_LINEAR_EXT,
        HDR10_ST2084_EXT,
        DOLBYVISION_EXT,
        HDR10_HLG_EXT,
        ADOBERGB_LINEAR_EXT,
        ADOBERGB_NONLINEAR_EXT,
        PASS_THROUGH_EXT,
        EXTENDED_SRGB_NONLINEAR_EXT,
        DISPLAY_NATIVE_AMD
    }

    public enum PresentMode {
        IMMEDIATE,
        MAILBOX,
        FIFO,
        FIFO_RELAXED,
        SHARED_DEMAND_REFRESH,
        SHARED_CONTINUOUS_REFRESH
    }
}
