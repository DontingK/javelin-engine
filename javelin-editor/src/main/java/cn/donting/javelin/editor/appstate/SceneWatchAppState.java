package cn.donting.javelin.editor.appstate;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Tag;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.NodeRenamedEvent;
import cn.donting.javelin.editor.event.SceneChangedEvent;
import cn.donting.javelin.editor.scene.EditorScene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 场景变更检测状态（快照 diff 模型，用户拍板 2026-09-30）。
 *
 * <p>替代「写入方显式发通知」：写入方（命令/Inspector/脚本/引擎侧）只管改场景，
 * 本状态按节流周期对用户场景做单次 DFS 快照（node 引用 + name + 有序 childIds
 * + layer/tags——快照仅在引擎线程内 diff 用，不跨线程外泄；2026-10-18 引用化
 * 拍板后持引用无虞）与上次快照 diff，自动发现变更并发布
 * ——「漏发通知 = 树静默不刷新」的纪律 bug 从机制上消灭，core 零侵入。
 * 单次 DFS 全收集替代分层递进检查：数量没变结构也可能变（挪动），且比较成本
 * 远小于遍历成本，分层短路只在「完全没变」的帧省比较、不省遍历。</p>
 *
 * <p>事件分工：结构变更（节点增删/挪动/重排）与图层/标签变更发 {@link SceneChangedEvent}
 * （前端重拉整树——layer/tags 树显示无变化，重拉仅为星标/VO 对齐，与保存路径
 * scene.changed(false) 同口径）；名称变更发 {@link NodeRenamedEvent}（定点刷新，不重拉）。
 * 任何变更置 {@link EditorScene} 脏标记——现状不区分用户编辑与程序性变更，
 * 将来运行时脚本接入后按需收窄（dirty 归用户编辑命令、通知归本检测器）。</p>
 *
 * <p>基线静默：场景对象首次就绪/切换（引用变化）时只建快照不发事件——
 * 场景加载本身不算变更。active 变化暂不检测（树灰显落地时快照加字段）。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public class SceneWatchAppState extends AbsEditorAppState {

    /** 检测周期（秒）：树刷新无需帧级实时，命令反馈 ≤0.25s 感知足够 */
    private static final float CHECK_INTERVAL_SECONDS = 0.25f;

    /** 当前基线场景（引用变化 = 场景切换/首次就绪，静默重建快照） */
    private GameScene watchedScene;
    /** 上次快照 */
    private Map<Integer, NodeSnap> lastSnapshot = new HashMap<>();

    private float elapsed;

    @Override
    public void update(float tpf) {
        elapsed += tpf;
        if (elapsed < CHECK_INTERVAL_SECONDS) {
            return;
        }
        elapsed = 0f;
        GameScene gameScene = javelinEditor.getEditorScene().getGameScene();
        if (gameScene != watchedScene) {
            // 场景切换/首次就绪：静默建基线（加载不算变更）
            watchedScene = gameScene;
            lastSnapshot = collectSnapshot(gameScene);
            return;
        }
        Map<Integer, NodeSnap> current = collectSnapshot(gameScene);
        diff(lastSnapshot, current);
        lastSnapshot = current;
    }

    /** 场景快照（gameScene 为 null 时空快照，与未就绪态保持基线一致） */
    private static Map<Integer, NodeSnap> collectSnapshot(GameScene gameScene) {
        Map<Integer, NodeSnap> snapshot = new HashMap<>();
        if (gameScene != null) {
            for (Node root : gameScene.getRootNodes()) {
                collectNode(root, snapshot);
            }
        }
        return snapshot;
    }

    private static void collectNode(Node node, Map<Integer, NodeSnap> out) {
        List<Integer> childIds = new ArrayList<>();
        for (Transform child : node.getTransform()) {
            childIds.add(child.getNode().getId());
        }
        out.put(node.getId(), new NodeSnap(node, node.getName(), childIds, layerOf(node), tagNames(node)));
        for (Transform child : node.getTransform()) {
            collectNode(child.getNode(), out);
        }
    }

    /** layer 索引（null 兜底 0，与 Inspector 收集口径一致） */
    private static int layerOf(Node node) {
        return node.getLayer() != null ? node.getLayer().getValue() : 0;
    }

    /** tag 名列表（null 元素剔除；List.equals 内容判等，顺序变动误报=多一次重拉，无害） */
    private static List<String> tagNames(Node node) {
        List<String> names = new ArrayList<>();
        if (node.getTags() != null) {
            for (Tag tag : node.getTags()) {
                if (tag != null && tag.getName() != null) {
                    names.add(tag.getName());
                }
            }
        }
        return names;
    }

    /** diff 并发布：key 集合差 / childIds 序列变 = 结构变更；name 变 = 更名；layer/tags 变 = 元数据变更 */
    private void diff(Map<Integer, NodeSnap> prev, Map<Integer, NodeSnap> curr) {
        boolean structureChanged = !curr.keySet().equals(prev.keySet());
        boolean metaChanged = false;
        List<NodeRenamedEvent> renames = null;
        for (Map.Entry<Integer, NodeSnap> entry : curr.entrySet()) {
            NodeSnap old = prev.get(entry.getKey());
            if (old == null) {
                structureChanged = true;
                continue;
            }
            NodeSnap snap = entry.getValue();
            if (!snap.childIds.equals(old.childIds)) {
                structureChanged = true;
            }
            if (snap.layer != old.layer || !snap.tags.equals(old.tags)) {
                metaChanged = true;
            }
            if (!Objects.equals(snap.name, old.name)) {
                if (renames == null) {
                    renames = new ArrayList<>();
                }
                renames.add(new NodeRenamedEvent(snap.node, snap.name));
            }
        }
        if (!structureChanged && !metaChanged && renames == null) {
            return;
        }
        javelinEditor.getEditorScene().markDirty();
        if (structureChanged || metaChanged) {
            EditorEventBus.publish(new SceneChangedEvent(true));
        }
        if (renames != null) {
            for (NodeRenamedEvent event : renames) {
                EditorEventBus.publish(event);
            }
        }
    }

    /** 节点快照（引擎线程内部 diff 数据：node 引用供事件发布，name 可为 null 判等用） */
    private static final class NodeSnap {
        private final Node node;
        private final String name;
        private final List<Integer> childIds;
        private final int layer;
        private final List<String> tags;

        private NodeSnap(Node node, String name, List<Integer> childIds, int layer, List<String> tags) {
            this.node = node;
            this.name = name;
            this.childIds = childIds;
            this.layer = layer;
            this.tags = tags;
        }
    }
}
