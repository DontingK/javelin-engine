package com.javelin.core.render;

import com.javelin.rhi.api.Device;

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

    /**
     * 渲染前准备 —— 传入 Device 和 DescriptorPoolManager，供实现类按需构建 GPU 资源。
     *
     * <p>在 {@link #collect} 之前由渲染管线调用，实现类可在此方法中构建 Pipeline、
     * 创建 Buffer、分配 DescriptorSet 等。</p>
     *
     * @param device                逻辑设备
     * @param descriptorPoolManager 描述符池管理器
     */
    void prepare(Device device, com.javelin.core.render.DescriptorPoolManager descriptorPoolManager);
}
