package com.javelin.core.render.material;

/**
 * 混合因子 —— {@link BlendDesc} 的源/目标因子取值。
 *
 * <p>着色器 DSL {@code blend} 声明的取值，由编辑器导入管线写入
 * BlendDesc 并随资产序列化（按 name() 落盘）。自包含定义，
 * 不依赖 RHI 的 BlendFactor。</p>
 */
public enum BlendFactor {

    /** 0 */
    Zero,

    /** 1 */
    One,

    /** 源颜色 */
    SrcColor,

    /** 1 - 源颜色 */
    OneMinusSrcColor,

    /** 目标颜色 */
    DstColor,

    /** 1 - 目标颜色 */
    OneMinusDstColor,

    /** 源 alpha */
    SrcAlpha,

    /** 1 - 源 alpha */
    OneMinusSrcAlpha,

    /** 目标 alpha */
    DstAlpha,

    /** 1 - 目标 alpha */
    OneMinusDstAlpha
}
