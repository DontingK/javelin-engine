package com.javelin.editor.menu.hierarchy;

import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 删除选中节点（hierarchy.delete，Unity Delete 语义：整棵子树移出场景并触发
 * onDisable → onDestroy）。无选中置灰；删除后清除选中（被删子树可能包含选中
 * 节点，统一清空）。动场景结构经 {@link JavelinEditor#submitTask} 提交引擎
 * 主线程帧边界执行；只写不发通知——变更由 SceneWatchAppState 快照 diff
 * 自动发现并推送前端。
 *
 * @author javelin
 * @create 2026/9/30
 */
public class DeleteMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeleteMenuCommand.class);

    @Override
    public String id() {
        return "hierarchy.delete";
    }

    @Override
    public String icon() {
        return "delete";
    }

    @Override
    public String shortcut() {
        return "Del";
    }

    @Override
    public Boolean enabled() {
        return getSelectedNode() != null;
    }

    @Override
    public void execute() {
        Node target = getSelectedNode();
        if (target == null) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过删除节点（hierarchy.delete）");
            return;
        }
        editor.submitTask(() -> delete(editor, target));
    }

    /** 删除子树（引擎主线程帧边界执行） */
    private static void delete(JavelinEditor editor, Node target) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null || target.getScene() != gameScene) {
            LOGGER.warn("删除目标不在用户场景（可能已被删或场景未就绪），跳过: {}", target.getName());
            return;
        }
        gameScene.remove(target);
        EditorSelection.setSelectedNode(null);
        LOGGER.info("删除节点: {} (id={})", target.getName(), target.getId());
    }
}
