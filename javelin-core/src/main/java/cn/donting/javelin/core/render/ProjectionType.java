package cn.donting.javelin.core.render;

import cn.donting.javelin.core.inspector.InsEnumLabel;

/**
 * 投影类型（engine2 同名枚举移植，2026-09-26）。
 * 对照相机矩阵生成：透视 perspective / 正交 ortho。
 * 实现 {@link InsEnumLabel} 为 Inspector 下拉提供显示名（Unity 简中口径）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum ProjectionType implements InsEnumLabel {
    /** 透视投影：fovY + aspect + near/far（3D 场景默认） */
    PERSPECTIVE("ins.enum.projection.perspective"),
    /** 正交投影：orthographicSize（半高） + aspect + near/far（2D/UI/编辑器辅助用） */
    ORTHOGRAPHIC("ins.enum.projection.orthographic");

    /** Inspector 下拉显示名（Unity 简中口径） */
    private final String label;

    ProjectionType(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
