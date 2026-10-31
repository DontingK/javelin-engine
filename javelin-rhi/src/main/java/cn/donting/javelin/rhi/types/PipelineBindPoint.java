package cn.donting.javelin.rhi.types;

/**
 * 管线绑定点。
 * 对照 VkPipelineBindPoint。
 */
public enum PipelineBindPoint {
    /** 图形管线 */
    GRAPHICS,               // VK_PIPELINE_BIND_POINT_GRAPHICS
    /** 计算管线 */
    COMPUTE;                // VK_PIPELINE_BIND_POINT_COMPUTE
}
