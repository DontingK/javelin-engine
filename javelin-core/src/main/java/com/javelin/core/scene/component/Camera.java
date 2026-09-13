package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.scene.Component;
import com.javelin.core.scene.Node;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 相机组件 —— 附加到 {@link Node}，提供视图/投影矩阵和裁剪参数。
 *
 * <p>相机从所属 {@link Node}  的 {@link com.javelin.core.scene.Transform}
 * 读取世界空间位置和旋转，实时构建视图矩阵。
 * 投影矩阵根据投影类型（透视/正交）和裁剪参数实时计算。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 *   Camera camera = new Camera();
 *   camera.setPerspective(60f, 0.1f, 1000f);
 *   node.addComponent(camera);
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Camera extends Component {

    /**
     * 投影类型
     */
    public enum ProjectionType {
        PERSPECTIVE,
        ORTHOGRAPHIC
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  投影参数
    // ═══════════════════════════════════════════════════════════════════════

    private ProjectionType projectionType = ProjectionType.PERSPECTIVE;

    /** 透视：垂直视场角（度） */
    private float fov = 60.0f;

    /** 近裁剪面 */
    private float nearClip = 0.1f;

    /** 远裁剪面 */
    private float farClip = 1000.0f;

    // ═══════════════════════════════════════════════════════════════════════
    //  正交投影参数（世界单位）
    // ═══════════════════════════════════════════════════════════════════════

    /** 正交：左边界（世界单位） */
    private float orthoLeft = -10.0f;

    /** 正交：右边界（世界单位） */
    private float orthoRight = 10.0f;

    /** 正交：下边界（世界单位） */
    private float orthoBottom = -10.0f;

    /** 正交：上边界（世界单位） */
    private float orthoTop = 10.0f;

    // ═══════════════════════════════════════════════════════════════════════
    //  视口比例（0-1，相对于窗口，用于多相机分屏）
    // ═══════════════════════════════════════════════════════════════════════

    /** 视口左边界比例（0-1） */
    private float viewportLeft = 0.0f;

    /** 视口右边界比例（0-1） */
    private float viewportRight = 1.0f;

    /** 视口上边界比例（0-1） */
    private float viewportTop = 0.0f;

    /** 视口下边界比例（0-1） */
    private float viewportBottom = 1.0f;

    // ═══════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════

    public Camera() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  投影配置
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 设置为透视投影。
     *
     * @param fovDeg   垂直视场角（度）
     * @param nearClip 近裁剪面
     * @param farClip  远裁剪面
     */
    public void setPerspective(float fovDeg, float nearClip, float farClip) {
        this.projectionType = ProjectionType.PERSPECTIVE;
        this.fov = fovDeg;
        this.nearClip = nearClip;
        this.farClip = farClip;
    }

    /**
     * 设置为正交投影。
     *
     * @param orthoLeft   左边界（世界单位）
     * @param orthoRight  右边界（世界单位）
     * @param orthoBottom 下边界（世界单位）
     * @param orthoTop    上边界（世界单位）
     * @param near        近裁剪面
     * @param far         远裁剪面
     */
    public void setOrthographic(float orthoLeft, float orthoRight, float orthoBottom, float orthoTop, float near, float far) {
        this.projectionType = ProjectionType.ORTHOGRAPHIC;
        this.orthoLeft = orthoLeft;
        this.orthoRight = orthoRight;
        this.orthoBottom = orthoBottom;
        this.orthoTop = orthoTop;
        this.nearClip = near;
        this.farClip = far;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  矩阵计算
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取投影矩阵（实时计算）。
     *
     * @param aspectRatio 宽高比（width / height）
     */
    public Matrix4f getProjectionMatrix(float aspectRatio) {
        var m = new Matrix4f();
        if (projectionType == ProjectionType.PERSPECTIVE) {
            m.perspective(
                (float) Math.toRadians(fov),
                aspectRatio,
                nearClip,
                farClip
            );
        } else {
            m.ortho(orthoLeft, orthoRight, orthoBottom, orthoTop, nearClip, farClip);
        }
        return m;
    }

    /**
     * 获取视图矩阵（从 Transform 实时构建）。
     */
    public Matrix4f getViewMatrix() {
        Node node = node();
        if (node == null) {
            return new Matrix4f();
        }

        var transform = node.transform();
        var worldPosition = new Vector3f();
        transform.worldPosition(worldPosition);

        var rotation = transform.localRotation();
        var forward = new Vector3f(0, 0, -1).rotate(rotation);
        var up = new Vector3f(0, 1, 0).rotate(rotation);

        return new Matrix4f().setLookAt(worldPosition,
                                        new Vector3f(worldPosition).add(forward),
                                        up);
    }

    /**
     * 获取视图-投影矩阵（VP = P * V，实时计算）。
     *
     * @param aspectRatio 宽高比（width / height）
     */
    public Matrix4f getViewProjectionMatrix(float aspectRatio) {
        return getProjectionMatrix(aspectRatio).mul(getViewMatrix());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public ProjectionType getProjectionType() {
        return projectionType;
    }

    public float getFov() {
        return fov;
    }

    public float getNearClip() {
        return nearClip;
    }

    public float getFarClip() {
        return farClip;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  正交投影参数
    // ═══════════════════════════════════════════════════════════════════════

    public float getOrthoLeft() {
        return orthoLeft;
    }

    public float getOrthoRight() {
        return orthoRight;
    }

    public float getOrthoBottom() {
        return orthoBottom;
    }

    public float getOrthoTop() {
        return orthoTop;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  视口比例
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 设置视口比例（0-1，相对于窗口）。
     * <p>用于多相机分屏渲染。</p>
     */
    public void setViewport(float left, float top, float right, float bottom) {
        this.viewportLeft = left;
        this.viewportTop = top;
        this.viewportRight = right;
        this.viewportBottom = bottom;
    }

    public float getViewportLeft() {
        return viewportLeft;
    }

    public float getViewportRight() {
        return viewportRight;
    }

    public float getViewportTop() {
        return viewportTop;
    }

    public float getViewportBottom() {
        return viewportBottom;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  清屏颜色
    // ═══════════════════════════════════════════════════════════════════════

    /** 清屏颜色（RGBA） */
    private float[] clearColor = new float[]{0.2f, 0.3f, 0.4f, 1.0f};

    /**
     * 设置清屏颜色（RGBA，0-1 范围）。
     */
    public void setClearColor(float r, float g, float b, float a) {
        clearColor[0] = r;
        clearColor[1] = g;
        clearColor[2] = b;
        clearColor[3] = a;
    }

    /**
     * 获取清屏颜色数组（RGBA）。
     */
    public float[] getClearColor() {
        return clearColor;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  位置/朝向
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取相机世界空间位置（从 Transform 实时提取）。
     */
    public Vector3f getWorldPosition() {
        Node node = node();
        if (node == null) {
            return new Vector3f(0, 0, 0);
        }
        var pos = new Vector3f();
        node.transform().worldPosition(pos);
        return pos;
    }

    /**
     * 获取相机朝向（从 Transform 实时提取）。
     */
    public Vector3f getForward() {
        Node node = node();
        if (node == null) {
            return new Vector3f(0, 0, -1);
        }
        return new Vector3f(0, 0, -1).rotate(node.transform().localRotation());
    }

    /**
     * 获取相机上方向（从 Transform 实时提取）。
     */
    public Vector3f getUp() {
        Node node = node();
        if (node == null) {
            return new Vector3f(0, 1, 0);
        }
        return new Vector3f(0, 1, 0).rotate(node.transform().localRotation());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Setter
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 设置近裁剪面。
     */
    public void setNearClip(float nearClip) {
        this.nearClip = nearClip;
    }

    /**
     * 设置远裁剪面。
     */
    public void setFarClip(float farClip) {
        this.farClip = farClip;
    }

    @Override
    public String toString() {
        return "Camera[type=" + projectionType +
               ", near=" + nearClip +
               ", far=" + farClip + "]";
    }
}
