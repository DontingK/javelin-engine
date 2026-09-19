package com.javelin.rhi.types;

/**
 * 着色器阶段。
 * 对照 VkShaderStageFlags，按位组合使用。
 */
public enum ShaderStage {
    VERTEX,                 // VK_SHADER_STAGE_VERTEX_BIT
    TESSELLATION_CONTROL,   // VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT
    TESSELLATION_EVALUATION, // VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT
    GEOMETRY,               // VK_SHADER_STAGE_GEOMETRY_BIT
    FRAGMENT,               // VK_SHADER_STAGE_FRAGMENT_BIT
    COMPUTE,                // VK_SHADER_STAGE_COMPUTE_BIT
    ALL_GRAPHICS,           // VK_SHADER_STAGE_ALL_GRAPHICS
    ALL;                    // VK_SHADER_STAGE_ALL
}
