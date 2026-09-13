package com.javelin.core.render.material;

/**
 * 渲染层 —— Pass 的渲染顺序与排序策略分类。
 *
 * <p>着色器 DSL {@code renderLayer} 声明的取值，由编辑器导入管线写入
 * {@link ShaderPass} 并随资产序列化（按 name() 落盘）。
 * 与 {@code com.javelin.core.render.RenderLayer}（RenderList 排序分类）
 * 是不同层面的概念。</p>
 */
public enum RenderLayer {

    /** 不透明层 */
    Opaque,

    /** 透明层 */
    Transparent,

    /** 覆盖层（UI、Gizmo） */
    Overlay
}
