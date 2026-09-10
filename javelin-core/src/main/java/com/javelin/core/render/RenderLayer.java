package com.javelin.core.render;

/**
 * 渲染层分类 —— 决定物体的渲染顺序和排序策略。
 *
 * <ul>
 *   <li><b>OPAQUE</b>：不透明物体，按前→后排序（Early-Z 优化）</li>
 *   <li><b>TRANSPARENT</b>：透明物体，按后→前排序（混合正确性）</li>
 *   <li><b>OVERLAY</b>：覆盖层（UI、Gizmo），按提交顺序渲染</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum RenderLayer {
    OPAQUE,
    TRANSPARENT,
    OVERLAY
}
