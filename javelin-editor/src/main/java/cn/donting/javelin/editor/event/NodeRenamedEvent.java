package cn.donting.javelin.editor.event;

import cn.donting.javelin.core.scene.Node;

/**
 * 节点更名事件（scene.nodeRenamed）：Node 名称发生变化后发布。
 *
 * <p>与 {@link SceneChangedEvent}（结构变更，消费方重拉整树）相对，本事件是
 * 属性级定点通知——消费方可只更新对应树行 label，无需整树重拉（结构/属性事件
 * 分类的第一块）。</p>
 *
 * <p>发布点现状（2026-09-30 建类）：写入方未接——rename 命令 / Inspector 改名 /
 * 场景加载回填时发布，随对应任务落地；当前实际发布方 = SceneWatchAppState
 * 快照 diff 检出改名。载荷 = Node 引用（2026-10-18 全链引用化拍板，原 id 值
 * 传递废弃），newName 为变更后的名称。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public final class NodeRenamedEvent implements EditorEvent {

    private final Node node;
    private final String newName;

    /**
     * @param node    更名节点
     * @param newName 变更后名称
     */
    public NodeRenamedEvent(Node node, String newName) {
        this.node = node;
        this.newName = newName;
    }

    /** 更名节点 */
    public Node getNode() {
        return node;
    }

    /** 变更后名称 */
    public String getNewName() {
        return newName;
    }
}
