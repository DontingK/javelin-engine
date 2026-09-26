package com.javelin.rhi.types;

/**
 * 采样器 mipmap 模式。
 * 对照 VkSamplerMipmapMode。
 */
public enum SamplerMipmapMode {
    /** 选取最近 mip 层级（层间无插值） */
    NEAREST,                // VK_SAMPLER_MIPMAP_MODE_NEAREST
    /** 相邻 mip 层级间插值（三线性过滤） */
    LINEAR;                 // VK_SAMPLER_MIPMAP_MODE_LINEAR
}
