package cn.donting.javelin.editor.appstate.gizmo;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.scene.component.Transform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 移动工具（TransformTools 首个实现，2026-10-04；拖拽落地 2026-10-06）。
 *
 * <p>三轴移动指示：内置 axisY.smesh +Y 箭头（全长约 9.9）复用旋转出三轴
 * （用户拍板 2026-10-04：+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°，同一 Mesh 共享）；
 * 红/绿/蓝 = XYZ 惯例。材质走内置
 * gizmo.shader（unlit 纯色 + _Color 属性），每轴一个材质实例按轴下发颜色；
 * shader Cull Off 容忍外部工具导出的混合绕序。子树归编辑子场景口径（不进
 * 用户树、不落盘、GAME 模式自动不可见）。尺度不自持：由 TransformGizmoAppState
 * 距离自适应统一下发（× scaleFactor()），不随选中物体缩放（Unity 移动轴口径）。</p>
 *
 * <p>轴 hover/拖拽判定走 GPU ID-buffer（Engine_Picking_Design.md，2026-10-23
 * 全线统一拍板）：轴几何进拾取附件（PickPass 画 "Gizmo" 桶、深度关置顶），
 * CylinderCollider 过渡线退役。轴节点经 userData 打轴标
 * （key={@link Axis#USER_DATA_KEY}）供 hover/拖拽判别轴，不解析节点名。</p>
 *
 * <p><b>可选性与视觉三态（2026-10-06 用户需求）</b>：轴与视线趋于平行
 * （|cos| 夹角超 {@link #AXIS_PARALLEL_COS} ≈ 10°）时不可选并<b>变灰</b>——
 * 视线 = 相机 → 选中目标方向（与鼠标位置无关，用户拍板 2026-10-06——用鼠标
 * 射线会把屏幕边缘的射线偏角算进夹角，变灰随鼠标位置漂移）。端视时屏幕上轴
 * 退化为点、拖拽平面退化走回退、投影被 1/sin(夹角) 放大拖拽病态（典型收益：
 * 俯视场景时 Y 轴让位，XZ 拖拽不被误抓）。视觉经驱动侧每帧
 * {@link #updateVisuals} 同步（幂等）：正常 RGB / 高亮黄（hover 与拖拽共用，
 * 拖拽中 hover 锁定即被拖轴）/ 不可选灰——判定与 {@link #isSelectable} 同源
 * （视觉与可选性一致），优先级高亮 &gt; 变灰（hover 入口已被可选性闸门，高亮
 * 轴必可选）；每轴独立 Material 实例，MaterialBlock 每次调用重打包 setColor
 * 即时生效。</p>
 *
 * <p><b>拖拽（2026-10-06，平面相交法）</b>：拖拽开始锁定一张过锚点（按下时
 * 目标世界位置）、含拖拽轴、对相机最正交的平面（法线 = 视线去掉轴分量）；每帧
 * 鼠标射线与平面求交，交点沿轴的投影差即世界位移（screenToRay 方向归一化 →
 * 投影即世界距离；初始投影作 offset 补偿，按下瞬间零位移不跳）。轴方向 = 轴
 * 箭头节点世界 +Y（父链无旋转 = Global 世界轴口径；将来 Local 模式 gizmoNode
 * 随节点旋转后同一代码直通）。世界位移经父矩阵逆回写 local（父旋转/缩放下世界
 * 平移 ≠ local 平移）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class MoveTool extends TransformTools {

    private static final Logger log = LoggerFactory.getLogger(MoveTool.class);

    /**
     * 内置移动轴网格 loadAsset 路径（builtin-asset-editor/mesh/axisY.smesh 释放到 .javelin/assets 后的相对路径）
     */
    private static final String AXIS_MESH_PATH = "editor/mesh/axisY.smesh";

    /**
     * 内置 Gizmo 着色器 loadAsset 路径（unlit 纯色，_Color 按轴下发）。
     * gizmo.shader 只声明 Pass Gizmo——严格制（2026-10-17）下物体进 RenderList
     * "Gizmo" 桶、由 GizmoPass 单画，管线侧无需知道本路径
     */
    static final String GIZMO_SHADER_PATH = "editor/shader/gizmo.shader";

    /** 颜色属性名（gizmo.shader 的 _Color） */
    private static final String COLOR_PROPERTY = "_Color";

    /** 轴本色（RGB = XYZ 惯例；数组下标 = Axis 枚举序 X/Y/Z） */
    private static final float[][] AXIS_COLORS = {
            {1.0f, 0.0f, 0.0f, 1.0f},
            {0.0f, 1.0f, 0.0f, 1.0f},
            {0.0f, 0.0f, 1.0f, 1.0f},
    };

    /** hover/拖拽高亮色（Unity 口径：交互中的轴变黄） */
    private static final float[] HIGHLIGHT_COLOR = {1.0f, 0.92f, 0.2f, 1.0f};

    /** 不可选（轴与视线近平行）置灰（实测可调） */
    private static final float[] GRAY_COLOR = {0.5f, 0.5f, 0.5f, 1.0f};

    /**
     * 轴与视线平行不可选阈值（|cos| 夹角；0.985 ≈ 10°——轴端视退化为点、拖拽
     * 投影被 1/sin 放大；实测手感可调）
     */
    private static final float AXIS_PARALLEL_COS = 0.985f;

    /** 轴视觉三态（驱动侧每帧同步、工具按状态数组幂等写色） */
    private enum AxisVisualState {
        /** 正常（轴本色 RGB） */
        NORMAL,
        /** 高亮（hover/拖拽中，黄） */
        HIGHLIGHT,
        /** 不可选（轴与视线近平行，灰） */
        DISABLED
    }

    /** 三轴节点（数组下标 = Axis 枚举序 X/Y/Z；init 填充，失败留 null） */
    private final Node[] axisNodes = new Node[3];

    /** 各轴当前视觉状态（与 axisNodes 平行；null = 尚未写过色，首帧必写） */
    private final AxisVisualState[] axisStates = new AxisVisualState[3];

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

    /** 可选性判定的轴方向缓冲（与 dragAxis 分离——每帧多轴调用不破坏进行中拖拽） */
    private final Vector3f axisDirBuf = new Vector3f();

    @Override
    protected void init(AssetManager assetManager, JavelinEngine javelinEngine) {
        Mesh axisMesh = assetManager.loadAsset(AXIS_MESH_PATH, Mesh.class);
        if (axisMesh == null) {
            log.error("移动轴网格加载失败 path={}（Library 缺产物或索引未建），移动工具不可用", AXIS_MESH_PATH);
            return;
        }
        Shader shader = assetManager.loadAsset(GIZMO_SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("Gizmo 着色器加载失败 path={}（Library 缺产物或索引未建），移动工具不可用", GIZMO_SHADER_PATH);
            return;
        }
        toolNode = new Node();
        toolNode.setName("MoveTool");
        Transform rootTransform = toolNode.getTransform();
        // 尺度不在此设：由 TransformGizmoAppState 每帧距离自适应下发（scaleFactor 参与）

        // RGB = XYZ；+Y 本体、+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°（同一 axisY 箭头复用旋转）
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
            renderer.setMesh(axisMesh);
            Material material = new Material(shader);
            material.setColor(COLOR_PROPERTY, AXIS_COLORS[i][0], AXIS_COLORS[i][1], AXIS_COLORS[i][2], AXIS_COLORS[i][3]);
            renderer.setMaterial(material);
            renderer.setCastShadow(false);
            // 轴拾取已全线迁 GPU ID-buffer（Engine_Picking_Design.md，2026-10-23）：
            // 轴几何进拾取附件（PickPass 画 "Gizmo" 桶、深度关置顶），CylinderCollider 过渡线退役
            axisNodes[i] = node;
        }
        log.info("移动工具已创建（三轴复用 axisY 箭头 + GPU ID-buffer 轴拾取，红/绿/蓝 = X/Y/Z，尺度随驱动侧距离自适应）");
    }

    @Override
    protected void update() {
        // 选中解析/显隐/位置对齐暂由 TransformGizmoAppState 驱动，工具管理器改造时迁入本方法
    }

    // ---------------- 可选性与视觉三态（驱动侧每帧同步） ----------------

    @Override
    protected boolean isSelectable(Node node, Vector3f viewDir) {
        return axisSelectable(node, viewDir);
    }

    @Override
    protected void updateVisuals(Node highlightedNode, Vector3f viewDir) {
        for (int i = 0; i < axisNodes.length; i++) {
            Node node = axisNodes[i];
            if (node == null) {
                continue; // init 失败防御
            }
            // 优先级：高亮 > 变灰（hover 入口已被 isSelectable 闸门，高亮轴必可选）
            AxisVisualState desired = node == highlightedNode ? AxisVisualState.HIGHLIGHT
                    : axisSelectable(node, viewDir) ? AxisVisualState.NORMAL : AxisVisualState.DISABLED;
            if (desired != axisStates[i]) {
                axisStates[i] = desired;
                setAxisColor(node, colorOf(desired, i));
            }
        }
    }

    /**
     * 轴可选性判定（{@link #isSelectable} 闸门与 {@link #updateVisuals} 变灰同源——
     * 视觉与可选性一致）：轴世界方向（节点 +Y）与视线（相机→选中目标）夹角小于
     * 阈值（端视）不可选——屏幕上轴退化为点、拖拽投影被 1/sin 放大；viewDir
     * 归一化故 dot 即 cos 夹角。
     */
    private boolean axisSelectable(Node node, Vector3f viewDir) {
        node.getTransform().getLocalToWorld().transformDirection(axisDirBuf.set(0, 1, 0)).normalize();
        float cos = Math.abs(axisDirBuf.dot(viewDir));
        return cos < AXIS_PARALLEL_COS;
    }

    /** 视觉状态对应颜色（正常 = 轴本色按轴下标） */
    private float[] colorOf(AxisVisualState state, int axisIndex) {
        return switch (state) {
            case HIGHLIGHT -> HIGHLIGHT_COLOR;
            case DISABLED -> GRAY_COLOR;
            case NORMAL -> AXIS_COLORS[axisIndex];
        };
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

    /** 写轴节点材质颜色（每轴独立 Material 实例；MaterialBlock 每帧重打包，setColor 即时生效） */
    private void setAxisColor(Node axisNode, float[] rgba) {
        MeshRenderer renderer = axisNode.getComponent(MeshRenderer.class);
        if (renderer != null && renderer.getMaterial() != null) {
            renderer.getMaterial().setColor(COLOR_PROPERTY, rgba[0], rgba[1], rgba[2], rgba[3]);
        }
    }

    @Override
    protected float scaleFactor() {
        return 1 / 9.8f;
    }

    @Override
    protected String getName() {
        return "Move";
    }
}
