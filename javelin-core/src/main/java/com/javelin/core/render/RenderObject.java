package com.javelin.core.render;

import com.javelin.core.render.mesh.Mesh;
import com.javelin.rhi.Texture;
import org.joml.Matrix4f;

/**
 * 渲染对象（Engine_Rendering_Design.md 4.1）：轻量引用，不复制数据。
 *
 * <p>v0.1 着色模型取舍（4.2，材质系统建后替换）：直挂贴图，内置无光照采样着色；
 * texture 为 null 时由 RenderingSystem 以 1×1 白图占位。
 * world 矩阵由持有方每帧更新（帧描述存活期内不得修改对应数据的约束由单线程退化模式天然满足）。
 */
public class RenderObject {

    private final Mesh mesh;
    private final Texture texture;
    private final Matrix4f world = new Matrix4f();
    private final boolean transparent;
    private final boolean castShadow;

    public RenderObject(Mesh mesh, Texture texture, boolean transparent, boolean castShadow) {
        if (mesh == null) {
            throw new IllegalArgumentException("mesh must not be null");
        }
        this.mesh = mesh;
        this.texture = texture;
        this.transparent = transparent;
        this.castShadow = castShadow;
    }

    public Mesh mesh() {
        return mesh;
    }

    /** 可空；null = 白图占位 */
    public Texture texture() {
        return texture;
    }

    public Matrix4f world() {
        return world;
    }

    public boolean isTransparent() {
        return transparent;
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    /** 不透明排序键：同 layout + 同贴图的对象相邻（减少 PSO 切换与描述符绑定） */
    long bucketKey() {
        long layoutHash = mesh.isUploaded() ? mesh.layout().hashCode() : mesh.vertexCount();
        return ((long) System.identityHashCode(texture) << 32) ^ (layoutHash & 0xFFFFFFFFL);
    }
}
