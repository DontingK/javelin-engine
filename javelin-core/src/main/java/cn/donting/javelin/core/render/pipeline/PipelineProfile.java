package cn.donting.javelin.core.render.pipeline;

/**
 * 管线档案（Engine_RenderPipeline_Design.md 6.3 / §8 步骤二）：
 * 决定默认管线跑纯前向还是混合（Deferred 不透明 + Forward 透明）两档，
 * 两档共用同一套光照/cluster 数据（Engine_Lighting_Design §9 批次④-B）。
 */
public enum PipelineProfile {
    /** 纯前向档：不透明与透明都走 Forward（无 G-Buffer）；MSAA 可开 */
    FORWARD,
    /**
     * 混合档：不透明走 G-Buffer 三张 MRT + 全屏 LightingPass，透明仍走 Forward。
     * <b>强制 1x</b>——MSAA 与 G-Buffer MRT 组合（多采样附件 + resolve）归四期，
     * 选本档时引擎启动期把有效采样档钳成 COUNT_1。
     */
    HYBRID
}
