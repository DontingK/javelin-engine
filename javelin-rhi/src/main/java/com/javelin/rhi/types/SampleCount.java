package com.javelin.rhi.types;

/**
 * 采样数（多重采样）。
 * 对照 VkSampleCountFlags。
 */
public enum SampleCount {
    COUNT_1,                // VK_SAMPLE_COUNT_1_BIT
    COUNT_2,                // VK_SAMPLE_COUNT_2_BIT
    COUNT_4,                // VK_SAMPLE_COUNT_4_BIT
    COUNT_8,                // VK_SAMPLE_COUNT_8_BIT
    COUNT_16,               // VK_SAMPLE_COUNT_16_BIT
    COUNT_32,               // VK_SAMPLE_COUNT_32_BIT
    COUNT_64;               // VK_SAMPLE_COUNT_64_BIT
}
