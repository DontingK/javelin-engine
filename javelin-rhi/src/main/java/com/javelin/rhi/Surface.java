package com.javelin.rhi;

import com.javelin.rhi.render.RenderSurface;

/**
 * Surface 接口。
 * 对照设计文档第 23.3 节 / 第 26.6 节。
 * 交换链一律经 {@link Device#createSwapChain} 创建（VkSurfaceKHR 由设备持有）。
 */
public interface Surface extends GPUResource {

    /** Vulkan 句柄 VkSurfaceKHR */
    long handle();

    /** 来源呈现目标（查尺寸 / resize / 可见性） */
    RenderSurface renderSurface();

    /** 能力信息 */
    Capabilities capabilities();

    /** 能力记录 */
    final class Capabilities {
        private final int minImageCount;
        private final int maxImageCount;
        private final int minWidth;
        private final int minHeight;
        private final int maxWidth;
        private final int maxHeight;

        public Capabilities(int minImageCount, int maxImageCount,
                            int minWidth, int minHeight, int maxWidth, int maxHeight) {
            this.minImageCount = minImageCount;
            this.maxImageCount = maxImageCount;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }

        public int minImageCount() { return minImageCount; }
        public int maxImageCount() { return maxImageCount; }
        public int minWidth() { return minWidth; }
        public int minHeight() { return minHeight; }
        public int maxWidth() { return maxWidth; }
        public int maxHeight() { return maxHeight; }
    }
}
