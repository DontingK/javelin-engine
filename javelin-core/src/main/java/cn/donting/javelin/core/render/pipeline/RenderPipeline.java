package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.rhi.RenderGraphBuilder;

/**
 * 引擎侧渲染管线（Engine_RenderPipeline_Design.md 6.1；模式 B 接管点，RHI 14.1）。
 *
 * <p>每视口调用一次 buildGraph；实现只准做 Pass 声明 + 回调录制，
 * 不碰屏障/布局（RHI 22.4 迁移纪律）。
 */
public interface RenderPipeline {

    /** 管线档案（主视口完整形态 / 预览视口简化形态） */
    PipelineProfile profile();

    /** 为本视口向图追加 Pass 链 */
    void buildGraph(RenderGraphBuilder graph, PipelineContext ctx);
    void addPass(RenderPass pass);
    void addPass(RenderPass pass,String afterPass);

    /**
     * 移除 Pass（设计 6.2「增删/插入」之删；列表组成变化 = 图缓存 structureHash
     * 正常失效，同 addPass）。
     */
    void removePass(RenderPass pass);
}
