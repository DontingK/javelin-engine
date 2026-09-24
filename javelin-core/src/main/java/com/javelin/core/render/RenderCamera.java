package com.javelin.core.render;

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
    private float fovY = (float) Math.toRadians(60.0);
    private float nearZ = 0.1f;
    private float farZ = 100.0f;

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
        proj.identity().perspective(fovY, aspect, nearZ, farZ, true);
        proj.m11(-proj.m11());   // Y 翻转入投影（拍板；视口正高度透传）
        viewProj.set(proj).mul(view);
    }

    public String name() {
        return name;
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

    public Matrix4f viewProj() {
        return viewProj;
    }

    public float fovY() {
        return fovY;
    }

    public void setFovY(float fovY) {
        this.fovY = fovY;
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
