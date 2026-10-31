package cn.donting.javelin.core.render.pipeline;

/**
 * 管线档案（Engine_RenderPipeline_Design.md 6.3）：
 * 主视口用完整目标管线，编辑器预览等可用简化形态（少 RT）。
 */
public enum PipelineProfile {
    /** v0.1 落地形态：Forward 不透明 + Forward 透明（无阴影/后处理） */
    FORWARD,
    /** 目标态：默认混合管线（Deferred 不透明 + Forward 透明 + 后处理链） */
    HYBRID
}
