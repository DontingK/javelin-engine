package com.javelin.core.input;

import com.javelin.core.subsystem.AbstractSubsystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 输入子系统 —— 键鼠状态采集与帧边沿维护（roadmap 二期①，Engine_Input_Design）。
 *
 * <p>本类身兼两职：</p>
 * <ul>
 *   <li>{@code InputSink}：mainLoop 的 pollEvents 时段被 Window 回调驱动
 *       （平台键码已由 Window 侧翻译成引擎枚举），只做状态写入；</li>
 *   <li>{@code Subsystem}：earlyUpdate 做帧边界翻转，发布本帧输入快照。
 *       —— 时序成立的前提：pollEvents 先于 earlyUpdate（mainLoop 接线），
 *       因此回调写入的就是本帧状态，之后 scene.update / AppState / update
 *       读到的是一致快照；earlyUpdate 之后零拷贝、零队列成本。</li>
 * </ul>
 *
 * <p><b>边沿双缓冲</b>：justPressed/justReleased 用两块缓冲交替——回调写
 * write 侧；earlyUpdate 交换 read/write 并清空新 write 侧（承接下帧事件）。
 * 相比"事件队列 + drain"省掉队列与逐条分发，且天然保证边沿"恰一帧有效"。</p>
 *
 * <p>查询与动作绑定均经 {@link Input} 静态门面（项目 Time 静态先例，
 * 用户拍板 2026-09-26；同日三次收束：ActionMap 概念移除，bind/动作名查询
 * 合流门面）；本类只暴露包内口给门面与绑定评估。</p>
 */
public class InputSubsystem extends AbstractSubsystem implements InputSink {

    private static final Logger log = LoggerFactory.getLogger(InputSubsystem.class);

    private static final int KEY_COUNT = KeyCode.values().length;
    private static final int BUTTON_COUNT = MouseButton.values().length;

    /** 持续按下态（回调即时维护：PRESS 置位 / RELEASE 清位） */
    private final boolean[] keyDown = new boolean[KEY_COUNT];
    private final boolean[] mouseDown = new boolean[BUTTON_COUNT];

    /** 边沿双缓冲：[0]/[1] 两块，writeBuf 回调写入侧、readBuf 本帧发布侧 */
    private final boolean[][] keyJustPressed = {new boolean[KEY_COUNT], new boolean[KEY_COUNT]};
    private final boolean[][] keyJustReleased = {new boolean[KEY_COUNT], new boolean[KEY_COUNT]};
    private final boolean[][] mouseJustPressed = {new boolean[BUTTON_COUNT], new boolean[BUTTON_COUNT]};
    private final boolean[][] mouseJustReleased = {new boolean[BUTTON_COUNT], new boolean[BUTTON_COUNT]};
    private int writeBuf = 0;
    private int readBuf = 1;

    /** 光标最后事件位置（窗口逻辑坐标）——首次事件前无值，用 seen 标记 */
    private double cursorX;
    private double cursorY;
    private boolean cursorSeen;

    /** 本 poll 窗口累计增量与滚轮（回调累计，earlyUpdate 发布后清零） */
    private double pendingMouseDx;
    private double pendingMouseDy;
    private double pendingScrollDx;
    private double pendingScrollDy;

    /** 本帧发布值（earlyUpdate 发布，帧内恒定） */
    private float frameMouseDx;
    private float frameMouseDy;
    private float frameScrollDx;
    private float frameScrollDy;

    /** UI 消费遮罩：true 时全部查询返回复位值（v0.1 口子，编辑器焦点接管用） */
    private boolean consumedByUI;

    /**
     * 动作绑定全局单表（用户拍板 2026-09-26 三次收束：ActionMap 概念移除，
     * bind/查询合流 {@link Input}）。配置与解析都在本子系统，配置动词
     * （bind/bindAxis/unbind）与动作查询（isOn/axis）经门面转调。
     * 同名重绑即覆盖——rebinding 口子。
     */
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, Axis> axes = new LinkedHashMap<>();

    @Override
    public void init(com.javelin.core.JavelinEngine engine) {
        super.init(engine);
        engine.window().attachInputSink(this);
        Input.bind(this);
        log.info("InputSubsystem initialized ({} keycodes, {} mouse buttons)", KEY_COUNT, BUTTON_COUNT);
    }

