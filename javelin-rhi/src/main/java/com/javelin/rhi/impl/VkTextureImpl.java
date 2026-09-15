package com.javelin.rhi.impl;

import com.javelin.rhi.RHI;
import com.javelin.rhi.RHITexture;

/**
 * Vulkan 纹理实现，封装 Vulkan 图像对象（{@code VkImage}）及其视图。
 * <p>
 * 纹理是 GPU 上存储图像数据的资源，可用于颜色附件、深度附件、采样纹理等多种用途。
 */
public class VkTextureImpl extends RHITexture {

    /** 纹理宽度（像素） */
    private final int width;
    /** 纹理高度（像素） */
    private final int height;

    /**
     * 创建 Vulkan 纹理实例。
     *
     * @param rhi    RHI 实例
     * @param handle Vulkan 图像句柄
     * @param width  纹理宽度
     * @param height 纹理高度
     */
    public VkTextureImpl(RHI rhi, long handle, int width, int height) {
        super(rhi, handle);
        this.width = width;
        this.height = height;
    }

    /**
     * 获取纹理宽度。
     *
     * @return 宽度（像素）
     */
    public int width() {
        return width;
    }

    /**
     * 获取纹理高度。
     *
     * @return 高度（像素）
     */
    public int height() {
        return height;
    }
}
