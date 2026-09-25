package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
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

    private float fov = 60.0f;
    private float near = 0.1f;
    private float far = 1000.0f;
    private boolean main;

    public float getFov() {
        return fov;
    }

    public void setFov(float fov) {
        this.fov = fov;
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

    public boolean isMain() {
        return main;
    }

    public void setMain(boolean main) {
        this.main = main;
    }
}
