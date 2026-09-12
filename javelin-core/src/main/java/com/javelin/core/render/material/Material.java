package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 材质资产 —— Shader GUID 引用 + 参数覆盖值。
 *
 * <p>纯数据层：不持有 RHI 资源（Buffer/DescriptorSet），运行时装配
 * （MaterialParams UBO、纹理 DescriptorSet 写入）属渲染侧阶段，后续实现。</p>
 *
 * <p>参数覆盖值 {@link #propertyOverrides} 的运行时形态：</p>
 * <ul>
 *   <li>FLOAT/RANGE → {@link Float}</li>
 *   <li>INT → {@link Integer}</li>
 *   <li>BOOL → {@link Boolean}</li>
 *   <li>VECTOR2/3/4、COLOR → {@code float[]}</li>
 *   <li>TEXTURE → {@link String}：纹理资产类型本期尚未建立，暂以 GUID 字符串占位，
 *       后续替换为 Texture 资产引用（序列化侧仍按字符串 GUID 落地）</li>
 * </ul>
 *
 * <p>序列化：{@code shader} 字段走 SERIALIZE 的 ASSET 机制（只写 GUID），
 * 因此导入侧必须通过 {@code new BinaryImporter(file, assetManager)} 提供
 * {@link com.javelin.core.assets.AssetManager}，否则读取时抛出 IllegalStateException。</p>
 */
@Reflective
public class Material extends AbsAsset {

    /** 引用的 Shader 资产（序列化只写 GUID，读取经 AssetManager 解析） */
    private Shader shader;

    /** 参数覆盖值：属性名 → 覆盖值（形态见类 javadoc） */
    private final Map<String, Object> propertyOverrides = new LinkedHashMap<>();

    public Material() {
    }

    public Material(Shader shader) {
        this.shader = shader;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  参数覆盖
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 设置参数覆盖值。
     *
     * @param name  属性名
     * @param value 覆盖值（Float/Integer/Boolean/float[]/String）
     */
    public void setProperty(String name, Object value) {
        propertyOverrides.put(name, value);
    }

    /**
     * 获取参数覆盖值。
     *
     * @param name 属性名
     * @return 覆盖值，未覆盖返回 null
     */
    public Object getProperty(String name) {
        return propertyOverrides.get(name);
    }

    /** 获取全部参数覆盖（只读） */
    public Map<String, Object> getPropertyOverrides() {
        return Collections.unmodifiableMap(propertyOverrides);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public Shader getShader() {
        return shader;
    }

    public void setShader(Shader shader) {
        this.shader = shader;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Serializable
    //
    //  serialize 包的 Capsule 不支持 Map，propertyOverrides 手写为索引条目：
    //  overrideCount + 每条 ov<i>.{name,kind,value}，kind 标记值类型。
    // ═══════════════════════════════════════════════════════════════════════

    /** 覆盖值类型标记 */
    private static final String KIND_FLOAT = "float";
    private static final String KIND_INT = "int";
    private static final String KIND_BOOL = "bool";
    private static final String KIND_VECTOR = "vec";
    private static final String KIND_STRING = "string";

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("shader", shader);
        output.write("overrideCount", propertyOverrides.size());
        int i = 0;
        for (Map.Entry<String, Object> entry : propertyOverrides.entrySet()) {
            String prefix = "ov" + i + ".";
            output.write(prefix + "name", entry.getKey());
            writeOverrideValue(output, prefix, entry.getValue());
            i++;
        }
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        shader = input.readSerializable("shader", Shader.class);
        propertyOverrides.clear();
        int count = input.readInt("overrideCount");
        for (int i = 0; i < count; i++) {
            String prefix = "ov" + i + ".";
            String name = input.readString(prefix + "name");
            propertyOverrides.put(name, readOverrideValue(input, prefix));
        }
    }

    /** 写单条覆盖值：kind 标记 + 按形态分派的值 */
    private void writeOverrideValue(OutputCapsule output, String prefix, Object value) {
        if (value == null) {
            output.write(prefix + "kind", (String) null); // null 值不写入 kind，读取侧得 null
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            output.write(prefix + "kind", KIND_FLOAT);
            output.write(prefix + "value", ((Number) value).floatValue());
        } else if (value instanceof Number num) {
            output.write(prefix + "kind", KIND_INT);
            output.write(prefix + "value", num.intValue());
        } else if (value instanceof Boolean bool) {
            output.write(prefix + "kind", KIND_BOOL);
            output.write(prefix + "value", bool);
        } else if (value instanceof float[] vec) {
            output.write(prefix + "kind", KIND_VECTOR);
            output.write(prefix + "value", vec);
        } else if (value instanceof String str) {
            output.write(prefix + "kind", KIND_STRING);
            output.write(prefix + "value", str);
        } else {
            throw new IllegalStateException("不支持的材质覆盖值类型: " + value.getClass());
        }
    }

    /** 读单条覆盖值，与 {@link #writeOverrideValue} 对应 */
    private Object readOverrideValue(InputCapsule input, String prefix) {
        String kind = input.readString(prefix + "kind");
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case KIND_FLOAT -> input.readFloat(prefix + "value");
            case KIND_INT -> input.readInt(prefix + "value");
            case KIND_BOOL -> input.readBoolean(prefix + "value");
            case KIND_VECTOR -> input.readFloatArray(prefix + "value");
            case KIND_STRING -> input.readString(prefix + "value");
            default -> throw new IllegalStateException("未知的材质覆盖值类型标记: " + kind);
        };
    }
}
