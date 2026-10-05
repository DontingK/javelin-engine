package com.javelin.editor.event;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.scene.Node;

import java.util.Collections;
import java.util.Map;

/**
 * 场景选中变更事件（scene.select）：{@link com.javelin.editor.selection.EditorSelection}
 * 在 setSelectedNodeId 收口发布——/scene/select 桥上报、创建命令自动选中、将来的
 * 视口拾取都过该 setter，一处发布全覆盖。消费方：变换 Gizmo 跟随
 * （TransformGizmoAppState 订阅）等。
 *
 * <p>payload 纯数据契约：node 以 id 值传递（{@code ObjectCache} 反查），
 * null = 清除选中。跨桥事件时序契约见 {@link BridgeEvent}。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public final class SceneSelectEvent implements BridgeEvent {

    /** 选中节点 id（null = 清除选中） */
    private final Integer nodeId;

    /**
     * @param nodeId 选中节点 id，null = 清除选中
     */
    public SceneSelectEvent(Integer nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public String bridgeType() {
        return "scene.select";
    }

    @Override
    public Object bridgePayload() {
        // singletonMap 容忍 null 值（Map.of 不容忍）——键恒为 nodeId，清除选中传 null
        return Collections.singletonMap("nodeId", nodeId);
    }

    /**
     * 选中节点（弱引用反查；无选中或对象已回收返回 null）。
     */
    public Node getNode() {
        return nodeId != null ? ObjectCache.get(nodeId) : null;
    }
}
