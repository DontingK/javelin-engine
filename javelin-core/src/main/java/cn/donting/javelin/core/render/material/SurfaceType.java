package cn.donting.javelin.core.render.material;

/**
 * 材质面型（URP Surface Type 口径，拍板 2026-10-17 方案 B）：
 * 透明/不透明由材质显式裁决，不再从 Shader 前向 Pass 混合态推导。
 *
 * <p>面型决定渲染分桶（Transparent 桶由远及近）与 PSO 固定状态覆盖
 * （RenderingSystem.pipelineFor）：OPAQUE → Blend Off / ZWrite On；
 * TRANSPARENT → Blend SrcAlpha OneMinusSrcAlpha / ZWrite Off。
 * 同一 Shader 可服务两种面型（材质实例各说各话）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum SurfaceType {
    /** 不透明：Blend Off / ZWrite On（默认） */
    OPAQUE,
    /** 透明：Blend SrcAlpha OneMinusSrcAlpha / ZWrite Off，进透明桶由远及近排序 */
    TRANSPARENT
}
