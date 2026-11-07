package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.component.BoxCollider;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.debug.drawer.BoxColliderDebugLine;
import cn.donting.javelin.editor.debug.drawer.CameraDebugLine;
import cn.donting.javelin.editor.debug.drawer.MeshDebugLine;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DebugDrawLine 装配与消费 AppState（Editor_DebugLine_Design §2/§5）。
 *
 * <p>onAttach 建 batch/Pass 并 {@code addPass(pass, "Transparent")} 插入管线
 * （GizmoPass 之前——Gizmo 轴恒压线）+ bind 门面 + 注册 drawers；update 每帧
 * {@code clear()} + 选中节点逐组件查表分派（选中驱动显隐，每帧重提交＝跟随物体
 * ——无绑定/通知机制）；onDetach 摘 Pass + unbind。GPU 资源不在此 dispose
 * （onDetach 早于 waitIdle；断引用交 Cleaner + 进程退出兜底，设计档 §5）。</p>
 *
 * <p>有效目标判定同 TransformGizmoAppState 兜底：选中节点须仍属用户场景
 * （删除后事件不清除选中的防残留）。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
public class DebugLineAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(DebugLineAppState.class);

    /** 管线插入锚点：TransparentForwardPass 之后（GizmoPass 之前） */
    private static final String INSERT_AFTER = "Transparent";

    private DebugLinePass debugLinePass;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        GameScene editorGameScene = javelinEditor.getEditorGameScene();
        if (editorGameScene == null) {
            // loadScene 失败口径（同 EditGridAppState/TransformGizmoAppState）：跳过装配
            log.warn("编辑器场景未就绪，跳过调试线装配（DebugLineAppState）");
            return;
        }
        Camera editorCamera = javelinEditor.getEditorCamera();
        if (editorCamera == null || editorCamera.getNode() == null) {
            log.warn("编辑器相机未就绪，跳过调试线装配（DebugLineAppState）");
            return;
        }
        DebugLineBatch batch = new DebugLineBatch();
        debugLinePass = new DebugLinePass(engine.device(), engine.getAssetManager(),
                engine.renderingSystem(), batch, editorCamera.getNode().getName());
        engine.renderPipeline().addPass(debugLinePass, INSERT_AFTER);
        DebugDrawLine.bind(batch);

        DebugLineRegistry.register(MeshRenderer.class, new MeshDebugLine());
        DebugLineRegistry.register(Camera.class, new CameraDebugLine(javelinEditor));
        DebugLineRegistry.register(BoxCollider.class, new BoxColliderDebugLine());
        log.info("DebugLineAppState 装配完成: 视口[{}] 容量={} 线",
                editorCamera.getNode().getName(), DebugLineBatch.MAX_LINES);
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        if (debugLinePass != null) {
            engine.renderPipeline().removePass(debugLinePass);
            debugLinePass = null;
        }
        DebugDrawLine.unbind();
        DebugLineRegistry.clear();
        super.onDetach(stateManager);
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (debugLinePass == null) {
            return; // 场景未就绪（onAttach 跳过装配），安全跳过
        }
        DebugDrawLine.clear();
        Node selected = EditorSelection.getSelectedNode();
        if (selected == null) {
            return;
        }
        // 有效目标 = 选中节点且仍属用户场景（删除兜底，TransformGizmo 同口径）；
        // 编辑器相机在编辑子场景不可被选中——选中门控天然排除其自身视锥
        Scene scene = engine.scene();
        if (!(scene instanceof EditorScene editorScene)) {
            return;
        }
        GameScene userScene = editorScene.getGameScene();
        if (userScene == null || selected.getScene() != userScene) {
            return;
        }
        for (Component component : selected.getComponents()) {
            dispatch(DebugLineRegistry.drawerFor(component), component, selected);
        }
    }

    /** 查表分派（unchecked cast 收口一处：注册时 Class<T> 与 drawer<T> 泛型配对保证类型安全） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatch(ComponentDebugLine drawer, Component component, Node node) {
        if (drawer == null) {
            return; // 无绘制器的组件（Transform 等）——常态跳过
        }
        drawer.drawDebugLine(component, node);
    }
}
