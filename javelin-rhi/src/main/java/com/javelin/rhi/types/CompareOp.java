package com.javelin.rhi.types;

/**
 * 比较操作。
 * 对照 VkCompareOp。
 */
public enum CompareOp {
    NEVER,                  // VK_COMPARE_OP_NEVER
    LESS,                   // VK_COMPARE_OP_LESS
    EQUAL,                  // VK_COMPARE_OP_EQUAL
    LESS_OR_EQUAL,          // VK_COMPARE_OP_LESS_OR_EQUAL
    GREATER,                // VK_COMPARE_OP_GREATER
    NOT_EQUAL,              // VK_COMPARE_OP_NOT_EQUAL
    GREATER_OR_EQUAL,       // VK_COMPARE_OP_GREATER_OR_EQUAL
    ALWAYS;                 // VK_COMPARE_OP_ALWAYS
}
