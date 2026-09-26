package com.javelin.core.window;

import com.javelin.core.input.InputAction;
import com.javelin.core.input.KeyCode;
import com.javelin.core.input.MouseButton;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW 键码 → 引擎输入枚举翻译（{@link GLFWWindow} 实现私有）。
 *
 * <p>KeyCode/MouseButton/InputAction 是纯语义枚举，不携带平台键码
 * （用户拍板 2026-09-26：映射由实现自己持有）。本类即 GLFW 侧的全部
 * 键码关联，换窗口平台时随 GLFWWindow 一起废弃。</p>
 *
 * <p>键码反查表：GLFW 键值域 [0, GLFW_KEY_MENU=348]，静态一次填充，
 * O(1) 无装箱；未收录键位（WORLD_1/2 等）与越界落 UNKNOWN。</p>
 */
final class GLFWKeyMapping {

    /** GLFW 键码值域上限（GLFW_KEY_MENU） */
    private static final int GLFW_KEY_LAST = GLFW_KEY_MENU;

    private static final KeyCode[] KEY_TABLE = new KeyCode[GLFW_KEY_LAST + 1];

    static {
        // 空格与标点
        KEY_TABLE[GLFW_KEY_SPACE] = KeyCode.SPACE;
        KEY_TABLE[GLFW_KEY_APOSTROPHE] = KeyCode.APOSTROPHE;
        KEY_TABLE[GLFW_KEY_COMMA] = KeyCode.COMMA;
        KEY_TABLE[GLFW_KEY_MINUS] = KeyCode.MINUS;
        KEY_TABLE[GLFW_KEY_PERIOD] = KeyCode.PERIOD;
        KEY_TABLE[GLFW_KEY_SLASH] = KeyCode.SLASH;
        // 数字排
        KEY_TABLE[GLFW_KEY_0] = KeyCode.ALPHA_0;
        KEY_TABLE[GLFW_KEY_1] = KeyCode.ALPHA_1;
        KEY_TABLE[GLFW_KEY_2] = KeyCode.ALPHA_2;
        KEY_TABLE[GLFW_KEY_3] = KeyCode.ALPHA_3;
        KEY_TABLE[GLFW_KEY_4] = KeyCode.ALPHA_4;
        KEY_TABLE[GLFW_KEY_5] = KeyCode.ALPHA_5;
        KEY_TABLE[GLFW_KEY_6] = KeyCode.ALPHA_6;
        KEY_TABLE[GLFW_KEY_7] = KeyCode.ALPHA_7;
        KEY_TABLE[GLFW_KEY_8] = KeyCode.ALPHA_8;
        KEY_TABLE[GLFW_KEY_9] = KeyCode.ALPHA_9;
        KEY_TABLE[GLFW_KEY_SEMICOLON] = KeyCode.SEMICOLON;
        KEY_TABLE[GLFW_KEY_EQUAL] = KeyCode.EQUAL;
        // 字母排（GLFW_KEY_A..Z = 'A'..'Z' 与 ASCII 同码值）
        KEY_TABLE[GLFW_KEY_A] = KeyCode.A;
        KEY_TABLE[GLFW_KEY_B] = KeyCode.B;
        KEY_TABLE[GLFW_KEY_C] = KeyCode.C;
        KEY_TABLE[GLFW_KEY_D] = KeyCode.D;
        KEY_TABLE[GLFW_KEY_E] = KeyCode.E;
        KEY_TABLE[GLFW_KEY_F] = KeyCode.F;
        KEY_TABLE[GLFW_KEY_G] = KeyCode.G;
        KEY_TABLE[GLFW_KEY_H] = KeyCode.H;
        KEY_TABLE[GLFW_KEY_I] = KeyCode.I;
        KEY_TABLE[GLFW_KEY_J] = KeyCode.J;
        KEY_TABLE[GLFW_KEY_K] = KeyCode.K;
        KEY_TABLE[GLFW_KEY_L] = KeyCode.L;
        KEY_TABLE[GLFW_KEY_M] = KeyCode.M;
        KEY_TABLE[GLFW_KEY_N] = KeyCode.N;
        KEY_TABLE[GLFW_KEY_O] = KeyCode.O;
        KEY_TABLE[GLFW_KEY_P] = KeyCode.P;
        KEY_TABLE[GLFW_KEY_Q] = KeyCode.Q;
        KEY_TABLE[GLFW_KEY_R] = KeyCode.R;
        KEY_TABLE[GLFW_KEY_S] = KeyCode.S;
        KEY_TABLE[GLFW_KEY_T] = KeyCode.T;
        KEY_TABLE[GLFW_KEY_U] = KeyCode.U;
        KEY_TABLE[GLFW_KEY_V] = KeyCode.V;
        KEY_TABLE[GLFW_KEY_W] = KeyCode.W;
        KEY_TABLE[GLFW_KEY_X] = KeyCode.X;
        KEY_TABLE[GLFW_KEY_Y] = KeyCode.Y;
        KEY_TABLE[GLFW_KEY_Z] = KeyCode.Z;
        KEY_TABLE[GLFW_KEY_LEFT_BRACKET] = KeyCode.LEFT_BRACKET;
        KEY_TABLE[GLFW_KEY_BACKSLASH] = KeyCode.BACKSLASH;
        KEY_TABLE[GLFW_KEY_RIGHT_BRACKET] = KeyCode.RIGHT_BRACKET;
        KEY_TABLE[GLFW_KEY_GRAVE_ACCENT] = KeyCode.GRAVE_ACCENT;
        // 导航与控制
        KEY_TABLE[GLFW_KEY_ESCAPE] = KeyCode.ESCAPE;
        KEY_TABLE[GLFW_KEY_ENTER] = KeyCode.ENTER;
        KEY_TABLE[GLFW_KEY_TAB] = KeyCode.TAB;
        KEY_TABLE[GLFW_KEY_BACKSPACE] = KeyCode.BACKSPACE;
        KEY_TABLE[GLFW_KEY_INSERT] = KeyCode.INSERT;
        KEY_TABLE[GLFW_KEY_DELETE] = KeyCode.DELETE;
        KEY_TABLE[GLFW_KEY_RIGHT] = KeyCode.RIGHT;
        KEY_TABLE[GLFW_KEY_LEFT] = KeyCode.LEFT;
        KEY_TABLE[GLFW_KEY_DOWN] = KeyCode.DOWN;
        KEY_TABLE[GLFW_KEY_UP] = KeyCode.UP;
        KEY_TABLE[GLFW_KEY_PAGE_UP] = KeyCode.PAGE_UP;
        KEY_TABLE[GLFW_KEY_PAGE_DOWN] = KeyCode.PAGE_DOWN;
        KEY_TABLE[GLFW_KEY_HOME] = KeyCode.HOME;
        KEY_TABLE[GLFW_KEY_END] = KeyCode.END;
        // 锁定与系统
        KEY_TABLE[GLFW_KEY_CAPS_LOCK] = KeyCode.CAPS_LOCK;
        KEY_TABLE[GLFW_KEY_SCROLL_LOCK] = KeyCode.SCROLL_LOCK;
        KEY_TABLE[GLFW_KEY_NUM_LOCK] = KeyCode.NUM_LOCK;
        KEY_TABLE[GLFW_KEY_PRINT_SCREEN] = KeyCode.PRINT_SCREEN;
        KEY_TABLE[GLFW_KEY_PAUSE] = KeyCode.PAUSE;
        // 功能键
        KEY_TABLE[GLFW_KEY_F1] = KeyCode.F1;
        KEY_TABLE[GLFW_KEY_F2] = KeyCode.F2;
        KEY_TABLE[GLFW_KEY_F3] = KeyCode.F3;
        KEY_TABLE[GLFW_KEY_F4] = KeyCode.F4;
        KEY_TABLE[GLFW_KEY_F5] = KeyCode.F5;
        KEY_TABLE[GLFW_KEY_F6] = KeyCode.F6;
        KEY_TABLE[GLFW_KEY_F7] = KeyCode.F7;
        KEY_TABLE[GLFW_KEY_F8] = KeyCode.F8;
        KEY_TABLE[GLFW_KEY_F9] = KeyCode.F9;
        KEY_TABLE[GLFW_KEY_F10] = KeyCode.F10;
        KEY_TABLE[GLFW_KEY_F11] = KeyCode.F11;
        KEY_TABLE[GLFW_KEY_F12] = KeyCode.F12;
        // 小键盘
        KEY_TABLE[GLFW_KEY_KP_0] = KeyCode.KP_0;
        KEY_TABLE[GLFW_KEY_KP_1] = KeyCode.KP_1;
        KEY_TABLE[GLFW_KEY_KP_2] = KeyCode.KP_2;
        KEY_TABLE[GLFW_KEY_KP_3] = KeyCode.KP_3;
        KEY_TABLE[GLFW_KEY_KP_4] = KeyCode.KP_4;
        KEY_TABLE[GLFW_KEY_KP_5] = KeyCode.KP_5;
        KEY_TABLE[GLFW_KEY_KP_6] = KeyCode.KP_6;
        KEY_TABLE[GLFW_KEY_KP_7] = KeyCode.KP_7;
        KEY_TABLE[GLFW_KEY_KP_8] = KeyCode.KP_8;
        KEY_TABLE[GLFW_KEY_KP_9] = KeyCode.KP_9;
        KEY_TABLE[GLFW_KEY_KP_DECIMAL] = KeyCode.KP_DECIMAL;
        KEY_TABLE[GLFW_KEY_KP_DIVIDE] = KeyCode.KP_DIVIDE;
        KEY_TABLE[GLFW_KEY_KP_MULTIPLY] = KeyCode.KP_MULTIPLY;
        KEY_TABLE[GLFW_KEY_KP_SUBTRACT] = KeyCode.KP_SUBTRACT;
        KEY_TABLE[GLFW_KEY_KP_ADD] = KeyCode.KP_ADD;
        KEY_TABLE[GLFW_KEY_KP_ENTER] = KeyCode.KP_ENTER;
        KEY_TABLE[GLFW_KEY_KP_EQUAL] = KeyCode.KP_EQUAL;
        // 修饰键
        KEY_TABLE[GLFW_KEY_LEFT_SHIFT] = KeyCode.LEFT_SHIFT;
        KEY_TABLE[GLFW_KEY_LEFT_CONTROL] = KeyCode.LEFT_CONTROL;
        KEY_TABLE[GLFW_KEY_LEFT_ALT] = KeyCode.LEFT_ALT;
        KEY_TABLE[GLFW_KEY_LEFT_SUPER] = KeyCode.LEFT_SUPER;
        KEY_TABLE[GLFW_KEY_RIGHT_SHIFT] = KeyCode.RIGHT_SHIFT;
        KEY_TABLE[GLFW_KEY_RIGHT_CONTROL] = KeyCode.RIGHT_CONTROL;
        KEY_TABLE[GLFW_KEY_RIGHT_ALT] = KeyCode.RIGHT_ALT;
        KEY_TABLE[GLFW_KEY_RIGHT_SUPER] = KeyCode.RIGHT_SUPER;
        KEY_TABLE[GLFW_KEY_MENU] = KeyCode.MENU;
    }

