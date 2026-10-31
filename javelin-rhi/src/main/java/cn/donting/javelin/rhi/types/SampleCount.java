package cn.donting.javelin.rhi.types;

/**
 * 采样数（多重采样）。
 * 对照 VkSampleCountFlags。
 */
public enum SampleCount {
    /** 每像素 1 采样（无 MSAA） */
    COUNT_1,                // VK_SAMPLE_COUNT_1_BIT
    /** 每像素 2 采样 */
    COUNT_2,                // VK_SAMPLE_COUNT_2_BIT
    /** 每像素 4 采样（常用） */
    COUNT_4,                // VK_SAMPLE_COUNT_4_BIT
    /** 每像素 8 采样 */
    COUNT_8,                // VK_SAMPLE_COUNT_8_BIT
    /** 每像素 16 采样 */
    COUNT_16,               // VK_SAMPLE_COUNT_16_BIT
    /** 每像素 32 采样 */
    COUNT_32,               // VK_SAMPLE_COUNT_32_BIT
    /** 每像素 64 采样 */
    COUNT_64;               // VK_SAMPLE_COUNT_64_BIT
}
