package com.javelin.core.render;

import com.javelin.core.render.material.Material;
import com.javelin.core.render.mesh.Mesh;
import org.joml.Matrix4f;

/**
 * 渲染对象（Engine_Rendering_Design.md 4.1）：轻量引用，不复制数据。
 *
 * <p>持 Mesh 资产 + Material 资产（材质桥接 2026-09-25 落地：set 2 描述符集与
 * PSO 由 RenderingSystem 按材质懒创建）。透明性由材质前向 Pass 的混合态得出
 * （RHI 21.1：渲染队列由引擎决定，Shader 不声明 Queue）；
 * world 矩阵由持有方每帧原地更新（帧描述存活期内不得修改的约束由
 * 单线程退化模式天然满足）。
 */
public class RenderObject {

    private final Mesh mesh;
    private final Material material;
    private final Matrix4f world = new Matrix4f();
    private final boolean transparent;
    private final boolean castShadow;

    public RenderObject(Mesh mesh, Material material, boolean castShadow) {
        if (mesh == null || material == null) {
            throw new IllegalArgumentException("mesh / material must not be null");
        }
        this.mesh = mesh;
        this.material = material;
        this.castShadow = castShadow;
        this.transparent = material.getShader().forwardPass().getState().isBlendEnabled();
    }

    public Mesh mesh() {
        return mesh;
    }

    public Material material() {
        return material;
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

    /** 不透明排序键：同 layout + 同材质的对象相邻（减少 PSO 切换与描述符绑定） */
    long bucketKey() {
        long layoutHash = mesh.isUploaded() ? mesh.layout().hashCode() : mesh.vertexCount();
        return ((long) System.identityHashCode(material) << 32) ^ (layoutHash & 0xFFFFFFFFL);
    }
}
