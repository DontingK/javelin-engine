package com.javelin.rhi.types;

/**
 * 纹理用途。
 * 对照 VkImageUsageFlags，按位组合使用。
 */
public enum TextureUsage {
    /** 可作为拷贝源 */
    TRANSFER_SRC,           // VK_IMAGE_USAGE_TRANSFER_SRC_BIT
    /** 可作为拷贝目标 */
    TRANSFER_DST,           // VK_IMAGE_USAGE_TRANSFER_DST_BIT
    /** 可被着色器采样 */
    SAMPLED,                // VK_IMAGE_USAGE_SAMPLED_BIT
    /** 可作为存储图像被着色器读写 */
    STORAGE,                // VK_IMAGE_USAGE_STORAGE_BIT
    /** 可作为颜色附件渲染目标 */
    COLOR_ATTACHMENT,       // VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
    /** 可作为深度/模板附件 */
    DEPTH_STENCIL_ATTACHMENT, // VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
    /** 瞬态附件：内容无需持久化（配合 DONT_CARE 加载/存储） */
    TRANSIENT_ATTACHMENT,   // VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT
    /** 可作为输入附件（片元着色器内读本 Pass 附件） */
    INPUT_ATTACHMENT;       // VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT
}
