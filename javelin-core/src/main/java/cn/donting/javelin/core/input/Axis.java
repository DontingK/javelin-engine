package cn.donting.javelin.core.input;

/**
 * 轴绑定配置 —— {@link Input#bindAxis(String)} 的链式返回（查询走 {@link Input#axis(String)}）。
 *
 * <p>两类源：<b>键组</b>——正组任一按住 - 负组任一按住，值域 [-1, +1]；
 * <b>连续源</b>——滚轮增量 / 光标增量，本帧值直通（无正负组概念）。
 * 链式配置：{@code Input.bindAxis("MoveX").positive(KeyCode.D, KeyCode.RIGHT)
 * .negative(KeyCode.A, KeyCode.LEFT)} 或 {@code Input.bindAxis("Zoom").scroll()}。</p>
 */
public final class Axis {

    /** 连续源种类（KEYS = 键组轴） */
    private static final byte SOURCE_KEYS = 0;
    private static final byte SOURCE_SCROLL = 1;
    private static final byte SOURCE_MOUSE_DELTA_X = 2;
    private static final byte SOURCE_MOUSE_DELTA_Y = 3;

    private KeyCode[] positive = new KeyCode[0];
    private KeyCode[] negative = new KeyCode[0];
    private byte source = SOURCE_KEYS;

    /** 正向键组（覆盖式） */
    public Axis positive(KeyCode... keys) {
        this.positive = keys;
        return this;
    }

    /** 负向键组（覆盖式） */
    public Axis negative(KeyCode... keys) {
        this.negative = keys;
        return this;
    }

    /** 连续源：本帧滚轮纵向增量（向上为正，直通） */
    public Axis scroll() {
        this.source = SOURCE_SCROLL;
        return this;
    }

    /** 连续源：本帧光标 X 增量（直通，窗口坐标向右为正） */
    public Axis mouseDeltaX() {
        this.source = SOURCE_MOUSE_DELTA_X;
        return this;
    }

    /** 连续源：本帧光标 Y 增量（直通，窗口坐标向下为正） */
    public Axis mouseDeltaY() {
        this.source = SOURCE_MOUSE_DELTA_Y;
        return this;
    }

    /**
     * 轴值（本帧）：连续源直通增量；键组 = 正组任一按住 - 负组任一按住。
     * 混配时（既配键组又选连续源）连续源生效。
     * 包私有——对外查询经 {@link Input#axis(String)}。
     */
    float evaluate() {
        switch (source) {
            case SOURCE_SCROLL:
                return Input.scrollDeltaY();
            case SOURCE_MOUSE_DELTA_X:
                return Input.mouseDeltaX();
            case SOURCE_MOUSE_DELTA_Y:
                return Input.mouseDeltaY();
            default:
                float value = 0f;
                if (anyDown(positive)) {
                    value += 1f;
                }
                if (anyDown(negative)) {
                    value -= 1f;
                }
                return value;
        }
    }

    private static boolean anyDown(KeyCode[] keys) {
        for (KeyCode key : keys) {
            if (Input.isDown(key)) {
                return true;
            }
        }
        return false;
    }
}
