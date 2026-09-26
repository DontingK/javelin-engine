package com.javelin.rhi.types;

/**
 * 采样器寻址模式。
 * 对照 VkSamplerAddressMode。
 */
public enum SamplerAddressMode {
    /** 超界坐标重复平铺（常规平铺纹理） */
    REPEAT,                 // VK_SAMPLER_ADDRESS_MODE_REPEAT
    /** 超界坐标镜像重复（每份翻转） */
    MIRRORED_REPEAT,        // VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
    /** 超界坐标钳制到最近边缘像素 */
    CLAMP_TO_EDGE,          // VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    /** 超界坐标返回边框常量色（需配合 borderColor 设置） */
    CLAMP_TO_BORDER,        // VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER
    /** 只镜像一次后钳制到边缘（0..2 范围内镜像） */
    MIRROR_CLAMP_TO_EDGE;   // VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE
}
