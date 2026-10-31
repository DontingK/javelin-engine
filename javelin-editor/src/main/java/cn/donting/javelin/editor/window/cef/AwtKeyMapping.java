package cn.donting.javelin.editor.window.cef;

import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.input.MouseButton;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * AWT 键码（VK_ 值）→ 引擎输入枚举翻译（{@link CanvasSurface} 实现私有）。
 *
 * <p>KeyCode/MouseButton 是纯语义枚举，不携带平台键码（用户拍板 2026-09-26：
 * 映射由实现自己持有）。本类即 Swing/CEF 窗口侧的全部键码关联。</p>
 *
 * <p>注意与 GLFW 的差异：VK_ 常量只有字母/数字/部分标点与 GLFW 重合
 * （ASCII 段），导航/F 键/小键盘全部错位；AWT 不区分左右修饰键（统一落
 * LEFT_*）。已知不映射：方括号（VK_BRACELEFT 物理键位不可靠）、
 * 小键盘回车（AWT 多按 VK_ENTER 报）——落 UNKNOWN。</p>
 */
final class AwtKeyMapping {

    /** VK_A..VK_Z（'A'..'Z'）→ A..Z（连号区间直落） */
    private static final KeyCode[] LETTERS = {
            KeyCode.A, KeyCode.B, KeyCode.C, KeyCode.D, KeyCode.E, KeyCode.F, KeyCode.G,
            KeyCode.H, KeyCode.I, KeyCode.J, KeyCode.K, KeyCode.L, KeyCode.M, KeyCode.N,
            KeyCode.O, KeyCode.P, KeyCode.Q, KeyCode.R, KeyCode.S, KeyCode.T, KeyCode.U,
            KeyCode.V, KeyCode.W, KeyCode.X, KeyCode.Y, KeyCode.Z,
    };

    /** VK_0..VK_9（'0'..'9'）→ ALPHA_0..9（连号区间直落） */
    private static final KeyCode[] DIGITS = {
            KeyCode.ALPHA_0, KeyCode.ALPHA_1, KeyCode.ALPHA_2, KeyCode.ALPHA_3, KeyCode.ALPHA_4,
            KeyCode.ALPHA_5, KeyCode.ALPHA_6, KeyCode.ALPHA_7, KeyCode.ALPHA_8, KeyCode.ALPHA_9,
    };

    /** VK_F1..VK_F12（连号区间直落） */
    private static final KeyCode[] FUNCTION = {
            KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5, KeyCode.F6,
            KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12,
    };

    /** VK_NUMPAD0..VK_NUMPAD9（连号区间直落） */
    private static final KeyCode[] KEYPAD = {
            KeyCode.KP_0, KeyCode.KP_1, KeyCode.KP_2, KeyCode.KP_3, KeyCode.KP_4,
            KeyCode.KP_5, KeyCode.KP_6, KeyCode.KP_7, KeyCode.KP_8, KeyCode.KP_9,
    };

    /** InputSink 修饰位掩码契约各组成部分 */
    private static final int MOD_SHIFT = 0x1;
    private static final int MOD_CONTROL = 0x2;
    private static final int MOD_ALT = 0x4;
    private static final int MOD_SUPER = 0x8;

    private AwtKeyMapping() {
    }

