package com.javelin.rhi.types;

/**
 * 采样器寻址模式。
 * 对照 VkSamplerAddressMode。
 */
public enum SamplerAddressMode {
    REPEAT,                 // VK_SAMPLER_ADDRESS_MODE_REPEAT
    MIRRORED_REPEAT,        // VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
    CLAMP_TO_EDGE,          // VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    CLAMP_TO_BORDER,        // VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER
    MIRROR_CLAMP_TO_EDGE;   // VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE
}
