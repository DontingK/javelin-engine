package cn.donting.javelin.rhi.types;

/**
 * 缓冲用途。
 * 对照 VkBufferUsageFlags，按位组合使用。
 */
public enum BufferUsage {
    /** 可作为拷贝源 */
    TRANSFER_SRC,           // VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    /** 可作为拷贝目标 */
    TRANSFER_DST,           // VK_BUFFER_USAGE_TRANSFER_DST_BIT
    /** 可建 uniform texel 缓冲视图 */
    UNIFORM_TEXEL,          // VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT
    /** 可建 storage texel 缓冲视图（可写） */
    STORAGE_TEXEL,          // VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT
    /** 可绑定为 UBO */
    UNIFORM,                // VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
    /** 可绑定为 SSBO（可读写） */
    STORAGE,                // VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    /** 可作为索引缓冲 */
    INDEX,                  // VK_BUFFER_USAGE_INDEX_BUFFER_BIT
    /** 可作为顶点缓冲 */
    VERTEX,                 // VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
    /** 可作为间接绘制参数来源 */
    INDIRECT,               // VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
    /** 可取设备地址（BDA，光追 AS 实例/顶点引用需要；P4-06 B1） */
    SHADER_DEVICE_ADDRESS,  // VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
    /** 可作为光追加速结构存储 */
    ACCELERATION_STRUCTURE_STORAGE,          // ..._ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
    /** 可作为 AS 构建输入（顶点/索引/实例数据只读） */
    ACCELERATION_STRUCTURE_BUILD_INPUT;      // ..._ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
}
