package cn.donting.javelin.editor.appstate.gizmo;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.CylinderCollider;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.mesh.GizmoMeshes;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 移动工具（TransformTools 首个实现，2026-10-04；拖拽落地 2026-10-06）。
 *
 * <p>三轴移动指示：{@link GizmoMeshes#arrow()} 的 +Y 箭头（杆 + 锥头）复用旋转
 * 出三轴（+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°，同一 Mesh 三节点共享；几何参数照
 * Babylon {@code axisDragGizmo}，2026-11-08 改程序化生成、原 axisY.smesh 退役）。
 * 红/绿/蓝 = XYZ 惯例，视觉状态（本色/高亮/变灰）走 {@link GizmoVisuals} 单点。
 * 子树归编辑子场景口径（不进用户树、不落盘、GAME 模式自动不可见）。尺度不自持：
 * 由 TransformGizmoAppState 距离自适应统一下发（× scaleFactor()），不随选中物体
 * 缩放（Unity 移动轴口径）。</p>
 *
 * <p>柄 hover/拖拽判定走 <b>CPU 射线打拾取体</b>（2026-11-08 用户拍板回撤 GPU ID-buffer
 * 路线：ID 附件「看得见即拾得」，轴细就不好选、环只能加粗；改按 Babylon colliderMeshes
 * 口径——可见几何与拾取体解耦，拾取体更粗）：每轴挂一个加粗
 * {@link CylinderCollider}（{@link GizmoMeshes#AXIS_PICK_RADIUS}，包住可见锥头），
 * 由 {@link TransformTools#pickHandle} 取最近命中。场景物体拾取仍走 GPU ID-buffer
 * （Engine_Picking_Design.md；Gizmo 不再进拾取附件）。柄节点经 userData 打轴标
 * （key={@link Axis#USER_DATA_KEY}）供 hover/拖拽判别轴，不解析节点名。</p>
 *
 * <p><b>可选性与视觉三态（2026-10-06 用户需求）</b>：轴与视线趋于平行
 * （|cos| 夹角超 {@link TransformTools#axisHandleSelectable} 阈值 ≈ 10°）时不可选并
 * <b>变灰</b>——视线 = 相机 → 选中目标方向（与鼠标位置无关，用户拍板
 * 2026-10-06——用鼠标射线会把屏幕边缘的射线偏角算进夹角，变灰随鼠标位置漂移）。
 * 端视时屏幕上轴退化为点、拖拽平面退化走回退、投影被 1/sin(夹角) 放大拖拽病态
 * （典型收益：俯视场景时 Y 轴让位，XZ 拖拽不被误抓）。视觉经驱动侧每帧
 * {@link #updateVisuals} 同步（幂等）：正常 RGB / 高亮黄（hover 与拖拽共用，
 * 拖拽中 hover 锁定即被拖轴）/ 不可选灰——判定与 {@link #isSelectable} 同源
 * （视觉与可选性一致），优先级高亮 &gt; 变灰（hover 入口已被可选性闸门，高亮
 * 轴必可选）。</p>
 *
 * <p><b>拖拽（2026-10-06，平面相交法）</b>：拖拽开始锁定一张过锚点（按下时
 * 目标世界位置）、含拖拽轴、对相机最正交的平面（法线 = 视线去掉轴分量）；每帧
 * 鼠标射线与平面求交，交点沿轴的投影差即世界位移（screenToRay 方向归一化 →
 * 投影即世界距离；初始投影作 offset 补偿，按下瞬间零位移不跳）。轴方向 = 轴
 * 箭头节点世界 +Y（父链无旋转 = Global 世界轴口径；Local 模式 gizmoNode 随节点
 * 旋转后同一代码直通）。世界位移经父矩阵逆回写 local（父旋转/缩放下世界平移
 * ≠ local 平移）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class MoveTool extends TransformTools {

    private static final Logger log = LoggerFactory.getLogger(MoveTool.class);

    /** 三轴节点（数组下标 = Axis 枚举序 X/Y/Z；init 填充，失败留 null） */
    private final Node[] axisNodes = new Node[3];

    /** 各轴当前视觉状态（与 axisNodes 平行；null = 尚未写过色，首帧必写） */
    private final GizmoVisuals.State[] axisStates = new GizmoVisuals.State[3];

    // ---------------- 拖拽几何状态（beginDrag 建立，updateDrag 消费） ----------------

    /** 拖拽轴世界方向（轴箭头本地 +Y 经世界变换） */
    private final Vector3f dragAxis = new Vector3f();

    /** 拖拽平面法线（视线去掉轴分量——平面含轴且对相机最正交） */
    private final Vector3f dragPlaneNormal = new Vector3f();

    /** 拖拽锚点 = 按下时目标世界位置（平面过点、位移基准） */
    private final Vector3f dragAnchor = new Vector3f();

    /** 按下时鼠标射线在轴上的投影（offset 补偿——按下瞬间零位移） */
    private float dragStartProj;

    // ---------------- 复用缓冲（每帧零分配） ----------------

    private final Vector3f planePoint = new Vector3f();
    private final Vector3f worldNew = new Vector3f();
    private final Vector3f localNew = new Vector3f();
    private final Vector3f dragScratch = new Vector3f();
    private final Matrix4f parentInv = new Matrix4f();

    @Override
    protected void init(AssetManager assetManager, JavelinEngine javelinEngine) {
        Mesh arrowMesh = GizmoMeshes.arrow();
        Shader shader = assetManager.loadAsset(GizmoVisuals.SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("Gizmo 着色器加载失败 path={}（Library 缺产物或索引未建），移动工具不可用",
                    GizmoVisuals.SHADER_PATH);
            return;
        }
        toolNode = new Node();
        toolNode.setName("MoveTool");
        Transform rootTransform = toolNode.getTransform();
        // 尺度不在此设：由 TransformGizmoAppState 每帧距离自适应下发（scaleFactor 参与）

        // RGB = XYZ；+Y 本体、+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°（同一箭头 Mesh 复用旋转）
        String[] names = {"MoveToolAxis-X", "MoveToolAxis-Y", "MoveToolAxis-Z"};
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
            renderer.setMesh(arrowMesh);
            renderer.setMaterial(GizmoVisuals.newMaterial(shader, GizmoVisuals.AXIS_COLORS[i]));
            renderer.setCastShadow(false);
            // 拾取体（与可见几何解耦的加粗圆柱，Babylon colliderMeshes 口径）：沿本地 +Y
            // 覆盖整条箭头，半径比可见杆粗一个量级——轴细也能抓（2026-11-08 用户实测
            // 「轴太细不好选」的根治；CPU 射线检测，不进拾取附件）
            CylinderCollider pick = node.addComponent(CylinderCollider.class);
            pick.setRadius(GizmoMeshes.AXIS_PICK_RADIUS);
            pick.setHeight(GizmoMeshes.ARROW_LENGTH);
            pick.getCenter().set(0f, GizmoMeshes.ARROW_LENGTH / 2f, 0f);
            axisNodes[i] = node;
        }
        log.info("移动工具已创建（程序化三轴箭头 + 加粗圆柱拾取体，红/绿/蓝 = X/Y/Z，尺度随驱动侧距离自适应）");
    }

    @Override
    protected void update() {
        // 选中解析/显隐/位置对齐暂由 TransformGizmoAppState 驱动，工具管理器改造时迁入本方法
    }

    // ---------------- 可选性与视觉三态（驱动侧每帧同步） ----------------

    @Override
    protected boolean isSelectable(Node node, Vector3f viewDir) {
        return axisHandleSelectable(node, viewDir);
    }

    @Override
    protected void updateVisuals(Node highlightedNode, Vector3f viewDir) {
        GizmoVisuals.sync(axisNodes, axisStates, GizmoVisuals.AXIS_COLORS,
                highlightedNode, viewDir, this::axisHandleSelectable);
    }

    // ---------------- 拖拽实现（平面相交法） ----------------

    @Override
    protected boolean beginDrag(Node axisNode, Node target, Ray pickRay, Vector3f camWorldPos) {
        // 轴世界方向 = 轴箭头本地 +Y 经世界变换（X/Z 轴经节点旋转指向；距离自适应为均匀
        // 缩放，transformDirection 后 normalize 方向不变）
        axisNode.getTransform().getLocalToWorld().transformDirection(dragAxis.set(0, 1, 0)).normalize();
        target.getTransform().getWorldPosition(dragAnchor);

        // 平面法线 = 视线（相机→锚点）去掉轴分量：平面含轴且被相机最正交地看（|d·n| 最大、
        // 交点最稳）；轴与视线近平行（视线垂直分量≈0，屏幕上轴退化为点）回退世界基向量
        dragPlaneNormal.set(dragAnchor).sub(camWorldPos);
        dragScratch.set(dragAxis).mul(dragPlaneNormal.dot(dragAxis));
        dragPlaneNormal.sub(dragScratch);
        if (dragPlaneNormal.lengthSquared() < 1e-8f) {
            dragPlaneNormal.set(0, 1, 0).cross(dragAxis);
            if (dragPlaneNormal.lengthSquared() < 1e-8f) {
                dragPlaneNormal.set(1, 0, 0).cross(dragAxis);
            }
        }
        dragPlaneNormal.normalize();

        float proj = rayProjection(pickRay);
        if (Float.isNaN(proj)) {
            return false; // 按下帧射线与拖拽平面近平行（极端掠射）——不开始
        }
        dragStartProj = proj;

        log.info("开始拖拽：轴节点={} 目标={}", axisNode.getName(), target.getName());
        return true;
    }

    @Override
    protected void updateDrag(Node target, Ray pickRay) {
        float proj = rayProjection(pickRay);
        if (Float.isNaN(proj)) {
            return; // 射线与平面近平行（极端掠射角）——保持上一帧位置
        }
        // 世界新位置 = 锚点 + 轴·(当前投影 - 初始投影)；按下瞬间投影差为 0 无跳动
        worldNew.set(dragAxis).mul(proj - dragStartProj).add(dragAnchor);

        // 世界 → local 回写：有父时经父世界矩阵逆变换（父旋转/缩放下世界平移 ≠ local 平移）；
        // 无父时父空间即世界空间。getLocalToWorld 返回缓存矩阵不可直接 invert——先 set 拷贝
        Transform transform = target.getTransform();
        Transform parent = transform.getParent();
        if (parent != null) {
            parentInv.set(parent.getLocalToWorld()).invert();
            parentInv.transformPosition(worldNew, localNew);
        } else {
            localNew.set(worldNew);
        }
        transform.setLocalPosition(localNew);
    }

    @Override
    protected void endDrag() {
        // 视觉由驱动侧 updateVisuals 同步（松开时鼠标仍悬停则高亮自然保持），此处无需收尾；
        // 拖拽几何状态亦无需清（下次 beginDrag 全量重建）
        log.info("结束拖拽");
    }

    /**
     * 当前射线与拖拽平面交点沿拖拽轴的投影（锚点为原点、世界距离计）。
     * 平面方程 (x − anchor)·n = 0 ⟹ t = (anchor − o)·n / (d·n)；screenToRay
     * 方向归一化故 t 即世界距离。射线与平面近平行（|d·n| &lt; ε）返回 NaN——
     * 调用方按跳过处理。
     */
    private float rayProjection(Ray ray) {
        Vector3f direction = ray.getDirection();
        float denom = direction.dot(dragPlaneNormal);
        if (Math.abs(denom) < 1e-6f) {
            return Float.NaN;
        }
        planePoint.set(dragAnchor).sub(ray.getOrigin());
        float t = planePoint.dot(dragPlaneNormal) / denom;
        planePoint.set(ray.getOrigin()).add(direction.x * t, direction.y * t, direction.z * t);
        return planePoint.sub(dragAnchor).dot(dragAxis);
    }

    @Override
    protected String getName() {
        return "Move";
    }
}
