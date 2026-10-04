package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 材质数值参数条目（Material.values 的存储与序列化节点，2026-10-04 随 Material
 * 序列化落地）。
 *
 * <p>Capsule 无 Map 通道——keyed 参数表用 name 唯一的条目列表（存储形态 =
 * 序列化形态，同 Mesh/MeshAttributeData 拍板：字段直通零转换、set 同名替换
 * 新名追加、查找线性扫——属性数为个位数）。COLOR/VECTOR 存 4 元、
 * FLOAT/RANGE 存 1 元。字段非 final（红线 9，反序列化回写）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class MaterialValueEntry implements Serializable {

    /** 参数名（= Shader Properties 声明名） */
    private String name;
    /** COLOR/VECTOR 4 元，FLOAT/RANGE 1 元 */
    private float[] data;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public MaterialValueEntry() {
    }

    public MaterialValueEntry(String name, float[] data) {
        this.name = name;
        this.data = data;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("data", data);
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        data = input.readFloatArray("data");
    }

    public String getName() {
        return name;
    }

    public float[] getData() {
        return data;
    }
}
