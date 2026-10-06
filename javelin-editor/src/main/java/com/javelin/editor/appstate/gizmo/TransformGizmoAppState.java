package com.javelin.editor.appstate.gizmo;

import com.javelin.core.JavelinEngine;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.input.Input;
import com.javelin.core.input.KeyCode;
import com.javelin.core.input.MouseButton;
import com.javelin.core.physics.Ray;
import com.javelin.core.render.material.Shader;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.Collider;
import com.javelin.core.scene.component.Transform;
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
 * <p>射线检测（2026-10-05）：update 每帧把鼠标（视口逻辑坐标）经
 * {@link Camera#screenToRay}（Unity ScreenPointToRay 口径，viewportRect 归一化）
 * 换算成世界射线，打活动工具子树的碰撞体取最近命中；命中候选再经
 * {@link TransformTools#isSelectable} 工具裁决（2026-10-06：移动轴与视线趋于
 * 平行端视时不可选——屏幕上轴退化为点、拖拽投影病态）；{@link #getHoveredNode()}
 * 暴露命中轴 Node（hover 变更打日志）、{@link #getHoveredAxis()} 经 userData
 * 标判别轴——轴拖拽判定的地基。</p>
 *
 * <p>拖拽（2026-10-06）：LMB 输入状态机——按下命中柄（hover 当帧结果）交所属
 * 工具 {@link TransformTools#beginDrag}，按住每帧 {@link TransformTools#updateDrag}
 * （hover 锁定拖拽轴不重检测），释放 {@link TransformTools#endDrag}；Alt+LMB 归
 * 相机环绕不抢（拖拽中按 Alt 与环绕并动为已知边缘）、按下与释放同帧（极速点击）
 * 直接收尾防拖拽态卡死、拖拽绑定开始时目标（选中变更/目标失效强制结束防跳变）。
 * 拖拽数学归工具层（MoveTool 平面相交法）。跟随后于拖拽 apply——同帧跟手。</p>
 *
 * <p>视觉状态（2026-10-06）：hover 与拖拽统一走驱动侧每帧
 * {@link TransformTools#updateVisuals} 同步（同步式而非 enter/leave 事件式——
 * 松开拖拽时鼠标往往仍悬停轴上，事件式会错误熄灭；拖拽中 hover 锁定即被拖轴；
 * viewDir = 相机→选中目标方向，与鼠标位置无关，供工具计算不可选轴变灰，判定
 * 与 isSelectable 同源）。gizmo 整体隐藏（无目标）时清 hover 并熄灭高亮防残留。</p>
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

    // 射线检测复用缓冲（每帧零分配）：世界射线 + 屏幕换算基向量 + 命中量
    private final Ray pickRay = new Ray();
    private final Vector3f hitPointBuf = new Vector3f();
    private final Vector3f hitNormalBuf = new Vector3f();

    /** 视线方向复用向量（相机→选中目标、归一化；轴可选性/变灰基准——与鼠标位置无关） */
    private final Vector3f viewDir = new Vector3f();

    /** 当前射线检测命中的轴 Node（null = 未命中；update 每帧刷新，拖拽中锁定不刷新） */
    private Node hoveredNode;

    /** 拖拽中的工具（null = 未拖拽；LMB 边沿驱动 begin/end、持续态驱动 updateDrag） */
    private TransformTools dragTool;

    /** 拖拽绑定的目标（beginDrag 时锁定；拖拽中选中变更即强制结束防目标跳变） */
    private Node dragTarget;

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
        hoveredNode = null;
        if (dragTool != null) {
            // 摘除时仍在拖拽（应用关闭等）——工具恢复轴高亮等收尾
            dragTool.endDrag();
            dragTool = null;
        }
        dragTarget = null;
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

        if (!hasTarget && dragTool != null) {
            forceEndDrag(); // 拖拽中目标失效（删除节点/场景卸载）——工具收尾防残留
        } else if (dragTool != null && dragTarget != target) {
            forceEndDrag(); // 拖拽中选中变更（层级面板等外部改选）——按绑定目标收尾防跳变
        }

        if (gizmoNode.isActive() != hasTarget) {
            gizmoNode.setActive(hasTarget);
        }
        if (!hasTarget) {
            if (hoveredNode != null) {
                // gizmo 整体隐藏时清 hover 并熄灭高亮（防残留：再次亮起时旧高亮闪一帧）。
                // viewDir 为上帧值——隐藏期间不渲染，重显同帧即按新视线收敛
                hoveredNode = null;
                for (TransformTools tool : tools) {
                    tool.updateVisuals(null, viewDir);
                }
            }
            return;
        }

        // 视线方向 = 相机 → 选中目标（轴可选性/变灰基准，与鼠标位置无关——用户拍板
        // 2026-10-06：用鼠标射线会把屏幕边缘的射线偏角算进夹角，变灰随鼠标位置漂移）。
        // 取本帧拖拽 apply 前的目标位置（方向用途，拖拽位移误差无感）；目标贴相机
        // （距离≈0）退化回退相机前向
        target.getTransform().getWorldPosition(targetWorldPos);
        editorCamera.getNode().getTransform().getWorldPosition(camWorldPos);
        viewDir.set(targetWorldPos).sub(camWorldPos);
        if (viewDir.lengthSquared() < 1e-8f) {
            editorCamera.getNode().getTransform().getLocalToWorld().transformDirection(viewDir.set(0, 0, 1)).normalize();
        } else {
            viewDir.normalize();
        }

        // 鼠标 → 世界射线（hover 命中与拖拽平面求交用；screenToRay 方向归一化 → t 即世界距离）
        int viewportW = javelinEditor.viewportWidth();
        int viewportH = javelinEditor.viewportHeight();
        boolean viewportValid = viewportW > 0 && viewportH > 0;
        if (viewportValid) {
            editorCamera.screenToRay(Input.mouseX(), Input.mouseY(), viewportW, viewportH, pickRay);
        }

        // ── 拖拽状态机（纯 LMB：按下命中柄开始、持续 apply、释放结束；Alt+LMB 归相机环绕） ──
        if (dragTool != null) {
            if (Input.wasMouseReleased(MouseButton.LEFT)) {
                dragTool.endDrag();
                dragTool = null;
                dragTarget = null;
            } else if (viewportValid) {
                dragTool.updateDrag(target, pickRay); // hover 锁定拖拽轴，不重检测
            }
        } else if (viewportValid) {
            // hover 检测（每帧刷新，拖拽判定地基）
            Node detected = raycastToolNodes();
            if (detected != hoveredNode) {
                hoveredNode = detected;
                if (detected != null) {
                    log.info("射线检测命中轴节点：{}", detected.getName());
                } else {
                    log.info("射线检测离开轴节点");
                }
            }

            // LMB 按下命中柄 → 交所属工具开始拖拽（Alt 按下时左键归相机环绕不抢）
            if (Input.wasMousePressed(MouseButton.LEFT) && hoveredNode != null
                    && !Input.isDown(KeyCode.LEFT_ALT)) {
                TransformTools owner = toolOf(hoveredNode);
                if (owner != null) {
                    // camWorldPos 本帧已在视线方向计算处取值
                    if (owner.beginDrag(hoveredNode, target, pickRay, camWorldPos)) {
                        dragTool = owner;
                        dragTarget = target;
                        if (Input.wasMouseReleased(MouseButton.LEFT)) {
                            // 按下与释放同帧（极速点击）——立即收尾防拖拽态卡死
                            dragTool.endDrag();
                            dragTool = null;
                            dragTarget = null;
                        }
                    }
                }
            }
        }

        // 视觉状态同步（hover 与拖拽共用一通道：拖拽中 hover 锁定=被拖轴、松开时鼠标仍
        // 悬停则高亮自然保持；viewDir 供工具计算不可选轴变灰——与鼠标位置无关）。
        // 节点归属哪个工具哪个亮，其余收 null——跨工具互不干扰
        TransformTools highlightOwner = hoveredNode != null ? toolOf(hoveredNode) : null;
        for (TransformTools tool : tools) {
            tool.updateVisuals(tool == highlightOwner ? hoveredNode : null, viewDir);
        }

        // 跟随目标：gizmoNode 一次写（工具无关；拖拽 apply 先于此——同帧跟手不滞后一帧；
        // setLocalPosition 拷贝语义不外泄复用向量）
        target.getTransform().getWorldPosition(targetWorldPos);
        gizmoNode.getTransform().setLocalPosition(targetWorldPos);

        // 距离自适应缩放（屏幕恒定尺寸口径；camWorldPos 本帧已在视线方向计算处取值）
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
     * 射线检测当前命中的轴 Node（null = 未命中/未激活；update 每帧刷新）——
     * 拖拽判定的消费口。
     */
    public Node getHoveredNode() {
        return hoveredNode;
    }

    /**
     * 当前射线检测命中的轴（轴节点 userData 标，key={@link Axis#USER_DATA_KEY}；
     * null = 未命中或命中未打标的柄——旋转/缩放工具的柄将来可不打轴标只判节点）。
     * 轴判别不解析节点名。
     */
    public Axis getHoveredAxis() {
        if (hoveredNode == null) {
            return null;
        }
        Object axis = hoveredNode.getUserData().get(Axis.USER_DATA_KEY);
        return axis instanceof Axis a ? a : null;
    }

    /**
     * 活动工具子树的碰撞体射线检测（{@link #pickRay}）：最近命中返回所在轴
     * Node，未命中返回 null（t 以 pickRay.direction 实长为单位，跨轴可比）。
     * 命中候选再经 {@link TransformTools#isSelectable} 工具裁决（视线基准 =
     * {@link #viewDir}，如移动轴与视线近平行端视不可选）——不可选候选视同
     * 未命中，更远的可选候选仍可胜出。
     */
    private Node raycastToolNodes() {
        Node hitNode = null;
        float bestT = -1f;
        for (TransformTools tool : tools) {
            Node toolNode = tool.getToolNode();
            if (toolNode == null || !toolNode.isActive()) {
                continue;
            }
            for (Transform child : toolNode.getTransform()) {
                Node childNode = child.getNode();
                for (Collider collider : childNode.getComponents(Collider.class)) {
                    float t = collider.collideWithRay(pickRay, hitPointBuf, hitNormalBuf);
                    if (t >= 0f && (bestT < 0f || t < bestT) && tool.isSelectable(childNode, viewDir)) {
                        bestT = t;
                        hitNode = childNode;
                    }
                }
            }
        }
        return hitNode;
    }

    /**
     * 拖拽/悬停节点所属工具（沿 Transform 父链找 toolNode 同一性；
     * null = 不属任何工具——射线只打工具子树，理论不可达的防御）。
     */
    private TransformTools toolOf(Node node) {
        for (TransformTools tool : tools) {
            for (Transform t = node.getTransform(); t != null; t = t.getParent()) {
                if (t.getNode() == tool.getToolNode()) {
                    return tool;
                }
            }
        }
        return null;
    }

    /** 强制结束拖拽（目标失效/选中变更——工具收尾（恢复轴高亮等）+ 状态清空） */
    private void forceEndDrag() {
        dragTool.endDrag();
        dragTool = null;
        dragTarget = null;
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
