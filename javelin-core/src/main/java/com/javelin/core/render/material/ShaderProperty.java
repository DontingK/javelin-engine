package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * Shader 可暴露属性定义。
 *
 * <p>由编辑器导入管线从 DSL {@code Properties} 块生成，描述一个属性的
 * 名称、显示名、类型、默认值；RANGE 类型额外携带上下限（编辑器滑动条需要，
 * engine4 解析后丢弃，本层保留）。</p>
 *
 * <p>默认值运行时形态由 {@link #type} 决定：FLOAT/RANGE→{@link Float}、
 * INT→{@link Integer}、BOOL→{@link Boolean}、
 * VECTOR2/3/4/COLOR→{@code float[]}、TEXTURE→{@link String}（内建纹理名或 GUID）。</p>
 */
@Reflective
public class ShaderProperty implements Serializable {

    /** 属性名，对应 GLSL 中的 uniform 名（如 "_Color"） */
    private String name;

    /** 显示名，供编辑器 UI 展示（如 "Tint"） */
    private String displayName;

    /** 属性类型 */
    private ShaderPropertyType type;

    /** 默认值，形态见类 javadoc；null 表示无默认值 */
    private Object defaultValue;

    /** RANGE 下限，非 RANGE 类型为 null */
    private Float rangeMin;

    /** RANGE 上限，非 RANGE 类型为 null */
    private Float rangeMax;

    public ShaderProperty() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Serializable
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("displayName", displayName);
        output.write("type", type);

        boolean hasDefault = type != null && defaultValue != null;
        output.write("hasDefault", hasDefault);
        if (hasDefault) {
            writeDefaultValue(output, "defaultValue", defaultValue);
        }

        boolean hasRange = type == ShaderPropertyType.RANGE && rangeMin != null && rangeMax != null;
        output.write("hasRange", hasRange);
        if (hasRange) {
            output.write("rangeMin", rangeMin);
            output.write("rangeMax", rangeMax);
        }
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        displayName = input.readString("displayName");
        type = input.readEnum("type", ShaderPropertyType.class);

        if (input.readBoolean("hasDefault") && type != null) {
            defaultValue = readDefaultValue(input, "defaultValue", type);
        } else {
            defaultValue = null;
        }

        if (input.readBoolean("hasRange") && type == ShaderPropertyType.RANGE) {
            rangeMin = input.readFloat("rangeMin");
            rangeMax = input.readFloat("rangeMax");
        } else {
            rangeMin = null;
            rangeMax = null;
        }
    }

    /** 按属性类型分派写默认值 */
    static void writeDefaultValue(OutputCapsule output, String key, Object value) {
        if (value instanceof Float || value instanceof Double) {
            output.write(key, ((Number) value).floatValue());
        } else if (value instanceof Number num) {
            output.write(key, num.intValue());
        } else if (value instanceof Boolean bool) {
            output.write(key, bool);
        } else if (value instanceof float[] vec) {
            output.write(key, vec);
        } else if (value instanceof String str) {
            output.write(key, str);
        } else {
            throw new IllegalStateException("不支持的属性默认值类型: " + value.getClass());
        }
    }

    /** 按属性类型分派读默认值 */
    static Object readDefaultValue(InputCapsule input, String key, ShaderPropertyType type) {
        return switch (type) {
            case FLOAT, RANGE -> input.readFloat(key);
            case INT -> input.readInt(key);
            case BOOL -> input.readBoolean(key);
            case VECTOR2, VECTOR3, VECTOR4, COLOR -> input.readFloatArray(key);
            case TEXTURE -> input.readString(key);
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public ShaderPropertyType getType() {
        return type;
    }

    public void setType(ShaderPropertyType type) {
        this.type = type;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Float getRangeMin() {
        return rangeMin;
    }

    public void setRangeMin(Float rangeMin) {
        this.rangeMin = rangeMin;
    }

    public Float getRangeMax() {
        return rangeMax;
    }

    public void setRangeMax(Float rangeMax) {
        this.rangeMax = rangeMax;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Builder
    // ═══════════════════════════════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    /** 链式构建器 */
    public static final class Builder {
        private String name;
        private String displayName;
        private ShaderPropertyType type;
        private Object defaultValue;
        private Float rangeMin;
        private Float rangeMax;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder type(ShaderPropertyType type) {
            this.type = type;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder rangeMin(Float rangeMin) {
            this.rangeMin = rangeMin;
            return this;
        }

        public Builder rangeMax(Float rangeMax) {
            this.rangeMax = rangeMax;
            return this;
        }

        public ShaderProperty build() {
            ShaderProperty prop = new ShaderProperty();
            prop.name = name;
            prop.displayName = displayName;
            prop.type = type;
            prop.defaultValue = defaultValue;
            prop.rangeMin = rangeMin;
            prop.rangeMax = rangeMax;
            return prop;
        }
    }
}
