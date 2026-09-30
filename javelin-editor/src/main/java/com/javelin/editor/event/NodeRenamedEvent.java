package com.javelin.editor.event;

import java.util.Map;

/**
 * 节点更名事件（scene.nodeRenamed）：Node 名称发生变化后发布。
 *
 * <p>与 {@link SceneChangedEvent}（结构变更，前端重拉整树）相对，本事件是
 * 属性级定点通知——消费方可只更新对应树行 label，无需整树重拉
 * （结构/属性事件分类的第一块，见场景树对接档「事件分类」预留）。</p>
 *
 * <p>发布点现状（2026-09-30）：写入方未接——rename 命令 / Inspector 改名 /
 * 场景加载回填时发布，随对应任务落地；本类先行建好。
 * payload 纯数据契约：node 以 id 值传递（{@code ObjectCache} 反查），
 * newName 为变更后的名称。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public final class NodeRenamedEvent implements BridgeEvent {

    private final int nodeId;
    private final String newName;

    /**
     * @param nodeId  更名节点 id
     * @param newName 变更后名称
     */
    public NodeRenamedEvent(int nodeId, String newName) {
        this.nodeId = nodeId;
        this.newName = newName;
    }

    @Override
    public String bridgeType() {
        return "scene.nodeRenamed";
    }

    @Override
    public Object bridgePayload() {
        return Map.of("nodeId", nodeId, "name", newName);
    }
}
