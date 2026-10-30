package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.obj.ObjectId;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 材质贴图槽条目（Material.textures 的存储与序列化节点，2026-10-04 随 Material
 * 序列化落地）。
 *
 * <p>同 {@link MaterialValueEntry}：Capsule 无 Map 通道——name 唯一条目列表，
 * 存储形态 = 序列化形态。texture 为 Asset 引用走框架 guid 分流（guid 非空 →
 * GUID 引用、空 → 图内嵌）；null = 未赋值，渲染侧按占位贴图约定
 * （white / bump / black）解析。贴图资产化（guid 通道）前引擎侧未赋贴图，
 * 条目承载槽位结构。字段非 final（红线 9，反序列化回写）。
 * 继承 {@link ObjectId}：纳入编辑器对象注册表，供资产热重载全量扫描（§4）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class MaterialTextureEntry extends ObjectId implements Serializable {

    /** 槽位名（= Shader Properties 声明名） */
    private String name;
    /** 贴图（null = 占位贴图） */
    private Texture2D texture;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public MaterialTextureEntry() {
    }

    public MaterialTextureEntry(String name, Texture2D texture) {
        this.name = name;
        this.texture = texture;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("texture", texture);
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        texture = input.readSerializable("texture", Texture2D.class);
    }

    public String getName() {
        return name;
    }

    public Texture2D getTexture() {
        return texture;
    }
}
