package cn.donting.javelin.rhi.types;

/**
 * 着色器阶段。
 * 对照 VkShaderStageFlags，按位组合使用。
 */
public enum ShaderStage {
    /** 顶点着色器 */
    VERTEX,                 // VK_SHADER_STAGE_VERTEX_BIT
    /** 细分控制着色器 */
    TESSELLATION_CONTROL,   // VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT
    /** 细分求值着色器 */
    TESSELLATION_EVALUATION, // VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT
    /** 几何着色器 */
    GEOMETRY,               // VK_SHADER_STAGE_GEOMETRY_BIT
    /** 片元着色器 */
    FRAGMENT,               // VK_SHADER_STAGE_FRAGMENT_BIT
    /** 计算着色器 */
    COMPUTE,                // VK_SHADER_STAGE_COMPUTE_BIT
    /** 全部图形阶段（组合用，不对应单个着色器） */
    ALL_GRAPHICS,           // VK_SHADER_STAGE_ALL_GRAPHICS
    /** 全部阶段（组合用） */
    ALL;                    // VK_SHADER_STAGE_ALL
}
