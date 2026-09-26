package com.javelin.core.input;

/**
 * 输入门面 —— 组件/游戏代码的输入配置与查询入口（doc/Engine_Input_Design.md），
 * <b>唯一使用方式</b>（用户拍板 2026-09-26 三次收束：ActionMap 概念移除，
 * bind 与查询合流本门面）。
 *
 * <p>形态对齐项目 {@code Time} 静态先例：{@link InputSubsystem} init 时绑定、
 * shutdown 时解绑。两层查询共用一个门面：</p>
 * <ul>
 *   <li><b>原始层</b>（物理源）：{@code Input.isDown(KeyCode.W)}——光标位置增量
 *       滚轮等无绑定概念的指针输入也在这层；</li>
 *   <li><b>动作层</b>（按名，经全局绑定表解析）：
 *       {@code Input.isDown("Fire")}——键与鼠标键混绑，同名重绑即覆盖（rebinding 口子）。</li>
 * </ul>
 *
 * <pre>
 * Input.bind("Fire", KeyCode.F, MouseButton.LEFT);           // 配置：任意时刻可改
 * Input.bindAxis("MoveX").positive(KeyCode.D, KeyCode.RIGHT)
 *                      .negative(KeyCode.A, KeyCode.LEFT);
 * Input.bindAxis("Zoom").scroll();                           // 连续源：滚轮增量
 *
 * if (Input.isDown("Fire")) ...                              // 动作查询
 * float x = Input.axis("MoveX");                             // 轴值
 * if (Input.isDown(KeyCode.LEFT_CONTROL)) ...                // 原始查询
 * </pre>
 *
 * <p>帧语义（消费方约定）：</p>
 * <ul>
 *   <li>{@code isDown}：持续态（本帧内恒定）——物理键与动作名同名重载，一个动词贯穿两层；</li>
 *   <li>{@code wasPressed/wasReleased}：本帧边沿（恰一帧有效）——
 *       pollEvents 先于 earlyUpdate，同一帧内 scene.update 与 update 读到一致快照；</li>
 *   <li>{@code mouseDelta/scrollDelta}：本帧累计增量（earlyUpdate 发布）。</li>
 * </ul>
 *
 * <p>未初始化（子系统未注册/引擎未启动/已关闭）调用、或查询从未绑定的
 * 动作名——一律抛异常（{@link IllegalStateException} /
 * {@link IllegalArgumentException}），静默返回 false 会掩盖接线错误。</p>
 */
public final class Input {

    /** 绑定的输入子系统（init 绑定 / shutdown 解绑） */
    private static InputSubsystem subsystem;

    private Input() {
    }

    /** 子系统绑定（InputSubsystem.init 调用） */
    static void bind(InputSubsystem subsystem) {
        Input.subsystem = subsystem;
    }

    /** 子系统解绑（InputSubsystem.shutdown 调用） */
    static void unbind() {
        Input.subsystem = null;
    }

    private static InputSubsystem input() {
        InputSubsystem s = subsystem;
        if (s == null) {
            throw new IllegalStateException(
                    "Input not initialized. Ensure InputSubsystem is registered and the engine has started.");
        }
        return s;
    }

    // ---------------- 键盘 ----------------

    /** 键是否持续按下（本帧） */
    public static boolean isDown(KeyCode key) {
        return input().isKeyDown(key);
    }

    /** 键是否在本帧被按下（边沿，恰一帧有效） */
    public static boolean wasPressed(KeyCode key) {
        return input().isKeyJustPressed(key);
    }

    /** 键是否在本帧被释放（边沿，恰一帧有效） */
    public static boolean wasReleased(KeyCode key) {
        return input().isKeyJustReleased(key);
    }

    // ---------------- 鼠标 ----------------

    /** 鼠标按钮是否持续按下（本帧） */
    public static boolean isMouseDown(MouseButton button) {
        return input().isMouseDown(button);
    }

    /** 鼠标按钮是否在本帧被按下（边沿） */
    public static boolean wasMousePressed(MouseButton button) {
        return input().isMouseJustPressed(button);
    }

    /** 鼠标按钮是否在本帧被释放（边沿） */
    public static boolean wasMouseReleased(MouseButton button) {
        return input().isMouseJustReleased(button);
    }

    /** 光标 X（窗口逻辑坐标，左上原点；首次移动事件前为 0） */
    public static float mouseX() {
        return input().mouseX();
    }

    /** 光标 Y（窗口逻辑坐标，左上原点） */
    public static float mouseY() {
        return input().mouseY();
    }

    /** 本帧光标 X 增量（累计；无移动为 0） */
    public static float mouseDeltaX() {
        return input().mouseDeltaX();
    }

    /** 本帧光标 Y 增量（窗口坐标向下为正） */
    public static float mouseDeltaY() {
        return input().mouseDeltaY();
    }

    // ---------------- 滚轮 ----------------

    /** 本帧滚轮横向增量（触控板才有非零值） */
    public static float scrollDeltaX() {
        return input().scrollDeltaX();
    }

    /** 本帧滚轮纵向增量（普通滚轮，向上为正） */
    public static float scrollDeltaY() {
        return input().scrollDeltaY();
    }

    // ---------------- 动作配置（全局单表；同名重绑即覆盖 = rebinding 口子） ----------------

    /**
     * 绑定动作：任一绑定源命中即动作命中（{@link KeyCode} 与 {@link MouseButton}
     * 混绑）。同名重绑覆盖旧绑定。任意时刻可调用（查询当帧生效）。
     *
     * @param name  动作名（全局唯一）
     * @param codes 绑定源（至少一个）
     */
    public static void bind(String name, InputCode... codes) {
        input().bind(name, codes);
    }

    /**
     * 绑定轴：返回链式配置的轴对象（键组正/负，或滚轮/光标增量连续源）。
     * 与 {@link #bind} 共享动作名空间（后到覆盖先到）。
     */
    public static Axis bindAxis(String name) {
        return input().bindAxis(name);
    }

    /** 解除动作/轴绑定（未绑定为 no-op） */
    public static void unbind(String name) {
        input().unbind(name);
    }

    // ---------------- 动作查询（按名，经全局绑定表解析） ----------------

    /**
     * 动作是否持续触发（本帧）——与原始层动词同名重载：
     * {@code isDown(KeyCode)} 问物理键、{@code isDown(String)} 问动作名，
     * "按下中"一个动词贯穿两层（2026-09-27 用户拍板补）。未绑定的动作名抛异常。
     */
    public static boolean isDown(String action) {
        return input().binding(action).on();
    }

    /** 动作是否持续触发（本帧）——{@link #isDown(String)} 的同义词（动词统一期暂并存） */
    public static boolean isOn(String action) {
        return input().binding(action).on();
    }

    /** 动作是否在本帧开始触发（边沿，恰一帧有效） */
    public static boolean wasPressed(String action) {
        return input().binding(action).justPressed();
    }

    /** 动作是否在本帧结束触发（边沿，恰一帧有效） */
    public static boolean wasReleased(String action) {
        return input().binding(action).justReleased();
    }

    /** 轴值（本帧）：键组 [-1,+1]（正组任一按住 - 负组任一按住）或连续源直通——未绑定抛异常 */
    public static float axis(String action) {
        return input().axis(action).evaluate();
    }

    // ---------------- 状态控制 ----------------

    /**
     * UI 消费遮罩（v0.1 口子）：true 时全部输入查询返回复位值。
     * 编辑器 UI 焦点接管游戏输入时打开；完整分发模型后置。
     */
    public static void setConsumedByUI(boolean consumedByUI) {
        input().setConsumedByUI(consumedByUI);
    }
}
