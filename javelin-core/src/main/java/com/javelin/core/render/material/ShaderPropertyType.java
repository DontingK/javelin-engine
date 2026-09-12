package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;

/**
 * Shader 属性类型枚举。
 *
 * <p>对应 DSL {@code Properties} 块中声明的属性类型，供编辑器 Inspector 与
 * {@link ShaderProperty} 的默认值/上下限序列化分派使用。</p>
 */
@Reflective
public enum ShaderPropertyType {

    /** 单精度浮点数 */
    FLOAT,

    /** 范围浮点数（带 rangeMin/rangeMax 上下限） */
    RANGE,

    /** 整数 */
    INT,

    /** 布尔值 */
    BOOL,

    /** 二维向量（float[2]） */
    VECTOR2,

    /** 三维向量（float[3]） */
    VECTOR3,

    /** 四维向量（float[4]） */
    VECTOR4,

    /** 颜色（float[4]，RGBA） */
    COLOR,

    /** 纹理（默认值为内建纹理名或纹理资产 GUID 字符串） */
    TEXTURE
}
