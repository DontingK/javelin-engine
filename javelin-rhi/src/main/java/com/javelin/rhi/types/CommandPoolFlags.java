package com.javelin.rhi.types;

/**
 * 命令池标志。
 * 对照 VkCommandPoolCreateFlags。
 */
public enum CommandPoolFlags {
    /** 短命命令缓冲提示（整池一次性重置更优） */
    TRANSIENT,              // VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
    /** 允许单独重置命令缓冲（否则只能重置整个池） */
    RESET_COMMAND_BUFFER,   // VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
    /** 保护内存命令缓冲 */
    PROTECTED;              // VK_COMMAND_POOL_CREATE_PROTECTED_BIT
}
