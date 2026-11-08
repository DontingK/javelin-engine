package cn.donting.javelin.editor.appstate.gizmo;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.mesh.GizmoMeshes;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * 旋转工具（2026-11-08 落地，Babylon {@code planeRotationGizmo} 语义）。
 *
 * <p>三环 = 三个互相垂直的平面旋转柄：{@link GizmoMeshes#ring()} 的 XZ 平面圆环（法线 +Y）
 * 经节点旋转把法线转到目标轴（X 环 {@code rotationZ(-90°)}、Z 环 {@code rotationX(+90°)}，
 * 与移动轴同惯例），环法线即该环的旋转轴。红/绿/蓝 = XYZ，视觉三态走
 * {@link GizmoVisuals}。</p>
 *
 * <p><b>拾取</b>：解析式<b>环带</b>（{@link #pickHandle}）——可见环保持 Babylon 的细管径
 * （0.005），拾取带宽按 Babylon 粗环副本（0.03 管径）给，二者解耦（2026-11-08 用户实测
 * 「环太粗」的根治；CPU 射线，不进 GPU 拾取附件）。</p>
 *
 * <p><b>坐标空间</b>：驱动侧 Local 模式把 gizmoNode 旋转对齐目标世界旋转（World 恒单位）——
 * 于是同一套「读环世界法线」的代码在 Local 下自动成为绕目标局部轴旋转，World 下为绕世界轴，
 * 工具零分支。</p>
 *
 * <p><b>可选性（几何与移动轴相反）</b>：环与视线趋垂直（环侧视退化成一條线、拖拽角度求交
 * 病态）时不可选并变灰——Babylon {@code PlaneRotationGizmo.MaxDragAngle = 9π/20 ≈ 81°} 同口径
 * （环法线与视线夹角 &lt; 81° 才可抓，即 {@code |cos| >= 0.156}）。</p>
 *
 * <p><b>拖拽数学（增量式，与 Babylon 同构但省掉相机侧翻转补偿）</b>：环平面即拖拽面
 * （过枢轴、法线 = 环世界法线），每帧求鼠标射线与环平面交点，与上一帧交点相对枢轴的两个
 * 矢量求<b>绕轴带符号夹角</b> ——{@code atan2(cross(v0,v1)·axis, v0·v1)}，再以该环法线为轴
 * 构造四元数增量乘进目标局部旋转。带符号夹角已含相机在环哪一侧的信息，故不需要 Babylon 的
 * {@code cameraFlipped}/{@code halfCircleSide} 翻符号补偿；行为判据 = 抓住的点跟着鼠标走。</p>
 *
 * <p><b>增量回写</b>：轴世界方向经父链世界旋转逆变换进父空间，得局部增量
 * {@code deltaLocal = R(parentInv·axisWorld, angle)}，目标 {@code newLocal = deltaLocal × local}
 * （父空间轴上转 ⟺ 世界轴转——共轭恒等式）。父链旋转在拖拽期间不变（beginDrag 取一次）。
 * Local/World 模式下环法线每帧重读（Local 下环随目标旋转，轴随动——Babylon 同款）。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public class RotateTool extends TransformTools {

    private static final Logger log = LoggerFactory.getLogger(RotateTool.class);

    /**
     * 环法线与视线夹角阈值（|cos| 下限 = cos(81°) ≈ 0.156——低于此值环侧视成线不可选；
     * Babylon {@code PlaneRotationGizmo.MaxDragAngle}，实测手感可调）
     */
    private static final float RING_EDGE_ON_COS = 0.156f;

    /** 环节点（数组下标 = Axis 枚举序 X/Y/Z；init 填充，失败留 null） */
    private final Node[] ringNodes = new Node[3];

    /** 各环当前视觉状态（与 ringNodes 平行；null = 尚未写过色，首帧必写） */
    private final GizmoVisuals.State[] ringStates = new GizmoVisuals.State[3];

    // ---------------- 拖拽几何状态（beginDrag 建立，updateDrag 消费） ----------------

    /** 拖拽中的环节点（拖拽锁定的柄；环世界法线每帧由它重取——Local 模式轴随目标旋转） */
    private Node dragRing;

    /** 枢轴 = 按下时目标世界位置（环平面过点、夹角基准） */
    private final Vector3f pivot = new Vector3f();

    /** 环世界法线（本帧读取，= 旋转轴；环法线方向即该轴正向） */
    private final Vector3f ringAxis = new Vector3f();

    /** 上一帧交点相对枢轴的矢量（增量夹角基准） */
    private final Vector3f prevArm = new Vector3f();

    /** 目标父链世界旋转（beginDrag 取一次——拖拽期间父链不动，只用于把轴换进父空间） */
    private final Quaternionf parentWorldRot = new Quaternionf();

    /** 父链临时列表（组合用，clear + 逆序下标，热路径列表复用口径） */
    private final ArrayList<Transform> chainBuf = new ArrayList<>();

    // ---------------- 复用缓冲（每帧零分配） ----------------

    private final Vector3f rayPoint = new Vector3f();
    private final Vector3f curArm = new Vector3f();
    private final Vector3f crossBuf = new Vector3f();
    private final Vector3f axisInParent = new Vector3f();
    private final Vector3f planeScratch = new Vector3f();

    /** 可选性判定的环方向缓冲（与拖拽轴 ringAxis 分离——同帧多环调用不破坏进行中拖拽） */
    private final Vector3f ringAxisBuf = new Vector3f();

    private final Quaternionf deltaRot = new Quaternionf();
    private final Quaternionf localRot = new Quaternionf();
    private final Quaternionf newLocalRot = new Quaternionf();

    @Override
    protected void init(AssetManager assetManager, JavelinEngine javelinEngine) {
        Mesh ringMesh = GizmoMeshes.ring();
        Shader shader = assetManager.loadAsset(GizmoVisuals.SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("Gizmo 着色器加载失败 path={}（Library 缺产物或索引未建），旋转工具不可用",
                    GizmoVisuals.SHADER_PATH);
            return;
        }
        toolNode = new Node();
        toolNode.setName("RotateTool");
        Transform rootTransform = toolNode.getTransform();

        // 环本体躺 XZ 平面（法线 +Y）：+Y 环不转、+X 环绕 Z -90°、+Z 环绕 X +90°
        String[] names = {"RotateToolRing-X", "RotateToolRing-Y", "RotateToolRing-Z"};
        Axis[] axes = {Axis.X, Axis.Y, Axis.Z};
        for (int i = 0; i < 3; i++) {
            Node node = new Node();
            node.setName(names[i]);
            node.getUserData().put(Axis.USER_DATA_KEY, axes[i]);
            Transform transform = node.getTransform();
            if (i == 0) {
                transform.setLocalRotation(new Quaternionf().rotationZ((float) Math.toRadians(-90.0)));
            } else if (i == 2) {
                transform.setLocalRotation(new Quaternionf().rotationX((float) Math.toRadians(90.0)));
            }
            transform.setParent(rootTransform);
            MeshRenderer renderer = node.addComponent(MeshRenderer.class);
            renderer.setMesh(ringMesh);
            renderer.setMaterial(GizmoVisuals.newMaterial(shader, GizmoVisuals.RING_COLORS[i]));
            renderer.setCastShadow(false);
            ringNodes[i] = node;
        }
        log.info("旋转工具已创建（程序化三环 + 解析式环带拾取，法线 = X/Y/Z = 各自旋转轴）");
    }

    @Override
    protected void update() {
        // 选中解析/显隐/位置对齐由 TransformGizmoAppState 驱动，工具侧无每帧自持状态
    }

    // ---------------- 可选性与视觉三态（驱动侧每帧同步） ----------------

    @Override
    protected boolean isSelectable(Node node, Vector3f viewDir) {
        return ringSelectable(node, viewDir);
    }

    @Override
    protected void updateVisuals(Node highlightedNode, Vector3f viewDir) {
        GizmoVisuals.sync(ringNodes, ringStates, GizmoVisuals.RING_COLORS,
                highlightedNode, viewDir, this::ringSelectable);
    }

    // ---------------- 柄拾取（解析式环带——L0 无圆环碰撞形状） ----------------

    /** 照射线进环本地空间的复用缓冲（每帧零分配） */
    private final Matrix4f ringInv = new Matrix4f();
    private final Vector3f localOrigin = new Vector3f();
    private final Vector3f localDirection = new Vector3f();

    /**
     * 柄拾取（覆写基类的碰撞体扫描）：环在 L0 里没有对应碰撞形状（只有
     * Box/Sphere/Cylinder），按<b>解析式环带</b>判定——即 Babylon「加粗圆环副本」的
     * 等价表达：射线与环平面求交，交点离环心的距离落在 {@code [R − tol, R + tol]}
     * 即命中（tol = {@link GizmoMeshes#RING_PICK_TOLERANCE}，正是 Babylon 粗环管径
     * 0.03 的半径）。判定在环节点本地空间做（环躺本地 XZ 平面、法线 +Y），模型单位
     * 随工具节点缩放 → 屏幕恒定宽容度，与可见细环解耦。
     */
    @Override
    protected Node pickHandle(Ray ray) {
        Node hitNode = null;
        float bestT = -1f;
        for (Node ring : ringNodes) {
            if (ring == null) {
                continue;   // init 失败防御
            }
            float t = rayRingBand(ring, ray);
            if (t >= 0f && (bestT < 0f || t < bestT)) {
                bestT = t;
                hitNode = ring;
            }
        }
        return hitNode;
    }

    /**
     * 射线 ∩ 环带（环节点本地空间：环平面 = 本地 y=0，环心 = 原点）。
     *
     * @return 世界 t（射线仿射变换保参数——局部方向不归一化，t 即世界距离，可跨环比较）；
     *         平行/背后/带外返回 -1
     */
    private float rayRingBand(Node ring, Ray ray) {
        ringInv.set(ring.getTransform().getLocalToWorld()).invert();
        ringInv.transformPosition(localOrigin.set(ray.getOrigin()));
        ringInv.transformDirection(localDirection.set(ray.getDirection()));
        if (Math.abs(localDirection.y) < 1e-6f) {
            return -1f;   // 射线平行于环平面（环侧视——正常已被可选性闸门挡住）
        }
        float t = -localOrigin.y / localDirection.y;
        if (t <= 0f) {
            return -1f;   // 交点落在相机背后
        }
        float px = localOrigin.x + localDirection.x * t;
        float pz = localOrigin.z + localDirection.z * t;
        float offset = (float) Math.sqrt(px * px + pz * pz) - GizmoMeshes.RING_DIAMETER / 2f;
        return Math.abs(offset) <= GizmoMeshes.RING_PICK_TOLERANCE ? t : -1f;
    }

    /**
     * 环可选性判定（{@link #isSelectable} 闸门与 {@link GizmoVisuals#sync} 变灰同源）：
     * 环法线与视线夹角小于 {@link #RING_EDGE_ON_COS} 对应角（≈81°）时环面朝相机足够、
     * 可抓；趋垂直即环侧视成线（屏幕上退化为线段、角度求交病态）不可选。
     */
    private boolean ringSelectable(Node node, Vector3f viewDir) {
        ringAxisBuf.set(0, 1, 0);
        node.getTransform().getLocalToWorld().transformDirection(ringAxisBuf).normalize();
        return Math.abs(ringAxisBuf.dot(viewDir)) >= RING_EDGE_ON_COS;
    }

    // ---------------- 拖拽实现（环平面增量夹角） ----------------

    @Override
    protected boolean beginDrag(Node axisNode, Node target, Ray pickRay, Vector3f camWorldPos) {
        dragRing = axisNode;
        target.getTransform().getWorldPosition(pivot);
        if (!readRingAxis()) {
            dragRing = null;
            return false;
        }
        // 父链世界旋转（组合口径与驱动侧 applyTargetWorldRotation 一致：自根向下 mul）
        chainBuf.clear();
        for (Transform t = target.getTransform().getParent(); t != null; t = t.getParent()) {
            chainBuf.add(t);
        }
        parentWorldRot.identity();
        for (int i = chainBuf.size() - 1; i >= 0; i--) {
            parentWorldRot.mul(chainBuf.get(i).getLocalRotation());
        }

        float t = intersectRingPlane(pickRay);
        if (Float.isNaN(t)) {
            dragRing = null;
            return false; // 按下帧射线与环平面近平行（环侧视，正常已被可选性闸门挡住）
        }
        prevArm.set(rayPoint).sub(pivot);
        if (prevArm.lengthSquared() < 1e-10f) {
            dragRing = null;
            return false; // 交点落在枢轴上（夹角无定义）
        }
        log.info("开始拖拽：环节点={} 目标={} 旋转轴=({}, {}, {})", axisNode.getName(), target.getName(),
                ringAxis.x, ringAxis.y, ringAxis.z);
        return true;
    }

    @Override
    protected void updateDrag(Node target, Ray pickRay) {
        if (!readRingAxis()) {
            return;
        }
        if (Float.isNaN(intersectRingPlane(pickRay))) {
            return; // 射线与环平面近平行——保持上一帧姿态
        }
        curArm.set(rayPoint).sub(pivot);
        if (curArm.lengthSquared() < 1e-10f) {
            return; // 交点落在枢轴上（夹角无定义）
        }

        // 绕环法线的带符号夹角（右手系；带符号本身已含相机侧信息，无需翻符号补偿）
        crossBuf.set(prevArm).cross(curArm);
        float angle = (float) Math.atan2(crossBuf.dot(ringAxis), prevArm.dot(curArm));
        prevArm.set(curArm);
        if (Math.abs(angle) < 1e-7f) {
            return; // 帧间零转角（抖动域）——不写回避免噪声累积
        }

        // 轴世界方向 → 父空间（父链旋转拖拽期间不变），构造局部增量四元数
        axisInParent.set(ringAxis);
        parentWorldRot.transformInverse(axisInParent);
        deltaRot.rotationAxis(angle, axisInParent);

        // newLocal = deltaLocal × local（父空间轴上转 ⟺ 世界轴上转——共轭恒等式；
        // getLocalRotation 返回内部活对象，先拷贝再算）
        localRot.set(target.getTransform().getLocalRotation());
        deltaRot.mul(localRot, newLocalRot);
        newLocalRot.normalize();
        target.getTransform().setLocalRotation(newLocalRot);
    }

    @Override
    protected void endDrag() {
        dragRing = null;
        // 视觉由驱动侧 updateVisuals 同步（松开时鼠标仍悬停则高亮自然保持）
        log.info("结束拖拽");
    }

    /** 读取拖拽环的世界法线（+Y 经节点/父链变换；归一化失败 = 退化，返回 false） */
    private boolean readRingAxis() {
        if (dragRing == null) {
            return false;
        }
        ringAxis.set(0, 1, 0);
        dragRing.getTransform().getLocalToWorld().transformDirection(ringAxis);
        if (ringAxis.lengthSquared() < 1e-12f) {
            return false;
        }
        ringAxis.normalize();
        return true;
    }

    /**
     * 鼠标射线 ∩ 环平面（过枢轴、法线 = {@link #ringAxis}），交点写入 {@link #rayPoint}。
     *
     * @return 交点在射线参数 t（&lt;= 0 = 平面在相机背后，视同未命中）；近平行/无交返回 NaN
     */
    private float intersectRingPlane(Ray ray) {
        Vector3f direction = ray.getDirection();
        float denom = direction.dot(ringAxis);
        if (Math.abs(denom) < 1e-6f) {
            return Float.NaN;
        }
        planeScratch.set(pivot).sub(ray.getOrigin());
        float t = planeScratch.dot(ringAxis) / denom;
        if (t <= 0f) {
            return Float.NaN;
        }
        rayPoint.set(ray.getOrigin()).add(direction.x * t, direction.y * t, direction.z * t);
        return t;
    }

    @Override
    protected String getName() {
        return "Rotate";
    }
}
