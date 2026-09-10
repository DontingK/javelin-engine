package com.javelin.core;

/**
 * Time —— Unity 风格的时间静态门面。
 *
 * <p>提供全局时间查询，所有游戏逻辑的时间相关计算都应通过此类，
 * 以保证时间缩放、暂停等功能的统一控制。</p>
 *
 * <p>使用示例：
 * <pre>
 *   // 每帧旋转（考虑时间缩放）
 *   transform.rotate(0, 100 * Time.deltaTime, 0);
 *
 *   // 物理无关的动画（不受时间缩放影响）
 *   loadingSpinner.rotate(0, 360 * Time.unscaledDeltaTime, 0);
 *
 *   // 2 秒后触发
 *   if (Time.time - startTime > 2.0f) { ... }
 *
 *   // 暂停游戏
 *   Time.timeScale = 0;
 * </pre>
 *
 * <p><strong>注意</strong>：必须在 {@link JavelinEngine} 初始化后才能使用，
 * 否则所有查询返回 0。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class Time {

    private Time() {
        // 静态门面，禁止实例化
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  内部状态（由 JavelinEngine 每帧更新）
    // ═══════════════════════════════════════════════════════════════════════

    /** 时间缩放系数（0 = 暂停，1 = 正常，2 = 二倍速） */
    private static float timeScale = 1.0f;

    /** 游戏开始后的累计时间（秒，受 timeScale 影响） */
    private static float time;

    /** 游戏开始后的累计时间（秒，不受 timeScale 影响） */
    private static float unscaledTime;

    /** 上一帧的时间间隔（秒，已乘 timeScale） */
    private static float deltaTime;

    /** 上一帧的真实时间间隔（秒，不受 timeScale 影响） */
    private static float unscaledDeltaTime;

    /** 已渲染的帧数 */
    private static int frameCount;

    /** 平滑后的帧率（FPS） */
    private static float fps;

    /** FPS 平滑系数（越大越平滑，0.1 表示每帧更新 10%） */
    private static final float FPS_SMOOTHING = 0.1f;

    /** 引擎启动时间戳（纳秒） */
    private static long startupTimeNanos;

    /** 是否已初始化 */
    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════════════════════
    //  引擎内部更新（由 JavelinEngine 每帧调用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 初始化时间系统。
     * <p>由 JavelinEngine 启动时调用。</p>
     */
    static void init() {
        startupTimeNanos = System.nanoTime();
        time = 0;
        unscaledTime = 0;
        deltaTime = 0;
        unscaledDeltaTime = 0;
        frameCount = 0;
        fps = 0;
        timeScale = 1.0f;
        initialized = true;
    }

    /**
     * 每帧更新时间状态。
     * <p>由 JavelinEngine 主循环调用。</p>
     *
     * @param rawDeltaTime 原始帧间隔（秒，未缩放）
     */
    static void update(float rawDeltaTime) {
        if (!initialized) {
            return;
        }

        unscaledDeltaTime = rawDeltaTime;
        deltaTime = rawDeltaTime * timeScale;

        unscaledTime += rawDeltaTime;
        time += deltaTime;

        frameCount++;

        // 平滑 FPS：指数移动平均
        float instantFps = rawDeltaTime > 0 ? 1.0f / rawDeltaTime : 0;
        fps = fps * (1 - FPS_SMOOTHING) + instantFps * FPS_SMOOTHING;
    }

    /**
     * 重置时间系统。
     * <p>由 JavelinEngine 关闭时调用。</p>
     */
    static void reset() {
        initialized = false;
        time = 0;
        unscaledTime = 0;
        deltaTime = 0;
        unscaledDeltaTime = 0;
        frameCount = 0;
        fps = 0;
        timeScale = 1.0f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  公共 API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 时间缩放系数。
     * <p>
     * 控制游戏时间的流逝速度：
     * <ul>
     *   <li>{@code 0} — 完全暂停</li>
     *   <li>{@code 1} — 正常速度</li>
     *   <li>{@code 2} — 二倍速</li>
     *   <li>{@code 0.5} — 半速（慢动作）</li>
     * </ul>
     * 影响 {@link #deltaTime} 和 {@link #time}，不影响 {@link #unscaledDeltaTime}。
     * </p>
     */
    public static float getTimeScale() {
        return timeScale;
    }

    public static void setTimeScale(float scale) {
        timeScale = Math.max(0, scale);
    }

    /**
     * 游戏开始后的累计时间（秒）。
     * <p>受 {@link #timeScale} 影响，暂停时停止增长。</p>
     */
    public static float getTime() {
        return time;
    }

    /**
     * 游戏开始后的累计真实时间（秒）。
     * <p>不受 {@link #timeScale} 影响，即使暂停也会继续增长。</p>
     */
    public static float getUnscaledTime() {
        return unscaledTime;
    }

    /**
     * 上一帧的时间间隔（秒）。
     * <p>已乘 {@link #timeScale}，用于所有游戏逻辑的时间相关计算。</p>
     */
    public static float getDeltaTime() {
        return deltaTime;
    }

    /**
     * 上一帧的真实时间间隔（秒）。
     * <p>不受 {@link #timeScale} 影响，用于 UI 动画、加载旋转等需要
     * 即使暂停也继续播放的效果。</p>
     */
    public static float getUnscaledDeltaTime() {
        return unscaledDeltaTime;
    }

    /**
     * 已渲染的帧数。
     * <p>从游戏开始累计，每帧 +1，不受时间缩放影响。</p>
     */
    public static int getFrameCount() {
        return frameCount;
    }

    /**
     * 当前平滑后的帧率（FPS）。
     * <p>使用指数移动平均（EMA），避免单帧跳动。</p>
     */
    public static float getFps() {
        return fps;
    }

    /**
     * 游戏是否处于暂停状态。
     * <p>{@code timeScale == 0} 时返回 true。</p>
     */
    public static boolean isPaused() {
        return timeScale <= 0;
    }

    /**
     * 获取自引擎启动以来的真实时间（秒）。
     * <p>基于系统时钟，不受 timeScale 影响，不可暂停。</p>
     */
    public static float getRealtimeSinceStartup() {
        if (!initialized) return 0;
        return (System.nanoTime() - startupTimeNanos) / 1_000_000_000.0f;
    }
}
