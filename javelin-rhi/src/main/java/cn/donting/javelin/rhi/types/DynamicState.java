package cn.donting.javelin.rhi.types;

/**
 * 动态状态。
 * 对照 VkDynamicState。
 */
public enum DynamicState {
    /** 视口动态化（绘制前由命令缓冲设置） */
    VIEWPORT,               // VK_DYNAMIC_STATE_VIEWPORT
    /** 裁剪矩形动态化 */
    SCISSOR,                // VK_DYNAMIC_STATE_SCISSOR
    /** 线宽动态化 */
    LINE_WIDTH,             // VK_DYNAMIC_STATE_LINE_WIDTH
    /** 深度偏移参数动态化 */
    DEPTH_BIAS,             // VK_DYNAMIC_STATE_DEPTH_BIAS
    /** 混合常量颜色动态化 */
    BLEND_CONSTANTS,        // VK_DYNAMIC_STATE_BLEND_CONSTANTS
    /** 深度边界测试范围动态化 */
    DEPTH_BOUNDS,           // VK_DYNAMIC_STATE_DEPTH_BOUNDS
    /** 模板比较掩码动态化 */
    STENCIL_COMPARE_MASK,   // VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK
    /** 模板写入掩码动态化 */
    STENCIL_WRITE_MASK,     // VK_DYNAMIC_STATE_STENCIL_WRITE_MASK
    /** 模板参考值动态化 */
    STENCIL_REFERENCE,      // VK_DYNAMIC_STATE_STENCIL_REFERENCE
    /** 剔除模式动态化（扩展动态状态） */
    CULL_MODE,              // VK_DYNAMIC_STATE_CULL_MODE
    /** 正面绕序动态化（扩展动态状态） */
    FRONT_FACE,             // VK_DYNAMIC_STATE_FRONT_FACE
    /** 图元拓扑动态化（扩展动态状态） */
    PRIMITIVE_TOPOLOGY,     // VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY
    /** 视口数组连同数量一起动态化（扩展动态状态） */
    VIEWPORT_WITH_COUNT,    // VK_DYNAMIC_STATE_VIEWPORT_WITH_COUNT
    /** 裁剪矩形数组连同数量一起动态化（扩展动态状态） */
    SCISSOR_WITH_COUNT,     // VK_DYNAMIC_STATE_SCISSOR_WITH_COUNT
    /** 顶点绑定步长动态化（扩展动态状态，绑定顶点缓冲时给） */
    VERTEX_INPUT_BINDING_STRIDE, // VK_DYNAMIC_STATE_VERTEX_INPUT_BINDING_STRIDE
    /** 深度测试开关动态化（扩展动态状态） */
    DEPTH_TEST_ENABLE,      // VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE
    /** 深度写入开关动态化（扩展动态状态） */
    DEPTH_WRITE_ENABLE,     // VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE
    /** 深度比较操作动态化（扩展动态状态） */
    DEPTH_COMPARE_OP,       // VK_DYNAMIC_STATE_DEPTH_COMPARE_OP
    /** 深度边界测试开关动态化（扩展动态状态） */
    DEPTH_BOUNDS_TEST_ENABLE, // VK_DYNAMIC_STATE_DEPTH_BOUNDS_TEST_ENABLE
    /** 模板测试开关动态化（扩展动态状态） */
    STENCIL_TEST_ENABLE,    // VK_DYNAMIC_STATE_STENCIL_TEST_ENABLE
    /** 模板操作动态化（扩展动态状态） */
    STENCIL_OP,             // VK_DYNAMIC_STATE_STENCIL_OP
    /** 光栅化丢弃开关动态化（扩展动态状态） */
    RASTERIZER_DISCARD_ENABLE, // VK_DYNAMIC_STATE_RASTERIZER_DISCARD_ENABLE
    /** 深度偏移开关动态化（扩展动态状态） */
    DEPTH_BIAS_ENABLE,      // VK_DYNAMIC_STATE_DEPTH_BIAS_ENABLE
    /** 图元重启开关动态化（扩展动态状态） */
    PRIMITIVE_RESTART_ENABLE; // VK_DYNAMIC_STATE_PRIMITIVE_RESTART_ENABLE
}
