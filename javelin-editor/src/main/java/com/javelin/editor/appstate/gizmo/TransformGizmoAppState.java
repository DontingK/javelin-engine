package com.javelin.editor.appstate.gizmo;

import com.javelin.core.JavelinEngine;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.render.material.Shader;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.Camera;
import com.javelin.editor.appstate.AbsEditorAppState;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.SceneSelectEvent;
import com.javelin.editor.event.Subscription;
import com.javelin.editor.scene.EditorScene;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * 变换 Gizmo 驱动 AppState（2026-10-05 用户重构版修齐）。
 *
 * <p>结构：{@code gizmoNode} 常驻编辑子场景（不进用户树、不落盘），各工具子树
 * （{@link TransformTools#getToolNode()}）挂其下；选中跟随写 gizmoNode 一次
 * （工具无关，编辑子场景根级 local==world）。选中事件驱动：
 * {@link SceneSelectEvent} 经 {@link com.javelin.editor.selection.EditorSelection}
 * 收口发布，本类订阅更新目标（volatile：事件泵线程写、引擎线程读）。</p>
 *
 * <p>显隐：gizmoNode.setActive 翻转——依赖引擎渲染过滤（SceneRenderBridge 按
 * isActiveInHierarchy 过滤，Unity inactive 口径，2026-10-05 拍板），setActive
 * 本身藏不住渲染、过滤是渲染侧唯一关卡。无选中（含节点被删除——事件不清除
 * 选中，按目标是否仍属用户场景兜底判定）整体隐藏。</p>
 *
 * <p>尺度：距离自适应——工具世界尺度 = 相机距离 / {@value #TOOLS_SCALE} ×
 * {@code scaleFactor()}（屏幕恒定尺寸口径，mesh 原生全长约 9.9）；仅作用于
 * isActive 的工具节点（show/hide = 工具切换位，默认激活首个工具）。</p>
 *
 * <p>置顶：{@link GizmoPass} 挂管线末尾（Opaque→Transparent→Gizmo）——颜色
 * LOAD 保画 + 深度 CLEAR，两层深度语义（用户拍板 2026-10-05）：gizmo 对外恒在
 * 场景之上不被遮挡（编辑操作对象始终可见）、内部箭头互有层次（ZWrite On 自写
 * 自测）；绘制对象按 gizmo.shader 实例身份过滤（全部变换工具共用该 shader）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class TransformGizmoAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(TransformGizmoAppState.class);

    /**
     * 距离自适应分母：箭头世界长 ≈ 9.9 × 相机距离 / 本值；80 → 箭头约占视口 1/8（实测定稿可调）
     */
    private static final float TOOLS_SCALE = 8;

    /**
     * Gizmo 总根（常驻编辑子场景；工具子树挂其下，位置跟随选中节点）
     */
    private Node gizmoNode;

    /**
     * 选中的目标（SceneSelectEvent 事件驱动写入；volatile：事件泵线程写、引擎线程读）
     */
    private volatile Node targetNode;

    /**
     * 工具注册表（首个为默认激活工具）
     */
    private final ArrayList<TransformTools> tools = new ArrayList<>();

    /**
     * 选中事件订阅句柄（onDetach 退订防重复订阅）
     */
    private Subscription selectSubscription;

    /**
     * Gizmo 置顶 Pass（onDetach 时从管线摘除防重复挂载）
     */
    private GizmoPass gizmoPass;

    /**
     * 相机世界位置复用向量（每帧暂存，避免分配）
     */
    private final Vector3f camWorldPos = new Vector3f();

    /**
     * 目标世界位置复用向量（每帧暂存，避免分配）
     */
    private final Vector3f targetWorldPos = new Vector3f();

    /**
     * 工具缩放复用向量（每帧下发，避免分配）
     */
    private final Vector3f scaleVec = new Vector3f();

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        GameScene editorGameScene = javelinEditor.getEditorGameScene();
        if (editorGameScene == null) {
            // loadScene 失败口径（同 EditGridAppState）：跳过装配，帧循环退化为清屏
            log.warn("编辑器场景未就绪，跳过变换 Gizmo 创建（TransformGizmoAppState）");
            return;
        }
        gizmoNode = new Node();
        gizmoNode.setName("gizmoNode");
        // 初始隐藏：渲染按 isActiveInHierarchy 过滤，有选中才亮（setActive 纯字段写、翻转变效）
        gizmoNode.setActive(false);
        editorGameScene.add(gizmoNode);

        tools.add(new MoveTool());
        for (TransformTools tool : tools) {
            tool.init(javelinEditor.getAssetManager(), javelinEditor);
            Node toolNode = tool.getToolNode();
            if (toolNode != null) {
                // setParent 自动入场景（gizmoNode 已在编辑子场景，工具子树随之注册）
                toolNode.getTransform().setParent(gizmoNode.getTransform());
            }
        }
        tools.getFirst().show();

        // 置顶 Pass 挂管线末尾（Opaque→Transparent→Gizmo）：颜色 LOAD 保画 + 深度 CLEAR，
        // gizmo 恒最前；绘制对象按 gizmo.shader 实例身份过滤（共享加载，与 MoveTool.init
        // 各自 loadAsset 同一对象）。shader 缺失时跳过——与 MoveTool 失败口径一致
        Shader gizmoShader = javelinEditor.getAssetManager().loadAsset(MoveTool.GIZMO_SHADER_PATH, Shader.class);
        if (gizmoShader != null) {
            gizmoPass = new GizmoPass(gizmoShader);
            engine.renderPipeline().addPass(gizmoPass);
        }

        selectSubscription = EditorEventBus.subscribe(SceneSelectEvent.class,
                sceneSelectEvent -> targetNode = sceneSelectEvent.getNode());
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        // 先收尾再 super（super 置空 engine，之后无法访问场景/管线）
        if (selectSubscription != null) {
            selectSubscription.dispose();
            selectSubscription = null;
        }
        if (gizmoPass != null) {
            engine.renderPipeline().removePass(gizmoPass);
            gizmoPass = null;
        }
        if (gizmoNode != null) {
            GameScene editorGameScene = javelinEditor.getEditorGameScene();
            if (editorGameScene != null && gizmoNode.getScene() == editorGameScene) {
                editorGameScene.remove(gizmoNode);
            }
            gizmoNode = null;
        }
        targetNode = null;
        super.onDetach(stateManager);
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (gizmoNode == null) {
            return; // 场景未就绪（onAttach 跳过装配），安全跳过
        }
        Camera editorCamera = javelinEditor.getEditorCamera();
        if (editorCamera == null) {
            return; // 相机未就绪（防御；onAttach 分支保证存在）
        }

        // 有效目标 = 选中节点且仍属用户场景（删除后不清除选中的兜底）
        Node target = targetNode;
        GameScene userScene = userGameScene();
        boolean hasTarget = target != null && userScene != null && target.getScene() == userScene;

        if (gizmoNode.isActive() != hasTarget) {
            gizmoNode.setActive(hasTarget);
        }
        if (!hasTarget) {
            return;
        }

        // 跟随目标：gizmoNode 一次写（工具无关；setLocalPosition 拷贝语义不外泄复用向量）
        target.getTransform().getWorldPosition(targetWorldPos);
        gizmoNode.getTransform().setLocalPosition(targetWorldPos);

        // 距离自适应缩放（屏幕恒定尺寸口径）
        editorCamera.getNode().getTransform().getWorldPosition(camWorldPos);
        float scale = Math.abs(targetWorldPos.distance(camWorldPos)) / TOOLS_SCALE;
        for (TransformTools tool : tools) {
            Node toolNode = tool.getToolNode();
            if (toolNode != null && toolNode.isActive()) {
                update(tool, scale * tool.scaleFactor());
            }
        }
    }

    /**
     * 单工具帧驱动：update + 统一缩放下发（scale 已含该工具 scaleFactor）
     */
    private void update(TransformTools transformTools, float scale) {
        transformTools.update();
        scaleVec.set(scale, scale, scale);
        transformTools.getToolNode().getTransform().setLocalScale(scaleVec);
    }

    /**
     * 用户场景（Gizmo 目标节点所在场景；目标不属用户场景视为无选中——覆盖已删除节点）
     */
    private GameScene userGameScene() {
        Scene scene = engine.scene();
        if (scene instanceof EditorScene editorScene) {
            return editorScene.getGameScene();
        }
        return null;
    }
}
