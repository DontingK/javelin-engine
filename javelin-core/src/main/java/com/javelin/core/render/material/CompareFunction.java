package com.javelin.core.render.material;

/**
 * 深度比较函数，Off 表示关闭深度测试。
 *
 * <p>着色器 DSL {@code zTest} 声明的取值，由编辑器导入管线写入
 * {@link ShaderPass} 并随资产序列化（按 name() 落盘）。</p>
 */
public enum CompareFunction {

    /** 关闭深度测试 */
    Off,

    /** 永远不通过 */
    Never,

    /** 小于 */
    Less,

    /** 小于等于 */
    LessEqual,

    /** 等于 */
    Equal,

    /** 大于 */
    Greater,

    /** 大于等于 */
    GreaterEqual,

    /** 总是通过 */
    Always
}
