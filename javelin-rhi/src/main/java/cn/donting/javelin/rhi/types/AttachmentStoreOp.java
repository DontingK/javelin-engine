package cn.donting.javelin.rhi.types;

/**
 * 附件存储操作。
 * 对照 VkAttachmentStoreOp。
 */
public enum AttachmentStoreOp {
    /** Pass 结束时写回附件内容 */
    STORE,                  // VK_ATTACHMENT_STORE_OP_STORE
    /** 不保证写回（内容未定义，本 Pass 结果可丢弃时用） */
    DONT_CARE;              // VK_ATTACHMENT_STORE_OP_DONT_CARE
}
