package com.javelin.core.render.material;

/**
 * 面剔除模式。
 *
 * <p>着色器 DSL {@code cullMode} 声明的取值，由编辑器导入管线写入
 * {@link ShaderPass} 并随资产序列化（按 name() 落盘）。</p>
 */
public enum CullMode {

    /** 剔除背面 */
    Back,

    /** 剔除正面 */
    Front,

    /** 不剔除 */
    None
}
