package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.Component;

/**
 * 网格渲染组件（合并设计，无 MeshFilter）。
 *
 * <p>几何共享靠资产引用（同一 Mesh 对象被多个 MeshRenderer 复用），
 * 渲染差异靠材质实例（每 renderer 独立），见场景设计文档 5.1 节。
 * mesh / material 为 null 时不渲染（Unity missing-mesh 语义），
 * 由 SceneRenderBridge 每帧 pull 翻译（Engine_Rendering_Design.md 收集流程）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class MeshRenderer extends Component implements Renderable {

    // 非 final：序列化反序列化需回写（AGENTS.md 红线 9）
    private Mesh mesh;
    private Material material;
    private boolean castShadow = true;
    private boolean receiveShadow = true;

    public Mesh getMesh() {
        return mesh;
    }

    public void setMesh(Mesh mesh) {
        this.mesh = mesh;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    public void setCastShadow(boolean castShadow) {
        this.castShadow = castShadow;
    }

    public boolean isReceiveShadow() {
        return receiveShadow;
    }

    public void setReceiveShadow(boolean receiveShadow) {
        this.receiveShadow = receiveShadow;
    }
}
