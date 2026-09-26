package com.javelin.core.render;

import com.javelin.rhi.data.RHIClearColor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 引擎侧相机（Engine_Rendering_Design.md 第 3 节；场景 Camera 组件落地前的直接注册形态）。
 *
 * <p>每帧由 RenderingSystem 调 {@link #recalculate()} 生成视图/投影矩阵：
 * <ul>
 *   <li>投影按 Vulkan NDC 约定（Y 向下、深度 [0,1]，zZeroToOne = true）；</li>
 *   <li><b>Y 翻转入投影</b>（已拍板）：透视生成后 m11 取负，视口透传正高度。</li>
 * </ul>
 *
 * <p>视锥剔除（AABB/FrustumIntersection）属引擎优化点，用户拍板后置（2026-09-25），
 * 未包含在本类。
 */
public class RenderCamera {

    private final String name;
    private ProjectionType projectionType = ProjectionType.PERSPECTIVE;
    /** 透视垂直视场角（弧度；JOML perspective 收弧度。场景组件 Camera 以度暴露，SceneRenderBridge 负责换算） */
    private float fovY = (float) Math.toRadians(60.0);
    /** 正交半高（世界单位；orthoHeight = 2 * orthographicSize） */
    private float orthographicSize = 5.0f;
    private float nearZ = 0.1f;
    private float farZ = 100.0f;
    /** 裁剪掩码：按位对应 Layer，渲染对象 layerMask 与之相交才进渲染列表（默认全可见） */
    private int cullingMask = ~0;
    /** 本相机清屏颜色（null = 未指定，OpaqueForwardPass 退回管线全局默认色） */
    private RHIClearColor clearColor;
    /** 清屏标志（默认常规清颜色+深度；DEPTH_ONLY 供叠加相机合成） */
    private ClearFlags clearFlags = ClearFlags.SOLID_COLOR;
    /** 渲染深度：值越大越晚渲染（collectFrame 按此升序稳定排序；同值保持注册序） */
    private float depth;

    private final Vector3f position = new Vector3f(0.0f, 0.0f, 5.0f);
    private final Vector3f target = new Vector3f(0.0f, 0.0f, 0.0f);
    private final Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

    /** 视口矩形（像素，framebuffer 坐标，原点在左上；多相机分屏各占一块） */
    private int viewportX;
    private int viewportY;
    private int viewportWidth = 640;
    private int viewportHeight = 480;

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();

    public RenderCamera(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("camera name must not be empty");
        }
        this.name = name;
    }

    /** 重算视图/投影/视锥（宽高比取视口矩形；viewport 变化或位姿变化后由收集期统一调用） */
    public void recalculate() {
        float aspect = viewportHeight > 0 ? (float) viewportWidth / viewportHeight : 1.0f;
        view.setLookAt(position, target, up);
        if (projectionType == ProjectionType.ORTHOGRAPHIC) {
            float top = orthographicSize;
            proj.identity().ortho(-top * aspect, top * aspect, -top, top, nearZ, farZ, true);
        } else {
            proj.identity().perspective(fovY, aspect, nearZ, farZ, true);
        }
        proj.m11(-proj.m11());   // Y 翻转入投影（拍板；视口正高度透传；透视/正交同翻转）
        viewProj.set(proj).mul(view);
    }

    public String name() {
        return name;
    }

    public ProjectionType projectionType() {
        return projectionType;
    }

    public void setProjectionType(ProjectionType projectionType) {
        this.projectionType = projectionType;
    }

    public Vector3f position() {
        return position;
    }

    public Vector3f target() {
        return target;
    }

    public Vector3f up() {
        return up;
    }

    public Matrix4f view() {
        return view;
    }

    public Matrix4f proj() {
        return proj;
    }

    public Matrix4f viewProj() {
        return viewProj;
    }

    public float fovY() {
        return fovY;
    }

    public void setFovY(float fovY) {
        this.fovY = fovY;
    }

    public float orthographicSize() {
        return orthographicSize;
    }

    public void setOrthographicSize(float orthographicSize) {
        this.orthographicSize = orthographicSize;
    }

    public float nearZ() {
        return nearZ;
    }

    public void setNearZ(float nearZ) {
        this.nearZ = nearZ;
    }

    public float farZ() {
        return farZ;
    }

    public void setFarZ(float farZ) {
        this.farZ = farZ;
    }

    public int cullingMask() {
        return cullingMask;
    }

    public void setCullingMask(int cullingMask) {
        this.cullingMask = cullingMask;
    }

    public RHIClearColor clearColor() {
        return clearColor;
    }

    public void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
    }

    public ClearFlags clearFlags() {
        return clearFlags;
    }

    public void setClearFlags(ClearFlags clearFlags) {
        this.clearFlags = clearFlags;
    }

    public float depth() {
        return depth;
    }

    public void setDepth(float depth) {
        this.depth = depth;
    }

    public void viewport(int x, int y, int width, int height) {
        this.viewportX = x;
        this.viewportY = y;
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    public int viewportX() {
        return viewportX;
    }

    public int viewportY() {
        return viewportY;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }
}
