package cn.donting.javelin.rhi.types;

/**
 * 比较操作。
 * 对照 VkCompareOp。
 */
public enum CompareOp {
    /** 比较永不通过 */
    NEVER,                  // VK_COMPARE_OP_NEVER
    /** 通过条件：参考值 &lt; 目标值（深度测试常用） */
    LESS,                   // VK_COMPARE_OP_LESS
    /** 通过条件：参考值 == 目标值 */
    EQUAL,                  // VK_COMPARE_OP_EQUAL
    /** 通过条件：参考值 &lt;= 目标值 */
    LESS_OR_EQUAL,          // VK_COMPARE_OP_LESS_OR_EQUAL
    /** 通过条件：参考值 &gt; 目标值 */
    GREATER,                // VK_COMPARE_OP_GREATER
    /** 通过条件：参考值 != 目标值 */
    NOT_EQUAL,              // VK_COMPARE_OP_NOT_EQUAL
    /** 通过条件：参考值 &gt;= 目标值 */
    GREATER_OR_EQUAL,       // VK_COMPARE_OP_GREATER_OR_EQUAL
    /** 比较恒通过 */
    ALWAYS;                 // VK_COMPARE_OP_ALWAYS
}
