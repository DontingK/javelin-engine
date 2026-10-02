package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.Component;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

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

    /**
     * 序列化数据面：mesh/material 资产引用直传 + 双 shadow 布尔。
     *
     * <p>资产引用走框架既有语义：guid 非空 → 按 GUID 引用（编辑器主路径，
     * 引用 Library 导入资产）；guid 为空 → @ref 内嵌对象图（Mesh 数据完整可
     * 兜底共享；Material 尚无 write 实现，内嵌仅剩 guid/lastModified——引用
     * 闭环阻塞 .mat 任务，见 Engine_Scene_Design §8 已知限制）。mesh/material
     * 为 null 时走 Unity missing-mesh/material 语义（读回 null 不渲染）。</p>
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("mesh", mesh);
        output.write("material", material);
        output.write("castShadow", castShadow);
        output.write("receiveShadow", receiveShadow);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        mesh = input.readSerializable("mesh", Mesh.class);
        material = input.readSerializable("material", Material.class);
        castShadow = input.readBoolean("castShadow");
        receiveShadow = input.readBoolean("receiveShadow");
    }
}
