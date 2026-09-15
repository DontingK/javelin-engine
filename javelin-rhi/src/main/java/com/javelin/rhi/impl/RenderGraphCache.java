package com.javelin.rhi.impl;

/**
 * 渲染图缓存，用于复用已编译的渲染图结果。
 * <p>
 * 当渲染图结构未发生变化时，直接返回上次编译的结果，避免重复编译带来的开销。
 */
public class RenderGraphCache {

    /** 上次渲染图结构的哈希值 */
    private long lastStructureHash = -1;
    /** 上次编译完成的渲染图对象 */
    private VkRenderGraphImpl.CompiledGraph compiled;

    /**
     * 编译渲染图，若结构未变化则返回缓存结果。
     *
     * @param graph 待编译的渲染图
     * @return 编译后的渲染图对象
     */
    public VkRenderGraphImpl.CompiledGraph compile(VkRenderGraphImpl graph) {
        long hash = structureHash(graph);
        if (hash == lastStructureHash && compiled != null) {
            return compiled;
        }
        compiled = graph.compile();
        lastStructureHash = hash;
        return compiled;
    }

    /**
     * 计算渲染图结构的哈希值，用于判断是否需要重新编译。
     *
     * @param graph 渲染图对象
     * @return 结构哈希值
     */
    private long structureHash(VkRenderGraphImpl graph) {
        // TODO: Deterministic hash of pass types, resource declarations, loadOps
        return 0;
    }
}
