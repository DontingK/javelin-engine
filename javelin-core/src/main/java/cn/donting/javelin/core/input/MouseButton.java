package cn.donting.javelin.core.input;

/**
 * 引擎鼠标键枚举——<b>纯语义，与平台实现零关联</b>（键码映射由各 Window
 * 实现自行持有，见 {@code GLFWKeyMapping}/{@code AwtKeyMapping}）。
 */
public enum MouseButton implements InputCode {
    /** 左键 */
    LEFT,
    /** 右键 */
    RIGHT,
    /** 中键 */
    MIDDLE,
    /** 侧键/扩展键 4 */
    BUTTON_4,
    /** 侧键/扩展键 5 */
    BUTTON_5,
    /** 扩展键 6 */
    BUTTON_6,
    /** 扩展键 7 */
    BUTTON_7,
    /** 扩展键 8 */
    BUTTON_8,
    /** 未知按钮（实现映射不到的键位兜底） */
    UNKNOWN
}
