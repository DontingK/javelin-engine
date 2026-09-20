package com.javelin.rhi;

/**
 * RenderGraph 图缓存（18.2）：结构哈希不变时复用编译结果（拓扑序 + 屏障计划）。
 *
 * 语义：
 * <ul>
 *   <li>哈希命中：不重新推导拓扑 / 屏障，仅把当前帧的运行时数据（回调 / 视口 / uniform / 管线绑定）
 *       重绑定到缓存的计划上（18.7-3：不缓存跨帧回调闭包）；</li>
 *   <li>哈希未命中：全量编译；</li>
 *   <li>swapchain recreate 等新纹理句柄天然改变哈希 → 自动失效（18.5 / 23.6-3），
 *       也可显式 {@link #invalidate()}。</li>
 * </ul>
 */
public final class RenderGraphCache {

    private long lastStructureHash = -1;
    private CompiledGraph compiled;

    /**
     * 编译（或复用）渲染图。
     *
     * @param graph 本帧的图构建器（声明与当前帧一致）
     * @return 可执行的编译结果（拓扑序 + 屏障计划已就绪）
     */
    public CompiledGraph compile(RenderGraphBuilder graph) {
        if (graph == null) {
            throw new IllegalArgumentException("RenderGraphBuilder must not be null");
        }
        long hash = graph.structureHash();
        if (compiled != null && hash == lastStructureHash) {
            if (graph instanceof RenderGraphImpl impl) {
                if (impl.declarationPassCount() == compiled.passes().size()) {
                    compiled = impl.rebind(compiled);
                    return compiled;
                }
            }
            // 非本实现或 Pass 数对不上：回退全量编译
        }
        compiled = graph.compile();
        lastStructureHash = hash;
        return compiled;
    }

    /** 当前缓存的编译结果（未编译过为 null） */
    public CompiledGraph cached() {
        return compiled;
    }

    /** 显式失效（swapchain recreate / 调试强制重编译，18.5） */
    public void invalidate() {
        compiled = null;
        lastStructureHash = -1;
    }
}
