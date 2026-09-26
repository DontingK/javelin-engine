package com.javelin.rhi.data;

/**
 * 附件加载操作（上层 API）。
 * 对照 VkAttachmentLoadOp。
 */
public enum RHILoadOp {
    /** 保留附件原有内容 */
    LOAD,
    /** Pass 启动时清除为指定清除值 */
    CLEAR,
    /** 丢弃原有内容，内容未定义（驱动可优化） */
    DONT_CARE
}
