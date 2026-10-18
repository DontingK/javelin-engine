package com.javelin.editor.swing.hierarchy;

import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Transform;
import com.javelin.editor.scene.EditorScene;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 场景树快照（{@link HierarchyPanel} 的数据通道——原 {@code /scene/tree} 桥 VO 的
 * Swing 直调形态，Editor_Swing_Design §7.2「桥退役、Java 直调」）。
 *
 * <p>纯数据投影，不持 Node 引用：引擎主线程经 {@code JavelinEngine.submitTask}
 * 帧边界组装（与渲染/命令同帧序，读树无撕裂），EDT 消费后整体换树。刷新由
 * {@code scene.changed}（结构变更，重拉整树）边沿驱动 + {@code scene.nodeRenamed}
 * 定点更新（NodeSnap.name 可变），快照 diff 检出归 SceneWatchAppState，写入方零通知义务。</p>
 *
 * <p>未就绪（gameScene 为 null：启动竞态或 loadScene 失败）以 {@link #isReady()}
 * false 表达，消费方走重试兜底——不走异常（对齐原桥口径）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class SceneTreeSnapshot {

    /** 未就绪快照（ready=false；不可变，共享单例安全） */
    private static final SceneTreeSnapshot NOT_READY =
            new SceneTreeSnapshot(false, "", false, new ArrayList<>());

    private final boolean ready;
    private final String sceneName;
    private final boolean dirty;
    private final ArrayList<NodeSnap> roots;

    private SceneTreeSnapshot(boolean ready, String sceneName, boolean dirty,
                              ArrayList<NodeSnap> roots) {
        this.ready = ready;
        this.sceneName = sceneName;
        this.dirty = dirty;
        this.roots = roots;
    }

    /**
     * 组装快照（<b>引擎主线程帧边界执行</b>——经 submitTask 提交，勿在 EDT 直接调）。
     *
     * @param editorScene 编辑器场景容器（用户场景/脏标记持有者）
     * @return 场景快照；场景未就绪时 ready=false
     */
    public static SceneTreeSnapshot build(EditorScene editorScene) {
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            return NOT_READY;
        }
        ArrayList<NodeSnap> roots = new ArrayList<>();
        for (Node root : gameScene.getRootNodes()) {
            roots.add(collect(root));
        }
        return new SceneTreeSnapshot(true, sceneDisplayName(editorScene, gameScene),
                editorScene.isDirty(), roots);
    }

    /** 场景根显示名：GameScene.name → 场景文件名去扩展名 → "Untitled"（原桥口径平移） */
    private static String sceneDisplayName(EditorScene editorScene, GameScene gameScene) {
        if (gameScene.getName() != null && !gameScene.getName().isBlank()) {
            return gameScene.getName();
        }
        File file = editorScene.getGameSceneFile();
        if (file != null) {
            String fileName = file.getName();
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        return "Untitled";
    }

    /** 节点子树投影（层级经 Transform 父子递归；name 空白兜底 GameObject） */
    private static NodeSnap collect(Node node) {
        ArrayList<NodeSnap> children = new ArrayList<>();
        for (Transform child : node.getTransform()) {
            children.add(collect(child.getNode()));
        }
        String name = node.getName() != null && !node.getName().isBlank()
                ? node.getName() : "GameObject";
        return new NodeSnap(node, name, node.isActive(), children);
    }

    public boolean isReady() {
        return ready;
    }

    /** 场景根显示名（脏星标由消费方按 scene.dirty 事件叠加，不入快照名） */
    public String getSceneName() {
        return sceneName;
    }

    /** 快照时点场景脏标记（重拉整树时熄灭星标的数据源） */
    public boolean isDirty() {
        return dirty;
    }

    /** 根级节点快照（不可变列表视图；面板侧只读） */
    public java.util.List<NodeSnap> getRoots() {
        return Collections.unmodifiableList(roots);
    }

    /**
     * 节点快照：node 引用（回查键——树行点击上报选中 / rename·select 事件匹配行，
     * EDT 侧不读其字段，显示数据走拷贝）、name（可变——scene.nodeRenamed 定点
     * 更新直写）、active（自身激活态，灰显用）、children。
     */
    public static final class NodeSnap {

        private final Node node;
        private String name;
        private final boolean active;
        private final ArrayList<NodeSnap> children;

        NodeSnap(Node node, String name, boolean active, ArrayList<NodeSnap> children) {
            this.node = node;
            this.name = name;
            this.active = active;
            this.children = children;
        }

        public Node getNode() {
            return node;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isActive() {
            return active;
        }

        public java.util.List<NodeSnap> getChildren() {
            return Collections.unmodifiableList(children);
        }
    }
}
