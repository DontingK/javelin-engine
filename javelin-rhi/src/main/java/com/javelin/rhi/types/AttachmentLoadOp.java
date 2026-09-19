package com.javelin.rhi.types;

/**
 * 附件加载操作。
 * 对照 VkAttachmentLoadOp。
 * 引擎层使用 RHILoadOp（data 包），此枚举供 RHI 内部映射使用。
 */
public enum AttachmentLoadOp {
    LOAD,                   // VK_ATTACHMENT_LOAD_OP_LOAD
    CLEAR,                  // VK_ATTACHMENT_LOAD_OP_CLEAR
    DONT_CARE;              // VK_ATTACHMENT_LOAD_OP_DONT_CARE
}
