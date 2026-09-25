package com.javelin.core.render.material;

/**
 * 材质属性元数据（.shader Properties 块的一条声明，RHI 21.4 节）。
 *
 * <p>纯数据、device 无关；由离线构建工具从 .shader 解析生成，
 * 是 set 2 材质布局（MaterialBlock + 贴图槽位）的来源。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class MaterialProperty {

    private final String name;
    private final String displayName;
    private final MaterialPropertyType type;
    /** COLOR/VECTOR 为 4 元，FLOAT/RANGE 为 1 元，TEXTURE_2D 为 null */
    private final float[] defaultValues;
    /** RANGE 的取值下限 */
    private final float min;
    /** RANGE 的取值上限 */
    private final float max;
    /** TEXTURE_2D 的占位贴图名（white / bump / black，RHI 21.4 节约定） */
    private final String texturePlaceholder;

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
