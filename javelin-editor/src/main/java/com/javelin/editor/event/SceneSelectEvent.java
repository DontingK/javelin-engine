package com.javelin.editor.event;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.scene.Node;

/**
 * 场景选中变更事件（scene.select）：{@link com.javelin.editor.selection.EditorSelection}
 * 在 setSelectedNodeId 收口发布——选中上报、创建命令自动选中、将来的视口拾取都过
 * 该 setter，一处发布全覆盖。消费方：变换 Gizmo 跟随（TransformGizmoAppState 订阅）、
 * M2 层级面板高亮等。
 *
 * <p>node 以 id 值传递（{@code ObjectCache} 弱引用反查），null = 清除选中。
 * 发布方在引擎线程，Swing 消费方自行 EDT hop（Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public final class SceneSelectEvent implements EditorEvent {

    /** 选中节点 id（null = 清除选中） */
    private final Integer nodeId;

    /**
     * @param nodeId 选中节点 id，null = 清除选中
     */
    public SceneSelectEvent(Integer nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * 选中节点（弱引用反查；无选中或对象已回收返回 null）。
     */
    public Node getNode() {
        return nodeId != null ? ObjectCache.get(nodeId) : null;
    }
}
