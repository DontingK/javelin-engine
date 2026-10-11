package com.javelin.editor.jcef.method;


import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Transform;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.SceneModelPar;
import com.javelin.editor.jcef.domain.par.SceneSelectPar;
import com.javelin.editor.jcef.domain.vo.SceneNodeVo;
import com.javelin.editor.jcef.domain.vo.SceneTreeVo;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.scene.SceneModel;
import com.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 场景桥方法：/scene/tree（树快照拉取）、/scene/select（选中上报，写
 * {@link EditorSelection} 单源，volatile 直写无需主线程）、/scene/setModel
 * （Scene/Game 视图模式切换，volatile 直写 {@link EditorScene}）。
 *
 * <p>树拉取经 {@link JavelinEditor#submitTask} 提交到引擎主线程帧边界组装
 * （桥线程与主线程的场景访问无竞争），CompletableFuture 回传结果、await 超时兜底。
 * 「未就绪」走 VO 字段（ready=false）不走异常——桥层异常会弹原生对话框，
 * 启动竞态（引擎装配晚于页面挂载）下前端拉取重试兜底。</p>
 *
 * @author javelin
 * @create 2026/9/30
 **/
@BridgeMethod("/scene")
public class SceneMethod {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneMethod.class);

    /** 树拉取等待上限（毫秒）：帧边界正常 ~16ms 完成，2 秒仍未完成视作主循环异常 */
    private static final long AWAIT_TIMEOUT_MILLIS = 2000;

    /**
     * 场景树快照拉取：场景根信息 + 根级节点递归展开 + 脏标记 + 当前选中 id
     * （前端拉取后对齐选中态——创建命令执行后自动选中新节点经此生效）。
     */
    @BridgeMethod("/tree")
    public SceneTreeVo tree() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return SceneTreeVo.notReady();
        }
        CompletableFuture<SceneTreeVo> future = new CompletableFuture<>();
        editor.submitTask(() -> future.complete(buildTree(editor)));
        SceneTreeVo vo = await(future);
        return vo != null ? vo : SceneTreeVo.notReady();
    }

    /**
     * 场景树选中上报（前端 onSelect；选中场景根/取消 = null 清除）。
     */
    @BridgeMethod("/select")
    public void select(SceneSelectPar par) {
        EditorSelection.setSelectedNodeId(par == null ? null : par.nodeId);
    }

    /**
     * Scene/Game 视图模式切换（中央停靠区标签页 onShow 上报，volatile 直写
     * {@link EditorScene#setSceneModel} 无需主线程）：GAME = 渲染只拉用户场景
     * 相机与物体（编辑器相机/网格/Gizmo 不进画面），SCENE = 合并编辑辅助。
     */
    @BridgeMethod("/setModel")
    public void setModel(SceneModelPar par) {
        String raw = par == null ? null : par.model;
        if (raw == null || raw.isBlank()) {
            return;
        }
        SceneModel model;
        try {
            model = SceneModel.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("忽略未知视图模式: {}", raw);
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor != null) {
            editor.getEditorScene().setSceneModel(model);
        }
    }

    /** 树快照组装（引擎主线程执行；场景未就绪返回 ready=false） */
    private static SceneTreeVo buildTree(JavelinEditor editor) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            return SceneTreeVo.notReady();
        }
        SceneTreeVo vo = new SceneTreeVo();
        vo.ready = true;
        vo.id = gameScene.getId();
        vo.name = sceneDisplayName(editorScene, gameScene);
        vo.dirty = editorScene.isDirty();
        vo.selectedNodeId = EditorSelection.getSelectedNodeId();
        vo.entities = new ArrayList<>();
        for (Node root : gameScene.getRootNodes()) {
            vo.entities.add(toVo(root));
        }
        return vo;
    }

    /** 场景根显示名：GameScene.name → 场景文件名去扩展名 → "Untitled" */
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

    /** 节点 → VO（层级经 Transform 父子递归；name 空兜底） */
    private static SceneNodeVo toVo(Node node) {
        SceneNodeVo vo = new SceneNodeVo();
        vo.id = node.getId();
        vo.name = node.getName() != null && !node.getName().isBlank() ? node.getName() : "GameObject";
        vo.active = node.isActive();
        vo.children = new ArrayList<>();
        for (Transform child : node.getTransform()) {
            vo.children.add(toVo(child.getNode()));
        }
        return vo;
    }

    /** 阻塞等待树快照（桥线程专用；超时/异常记日志返回 null，由调用方转「未就绪」勿向上抛） */
    private static SceneTreeVo await(CompletableFuture<SceneTreeVo> future) {
        try {
            return future.get(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }
}
