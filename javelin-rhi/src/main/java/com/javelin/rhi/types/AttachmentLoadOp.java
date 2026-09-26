package com.javelin.rhi.types;

/**
 * 附件加载操作。
 * 对照 VkAttachmentLoadOp。
 * 引擎层使用 RHILoadOp（data 包），此枚举供 RHI 内部映射使用。
 */
public enum AttachmentLoadOp {
    /** 保留附件原有内容 */
    LOAD,                   // VK_ATTACHMENT_LOAD_OP_LOAD
    /** 清除为指定清除值 */
    CLEAR,                  // VK_ATTACHMENT_LOAD_OP_CLEAR
    /** 内容未定义（驱动可优化，不许依赖旧内容） */
    DONT_CARE;              // VK_ATTACHMENT_LOAD_OP_DONT_CARE
}