    /** AWT 键码（{@link KeyEvent#getKeyCode()}）→ 引擎键枚举（映射不到归 UNKNOWN） */
    static KeyCode keyCode(int vk) {
        if (vk >= KeyEvent.VK_A && vk <= KeyEvent.VK_Z) {
            return LETTERS[vk - KeyEvent.VK_A];
        }
        if (vk >= KeyEvent.VK_0 && vk <= KeyEvent.VK_9) {
            return DIGITS[vk - KeyEvent.VK_0];
        }
        if (vk >= KeyEvent.VK_F1 && vk <= KeyEvent.VK_F12) {
            return FUNCTION[vk - KeyEvent.VK_F1];
        }
        if (vk >= KeyEvent.VK_NUMPAD0 && vk <= KeyEvent.VK_NUMPAD9) {
            return KEYPAD[vk - KeyEvent.VK_NUMPAD0];
        }
        switch (vk) {
            // ASCII 重合标点
            case KeyEvent.VK_SPACE: return KeyCode.SPACE;
            case KeyEvent.VK_COMMA: return KeyCode.COMMA;
            case KeyEvent.VK_MINUS: return KeyCode.MINUS;
            case KeyEvent.VK_PERIOD: return KeyCode.PERIOD;
            case KeyEvent.VK_SLASH: return KeyCode.SLASH;
            case KeyEvent.VK_SEMICOLON: return KeyCode.SEMICOLON;
            case KeyEvent.VK_EQUALS: return KeyCode.EQUAL;
            case KeyEvent.VK_BACK_SLASH: return KeyCode.BACKSLASH;
            case KeyEvent.VK_BACK_QUOTE: return KeyCode.GRAVE_ACCENT;
            case KeyEvent.VK_QUOTE: return KeyCode.APOSTROPHE;
            // 与 GLFW 错位的导航/控制
            case KeyEvent.VK_ENTER: return KeyCode.ENTER;
            case KeyEvent.VK_BACK_SPACE: return KeyCode.BACKSPACE;
            case KeyEvent.VK_TAB: return KeyCode.TAB;
            case KeyEvent.VK_ESCAPE: return KeyCode.ESCAPE;
            case KeyEvent.VK_PAGE_UP: return KeyCode.PAGE_UP;
            case KeyEvent.VK_PAGE_DOWN: return KeyCode.PAGE_DOWN;
            case KeyEvent.VK_END: return KeyCode.END;
            case KeyEvent.VK_HOME: return KeyCode.HOME;
            case KeyEvent.VK_LEFT: return KeyCode.LEFT;
            case KeyEvent.VK_UP: return KeyCode.UP;
            case KeyEvent.VK_RIGHT: return KeyCode.RIGHT;
            case KeyEvent.VK_DOWN: return KeyCode.DOWN;
            // 修饰键（AWT 不分左右，统一落左侧）
            case KeyEvent.VK_SHIFT: return KeyCode.LEFT_SHIFT;
            case KeyEvent.VK_CONTROL: return KeyCode.LEFT_CONTROL;
            case KeyEvent.VK_ALT: return KeyCode.LEFT_ALT;
            case KeyEvent.VK_META: return KeyCode.LEFT_SUPER;
            // 锁定与系统
            case KeyEvent.VK_CAPS_LOCK: return KeyCode.CAPS_LOCK;
            case KeyEvent.VK_NUM_LOCK: return KeyCode.NUM_LOCK;
            case KeyEvent.VK_SCROLL_LOCK: return KeyCode.SCROLL_LOCK;
            case KeyEvent.VK_PAUSE: return KeyCode.PAUSE;
            case KeyEvent.VK_INSERT: return KeyCode.INSERT;
            case KeyEvent.VK_DELETE: return KeyCode.DELETE;
            // 小键盘运算符
            case KeyEvent.VK_MULTIPLY: return KeyCode.KP_MULTIPLY;
            case KeyEvent.VK_ADD: return KeyCode.KP_ADD;
            case KeyEvent.VK_SUBTRACT: return KeyCode.KP_SUBTRACT;
            case KeyEvent.VK_DECIMAL:
            case KeyEvent.VK_SEPARATER: return KeyCode.KP_DECIMAL;
            case KeyEvent.VK_DIVIDE: return KeyCode.KP_DIVIDE;
            default: return KeyCode.UNKNOWN;
        }
    }

    /**
     * AWT 鼠标按钮号（1-8：BUTTON1=左 / BUTTON2=中 / BUTTON3=右）→ 引擎枚举。
     */
    static MouseButton mouseButton(int awtButton) {
        switch (awtButton) {
            case MouseEvent.BUTTON1: return MouseButton.LEFT;
            case MouseEvent.BUTTON2: return MouseButton.MIDDLE;
            case MouseEvent.BUTTON3: return MouseButton.RIGHT;
            case 4: return MouseButton.BUTTON_4;
            case 5: return MouseButton.BUTTON_5;
            case 6: return MouseButton.BUTTON_6;
            case 7: return MouseButton.BUTTON_7;
            case 8: return MouseButton.BUTTON_8;
            default: return MouseButton.UNKNOWN;
        }
    }

    /** AWT 修饰掩码（{@link InputEvent#getModifiersEx()}）→ InputSink 契约位掩码 */
    static int modifiers(int modifiersEx) {
        int mods = 0;
        if ((modifiersEx & InputEvent.SHIFT_DOWN_MASK) != 0) {
            mods |= MOD_SHIFT;
        }
        if ((modifiersEx & InputEvent.CTRL_DOWN_MASK) != 0) {
            mods |= MOD_CONTROL;
        }
        if ((modifiersEx & InputEvent.META_DOWN_MASK) != 0) {
            mods |= MOD_SUPER;
        }
        if ((modifiersEx & InputEvent.ALT_DOWN_MASK) != 0) {
            mods |= MOD_ALT;
        }
        return mods;
    }
}
