package com.javelin.core.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 单个相机的渲染上下文 —— 每相机独立，支持多线程并行构建。
 *
 * <p>包含相机专属数据（矩阵、视口、渲染列表），与 {@link FrameContext} 分离。
 * 多相机渲染时，每个相机创建独立实例，可在不同线程并行构建 RenderList 和录制 CommandBuffer。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class CameraContext {

    /** 视图矩阵 */
    private final Matrix4f viewMatrix = new Matrix4f();

    /** 投影矩阵 */
    private final Matrix4f projectionMatrix = new Matrix4f();

    /** 相机世界空间位置 */
    private final Vector3f cameraPosition = new Vector3f();

    /** 视口宽度 */
    private int viewportWidth = 1280;

    /** 视口高度 */
    private int viewportHeight = 720;

    /** 该相机的渲染列表 */
    private RenderList renderList;

    /** 当前 Pass 名称 */
    private String passName;

    /**
     * 设置视图矩阵。
     */
    public void setViewMatrix(Matrix4f viewMatrix) {
        this.viewMatrix.set(viewMatrix);
    }

    public Matrix4f getViewMatrix() {
        return viewMatrix;
    }

    /**
     * 设置投影矩阵。
     */
    public void setProjectionMatrix(Matrix4f projectionMatrix) {
        this.projectionMatrix.set(projectionMatrix);
    }

    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    /**
     * 设置相机世界空间位置。
     */
    public void setCameraPosition(Vector3f position) {
        this.cameraPosition.set(position);
    }

    public Vector3f getCameraPosition() {
        return cameraPosition;
    }

    /**
     * 设置视口尺寸。
     */
    public void setViewportSize(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    /**
     * 设置渲染列表。
     */
    public void setRenderList(RenderList renderList) {
        this.renderList = renderList;
    }

    public RenderList getRenderList() {
        return renderList;
    }

    /**
     * 设置当前 Pass 名称。
     */
    public void setPassName(String passName) {
        this.passName = passName;
    }

    public String getPassName() {
        return passName;
    }
}
