package cn.donting.javelin.core.render.pipeline;

/**
 * 渲染管线形态（Engine_RenderPipeline_Design.md 6.3）：启动期选哪个管线实现类
 * ——纯前向（{@link ForwardRenderPipeline}）还是混合（{@link DefaultHybridRenderPipeline}，
 * Deferred 不透明 + Forward 透明）；两实现共用同一套光照/cluster 数据
 * （Engine_Lighting_Design §9 批次④-B）。运行期不可切（2026-11-13 拆分）。
 */
public enum PipelineProfile {
    /** 纯前向：不透明与透明都走 Forward（无 G-Buffer） */
    FORWARD,
    /** 混合：不透明走 G-Buffer 三张 MRT + 全屏 LightingPass，透明仍走 Forward */
    HYBRID
}
