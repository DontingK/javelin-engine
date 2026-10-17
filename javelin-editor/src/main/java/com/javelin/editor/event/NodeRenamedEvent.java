package com.javelin.editor.event;

/**
 * 节点更名事件（scene.nodeRenamed）：Node 名称发生变化后发布。
 *
 * <p>与 {@link SceneChangedEvent}（结构变更，消费方重拉整树）相对，本事件是
 * 属性级定点通知——消费方可只更新对应树行 label，无需整树重拉（结构/属性事件
 * 分类的第一块）。</p>
 *
 * <p>发布点现状（2026-09-30）：写入方未接——rename 命令 / Inspector 改名 /
 * 场景加载回填时发布，随对应任务落地；本类先行建好。node 以 id 值传递
 * （{@code ObjectCache} 弱引用反查），newName 为变更后的名称。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public final class NodeRenamedEvent implements EditorEvent {

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

    /** 更名节点 id */
    public int getNodeId() {
        return nodeId;
    }

    /** 变更后名称 */
    public String getNewName() {
        return newName;
    }
}
