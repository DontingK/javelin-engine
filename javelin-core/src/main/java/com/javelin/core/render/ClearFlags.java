package com.javelin.core.render;

/**
 * 相机清屏标志（对照 Unity Camera.clearFlags，用户拍板 2026-09-26 落地）。
 *
 * <p>决定本视口渲染前对颜色/深度附件的清屏策略（loadOp）。
 * Unity 的 Skybox 选项待天空盒系统落地后再入枚举（v0.1 无天空盒，不瞎猜行为）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum ClearFlags {
    /** 清颜色（相机背景色/管线默认色）+ 清深度——常规视口（默认） */
    SOLID_COLOR,
    /** 只清深度，颜色保留已有画面——叠加相机合成（全屏 UI/Gizmo 层，配合 depth 排序） */
    DEPTH_ONLY,
    /** 颜色与深度都保留（LOAD）——残影类特效；附件从未被清过时内容未定义，慎用 */
    DONT_CLEAR
}
