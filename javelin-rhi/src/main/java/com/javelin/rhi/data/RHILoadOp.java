package com.javelin.rhi.data;

/**
 * 渲染附件的加载操作，定义在开始渲染时如何处理附件的现有内容。
 */
public enum RHILoadOp {
    /** 保留附件的现有内容。 */
    LOAD,
    /** 使用清除值清除附件内容。 */
    CLEAR,
    /** 附件内容未定义，内容可能为任意值。 */
    DONT_CARE
}
