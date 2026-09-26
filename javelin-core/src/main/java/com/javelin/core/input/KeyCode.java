package com.javelin.core.input;

/**
 * 引擎键盘键枚举——<b>纯语义，与平台实现零关联</b>（用户拍板 2026-09-26：
 * KeyCode 不携带 GLFW 键码，GLFW/AWT 等平台键码 → 本枚举的映射由各
 * Window 实现自行持有，见 {@code GLFWKeyMapping}/{@code AwtKeyMapping}）。
 *
 * <p>收录常用键子集 + UNKNOWN 兜底；实现映射不到的键位落 UNKNOWN。</p>
 */
public enum KeyCode implements InputCode {

    // ---- 空格与标点 ----
    /** 空格 */
    SPACE,
    /** 撇号 ' */
    APOSTROPHE,
    /** 逗号 , */
    COMMA,
    /** 减号 -（主键盘） */
    MINUS,
    /** 句号 .（主键盘） */
    PERIOD,
    /** 斜杠 /（主键盘） */
    SLASH,

    // ---- 数字排 0-9 ----
    /** 主键盘 0 */
    ALPHA_0,
    /** 主键盘 1 */
    ALPHA_1,
    /** 主键盘 2 */
    ALPHA_2,
    /** 主键盘 3 */
    ALPHA_3,
    /** 主键盘 4 */
    ALPHA_4,
    /** 主键盘 5 */
    ALPHA_5,
    /** 主键盘 6 */
    ALPHA_6,
    /** 主键盘 7 */
    ALPHA_7,
    /** 主键盘 8 */
    ALPHA_8,
    /** 主键盘 9 */
    ALPHA_9,

    /** 分号 ; */
    SEMICOLON,
    /** 等号 =（主键盘） */
    EQUAL,

    // ---- 字母排 A-Z ----
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,

    /** 左方括号 [ */
    LEFT_BRACKET,
    /** 反斜杠 \ */
    BACKSLASH,
    /** 右方括号 ] */
    RIGHT_BRACKET,
    /** 重音符 ` */
    GRAVE_ACCENT,

    // ---- 导航与控制 ----
    /** Esc */
    ESCAPE,
    /** 回车 */
    ENTER,
    /** Tab */
    TAB,
    /** 退格 */
    BACKSPACE,
    /** Insert */
    INSERT,
    /** Delete */
    DELETE,
    /** → */
    RIGHT,
    /** ← */
    LEFT,
    /** ↓ */
    DOWN,
    /** ↑ */
    UP,
    /** Page Up */
    PAGE_UP,
    /** Page Down */
    PAGE_DOWN,
    /** Home */
    HOME,
    /** End */
    END,

    // ---- 锁定与系统 ----
    /** 大写锁定 */
    CAPS_LOCK,
    /** 滚动锁定 */
    SCROLL_LOCK,
    /** 数字锁定 */
    NUM_LOCK,
    /** Print Screen */
    PRINT_SCREEN,
    /** Pause */
    PAUSE,

    // ---- 功能键 F1-F12 ----
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,

    // ---- 小键盘 ----
    /** 小键盘 0 */
    KP_0,
    /** 小键盘 1 */
    KP_1,
    /** 小键盘 2 */
    KP_2,
    /** 小键盘 3 */
    KP_3,
    /** 小键盘 4 */
    KP_4,
    /** 小键盘 5 */
    KP_5,
    /** 小键盘 6 */
    KP_6,
    /** 小键盘 7 */
    KP_7,
    /** 小键盘 8 */
    KP_8,
    /** 小键盘 9 */
    KP_9,
    /** 小键盘 . */
    KP_DECIMAL,
    /** 小键盘 / */
    KP_DIVIDE,
    /** 小键盘 * */
    KP_MULTIPLY,
    /** 小键盘 - */
    KP_SUBTRACT,
    /** 小键盘 + */
    KP_ADD,
    /** 小键盘回车 */
    KP_ENTER,
    /** 小键盘 = */
    KP_EQUAL,

    // ---- 修饰键 ----
    /** 左 Shift */
    LEFT_SHIFT,
    /** 左 Ctrl */
    LEFT_CONTROL,
    /** 左 Alt */
    LEFT_ALT,
    /** 左 Super（Win/Cmd） */
    LEFT_SUPER,
    /** 右 Shift */
    RIGHT_SHIFT,
    /** 右 Ctrl */
    RIGHT_CONTROL,
    /** 右 Alt */
    RIGHT_ALT,
    /** 右 Super（Win/Cmd） */
    RIGHT_SUPER,

    /** Menu 键 */
    MENU,

    /** 未知键（实现映射不到的键位兜底） */
    UNKNOWN
}
