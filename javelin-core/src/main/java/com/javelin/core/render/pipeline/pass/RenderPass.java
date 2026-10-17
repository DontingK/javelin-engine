package com.javelin.core.render.pipeline.pass;

import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.rhi.RenderGraphBuilder;

/**
 * 引擎侧渲染管线 Pass（Engine_RenderPipeline_Design.md 第 4 节「Pass 逐个设计」）。
 *
 * <p>管线 = 有序 Pass 列表（编排见 DefaultHybridRenderPipeline）；Pass 负责
 * 向图声明附件/视口/回调（addToGraph，构建期），渲染回调内只做绘制调用，
 * 不碰屏障/布局（RHI 22.4 迁移纪律）。
 */
public interface RenderPass {

    /** 前向主通道消费的 shader Pass 名（严格制，拍板 2026-10-17：Pass 名即阶段归属） */
    String FORWARD_PASS_NAME = "Forward";

    /** Pass 名（语义标识；图中实际 Pass 名由管线拼 view 前缀，图内唯一） */
    String name();

    /**
     * 本管线 Pass 消费的 shader Pass 名——PSO 取用与 RenderList 自定义桶的
     * 查找键（块名即渲染阶段名，RHI 20.2）。默认前向主通道；Gizmo 等专用
     * Pass 覆盖返回自己的名字。
     */
    default String shaderPassName() {
        return FORWARD_PASS_NAME;
    }

    /** 为本视口向图追加本 Pass：附件声明 + viewport/scissor + 渲染回调 */
    void addToGraph(RenderGraphBuilder graph, PipelineContext ctx);

}