    @Override
    public void earlyUpdate(float deltaTime) {
        // 帧边界翻转：回调在本帧 pollEvents 已写入 write 侧，发布为 read 侧；
        // 上一帧 read 侧转新 write 侧并清空，承接下帧 pollEvents 事件。
        int fresh = writeBuf;
        writeBuf = readBuf;
        readBuf = fresh;
        Arrays.fill(keyJustPressed[writeBuf], false);
        Arrays.fill(keyJustReleased[writeBuf], false);
        Arrays.fill(mouseJustPressed[writeBuf], false);
        Arrays.fill(mouseJustReleased[writeBuf], false);

        // 增量/滚轮同窗口发布：累计窗口 = [上帧 earlyUpdate, 本帧 pollEvents 末]，
        // 恰为本帧事件总量；发布到 frame 值后清零承接下帧。
        frameMouseDx = (float) pendingMouseDx;
        frameMouseDy = (float) pendingMouseDy;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        frameScrollDx = (float) pendingScrollDx;
        frameScrollDy = (float) pendingScrollDy;
        pendingScrollDx = 0;
        pendingScrollDy = 0;
    }

    @Override
    public void shutdown() {
        // 引擎 shutdown 序：subsystemManager.shutdownAll 在 window.close 之前，
        // 此时窗口仍在，先摘回调再解绑门面
        if (engine != null) {
            engine.window().attachInputSink(null);
        }
        Input.unbind();
    }

    // ---------------- InputSink（pollEvents 时段，主线程） ----------------

    @Override
    public void onKey(KeyCode key, InputAction action, int mods) {
        int index = key.ordinal();
        // 归一化：PRESS 且已按住 = 键重复（GLFW 显式 REPEAT；AWT 无 REPEAT 事件、
        // 按住会重发 KEY_PRESSED）——跳过边沿只维持状态，两端边沿语义一致"恰一次"。
        if (action == InputAction.PRESS && keyDown[index]) {
            return;
        }
        if (action == InputAction.REPEAT) {
            return;
        }
        keyDown[index] = action == InputAction.PRESS;
        if (action == InputAction.PRESS) {
            keyJustPressed[writeBuf][index] = true;
        } else {
            keyJustReleased[writeBuf][index] = true;
        }
    }

    @Override
    public void onMouseButton(MouseButton button, InputAction action, int mods) {
        int index = button.ordinal();
        if (action == InputAction.PRESS && mouseDown[index]) {
            return;   // 同 onKey 归一化：重复按下不产生第二个边沿
        }
        if (action == InputAction.REPEAT) {
            return;
        }
        mouseDown[index] = action == InputAction.PRESS;
        if (action == InputAction.PRESS) {
            mouseJustPressed[writeBuf][index] = true;
        } else {
            mouseJustReleased[writeBuf][index] = true;
        }
    }

    @Override
    public void onCursorMove(double x, double y) {
        if (!cursorSeen) {
            cursorSeen = true;
            cursorX = x;
            cursorY = y;
            return;
        }
        pendingMouseDx += x - cursorX;
        pendingMouseDy += y - cursorY;
        cursorX = x;
        cursorY = y;
    }

    @Override
    public void onScroll(double dx, double dy) {
        pendingScrollDx += dx;
        pendingScrollDy += dy;
    }

    @Override
    public void onFocusChanged(boolean focused) {
        if (focused) {
            return;
        }
        // 失焦清持续按下态：GLFW 不保证补发 RELEASE，不清则按住的键永久"卡住"。
        // write 侧边沿一并丢弃（失焦瞬间的按下不该出现在随后帧）；read 侧保留本帧已发布值。
        Arrays.fill(keyDown, false);
        Arrays.fill(mouseDown, false);
        Arrays.fill(keyJustPressed[writeBuf], false);
        Arrays.fill(mouseJustReleased[writeBuf], false);
        Arrays.fill(keyJustReleased[writeBuf], false);
        Arrays.fill(mouseJustPressed[writeBuf], false);
        Arrays.fill(mouseJustReleased[writeBuf], false);
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        pendingScrollDx = 0;
        pendingScrollDy = 0;
        log.debug("Window focus lost, input state cleared");
    }

