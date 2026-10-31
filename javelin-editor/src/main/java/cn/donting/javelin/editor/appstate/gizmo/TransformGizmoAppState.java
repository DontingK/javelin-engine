package cn.donting.javelin.editor.appstate.gizmo;

import cn.donting.javelin.editor.selection.EditorSelection;
import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.input.MouseButton;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.SceneSelectEvent;
import cn.donting.javelin.editor.event.Subscription;
import cn.donting.javelin.editor.scene.EditorScene;
import org.joml.Quaternionf;
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
 * {@link SceneSelectEvent} 经 {@link EditorSelection}
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
 * <p>hover 拾取（2026-10-23 全线统一拍板，Engine_Picking_Design.md）：轴命中改
 * GPU ID-buffer——PickPass 把轴几何画进拾取附件（深度关置顶），PickSystem
 * hover 读回结果经 {@link #setHoveredFromPick} 帧末直写（1~2 帧延迟、静止指针
 * 零读回）；本类 update 消费时再经 {@link TransformTools#isSelectable} 工具
 * 裁决（2026-10-06：移动轴与视线趋于平行端视时不可选——屏幕上轴退化为点、
 * 拖拽投影病态）；{@link #getHoveredNode()} 暴露命中轴 Node（hover 变更打
 * 日志）、{@link #getHoveredAxis()} 经 userData 标判别轴——轴拖拽判定的地基。</p>
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
 * <p>工具切换与坐标空间（2026-10-14，场景视图工具栏消费口）：桥线程 /gizmo/setTool、
 * /gizmo/setSpace 只写本类 static volatile 意图镜像（跨线程镜像先例=GridMenuCommand，
 * AppStateManager 非并发勿跨线程 getState），update 帧内消费——切工具先强制收尾拖拽、
 * 旧工具 hide 新工具 show（TransformTools 契约预留的切换位）；坐标空间 Local = gizmoNode
 * 旋转跟随目标世界旋转（父链四元数自根向下组合，避开从含非均匀缩放的世界矩阵提旋转）、
 * World = 恒单位（现状语义）——仅影响 Gizmo 朝向与拖拽轴（Inspector 恒 local，Unity
 * Global/Local 口径），工具层轴方向走箭头世界矩阵零改动直通。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class TransformGizmoAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(TransformGizmoAppState.class);

    /**
     * 距离自适应分母：箭头世界长 ≈ 9.9 × 相机距离 / 本值；80 → 箭头约占视口 1/8（实测定稿可调）
     */
    private static final float TOOLS_SCALE = 5;

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

    /** 工具切换意图（桥线程 /gizmo/setTool 直写；update 帧内按 getName 解析消费——查名落空维持现状；编辑器单实例进程级） */
    private static volatile String desiredToolName;

    /** 坐标空间（true=局部：gizmoNode 旋转跟随目标世界旋转；false=世界=恒单位，现状语义）。桥线程 /gizmo/setSpace 直写、update 每帧读取 */
    private static volatile boolean localSpace;

    /** 当前激活工具（onAttach 默认首个；切换 = 拖拽中先强制收尾 + 旧 hide 新 show） */
    private TransformTools activeTool;

    /** 桥线程写入：工具切换意图（工具名=TransformTools.getName 口径、线上惯例小写，解析大小写不敏感） */
    public static void setDesiredToolName(String toolName) {
        desiredToolName = toolName;
    }

    /** 桥线程写入：坐标空间（true=局部） */
    public static void setLocalSpace(boolean local) {
        localSpace = local;
    }

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

    /** 视线方向复用向量（相机→选中目标、归一化；轴可选性/变灰基准——与鼠标位置无关） */
    private final Vector3f viewDir = new Vector3f();

    /** 目标世界旋转复用四元数（Local 空间每帧父链组合；setLocalRotation 拷贝语义不外泄） */
    private final Quaternionf worldRotBuf = new Quaternionf();

    /** 单位旋转复用四元数（World 空间每帧回写恒单位） */
    private final Quaternionf identityRot = new Quaternionf();

    /** 目标父链复用列表（Local 空间旋转组合：clear+逆序下标循环，热路径列表复用口径） */
    private final ArrayList<Transform> rotationChainBuf = new ArrayList<>();

    /** 当前射线检测命中的轴 Node（null = 未命中；update 每帧刷新，拖拽中锁定不刷新） */
    private Node hoveredNode;
    /** PickSystem hover 结果直写位（GPU ID-buffer 拾取，ViewportPickAppState 帧末转发；null = 离开轴/点空） */
    private Node pickHoverNode;

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
        activeTool = tools.getFirst();
        activeTool.show();

        // 置顶 Pass 挂管线末尾（Opaque→Transparent→Gizmo）：颜色 LOAD 保画 + 深度 CLEAR，
        // gizmo 恒最前；绘制对象 = RenderList 的 "Gizmo" Pass 桶（严格制，2026-10-17——
        // shader 由 MoveTool.init 各自加载建材质，收集侧按 Pass 名分桶，无需在此过滤）
        gizmoPass = new GizmoPass();
        engine.renderPipeline().addPass(gizmoPass);

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
        activeTool = null;
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

        // ── 工具切换意图消费（桥线程 /gizmo/setTool 写 static 镜像；查名落空维持现状） ──
        TransformTools desiredTool = resolveDesiredTool();
        if (desiredTool != null && desiredTool != activeTool) {
            if (dragTool != null) {
                forceEndDrag(); // 拖拽中切工具——先按绑定目标收尾防跳变（点击工具栏时拖拽本应已松开，防御位）
            }
            if (activeTool != null) {
                activeTool.hide();
            }
            desiredTool.show();
            activeTool = desiredTool;
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

        // 鼠标 → 世界射线（拖拽平面求交用；hover 命中已迁 GPU ID-buffer——
        // screenToRay 方向归一化 → t 即世界距离）
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
            // hover 检测（GPU ID-buffer，Engine_Picking_Design.md）：PickSystem hover 结果
            // （setHoveredFromPick 帧末直写，1~2 帧延迟）+ 工具裁决过滤——轴归属（toolOf）
            // 与可选择性（isSelectable，视线近平行变灰）与原射线线同口径
            Node detected = null;
            if (pickHoverNode != null) {
                TransformTools owner = toolOf(pickHoverNode);
                if (owner != null && owner.isSelectable(pickHoverNode, viewDir)) {
                    detected = pickHoverNode;
                }
            }
            if (detected != hoveredNode) {
                hoveredNode = detected;
                if (detected != null) {
                    log.info("GPU 拾取命中轴节点：{}", detected.getName());
                } else {
                    log.info("GPU 拾取离开轴节点");
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

        // 坐标空间跟随（/gizmo/setSpace）：Local = gizmoNode 旋转对齐目标世界旋转（父链
        // 四元数自根向下 mul 组合——避开从含非均匀缩放的世界矩阵提旋转；gizmoNode 根级
        // local==world，setLocalRotation 即世界朝向）；World = 恒单位（现状语义）。工具层
        // 轴方向/hover/变灰走箭头世界矩阵，gizmoNode 旋转后同一代码直通（设计预留兑现）
        if (localSpace) {
            applyTargetWorldRotation(target);
        } else {
            gizmoNode.getTransform().setLocalRotation(identityRot);
        }

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
     * 目标世界旋转组合进 {@link #worldRotBuf} 并回写 gizmoNode（Local 坐标空间）：
     * 父链自根向下 mul——world = root × … × parent × local（mul 累乘语义 this = this × q）。
     * 链表复用（clear + 逆序下标）防每帧分配。
     */
    private void applyTargetWorldRotation(Node target) {
        rotationChainBuf.clear();
        for (Transform t = target.getTransform(); t != null; t = t.getParent()) {
            rotationChainBuf.add(t);
        }
        worldRotBuf.identity();
        for (int i = rotationChainBuf.size() - 1; i >= 0; i--) {
            worldRotBuf.mul(rotationChainBuf.get(i).getLocalRotation());
        }
        gizmoNode.getTransform().setLocalRotation(worldRotBuf);
    }

    /**
     * 解析工具切换意图：null = 无请求维持现状；按名字查注册表（线上惯例小写、
     * 与 getName() 大小写不敏感比对）；查名落空（如 rotate/scale 未注册）维持现状。
     */
    private TransformTools resolveDesiredTool() {
        String name = desiredToolName;
        if (name == null) {
            return activeTool;
        }
        for (TransformTools tool : tools) {
            if (name.equalsIgnoreCase(tool.getName())) {
                return tool;
            }
        }
        return activeTool;
    }

    /**
     * 当前 hover 命中的轴 Node（null = 未命中/未激活；update 每帧刷新）——
     * 拖拽判定的消费口。命中源 = GPU ID-buffer 拾取（PickSystem hover 读回）。
     */
    public Node getHoveredNode() {
        return hoveredNode;
    }

    /**
     * PickSystem hover 结果直写（ViewportPickAppState 帧末转发，引擎线程；
     * 本帧 update 消费——含工具裁决过滤，见 update 内 hover 检测段）。
     */
    public void setHoveredFromPick(Node node) {
        this.pickHoverNode = node;
    }

    /** 拖拽进行中（ViewportPickAppState 点击门控：拖拽中不发改选中） */
    public boolean isDragging() {
        return dragTool != null;
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
     * 拖拽/悬停节点所属工具（沿 Transform 父链找 toolNode 同一性；
     * null = 不属任何工具——GPU 拾取命中用户物体等非工具节点时的过滤位）。
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
