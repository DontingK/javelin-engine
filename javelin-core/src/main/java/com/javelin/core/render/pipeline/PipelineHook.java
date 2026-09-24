package com.javelin.core.render.pipeline;

/**
 * 管线钩子点（Engine_RenderPipeline_Design.md 6.2，对齐 RHI 14.3）。
 *
 * <p>钩子注入的 Pass 属于图结构 → 注入/移除变化 = 图缓存 structureHash 正常失效（RHI 18.5）。
 * v0.1 Forward 形态下，GBuffer/Lighting 相关钩子在对应 Pass 未实现时于等价位置触发
 * （POST_GBUFFER = 不透明完成后、POST_LIGHTING = 透明前），语义以位置为准。
 */
public enum PipelineHook {
    PRE_SHADOW,
    PRE_GBUFFER,
    POST_GBUFFER,
    POST_LIGHTING,
    PRE_TRANSPARENT,
    POST_TRANSPARENT,
    PRE_POST_PROCESS,
    PRE_COMPOSITE
}
