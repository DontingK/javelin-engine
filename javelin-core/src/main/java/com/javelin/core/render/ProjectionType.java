package com.javelin.core.render;

/**
 * 投影类型（engine2 同名枚举移植，2026-09-26）。
 * 对照相机矩阵生成：透视 perspective / 正交 ortho。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum ProjectionType {
    /** 透视投影：fovY + aspect + near/far（3D 场景默认） */
    PERSPECTIVE,
    /** 正交投影：orthographicSize（半高） + aspect + near/far（2D/UI/编辑器辅助用） */
    ORTHOGRAPHIC
}
