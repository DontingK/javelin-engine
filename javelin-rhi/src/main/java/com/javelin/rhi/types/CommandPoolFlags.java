package com.javelin.rhi.types;

/**
 * 命令池标志。
 * 对照 VkCommandPoolCreateFlags。
 */
public enum CommandPoolFlags {
    TRANSIENT,              // VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
    RESET_COMMAND_BUFFER,   // VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
    PROTECTED;              // VK_COMMAND_POOL_CREATE_PROTECTED_BIT
}
