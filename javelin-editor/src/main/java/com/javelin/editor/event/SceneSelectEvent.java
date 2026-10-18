package com.javelin.editor.event;

import com.javelin.core.scene.Node;

/**
 * 场景选中变更事件（scene.select）：{@link com.javelin.editor.selection.EditorSelection}
 * 在 setSelectedNode 收口发布——选中上报、创建命令自动选中、将来的视口拾取都过
 * 该 setter，一处发布全覆盖。消费方：变换 Gizmo 跟随（TransformGizmoAppState 订阅）、
 * Transform 变更检测基线重建（TransformWatchAppState）、M2 层级面板高亮等。
 *
 * <p>载荷 = Node 引用（2026-10-18 全链引用化拍板：桥时代「id 值 + ObjectCache
 * 弱引用反查」的句柄链废弃——弱隔离早已名存实亡，引擎侧消费方收到事件即强引用
 * 长持，且已删检测实际一直靠消费方 getScene 归属校验兜底）。null = 清除选中。
 * 发布方可在任意线程，Swing 消费方自行 EDT hop（Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public final class SceneSelectEvent implements EditorEvent {

    /** 选中节点（null = 清除选中） */
    private final Node node;

    /**
     * @param node 选中节点，null = 清除选中
     */
    public SceneSelectEvent(Node node) {
        this.node = node;
    }

    /** 选中节点（null = 清除选中） */
    public Node getNode() {
        return node;
    }
}
