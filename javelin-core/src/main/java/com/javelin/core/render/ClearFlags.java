package com.javelin.core.render;

import com.javelin.core.inspector.InsEnumLabel;

/**
 * 相机清屏标志（对照 Unity Camera.clearFlags，用户拍板 2026-09-26 落地）。
 *
 * <p>决定本视口渲染前对颜色/深度附件的清屏策略（loadOp）。
 * Unity 的 Skybox 选项待天空盒系统落地后再入枚举（v0.1 无天空盒，不瞎猜行为）。</p>
 * 实现 {@link InsEnumLabel} 为 Inspector 下拉提供显示名——口径=自述清屏行为
 * （用户拍板 2026-10-11：SOLID_COLOR 不沿用 Unity 直译「纯色」，作为清屏策略
 * 名不自述行为；Skybox 入列后集合=天空盒/颜色+深度/仅深度/不清除）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum ClearFlags implements InsEnumLabel {
    /** 清颜色（相机背景色/管线默认色）+ 清深度——常规视口（默认） */
    SOLID_COLOR("ins.enum.clearFlags.solidColor"),
    /** 只清深度，颜色保留已有画面——叠加相机合成（全屏 UI/Gizmo 层，配合 depth 排序） */
    DEPTH_ONLY("ins.enum.clearFlags.depthOnly"),
    /** 颜色与深度都保留（LOAD）——残影类特效；附件从未被清过时内容未定义，慎用 */
    DONT_CLEAR("ins.enum.clearFlags.dontClear");

    /** Inspector 下拉显示名（自述清屏行为，见类注释） */
    private final String label;

    ClearFlags(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
