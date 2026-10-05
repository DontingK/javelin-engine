package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.math.Color;
import com.javelin.core.math.Rect;
import com.javelin.core.physics.Ray;
import com.javelin.core.render.ClearFlags;
import com.javelin.core.render.ProjectionType;
import com.javelin.core.scene.Layer;
import com.javelin.core.scene.LayerMask;
import com.javelin.core.scene.Component;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 相机组件（对应 RHI 9 节 RenderView）。
 *
 * <p>投影矩阵按 Vulkan NDC 约定生成（Y 向下、深度 [0,1]）；
 * RenderView 由引擎内部生成并关联（视图矩阵 / 投影矩阵 / 视口），见场景设计文档 5.2 节。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Camera extends Component {

    private ProjectionType projectionType = ProjectionType.PERSPECTIVE;
    /** 垂直视场角（度；仅透视投影。渲染侧换算弧度） */
    private float fov = 60.0f;
    /** 正交半高（世界单位；仅正交投影） */
    private float orthographicSize = 5.0f;
    private float near = 0.1f;
    private float far = 1000.0f;
    /** 裁剪掩码：按位对应 Layer，默认全可见（LayerMask.everything） */
    private int cullingMask = LayerMask.everything();
    /** 相机背景色（null = 跟随引擎全局背景色 JavelinSetting.backgroundColor） */
    private Color backgroundColor;
    /** 清屏标志（SOLID_COLOR 才消费 backgroundColor；DEPTH_ONLY 供叠加相机） */
    private ClearFlags clearFlags = ClearFlags.SOLID_COLOR;
    /**
     * 视口归一化矩形（null = 默认全屏；多相机分屏/画中画须显式设置）。
     * [0, 1] 归一化，原点左下、y 向上（Unity normalizedViewPortRect 口径，
     * SceneRenderBridge 负责翻转为 Vulkan 视口像素坐标）。
     */
    private Rect viewportRect;
    private boolean main;
    /** 渲染深度（值越大越晚渲染，可覆盖在小值相机画面之上——小地图/画中画用） */
    private float depth;

    // screenToRay 复用缓冲（运行态不落盘、单线程每查复用）
    private Vector3f rayOrigin = new Vector3f();
    private Vector3f rayForward = new Vector3f();
    private Vector3f rayUp = new Vector3f();
    private Vector3f rayRight = new Vector3f();
    private Vector3f rayDirection = new Vector3f();

    public ProjectionType getProjectionType() {
        return projectionType;
    }

    public void setProjectionType(ProjectionType projectionType) {
        this.projectionType = projectionType;
    }

    /** 垂直视场角（度） */
    public float getFov() {
        return fov;
    }

    public void setFov(float fov) {
        this.fov = fov;
    }

    /** 正交半高（世界单位，orthoHeight = 2 * size；仅正交投影） */
    public float getOrthographicSize() {
        return orthographicSize;
    }

    public void setOrthographicSize(float orthographicSize) {
        this.orthographicSize = orthographicSize;
    }

    public float getNear() {
        return near;
    }

    public void setNear(float near) {
        this.near = near;
    }

    public float getFar() {
        return far;
    }

    public void setFar(float far) {
        this.far = far;
    }

    /** 裁剪掩码（按位对应 Layer；配合 {@link LayerMask} 组合） */
    public int getCullingMask() {
        return cullingMask;
    }

    public void setCullingMask(int cullingMask) {
        this.cullingMask = cullingMask;
    }

    /** 按层级集合设置裁剪掩码 */
    public void setCullingMask(Layer... layers) {
        this.cullingMask = LayerMask.of(layers);
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public ClearFlags getClearFlags() {
        return clearFlags;
    }

    public void setClearFlags(ClearFlags clearFlags) {
        this.clearFlags = clearFlags;
    }

    /** 视口归一化矩形；null = 默认全屏 */
    public Rect getViewportRect() {
        return viewportRect;
    }

    public void setViewportRect(Rect viewportRect) {
        this.viewportRect = viewportRect;
    }

    /** 渲染深度：值越大越晚渲染（同值保持声明序），覆盖关系由此决定 */
    public float getDepth() {
        return depth;
    }

    public void setDepth(float depth) {
        this.depth = depth;
    }

    public boolean isMain() {
        return main;
    }

    public void setMain(boolean main) {
        this.main = main;
    }

    /**
     * 屏幕点 → 世界射线（Unity {@code Camera.ScreenPointToRay} 口径——经本相机
     * {@code viewportRect} 归一化，仅透视投影）。
     *
     * <p>坐标约定：screenX/Y 为渲染目标像素坐标（原点左上、y 向下——本引擎鼠标
     * 坐标口径；Unity 左下原点的差异在内部吸收）；归一化经 viewportRect
     *（null = 全屏），视口外的点映射到 NDC [−1,1] 之外（射线指向视锥外，
     * 调用方按需先判包含）。符号口径（渲染侧同源）：<b>屏幕右向 = forward×up</b>
     *（RH：面向 +Z 屏幕右 = −X——EditorCameraAppState 2026-10-04 实测修正同源，
     * 拾取换算必守，用 localX 会左右镜像）；投影已含 Vulkan Y 翻转
     *（view +y 上 ↔ NDC −y），屏幕顶 = NDC −1 = 视线上方。</p>
     *
     * @param screenX     屏幕像素 x（渲染目标坐标）
     * @param screenY     屏幕像素 y（原点左上）
     * @param targetWidth 渲染目标宽（与 screenX/Y 同坐标系，须 &gt; 0）
     * @param targetHeight 渲染目标高（须 &gt; 0）
     * @param out         输出射线（origin=相机世界位置，direction 归一化）
     * @return out
     * @throws UnsupportedOperationException 正交投影（后置）
     * @throws IllegalStateException 相机未挂节点（无世界位姿）
     */
    public Ray screenToRay(float screenX, float screenY, float targetWidth, float targetHeight, Ray out) {
        if (projectionType != ProjectionType.PERSPECTIVE) {
            throw new UnsupportedOperationException("正交投影的 screenToRay 后置（当前仅透视）");
        }
        if (getNode() == null) {
            throw new IllegalStateException("相机未挂节点，无世界位姿可换算");
        }
        Matrix4f localToWorld = getNode().getTransform().getLocalToWorld();
        localToWorld.getTranslation(rayOrigin);
        rayForward.set(0, 0, 1);
        localToWorld.transformDirection(rayForward);
        rayForward.normalize();
        rayUp.set(0, 1, 0);
        localToWorld.transformDirection(rayUp);
        rayUp.normalize();
        rayRight.set(rayForward).cross(rayUp).normalize();

        // 视口像素区域（Unity viewportRect [0,1] 原点左下 y 向上 → 原点左上换算；
        // null = 全屏）
        Rect rect = viewportRect;
        float rectX = rect != null ? rect.x : 0.0f;
        float rectY = rect != null ? rect.y : 0.0f;
        float rectW = rect != null ? rect.width : 1.0f;
        float rectH = rect != null ? rect.height : 1.0f;
        float vpLeft = rectX * targetWidth;
        float vpTop = (1.0f - rectY - rectH) * targetHeight;
        float vpW = rectW * targetWidth;
        float vpH = rectH * targetHeight;

        float ndcX = 2.0f * (screenX - vpLeft) / vpW - 1.0f;
        float ndcY = 2.0f * (screenY - vpTop) / vpH - 1.0f;

        float tanHalfFov = (float) Math.tan(Math.toRadians(fov) * 0.5f);
        float aspect = vpW / vpH;
        // 世界方向 = forward + right·hX + up·hY；竖直分量取 −ndcY（Vulkan Y 翻转）
        float hX = ndcX * tanHalfFov * aspect;
        float hY = -ndcY * tanHalfFov;
        rayDirection.set(rayForward);
        rayDirection.add(rayRight.x * hX, rayRight.y * hX, rayRight.z * hX);
        rayDirection.add(rayUp.x * hY, rayUp.y * hY, rayUp.z * hY);
        rayDirection.normalize();
        return out.set(rayOrigin, rayDirection);
    }

    /**
     * 序列化数据面：全部用户数据字段（无资产引用——RenderView 由引擎内部生成不回存）。
     *
     * <p>projectionType/clearFlags 走 Enum 通道（name() 落盘）；backgroundColor/
     * viewportRect 为可空值对象（null = 跟随全局/全屏的合法语义）。</p>
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("projectionType", projectionType);
        output.write("fov", fov);
        output.write("orthographicSize", orthographicSize);
        output.write("near", near);
        output.write("far", far);
        output.write("cullingMask", cullingMask);
        output.write("backgroundColor", backgroundColor);
        output.write("clearFlags", clearFlags);
        output.write("viewportRect", viewportRect);
        output.write("main", main);
        output.write("depth", depth);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        ProjectionType type = input.readEnum("projectionType", ProjectionType.class);
        if (type != null) {
            projectionType = type;
        }
        fov = input.readFloat("fov");
        orthographicSize = input.readFloat("orthographicSize");
        near = input.readFloat("near");
        far = input.readFloat("far");
        cullingMask = input.readInt("cullingMask");
        backgroundColor = input.readSerializable("backgroundColor", Color.class);
        ClearFlags flags = input.readEnum("clearFlags", ClearFlags.class);
        if (flags != null) {
            clearFlags = flags;
        }
        viewportRect = input.readSerializable("viewportRect", Rect.class);
        main = input.readBoolean("main");
        depth = input.readFloat("depth");
    }
}