    private GLFWKeyMapping() {
    }

    /** GLFW 键码 → 引擎键枚举（越界/未收录归 UNKNOWN） */
    static KeyCode keyCode(int glfwCode) {
        if (glfwCode >= 0 && glfwCode <= GLFW_KEY_LAST) {
            KeyCode key = KEY_TABLE[glfwCode];
            if (key != null) {
                return key;
            }
        }
        return KeyCode.UNKNOWN;
    }

    /** GLFW 鼠标按钮值（0-7）→ 引擎枚举（越界归 UNKNOWN） */
    static MouseButton mouseButton(int glfwButton) {
        switch (glfwButton) {
            case GLFW_MOUSE_BUTTON_LEFT: return MouseButton.LEFT;
            case GLFW_MOUSE_BUTTON_RIGHT: return MouseButton.RIGHT;
            case GLFW_MOUSE_BUTTON_MIDDLE: return MouseButton.MIDDLE;
            case GLFW_MOUSE_BUTTON_4: return MouseButton.BUTTON_4;
            case GLFW_MOUSE_BUTTON_5: return MouseButton.BUTTON_5;
            case GLFW_MOUSE_BUTTON_6: return MouseButton.BUTTON_6;
            case GLFW_MOUSE_BUTTON_7: return MouseButton.BUTTON_7;
            case GLFW_MOUSE_BUTTON_8: return MouseButton.BUTTON_8;
            default: return MouseButton.UNKNOWN;
        }
    }

    /** GLFW 动作值 → 引擎动作枚举（值域固定 0/1/2，越界按 RELEASE 兜底） */
    static InputAction inputAction(int glfwAction) {
        switch (glfwAction) {
            case GLFW_PRESS: return InputAction.PRESS;
            case GLFW_REPEAT: return InputAction.REPEAT;
            default: return InputAction.RELEASE;
        }
    }
}
