package com.javelin.rhi.types;

/**
 * 管线阶段。
 * 对照 VkPipelineStageFlags，按位组合使用。
 * 用于屏障生成（Synchronization2）。
 */
public enum PipelineStage {
    /** 管线起点（仅作依赖开端，无实际访问语义） */
    TOP_OF_PIPE,            // VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
    /** 读取间接绘制参数 */
    DRAW_INDIRECT,          // VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
    /** 顶点/索引输入装配 */
    VERTEX_INPUT,           // VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
    /** 顶点着色器执行 */
    VERTEX_SHADER,          // VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
    /** 细分控制着色器执行 */
    TESSELLATION_CONTROL_SHADER, // VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT
    /** 细分求值着色器执行 */
    TESSELLATION_EVALUATION_SHADER, // VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT
    /** 几何着色器执行 */
    GEOMETRY_SHADER,        // VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT
    /** 片元着色器执行 */
    FRAGMENT_SHADER,        // VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
    /** 片元着色器前的深度/模板提前测试 */
    EARLY_FRAGMENT_TESTS,   // VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
    /** 片元着色器后的深度/模板滞后测试 */
    LATE_FRAGMENT_TESTS,    // VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
    /** 颜色附件混合与写出 */
    COLOR_ATTACHMENT_OUTPUT, // VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
    /** 计算着色器执行 */
    COMPUTE_SHADER,         // VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    /** 传输操作（拷贝/填充等） */
    TRANSFER,               // VK_PIPELINE_STAGE_TRANSFER_BIT
    /** 管线终点（仅作依赖末端，无实际访问语义） */
    BOTTOM_OF_PIPE,         // VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
    /** 主机端（CPU）阶段 */
    HOST,                   // VK_PIPELINE_STAGE_HOST_BIT
    /** 全部图形管线阶段 */
    ALL_GRAPHICS,           // VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT
    /** 全部命令阶段（含图形与计算） */
    ALL_COMMANDS;           // VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
}
