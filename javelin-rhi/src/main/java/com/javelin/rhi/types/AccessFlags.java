package com.javelin.rhi.types;

/**
 * 访问标志。
 * 对照 VkAccessFlags，按位组合使用。
 * 用于屏障生成。
 */
public enum AccessFlags {
    /** 读取间接绘制命令缓冲 */
    INDIRECT_COMMAND_READ,  // VK_ACCESS_INDIRECT_COMMAND_READ_BIT
    /** 读取索引缓冲 */
    INDEX_READ,             // VK_ACCESS_INDEX_READ_BIT
    /** 读取顶点属性 */
    VERTEX_ATTRIBUTE_READ,  // VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT
    /** 读取 uniform 数据（UBO） */
    UNIFORM_READ,           // VK_ACCESS_UNIFORM_READ_BIT
    /** 片元着色器读取输入附件 */
    INPUT_ATTACHMENT_READ,  // VK_ACCESS_INPUT_ATTACHMENT_READ_BIT
    /** 着色器读取（纹理采样、存储资源读） */
    SHADER_READ,            // VK_ACCESS_SHADER_READ_BIT
    /** 着色器写入（SSBO/存储图像写） */
    SHADER_WRITE,           // VK_ACCESS_SHADER_WRITE_BIT
    /** 读颜色附件（混合/逻辑运算等） */
    COLOR_ATTACHMENT_READ,  // VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
    /** 写颜色附件 */
    COLOR_ATTACHMENT_WRITE, // VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
    /** 读深度/模板附件 */
    DEPTH_STENCIL_ATTACHMENT_READ,  // VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
    /** 写深度/模板附件 */
    DEPTH_STENCIL_ATTACHMENT_WRITE, // VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
    /** 作为传输源被读 */
    TRANSFER_READ,          // VK_ACCESS_TRANSFER_READ_BIT
    /** 作为传输目标被写 */
    TRANSFER_WRITE,         // VK_ACCESS_TRANSFER_WRITE_BIT
    /** 主机读映射内存 */
    HOST_READ,              // VK_ACCESS_HOST_READ_BIT
    /** 主机写映射内存 */
    HOST_WRITE,             // VK_ACCESS_HOST_WRITE_BIT
    /** 任意读（最宽范围，慎用） */
    MEMORY_READ,            // VK_ACCESS_MEMORY_READ_BIT
    /** 任意写（最宽范围，慎用） */
    MEMORY_WRITE;           // VK_ACCESS_MEMORY_WRITE_BIT
}
