package cn.donting.javelin.core.input;

/**
 * 布尔动作绑定 —— InputSubsystem 全局绑定表的表项（包私有内部类型）。
 *
 * <p>源组任一命中即动作命中；查询按源实际类型分发到对应
 * {@link Input} 通道（键 / 鼠标键）。动作的配置与查询对外都走
 * {@link Input}（bind / isOn），本类型不出包。</p>
 */
final class Binding {

    private final InputCode[] codes;

    Binding(InputCode... codes) {
        this.codes = codes;
    }

    /** 持续态：任一源按住 */
    boolean on() {
        for (InputCode code : codes) {
            if (isDown(code)) {
                return true;
            }
        }
        return false;
    }

    /** 按下边沿：任一源本帧按下 */
    boolean justPressed() {
        for (InputCode code : codes) {
            if (wasPressed(code)) {
                return true;
            }
        }
        return false;
    }

    /** 释放边沿：任一源本帧释放 */
    boolean justReleased() {
        for (InputCode code : codes) {
            if (wasReleased(code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDown(InputCode code) {
        if (code instanceof KeyCode) {
            return Input.isDown((KeyCode) code);
        }
        return Input.isMouseDown((MouseButton) code);
    }

    private static boolean wasPressed(InputCode code) {
        if (code instanceof KeyCode) {
            return Input.wasPressed((KeyCode) code);
        }
        return Input.wasMousePressed((MouseButton) code);
    }

    private static boolean wasReleased(InputCode code) {
        if (code instanceof KeyCode) {
            return Input.wasReleased((KeyCode) code);
        }
        return Input.wasMouseReleased((MouseButton) code);
    }
}
