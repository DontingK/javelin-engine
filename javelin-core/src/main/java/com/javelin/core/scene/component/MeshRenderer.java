package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.inspector.InsField;
import com.javelin.core.inspector.InsHeader;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.Component;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.util.ArrayList;
import java.util.List;

/**
 * 网格渲染组件（合并设计，无 MeshFilter）。
 *
 * <p>几何共享靠资产引用（同一 Mesh 对象被多个 MeshRenderer 复用），
 * 渲染差异靠材质实例（每 renderer 每子网格独立），见场景设计文档 5.1 节。
 * 多材质（2026-10-29 拍板）：{@code materials} 列表 i 位对应子网格 i，长度由
 * setMesh/setMaterials 按子网格数对齐（缺补 null、多余截断）；缺失位/空列表
 * 渲染走 DefaultMaterial 默认材质兜底（Unity missing→error shader 口径），仅
 * mesh 为 null 不渲染（missing-mesh 语义）；材质多于子网格数忽略多余。
 * 节点层级不激活或组件 enabled=false 同样不渲染（Unity 口径，
 * SceneRenderBridge 每帧按 isActiveAndEnabled 过滤），由 SceneRenderBridge
 * 每帧 pull 翻译（Engine_Rendering_Design.md 收集流程）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
@InsHeader(label = "网格渲染器", icon = "mesh")
public class MeshRenderer extends Component implements Renderable {

    // 非 final：序列化反序列化需回写（AGENTS.md 红线 9）；ArrayList 直通（红线 10）
    @InsField(label = "网格")
    private Mesh mesh;
    @InsField(label = "材质")
    private ArrayList<Material> materials = new ArrayList<>();
    private boolean castShadow = true;
    private boolean receiveShadow = true;

    public Mesh getMesh() {
        return mesh;
    }

    public void setMesh(Mesh mesh) {
        this.mesh = mesh;
        alignMaterials();
    }

    /**
     * 多材质列表（i 位对应子网格 i；活列表引用，勿外部修改，改走 setMaterials）
     */
    public List<Material> getMaterials() {
        return materials;
    }

    /**
     * 多材质设置（防御性拷贝 + 按当前 mesh 子网格数对齐：缺补 null、多余截断）
     */
    public void setMaterials(List<Material> materials) {
        this.materials = new ArrayList<>(materials == null ? new ArrayList<>() : materials);
        alignMaterials();
    }

    /**
     * 子网格 0 的材质（兼容门面；多子网格用 getMaterials / getMaterial(int)）
     */
    public Material getMaterial() {
        return materials.isEmpty() ? null : materials.get(0);
    }

    /**
     * 子网格 0 材质替换（兼容门面）；空列表且非 null 时首位插入
     */
    public void setMaterial(Material material) {
        if (materials.isEmpty()) {
            if (material != null) {
                materials.add(material);
            }
        } else {
            materials.set(0, material);
        }
    }

    /**
     * 子网格 i 的材质（越界/未赋位 = null，渲染侧 DefaultMaterial 缺失兜底）
     */
    public Material getMaterial(int submesh) {
        return submesh >= 0 && submesh < materials.size() ? materials.get(submesh) : null;
    }

    /**
     * 材质列表长度对齐当前 mesh 子网格数（缺补 null、多余截断；mesh null 按 1 兜底）
     */
    private void alignMaterials() {
        int count = mesh != null ? mesh.getSubmeshCount() : 1;
        while (materials.size() < count) {
            materials.add(null);
        }
        if (materials.size() > count) {
            materials.subList(count, materials.size()).clear();
        }
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
     * 序列化数据面：mesh 资产引用 + materials 列表直传（i 位对应子网格 i）+ 双 shadow 布尔。
     *
     * <p>资产引用走框架既有语义：guid 非空 → 按 GUID 引用（编辑器主路径，
     * 引用 Library 导入资产）；guid 为空 → @ref 内嵌对象图（Mesh/Material 数据
     * 完整可兜底共享，往返无损）。mesh 为 null 走 missing-mesh 语义（
     * SceneRenderBridge 跳过不渲染）；材质缺失位/空列表与 shader 无效（null /
     * SPIR-V 缺失——编译失败 stub 产物）均走 DefaultMaterial 默认材质兜底渲染
     * （拍板 2026-10-29，原「material null 不渲染」口径作废）。旧 .scene 单
     * {@code material} 键：read 兼容包单元素（多材质化前产物，加载不破）。</p>
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("mesh", mesh);
        output.write("materials", materials);
        output.write("castShadow", castShadow);
        output.write("receiveShadow", receiveShadow);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        mesh = input.readSerializable("mesh", Mesh.class);
        materials = input.readArrayList("materials", Material.class);
        castShadow = input.readBoolean("castShadow");
        receiveShadow = input.readBoolean("receiveShadow");
    }
}
