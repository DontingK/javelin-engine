package cn.donting.javelin.rhi.types;

/**
 * 描述符类型。
 * 对照 VkDescriptorType。
 */
public enum DescriptorType {
    /** 独立采样器 */
    SAMPLER,                // VK_DESCRIPTOR_TYPE_SAMPLER
    /** 纹理+采样器组合绑定 */
    COMBINED_IMAGE_SAMPLER, // VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
    /** 可采样纹理（不含采样器） */
    SAMPLED_IMAGE,          // VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
    /** 可读写存储图像 */
    STORAGE_IMAGE,          // VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
    /** uniform texel 缓冲视图（只读） */
    UNIFORM_TEXEL_BUFFER,   // VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER
    /** storage texel 缓冲视图（可写） */
    STORAGE_TEXEL_BUFFER,   // VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER
    /** UBO：只读统一缓冲块 */
    UNIFORM_BUFFER,         // VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
    /** SSBO：可读写存储缓冲块 */
    STORAGE_BUFFER,         // VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
    /** UBO + 动态偏移（绑定偏移绘制时动态指定，UBO1 共享缓冲用） */
    UNIFORM_BUFFER_DYNAMIC, // VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC
    /** SSBO + 动态偏移 */
    STORAGE_BUFFER_DYNAMIC, // VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC
    /** 输入附件（片元着色器内读本 Pass 附件） */
    INPUT_ATTACHMENT;       // VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT
}
