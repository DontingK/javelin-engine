package com.javelin.editor.debug;

import com.javelin.core.scene.Component;
import com.javelin.core.scene.Node;

/**
 * 组件调试线绘制器（Editor_DebugLine_Design §4）：按组件类型注册到
 * {@link DebugLineRegistry}，选中节点时由 {@link DebugLineAppState} 查表分派——
 * 新增组件类型零改消费方（Inspector 注册器同模式）。
 *
 * <p>实现契约：世界端点在本方法内算好（本地几何 × node 世界矩阵）并提交
 * {@link DebugDrawLine}；实现方保持无编辑器状态依赖（CameraDebugLine 的
 * aspect 注入是唯一例外），便于将来运行时复用。</p>
 *
 * @param <T> 绘制的组件类型
 * @author javelin
 * @create 2026/10/23
 */
public interface ComponentDebugLine<T extends Component> {

    /**
     * 为选中节点的该组件提交调试线（世界空间端点）。
     *
     * @param component 目标组件
     * @param node      组件宿主节点（世界矩阵来源；显式传参免反向导航）
     */
    void drawDebugLine(T component, Node node);
}
