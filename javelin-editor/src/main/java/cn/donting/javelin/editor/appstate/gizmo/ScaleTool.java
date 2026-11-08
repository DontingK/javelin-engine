package cn.donting.javelin.editor.appstate.gizmo;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.BoxCollider;
import cn.donting.javelin.core.scene.component.CylinderCollider;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.mesh.GizmoMeshes;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 缩放工具（2026-11-08 落地，Babylon {@code scaleGizmo} 语义）。
 *
 * <p>四柄：三条轴柄（{@link GizmoMeshes#scaleArrow()} 细杆 + 方块头，节点旋转出 X/Y/Z，
 * 与移动轴同惯例）+ 原点<b>中心均匀柄</b>（方块，一柄管三轴同倍缩放；Babylon/Unity 同款
 * 对等件）。红/绿/蓝 + 中心中性灰，视觉三态走 {@link GizmoVisuals}。</p>
 *
 * <p><b>坐标空间恒局部</b>（{@link #localAxesForced()}）：Unity 与 Babylon 均不支持世界轴
 * 缩放（Babylon 直接 {@code Logger.Warn("Setting coordinates Mode to world on scaling gizmo
 * is not supported")}）——世界轴缩放与父级非均匀缩放叠加无稳定语义；故工具栏的
 * Local/World 切换对本工具无效，轴柄恒随目标局部轴。</p>
 *
 * <p><b>拖拽（与 MoveTool 同款平面相交法，多一步归一）</b>：世界位移按 gizmo 箭头世界长度
 * 归一成比例增量——{@code factor = 1 + 投影差 / 箭头世界长}，即「沿轴拖过一个箭头长度 =
 * 该轴分量翻一倍」；均匀柄同式但 factor 乘三轴（其拖拽轴 = 工具 +Y，Babylon 同口径）。
 * 只写目标 localScale 的分量，故与父级缩放/旋转解耦（精确，无矩阵分解）。分量下限
 * {@link #MIN_ABS_SCALE} 保号钳制（Babylon {@code MinimumAbsoluteScale} 口径——避免 0 值
 * 翻转抖动）。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public class ScaleTool extends TransformTools {

    private static final Logger log = LoggerFactory.getLogger(ScaleTool.class);

    /**
     * 缩放分量下限（绝对值、保号；Babylon {@code AxisScaleGizmo.MinimumAbsoluteScale} 口径）
     */
    private static final float MIN_ABS_SCALE = 1e-3f;

    /** 柄索引：0..2 = X/Y/Z 轴柄，3 = 中心均匀柄 */
    private static final int CENTER_HANDLE = 3;

    /** 柄本色（与柄数组平行：XYZ 轴本色 + 中心中性灰） */
    private static final float[][] HANDLE_COLORS = {
            GizmoVisuals.AXIS_COLORS[0],
            GizmoVisuals.AXIS_COLORS[1],
            GizmoVisuals.AXIS_COLORS[2],
            GizmoVisuals.CENTER_COLOR,
    };

    /** 柄节点（init 填充，失败留 null） */
    private final Node[] handles = new Node[4];

    /** 各柄当前视觉状态（与 handles 平行；null = 尚未写过色，首帧必写） */
    private final GizmoVisuals.State[] handleStates = new GizmoVisuals.State[4];

    // ---------------- 拖拽几何状态（beginDrag 建立，updateDrag 消费） ----------------

    /** 拖拽柄判别（X/Y/Z = 单轴，UNIFORM = 三轴同倍） */
    private Axis dragAxisTag;

    /** 拖拽轴世界方向（轴柄 = 柄节点世界 +Y；中心柄 = 工具 +Y——柄无旋转，同一读法） */
    private final Vector3f dragAxis = new Vector3f();

    /** 拖拽平面法线（视线去掉轴分量——平面含轴且对相机最正交，同 MoveTool） */
    private final Vector3f dragPlaneNormal = new Vector3f();

    /** 拖拽锚点 = 按下时目标世界位置 */
    private final Vector3f dragAnchor = new Vector3f();

    /** 按下时的目标 localScale（比例基准） */
    private final Vector3f dragStartScale = new Vector3f();

    /** 按下时鼠标射线在轴上的投影（offset 补偿——按下瞬间比例 1 不跳） */
    private float dragStartProj;

    /** 箭头世界长度（归一基准 = 工具世界尺度 × 箭头模型全长） */
    private float gizmoLength;

    // ---------------- 复用缓冲（每帧零分配） ----------------

    private final Vector3f planePoint = new Vector3f();
    private final Vector3f dragScratch = new Vector3f();
    private final Vector3f newScale = new Vector3f();

    @Override
    protected void init(AssetManager assetManager, JavelinEngine javelinEngine) {
        Mesh axisMesh = GizmoMeshes.scaleArrow();
        Mesh centerMesh = GizmoMeshes.centerHandle();
        Shader shader = assetManager.loadAsset(GizmoVisuals.SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("Gizmo 着色器加载失败 path={}（Library 缺产物或索引未建），缩放工具不可用",
                    GizmoVisuals.SHADER_PATH);
            return;
        }
        toolNode = new Node();
        toolNode.setName("ScaleTool");
        Transform rootTransform = toolNode.getTransform();

        // RGB = XYZ；+Y 本体、+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°（同一柄 Mesh 复用旋转）
        String[] names = {"ScaleToolAxis-X", "ScaleToolAxis-Y", "ScaleToolAxis-Z"};
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
            renderer.setMesh(axisMesh);
            renderer.setMaterial(GizmoVisuals.newMaterial(shader, HANDLE_COLORS[i]));
            renderer.setCastShadow(false);
            // 拾取体（同移动工具：加粗圆柱覆盖整条轴柄，可见几何与拾取解耦）
            CylinderCollider pick = node.addComponent(CylinderCollider.class);
            pick.setRadius(GizmoMeshes.AXIS_PICK_RADIUS);
            pick.setHeight(GizmoMeshes.ARROW_LENGTH);
            pick.getCenter().set(0f, GizmoMeshes.ARROW_LENGTH / 2f, 0f);
            handles[i] = node;
        }

        // 中心均匀柄（原点方块，无旋转，柄标 UNIFORM）+ 略胖的方块拾取体
        Node center = new Node();
        center.setName("ScaleToolCenter");
        center.getUserData().put(Axis.USER_DATA_KEY, Axis.UNIFORM);
        center.getTransform().setParent(rootTransform);
        MeshRenderer centerRenderer = center.addComponent(MeshRenderer.class);
        centerRenderer.setMesh(centerMesh);
        centerRenderer.setMaterial(GizmoVisuals.newMaterial(shader, HANDLE_COLORS[CENTER_HANDLE]));
        centerRenderer.setCastShadow(false);
        BoxCollider centerPick = center.addComponent(BoxCollider.class);
        centerPick.setSize(new Vector3f(GizmoMeshes.CENTER_PICK_SIZE, GizmoMeshes.CENTER_PICK_SIZE,
                GizmoMeshes.CENTER_PICK_SIZE));
        handles[CENTER_HANDLE] = center;

        log.info("缩放工具已创建（程序化三轴柄 + 中心均匀柄 + 加粗拾取体，坐标轴恒局部）");
    }

    @Override
    protected void update() {
        // 选中解析/显隐/位置对齐由 TransformGizmoAppState 驱动，工具侧无每帧自持状态
    }

    @Override
    protected boolean localAxesForced() {
        return true;   // 缩放恒局部（Unity / Babylon 口径，见类注释）
    }

    // ---------------- 可选性与视觉三态（驱动侧每帧同步） ----------------

    @Override
    protected boolean isSelectable(Node node, Vector3f viewDir) {
        // 四柄同一条闸门：拖拽都沿「本柄世界 +Y」——轴柄经节点旋转指向轴、中心柄无旋转 =
        // 工具 +Y；端视近平行时投影病态不可选（中心柄同式，几何自洽）
        return axisHandleSelectable(node, viewDir);
    }

    @Override
    protected void updateVisuals(Node highlightedNode, Vector3f viewDir) {
        GizmoVisuals.sync(handles, handleStates, HANDLE_COLORS,
                highlightedNode, viewDir, this::axisHandleSelectable);
    }

    // ---------------- 拖拽实现（平面相交法 + 箭头长度归一） ----------------

    @Override
    protected boolean beginDrag(Node axisNode, Node target, Ray pickRay, Vector3f camWorldPos) {
        Object tag = axisNode.getUserData().get(Axis.USER_DATA_KEY);
        if (!(tag instanceof Axis axis)) {
            return false;   // 未打轴标（正常不会发生——四柄建时全打）
        }

        // 拖拽轴 = 柄节点世界 +Y；归一基准 = 工具世界尺度 × 箭头模型全长
        // （工具节点局部缩放即世界尺度——gizmoNode 只写位置/旋转、缩放全下发给工具节点）
        axisNode.getTransform().getLocalToWorld().transformDirection(dragAxis.set(0, 1, 0));
        if (dragAxis.lengthSquared() < 1e-12f) {
            return false;
        }
        dragAxis.normalize();
        float toolScale = toolNode.getTransform().getLocalScale().x;
        gizmoLength = toolScale * GizmoMeshes.ARROW_LENGTH;
        if (gizmoLength < 1e-6f) {
            return false;   // 驱动侧尚未下发尺度（异常态）——不开始
        }

        target.getTransform().getWorldPosition(dragAnchor);
        // getLocalScale 返回内部活对象——拷贝进基准缓冲（拖拽期间写 setLocalScale 不会污染基准）
        dragStartScale.set(target.getTransform().getLocalScale());
        // 平面法线 = 视线（相机→锚点）去掉轴分量；轴与视线近平行回退世界基向量（同 MoveTool）
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
            return false;   // 按下帧射线与拖拽平面近平行（极端掠射）——不开始
        }
        dragStartProj = proj;
        dragAxisTag = axis;   // 全部前置校验通过才落拖拽轴标（失败路径不留半态）

        log.info("开始拖拽：柄节点={} 目标={} 轴={}", axisNode.getName(), target.getName(), dragAxisTag);
        return true;
    }

    @Override
    protected void updateDrag(Node target, Ray pickRay) {
        float proj = rayProjection(pickRay);
        if (Float.isNaN(proj)) {
            return;   // 射线与平面近平行（极端掠射角）——保持上一帧缩放
        }
        // 比例增量：沿轴拖过一个箭头世界长 = 该轴分量 ×2（1 + 1）；按下瞬间投影差 0 → 比例 1
        float factor = 1.0f + (proj - dragStartProj) / gizmoLength;
        newScale.set(dragStartScale);
        switch (dragAxisTag) {
            case X -> newScale.x *= factor;
            case Y -> newScale.y *= factor;
            case Z -> newScale.z *= factor;
            case UNIFORM -> newScale.mul(factor);
        }
        newScale.set(clampAxis(newScale.x), clampAxis(newScale.y), clampAxis(newScale.z));
        target.getTransform().setLocalScale(newScale);
    }

    @Override
    protected void endDrag() {
        dragAxisTag = null;
        // 视觉由驱动侧 updateVisuals 同步（松开时鼠标仍悬停则高亮自然保持）
        log.info("结束拖拽");
    }

    /**
     * 分量下限钳制（保号）：{@code |value| < MIN_ABS_SCALE} 时按原符号抬到下限——
     * 避免缩放穿过 0 后符号翻转、拖拽在 0 附近来回抖动（Babylon 同口径）。
     */
    private static float clampAxis(float value) {
        if (Math.abs(value) < MIN_ABS_SCALE) {
            return value < 0f ? -MIN_ABS_SCALE : MIN_ABS_SCALE;
        }
        return value;
    }

    /**
     * 当前射线与拖拽平面交点沿拖拽轴的投影（锚点为原点、世界距离计）——与 MoveTool
     * 同款平面相交法；平面近平行返回 NaN（调用方按跳过处理）。
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
        return "Scale";
    }
}
