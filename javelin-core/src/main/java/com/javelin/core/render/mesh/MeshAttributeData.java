package com.javelin.core.render.mesh;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import com.javelin.rhi.types.Format;

/**
 * Mesh 单个顶点属性的序列化节点（.smesh 导入产物格式，Engine_Assets_Design §2）：
 * 属性描述（name/components/format）+ 顶点数据数组。
 *
 * <p>Mesh 内部按 name 索引（Map），Capsule 无 Map 支持——序列化形态为
 * {@code ArrayList&lt;MeshAttributeData&gt;}（Mesh 的序列化字段），
 * 读回后由 Mesh 懒装配重建索引（红线 10：read 不解嵌套元素，Map 拖到首次访问）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class MeshAttributeData implements Serializable {

    private String name;
    private int components;
    private Format format;
    private float[] data;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public MeshAttributeData() {
    }

    public MeshAttributeData(String name, int components, Format format, float[] data) {
        this.name = name;
        this.components = components;
        this.format = format;
        this.data = data;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("components", components);
        output.write("format", format);
        output.write("data", data);
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        components = input.readInt("components");
        format = input.readEnum("format", Format.class);
        data = input.readFloatArray("data");
    }

    public String getName() {
        return name;
    }

    public int getComponents() {
        return components;
    }

    public Format getFormat() {
        return format;
    }

    public float[] getData() {
        return data;
    }

    /** 等价的 VertexAttribute 描述（布局/规范序推导用；无状态转换） */
    public VertexAttribute toVertexAttribute() {
        return new VertexAttribute(name, components, format);
    }
}
