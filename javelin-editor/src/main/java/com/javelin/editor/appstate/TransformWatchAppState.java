package com.javelin.editor.appstate;

import com.javelin.core.JavelinEngine;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Transform;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.NodeTransformChangeEvent;
import com.javelin.editor.event.SceneSelectEvent;
import com.javelin.editor.event.Subscription;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Transform 变更检测状态（帧级快照 diff，<b>只盯当前选中节点</b>，用户拍板
 * 2026-10-06）。
 *
 * <p>替代「写入方显式发通知」：任何改<b>选中节点</b> Transform 的路径（gizmo
 * 拖拽/将来 Inspector 回写/运行时脚本）零感知，本状态每帧比对选中节点 local
 * TRS 与基线，实际变化即发布 {@link NodeTransformChangeEvent}（定点通知，
 * Inspector 拖拽实时跟手）并置场景脏标记（拖拽 = 未保存编辑 → 星号）。
 * 检测范围 = 当前选中节点（用户拍板：消费方就是 Inspector 的选中面板，全场景
 * 遍历无消费方；每帧成本 = 一个节点 10 个 float 比较，选中之外零开销）。</p>
 *
 * <p>选中驱动：订阅 {@link SceneSelectEvent}（EditorSelection 收口发布）写入
 * volatile 字段（事件泵线程写、引擎线程读，同 TransformGizmoAppState 口径）；
 * 选中变更/首次就绪<b>静默重建基线</b>（选中变化不算变换变更）；无选中、或
 * 选中节点已删除/场景卸载（事件不清除选中的口径，经 getScene 归属校验）不
 * 监听——重新选中时自然重建基线。同帧发布：挂载序在
 * TransformGizmoAppState 之后，拖拽 apply 当帧检出当帧通知。</p>
 *
 * @author javelin
 * @create 2026/10/6
 */
public class TransformWatchAppState extends AbsEditorAppState {

    /** 基线布局：[0..2]=位置、[3..6]=旋转四元数(x,y,z,w)、[7..9]=缩放 */
    private static final int SNAP_FLOATS = 10;

    /** 当前选中节点（SceneSelectEvent 事件驱动写入；volatile：事件泵线程写、引擎线程读） */
    private volatile Node selectedNode;

    /** 基线对应节点（引擎线程持有；与 selectedNode 不同一性 = 选中变更，静默重建基线） */
    private Node baselineNode;

    /** 选中节点 TRS 基线（变更原地回写，零分配） */
    private final float[] baseline = new float[SNAP_FLOATS];

    /** 选中事件订阅句柄（onDetach 退订防重复订阅） */
    private Subscription selectSubscription;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        selectSubscription = EditorEventBus.subscribe(SceneSelectEvent.class,
                sceneSelectEvent -> selectedNode = sceneSelectEvent.getNode());
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        if (selectSubscription != null) {
            selectSubscription.dispose();
            selectSubscription = null;
        }
        selectedNode = null;
        baselineNode = null;
        super.onDetach(stateManager);
    }

    @Override
    public void update(float tpf) {
        Node node = selectedNode;
        if (node == null) {
            baselineNode = null; // 无选中不监听（重新选中时经基线重建分支恢复）
            return;
        }
        // 归属校验（事件不清除选中口径）：节点已删/场景卸载 → getScene 不再匹配用户场景
        GameScene userScene = javelinEditor.getEditorScene().getGameScene();
        if (userScene == null || node.getScene() != userScene) {
            baselineNode = null;
            return;
        }
        if (baselineNode != node) {
            // 选中变更/首次就绪：静默建基线（选中变化不算变换变更）
            baselineNode = node;
            snapshot(node.getTransform(), baseline);
            return;
        }
        Transform transform = node.getTransform();
        if (differs(baseline, transform)) {
            // 变更：基线原地回写 + 同帧发布（Inspector 实时）+ 置脏（拖拽 = 未保存编辑）
            snapshot(transform, baseline);
            EditorEventBus.publish(new NodeTransformChangeEvent(node,
                    transform.getLocalPosition(), transform.getLocalRotation(), transform.getLocalScale()));
            javelinEditor.getEditorScene().markDirty();
        }
    }

    /** TRS 是否与基线不同（空闲值不自漂移，逐分量精确比较足够） */
    private static boolean differs(float[] snap, Transform transform) {
        Vector3f p = transform.getLocalPosition();
        Quaternionf r = transform.getLocalRotation();
        Vector3f s = transform.getLocalScale();
        return snap[0] != p.x || snap[1] != p.y || snap[2] != p.z
                || snap[3] != r.x || snap[4] != r.y || snap[5] != r.z || snap[6] != r.w
                || snap[7] != s.x || snap[8] != s.y || snap[9] != s.z;
    }

    /** TRS 写入基线（原地回写，逐分量拷贝） */
    private static void snapshot(Transform transform, float[] out) {
        Vector3f p = transform.getLocalPosition();
        Quaternionf r = transform.getLocalRotation();
        Vector3f s = transform.getLocalScale();
        out[0] = p.x;
        out[1] = p.y;
        out[2] = p.z;
        out[3] = r.x;
        out[4] = r.y;
        out[5] = r.z;
        out[6] = r.w;
        out[7] = s.x;
        out[8] = s.y;
        out[9] = s.z;
    }
}
