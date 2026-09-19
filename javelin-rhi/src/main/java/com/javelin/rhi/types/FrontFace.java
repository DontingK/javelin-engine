package com.javelin.rhi.types;

/**
 * 正面绕序。
 * 对照 VkFrontFace。
 *
 * 重要：Vulkan NDC Y 轴向下，投影矩阵 Y 翻转后，
 * 引擎约定 frontFace = CLOCKWISE（CW），资产保持 CCW 不变。
 * 见设计文档第 5 节坐标约定。
 */
public enum FrontFace {
    COUNTER_CLOCKWISE,      // VK_FRONT_FACE_COUNTER_CLOCKWISE
    CLOCKWISE;              // VK_FRONT_FACE_CLOCKWISE
}
