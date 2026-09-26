package com.javelin.rhi.types;

/**
 * 纹理类型。
 * 对照 VkImageType。
 */
public enum TextureType {
    /** 一维纹理 */
    TYPE_1D,        // VK_IMAGE_TYPE_1D
    /** 二维纹理（最常用） */
    TYPE_2D,        // VK_IMAGE_TYPE_2D
    /** 三维纹理（体数据） */
    TYPE_3D,        // VK_IMAGE_TYPE_3D
    /** 立方体贴图（6 面天空盒） */
    CUBE,           // VK_IMAGE_TYPE_2D + VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT
    /** 立方体贴图数组（多天空盒） */
    CUBE_ARRAY;     // VK_IMAGE_TYPE_2D + CUBE + ARRAY
}
