package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.math.Color;
import com.javelin.core.math.Rect;
import com.javelin.core.render.ClearFlags;
import com.javelin.core.render.ProjectionType;
import com.javelin.core.scene.Layer;
import com.javelin.core.scene.LayerMask;
import com.javelin.core.scene.Component;

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
}
