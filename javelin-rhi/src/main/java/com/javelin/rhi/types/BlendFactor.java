package com.javelin.rhi.types;

/**
 * 混合因子。
 * 对照 VkBlendFactor。
 */
public enum BlendFactor {
    /** 因子 = 0（该项完全丢弃） */
    ZERO,                   // VK_BLEND_FACTOR_ZERO
    /** 因子 = 1（该项原值直通） */
    ONE,                    // VK_BLEND_FACTOR_ONE
    /** 因子 = 源片元颜色（逐通道取对应分量） */
    SRC_COLOR,              // VK_BLEND_FACTOR_SRC_COLOR
    /** 因子 = 1 - 源片元颜色（逐通道） */
    ONE_MINUS_SRC_COLOR,    // VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
    /** 因子 = 目标颜色（逐通道取对应分量） */
    DST_COLOR,              // VK_BLEND_FACTOR_DST_COLOR
    /** 因子 = 1 - 目标颜色（逐通道） */
    ONE_MINUS_DST_COLOR,    // VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
    /** 因子 = 源 alpha（展开到 RGBA 四通道） */
    SRC_ALPHA,              // VK_BLEND_FACTOR_SRC_ALPHA
    /** 因子 = 1 - 源 alpha（展开到 RGBA 四通道） */
    ONE_MINUS_SRC_ALPHA,    // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
    /** 因子 = 目标 alpha（展开到 RGBA 四通道） */
    DST_ALPHA,              // VK_BLEND_FACTOR_DST_ALPHA
    /** 因子 = 1 - 目标 alpha（展开到 RGBA 四通道） */
    ONE_MINUS_DST_ALPHA,    // VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA
    /** 因子 = 混合常量颜色（需运行时设置 blendConstants） */
    CONSTANT_COLOR,         // VK_BLEND_FACTOR_CONSTANT_COLOR
    /** 因子 = 1 - 混合常量颜色 */
    ONE_MINUS_CONSTANT_COLOR, // VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR
    /** 因子 = 混合常量 alpha（展开到 RGBA 四通道） */
    CONSTANT_ALPHA,         // VK_BLEND_FACTOR_CONSTANT_ALPHA
    /** 因子 = 1 - 混合常量 alpha（展开到 RGBA 四通道） */
    ONE_MINUS_CONSTANT_ALPHA, // VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA
    /** 因子 = min(srcA, 1 - srcA)（仅对源因子有意义） */
    SRC_ALPHA_SATURATE,     // VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
    /** 因子 = 片元着色器第二输出的颜色（双源混合，需特性支持） */
    SRC1_COLOR,             // VK_BLEND_FACTOR_SRC1_COLOR
    /** 因子 = 1 - 第二输出颜色（逐通道） */
    ONE_MINUS_SRC1_COLOR,   // VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR
    /** 因子 = 第二输出 alpha（展开到 RGBA 四通道） */
    SRC1_ALPHA,             // VK_BLEND_FACTOR_SRC1_ALPHA
    /** 因子 = 1 - 第二输出 alpha（展开到 RGBA 四通道） */
    ONE_MINUS_SRC1_ALPHA;   // VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA
}
