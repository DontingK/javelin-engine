package com.javelin.core.input;

/**
 * 输入事件动作类型——<b>纯语义，与平台实现零关联</b>（GLFW 的动作值、
 * AWT 的 pressed/released 事件由各 Window 实现自行翻译）。
 *
 * <p>键与鼠标按钮共用。REPEAT 由系统键重复产生（按住不放），
 * v0.1 边沿查询不消费 repeat（等价 Unity GetKeyDown 只含首次按下）；
 * 文本输入场景消费 repeat 后置。</p>
 */
public enum InputAction {
    /** 释放 */
    RELEASE,
    /** 按下（首次） */
    PRESS,
    /** 系统键重复（按住不放产生） */
    REPEAT
}
