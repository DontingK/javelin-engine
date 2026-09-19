package com.javelin.rhi.data;

/**
 * 附件加载操作（上层 API）。
 * 对照 VkAttachmentLoadOp。
 */
public enum RHILoadOp {
    LOAD,       // 保留附件原有内容
    CLEAR,      // Pass 启动自动清屏
    DONT_CARE   // 丢弃原有内容，驱动优化
}