    // ---------------- 包内查询口（Input 门面转调） ----------------

    boolean isKeyDown(KeyCode key) {
        return !consumedByUI && keyDown[key.ordinal()];
    }

    boolean isKeyJustPressed(KeyCode key) {
        return !consumedByUI && keyJustPressed[readBuf][key.ordinal()];
    }

    boolean isKeyJustReleased(KeyCode key) {
        return !consumedByUI && keyJustReleased[readBuf][key.ordinal()];
    }

    boolean isMouseDown(MouseButton button) {
        return !consumedByUI && mouseDown[button.ordinal()];
    }

    boolean isMouseJustPressed(MouseButton button) {
        return !consumedByUI && mouseJustPressed[readBuf][button.ordinal()];
    }

    boolean isMouseJustReleased(MouseButton button) {
        return !consumedByUI && mouseJustReleased[readBuf][button.ordinal()];
    }

    float mouseX() {
        return consumedByUI ? 0f : (float) cursorX;
    }

    float mouseY() {
        return consumedByUI ? 0f : (float) cursorY;
    }

    float mouseDeltaX() {
        return consumedByUI ? 0f : frameMouseDx;
    }

    float mouseDeltaY() {
        return consumedByUI ? 0f : frameMouseDy;
    }

    float scrollDeltaX() {
        return consumedByUI ? 0f : frameScrollDx;
    }

    float scrollDeltaY() {
        return consumedByUI ? 0f : frameScrollDy;
    }

    // ---------------- 动作绑定（全局单表，Input 门面转调；包内口） ----------------

    /**
     * 绑定布尔动作：任一源命中即动作命中（KeyCode 与 MouseButton 混绑）。
     * 同名重绑覆盖（rebinding 口子）。
     */
    void bind(String name, InputCode... codes) {
        validateActionName(name);
        if (codes == null || codes.length == 0) {
            throw new IllegalArgumentException("Action '" + name + "' needs at least one binding source");
        }
        bindings.put(name, new Binding(codes));
        axes.remove(name);
    }

    /** 绑定轴：返回链式配置对象（键组正/负，或滚轮/光标增量连续源）。与 bind 共享动作名空间（后到覆盖）。 */
    Axis bindAxis(String name) {
        validateActionName(name);
        Axis axis = new Axis();
        axes.put(name, axis);
        bindings.remove(name);
        return axis;
    }

    /** 解除动作绑定（未绑定为 no-op） */
    void unbind(String name) {
        bindings.remove(name);
        axes.remove(name);
    }

    /** 解析布尔动作绑定——未绑定视为接线错误，快速失败而非静默 false */
    Binding binding(String name) {
        Binding binding = bindings.get(name);
        if (binding == null) {
            throw unknownAction(name);
        }
        return binding;
    }

    /** 解析轴绑定——未绑定同样快速失败 */
    Axis axis(String name) {
        Axis axis = axes.get(name);
        if (axis == null) {
            throw unknownAction(name);
        }
        return axis;
    }

    private static void validateActionName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Action name must not be null or empty");
        }
    }

    private static IllegalArgumentException unknownAction(String name) {
        return new IllegalArgumentException(
                "Unknown input action '" + name + "': never bound via Input.bind/bindAxis (or already unbound)");
    }

    // ---------------- 状态控制 ----------------

    /**
     * UI 消费遮罩（v0.1 口子）：true 时输入系统视为"UI 接管"——
     * 全部查询返回复位值。开启时顺带清空持续态，避免残留按下。
     * 完整的事件分发/消费模型随编辑器 UI 焦点体系后置。
     */
    public void setConsumedByUI(boolean consumedByUI) {
        this.consumedByUI = consumedByUI;
        if (consumedByUI) {
            Arrays.fill(keyDown, false);
            Arrays.fill(mouseDown, false);
            pendingMouseDx = 0;
            pendingMouseDy = 0;
            pendingScrollDx = 0;
            pendingScrollDy = 0;
        }
    }

    boolean isConsumedByUI() {
        return consumedByUI;
    }
}
