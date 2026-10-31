package cn.donting.javelin.core.render.material;

/**
 * 材质属性类型（.shader Properties 块，RHI 21.4 节类型集）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum MaterialPropertyType {

    /** 颜色，vec4（RGBA） */
    COLOR("vec4"),

    /** 向量，vec4 */
    VECTOR("vec4"),

    /** 浮点，float */
    FLOAT("float"),

    /** 限定范围浮点，float（带 min/max 元数据） */
    RANGE("float"),

    /** 2D 贴图槽，sampler2D（默认值是占位贴图名） */
    TEXTURE_2D("sampler2D");

    private final String glslType;

    MaterialPropertyType(String glslType) {
        this.glslType = glslType;
    }

    /**
     * 对应 GLSL 声明类型（离线构建工具生成 MaterialBlock / sampler 声明用）。
     */
    public String glslType() {
        return glslType;
    }
}
