package com.javelin.rhi.types;

/**
 * 纹理类型。
 * 对照 VkImageType。
 */
public enum TextureType {
    TYPE_1D,        // VK_IMAGE_TYPE_1D
    TYPE_2D,        // VK_IMAGE_TYPE_2D
    TYPE_3D,        // VK_IMAGE_TYPE_3D
    CUBE,           // VK_IMAGE_TYPE_2D + VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT
    CUBE_ARRAY;     // VK_IMAGE_TYPE_2D + CUBE + ARRAY
}
