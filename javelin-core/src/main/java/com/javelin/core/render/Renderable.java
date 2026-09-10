package com.javelin.core.render;

import java.util.List;

/**
 * 可渲染对象 —— 能向渲染队列提交绘制命令的标记接口。
 *
 * <p>实现类负责将自身转换为 {@link RenderCommand} 列表。
 * {@link RenderPass} 遍历场景时调用 {@link #collect} 收集命令。</p>
 *
 * <p><strong>职责边界</strong>：
 * <ul>
 *   <li>实现类<strong>只负责生成 RenderCommand</strong>（如 Mesh → SubMesh → Pass 展开）</li>
 *   <li>视锥剔除、Layer 剔除、排序由 {@link RenderList} 统一处理</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public interface Renderable {

    /**
     * 收集本对象的渲染命令。
     *
     * <p>实现类在此方法中只负责生成 {@link RenderCommand} 并添加到 {@code outCommands}。
     * 不应执行任何剔除决策（视锥、Layer 等）。</p>
     *
     * @param outCommands 输出命令列表
     */
    void collect(List<RenderCommand> outCommands);
}
