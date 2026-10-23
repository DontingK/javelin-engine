package com.javelin.core.render;

import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.SurfaceType;
import com.javelin.core.render.mesh.Mesh;
import org.joml.Matrix4f;

/**
 * 渲染对象（Engine_Rendering_Design.md 4.1）：轻量引用，不复制数据。
 *
 * <p>持 Mesh 资产 + Material 资产（材质桥接 2026-09-25 落地：set 2 描述符集与
 * PSO 由 RenderingSystem 按材质懒创建）。透明性由材质 surfaceType 裁决
 * （URP 口径，拍板 2026-10-17 方案 B——不再从 Shader 混合态推导，运行时
 * 翻转即时生效，PSO 缓存键含面型不串）；
 * world 矩阵由持有方每帧原地更新（帧描述存活期内不得修改的约束由
 * 单线程退化模式天然满足）。
 */
public class RenderObject {

    private final Mesh mesh;
    private final Material material;
    private final Matrix4f world = new Matrix4f();
    private final boolean castShadow;
    /** 渲染对象层级掩码（来源 Node.layer；与相机 cullingMask 相交才可见） */
    private final int layerMask;
    /** 属主组件（MeshRenderer；GPU 拾取 ID→对象映射的数据面，null = 直注册 Demo 对象） */
    private final Object owner;
    /** 帧内拾取 ID（PickPass 录制期赋值，0 = 未分配；帧生命周期，无持久语义） */
    private int pickId;

    public RenderObject(Mesh mesh, Material material, boolean castShadow) {
        this(mesh, material, castShadow, ~0);
    }

    public RenderObject(Mesh mesh, Material material, boolean castShadow, int layerMask) {
        this(mesh, material, castShadow, layerMask, null);
    }

    public RenderObject(Mesh mesh, Material material, boolean castShadow, int layerMask, Object owner) {
        if (mesh == null || material == null || material.getShader() == null) {
            throw new IllegalArgumentException("mesh / material / material.shader must not be null");
        }
        this.mesh = mesh;
        this.material = material;
        this.castShadow = castShadow;
        this.layerMask = layerMask;
        this.owner = owner;
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
        return material.getSurfaceType() == SurfaceType.TRANSPARENT;
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    public int layerMask() {
        return layerMask;
    }

    /** 属主组件（GPU 拾取映射数据面；null = 直注册 Demo 对象） */
    public Object owner() {
        return owner;
    }

    public int pickId() {
        return pickId;
    }

    /** 帧内拾取 ID 分配（PickPass 录制期写入；每帧重建，勿跨帧依赖） */
    public void pickId(int pickId) {
        this.pickId = pickId;
    }

    /** 不透明排序键：同 layout + 同材质的对象相邻（减少 PSO 切换与描述符绑定） */
    long bucketKey() {
        long layoutHash = mesh.isUploaded() ? mesh.layout().hashCode() : mesh.vertexCount();
        return ((long) System.identityHashCode(material) << 32) ^ (layoutHash & 0xFFFFFFFFL);
    }
}
