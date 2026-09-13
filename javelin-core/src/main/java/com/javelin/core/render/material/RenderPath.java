package com.javelin.core.render.material;

/**
 * 渲染路径 —— Pass 所属的渲染管线。
 *
 * <p>着色器 DSL {@code renderPath} 声明的取值，由编辑器导入管线写入
 * {@link ShaderPass} 并随资产序列化（按 name() 落盘）。</p>
 */
public enum RenderPath {

    /** 前向渲染 */
    Forward,

    /** 延迟渲染 */
    Deferred,

    /** 阴影投射 */
    Shadow
}
