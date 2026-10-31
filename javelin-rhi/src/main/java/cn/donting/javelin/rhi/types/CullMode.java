package cn.donting.javelin.rhi.types;

/**
 * 面剔除模式。
 * 对照 VkCullModeFlags。
 */
public enum CullMode {
    /** 不剔除任何面（双面绘制） */
    NONE,                   // VK_CULL_MODE_NONE
    /** 剔除正面三角形 */
    FRONT,                  // VK_CULL_MODE_FRONT_BIT
    /** 剔除背面三角形（封闭网格最常用） */
    BACK,                   // VK_CULL_MODE_BACK_BIT
    /** 正反面全剔除（不产生片元，阴影体等技巧用） */
    FRONT_AND_BACK;         // VK_CULL_MODE_FRONT_AND_BACK
}
