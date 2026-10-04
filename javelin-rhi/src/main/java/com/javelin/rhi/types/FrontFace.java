package com.javelin.rhi.types;

/**
 * 正面绕序。
 * 对照 VkFrontFace。
 *
 * 重要（2026-10-04 实测定论）：场景管线 frontFace = COUNTER_CLOCKWISE（GL 等效）——
 * 投影 Y 翻转（GL NDC → Vulkan NDC）与 Vulkan 窗口 y 向下映射是两次镜像、相互
 * 抵消，管线行为同 OpenGL：CCW 资产从几何法向侧可见（Cull Back）。
 * 曾误注「引擎约定 CW、Y 翻转后 CCW 资产呈 CW」——漏算窗口 y 向下的第二次
 * 镜像，该推理链错误勿再采信（Vulkan_Pitfalls 存档）。
 */
public enum FrontFace {
    /** 逆时针绕序视为正面（资产常规建模绕序；场景管线实配） */
    COUNTER_CLOCKWISE,      // VK_FRONT_FACE_COUNTER_CLOCKWISE
    /** 顺时针绕序视为正面 */
    CLOCKWISE;              // VK_FRONT_FACE_CLOCKWISE
}
