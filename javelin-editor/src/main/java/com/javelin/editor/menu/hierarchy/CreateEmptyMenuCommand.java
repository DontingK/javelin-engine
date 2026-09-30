package com.javelin.editor.menu.hierarchy;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Transform;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 创建空节点（hierarchy.create.empty，Unity Create Empty 语义）。
 *
 * <p>挂入位置：有选中 → 选中节点下（Transform.setParent 自动入场景）；无选中
 * （含右键场景根）→ 场景根级（GameScene.add）。创建后自动选中新节点
 * （Unity 语义，经树拉取的 selectedNodeId 对齐生效）。重名去重
 * GameObject / GameObject (n)。动场景结构经 {@link JavelinEditor#submitTask}
 * 提交引擎主线程帧边界执行；只写不发通知——变更由 SceneWatchAppState
 * 快照 diff 自动发现并推送前端。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public class CreateEmptyMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateEmptyMenuCommand.class);

    private static final String BASE_NAME = "GameObject";

    @Override
    public String id() {
        return "hierarchy.create.empty";
    }

    @Override
    public String label() {
        return "创建空物体";
    }

    @Override
    public void execute() {
        // 提交时快照父节点（Unity 语义：右键对象下创建；快照防执行帧选中漂移）
        Node parent = getSelectedNode();
        Integer parentId = parent != null ? parent.getId() : null;
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过创建节点（hierarchy.create.empty）");
            return;
        }
        editor.submitTask(() -> create(editor, parentId));
    }

    /** 创建节点（引擎主线程帧边界执行） */
    private static void create(JavelinEditor editor, Integer parentId) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            LOGGER.warn("场景未就绪，跳过创建节点（hierarchy.create.empty）");
            return;
        }
        Node node = new Node();
        node.setName(uniqueName(gameScene));
        Node parent = parentId != null ? ObjectCache.get(parentId) : null;
        if (parent != null && parent.getScene() == gameScene) {
            node.getTransform().setParent(parent.getTransform());
        } else {
            gameScene.add(node);
        }
        EditorSelection.setSelectedNodeId(node.getId());
        LOGGER.info("创建节点: {} (id={})", node.getName(), node.getId());
    }

    /** 全树重名去重：GameObject / GameObject (1) / ...（Unity 编号口径） */
    private static String uniqueName(GameScene gameScene) {
        Set<String> names = new HashSet<>();
        for (Node root : gameScene.getRootNodes()) {
            collectNames(root, names);
        }
        if (!names.contains(BASE_NAME)) {
            return BASE_NAME;
        }
        int n = 1;
        while (names.contains(BASE_NAME + " (" + n + ")")) {
            n++;
        }
        return BASE_NAME + " (" + n + ")";
    }

    private static void collectNames(Node node, Set<String> out) {
        if (node.getName() != null) {
            out.add(node.getName());
        }
        for (Transform child : node.getTransform()) {
            collectNames(child.getNode(), out);
        }
    }
}
