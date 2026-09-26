package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 材质属性元数据（.shader Properties 块的一条声明，RHI 21.4 节）。
 *
 * <p>纯数据、device 无关；由离线构建工具从 .shader 解析生成，
 * 是 set 2 材质布局（MaterialBlock + 贴图槽位）的来源。</p>
 *
 * <p>序列化：实现 {@link Serializable} 走 Capsule 二进制协议（.shader 资产产物格式，
 * Engine_Material_Design §3）；字段非 final——反序列化需回写（红线 9）；
 * TEXTURE_2D 的 defaultValues/占位名为 null，Capsule 写入跳过、读取返回 null。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class MaterialProperty implements Serializable {

    private String name;
    private String displayName;
    private MaterialPropertyType type;
    /** COLOR/VECTOR 为 4 元，FLOAT/RANGE 为 1 元，TEXTURE_2D 为 null */
    private float[] defaultValues;
    /** RANGE 的取值下限 */
    private float min;
    /** RANGE 的取值上限 */
    private float max;
    /** TEXTURE_2D 的占位贴图名（white / bump / black，RHI 21.4 节约定） */
    private String texturePlaceholder;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public MaterialProperty() {
    }

    private MaterialProperty(String name, String displayName, MaterialPropertyType type,
                             float[] defaultValues, float min, float max, String texturePlaceholder) {
        this.name = name;
        this.displayName = displayName;
        this.type = type;
        this.defaultValues = defaultValues;
        this.min = min;
        this.max = max;
        this.texturePlaceholder = texturePlaceholder;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("displayName", displayName);
        output.write("type", type);
        output.write("defaultValues", defaultValues);
        output.write("min", min);
        output.write("max", max);
        output.write("texturePlaceholder", texturePlaceholder);
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        displayName = input.readString("displayName");
        type = input.readEnum("type", MaterialPropertyType.class);
        defaultValues = input.readFloatArray("defaultValues");
        min = input.readFloat("min");
        max = input.readFloat("max");
        texturePlaceholder = input.readString("texturePlaceholder");
    }

    /**
     * Color 属性（vec4，默认白）。
     */
    public static MaterialProperty color(String name, String displayName,
                                         float r, float g, float b, float a) {
        return new MaterialProperty(name, displayName, MaterialPropertyType.COLOR,
                new float[]{r, g, b, a}, 0, 0, null);
    }

    /**
     * Vector 属性（vec4）。
     */
    public static MaterialProperty vector(String name, String displayName,
                                          float x, float y, float z, float w) {
        return new MaterialProperty(name, displayName, MaterialPropertyType.VECTOR,
                new float[]{x, y, z, w}, 0, 0, null);
    }

    /**
     * Float 属性。
     */
    public static MaterialProperty floatProp(String name, String displayName, float defaultValue) {
        return new MaterialProperty(name, displayName, MaterialPropertyType.FLOAT,
                new float[]{defaultValue}, 0, 0, null);
    }

    /**
     * Range 属性（带取值区间）。
     */
    public static MaterialProperty range(String name, String displayName,
                                         float min, float max, float defaultValue) {
        if (defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException(
                    "Range 默认值越界: " + name + " = " + defaultValue + " [" + min + ", " + max + "]");
        }
        return new MaterialProperty(name, displayName, MaterialPropertyType.RANGE,
                new float[]{defaultValue}, min, max, null);
    }

    /**
     * 2D 贴图槽（占位贴图名见 RHI 21.4 节：white / bump / black）。
     */
    public static MaterialProperty texture2D(String name, String displayName, String placeholder) {
        return new MaterialProperty(name, displayName, MaterialPropertyType.TEXTURE_2D,
                null, 0, 0, placeholder);
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public MaterialPropertyType getType() {
        return type;
    }

    /**
     * 数值型默认值（COLOR/VECTOR 4 元，FLOAT/RANGE 1 元，TEXTURE_2D 返回 null）。
     */
    public float[] getDefaultValues() {
        return defaultValues;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    public String getTexturePlaceholder() {
        return texturePlaceholder;
    }
}
