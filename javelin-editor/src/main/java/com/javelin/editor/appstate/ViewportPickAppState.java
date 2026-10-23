package com.javelin.editor.appstate;

import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.JavelinEngine;
import com.javelin.core.input.Input;
import com.javelin.core.input.KeyCode;
import com.javelin.core.input.MouseButton;
import com.javelin.core.render.picking.PickSystem;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.editor.appstate.gizmo.TransformGizmoAppState;
import com.javelin.editor.scene.SceneModel;
import com.javelin.editor.selection.EditorSelection;

/**
 * 视口拾取消费 AppState（Engine_Picking_Design.md，2026-10-23 全线统一拍板落地）。
 *
 * <p>职责 = PickSystem 的编辑器侧语义裁决：每帧把指针位置（逻辑坐标 × dpi 换算
 * 成拾取附件物理像素）喂给 PickSystem（hover 通道）；LMB 按下且非 Alt（归相机
 * 环绕）、SCENE 视图、gizmo 非拖拽且 hover 非轴（轴点击归 gizmo 拖拽）时发
 * 点击请求；点击结果经 {@link EditorSelection#setSelectedNode} 收口——用户场景
 * 节点选中、背景/编辑器自有节点清空，层级树/Inspector 订阅零改动自动联动。</p>
 *
 * <p>挂载序在 TransformGizmoAppState 之后（点击门控消费其当帧 hover/拖拽态）；
 * hover/点击回调均在引擎线程（PickSystem.frameEnd → listener），选中收口线程
 * 安全、gizmo 直写同线程串行。</p>
 */
public class ViewportPickAppState extends AbsEditorAppState {

    private final TransformGizmoAppState gizmoState;
    private PickSystem pickSystem;

    public ViewportPickAppState(TransformGizmoAppState gizmoState) {
        this.gizmoState = gizmoState;
    }

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        pickSystem = javelinEditor.getPickSystem();
        if (pickSystem == null) {
            return;   // 场景未就绪（onInitialize 跳过装配）——update 同口径安全跳过
        }
        // hover：拾取结果 → 轴节点直写 gizmo（工具裁决在 gizmo update 消费侧）
        pickSystem.setHoverListener(owner ->
                gizmoState.setHoveredFromPick(owner instanceof MeshRenderer mr ? mr.getNode() : null));
        // 点击：用户场景节点选中、背景/编辑器自有节点（网格/轴线）清空（Unity 口径）
        pickSystem.setClickListener(owner -> {
            Node node = owner instanceof MeshRenderer mr ? mr.getNode() : null;
            if (node != null && node.getScene() == javelinEditor.getEditorScene().getGameScene()) {
                EditorSelection.setSelectedNode(node);
            } else {
                EditorSelection.setSelectedNode(null);
            }
        });
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (pickSystem == null) {
            return;
        }
        // GAME 视图 = 游戏预览（编辑器相机摘除、拾取面为空），拾取整体停发
        if (javelinEditor.getEditorScene().getSceneModel() != SceneModel.SCENE) {
            pickSystem.pointer(-1, -1);
            return;
        }
        // 指针逻辑坐标 → 拾取附件物理像素（附件 = 交换链尺寸；越界由 PickSystem 裁决）
        int logicalW = javelinEditor.viewportWidth();
        int logicalH = javelinEditor.viewportHeight();
        if (logicalW <= 0 || logicalH <= 0) {
            pickSystem.pointer(-1, -1);
            return;
        }
        int swapW = engine.frameLoop().swapChain().width();
        int swapH = engine.frameLoop().swapChain().height();
        int px = Math.round(Input.mouseX() * (swapW / (float) logicalW));
        int py = Math.round(Input.mouseY() * (swapH / (float) logicalH));
        pickSystem.pointer(px, py);

        // 点击门控：纯 LMB（Alt+LMB 归相机环绕）且 gizmo 未占用（拖拽中 / hover 轴=轴点击）
        if (Input.wasMousePressed(MouseButton.LEFT)
                && !Input.isDown(KeyCode.LEFT_ALT)
                && !gizmoState.isDragging()
                && gizmoState.getHoveredNode() == null) {
            pickSystem.requestClick(px, py);
        }
    }
}
