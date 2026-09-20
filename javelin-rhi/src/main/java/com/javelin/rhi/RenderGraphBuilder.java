package com.javelin.rhi;

import java.util.function.Consumer;

/**
 * RenderGraph 构建器（声明 + 编译 + 执行）。
 * 对照设计文档第 26.5 节 / 18 节（图缓存）/ 24.3 节（执行器）。
 *
 * 典型用法（每帧）：
 * <pre>{@code
 * RenderGraphBuilder graph = RHI.beginRenderGraph();
 * graph.addGraphicsPass("GBuffer", pass -> { ... });
 * graph.addGraphicsPass("Lighting", pass -> { ... });
 * CompiledGraph compiled = graph.compile();
 * compiled / graph.execute(cmd, swapChain.image(idx));
 * }</pre>
 */
public interface RenderGraphBuilder {

    /** 新增图形 Pass：回调内声明读写意图与渲染回调 */
    GraphicsPassBuilder addGraphicsPass(String name, Consumer<PassDef> def);

    /** 新增计算 Pass：回调内声明读写意图与渲染回调 */
    ComputePassBuilder addComputePass(String name, Consumer<PassDef> def);

    /**
     * 图结构哈希（18.2）：Pass 类型 / 资源读写声明 / LoadOp / 管线绑定。
     * 不含运行时数据（相机矩阵、清屏值、视口、回调闭包）。
     */
    long structureHash();

    /** 全量编译：拓扑排序 + 依赖推导 + 屏障计划（18 节） */
    CompiledGraph compile();

    /**
     * 执行器（24.3）：cmd.begin → 按拓扑序插入屏障 + 录制各 Pass 回调 → cmd.end。
     * backbuffer 非空时：起始布局按 PRESENT_SRC 跟踪，图尾自动转回 PRESENT_SRC。
     */
    void execute(CommandBuffer cmd, Texture backbuffer);
}
