package cn.donting.javelin.rhi.types;

/**
 * 采样过滤器。
 * 对照 VkFilter。
 */
public enum Filter {
    /** 最近邻采样（无插值，像素风/精确取值） */
    NEAREST,                // VK_FILTER_NEAREST
    /** 双线性插值采样（平滑缩放） */
    LINEAR;                 // VK_FILTER_LINEAR
}
