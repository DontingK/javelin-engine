package cn.donting.javelin.rhi.types;

/**
 * 纹理布局。
 * 对照 VkImageLayout。
 * 上层不直接使用，RHI 内部布局跟踪（LayoutTracker）自动管理。
 */
public enum TextureLayout {
    /** 初始/未知布局（不可直接使用） */
    UNDEFINED,              // VK_IMAGE_LAYOUT_UNDEFINED
    /** 通用布局（任意访问合法，性能兜底） */
    GENERAL,                // VK_IMAGE_LAYOUT_GENERAL
    /** 颜色附件渲染目标最优布局 */
    COLOR_ATTACHMENT,       // VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    /** 深度/模板附件渲染目标最优布局 */
    DEPTH_STENCIL_ATTACHMENT, // VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
    /** 深度/模板只读最优布局（阴影采样等） */
    DEPTH_STENCIL_READ_ONLY, // VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
    /** 着色器采样最优布局 */
    SHADER_READ_ONLY,       // VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    /** 拷贝源最优布局 */
    TRANSFER_SRC,           // VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
    /** 拷贝目标最优布局 */
    TRANSFER_DST,           // VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
    /** 呈现队列可读（交换链图像专用） */
    PRESENT_SRC;            // VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
}
