package cn.donting.javelin.core.input;

/**
 * 输入事件出口 —— {@code Window} 实现向引擎翻译键鼠事件的挂载目标。
 *
 * <p>事件源方向：GLFWWindow 等 Window 实现在自己的原生回调里完成
 * "平台键码 → {@link KeyCode}/{@link MouseButton} 翻译"后调用本接口；
 * 接收端默认为 {@link InputSubsystem}（状态写入与帧边沿维护）。
 * core 不经手任何平台类型。</p>
 *
 * <p>编辑器 Swing 窗口将来实现 {@code Window.attachInputSink} 时，
 * 把 AWT 事件翻译进同一套枚举即可接入，与 GLFW 无耦合。</p>
 *
 * <p>所有回调在主线程 pollEvents 时段触发（单线程模型，实现方无需加锁）。</p>
 */
public interface InputSink {

    /**
     * 键盘事件。
     *
     * @param key    引擎键枚举（平台键码由 Window 实现侧映射，未知键位为 UNKNOWN）
     * @param action 动作（PRESS/RELEASE/REPEAT）
     * @param mods   修饰键位掩码（引擎契约：SHIFT=0x1 / CONTROL=0x2 / ALT=0x4 / SUPER=0x8，
     *               实现侧负责自平台修饰掩码翻译）
     */
    void onKey(KeyCode key, InputAction action, int mods);

    /**
     * 鼠标按钮事件。
     */
    void onMouseButton(MouseButton button, InputAction action, int mods);

    /**
     * 光标移动事件（窗口逻辑坐标，左上原点）。
     * <p>高频事件（500Hz+ 鼠标一帧多次），实现方自行累计增量。</p>
     */
    void onCursorMove(double x, double y);

    /**
     * 滚轮事件（本帧增量，引擎约定 y 向上为正）。
     */
    void onScroll(double dx, double dy);

    /**
     * 窗口焦点变化（失去焦点时实现方应清空持续按下态，防"卡键"）。
     */
    default void onFocusChanged(boolean focused) {
    }
}
