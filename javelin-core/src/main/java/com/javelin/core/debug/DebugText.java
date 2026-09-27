package com.javelin.core.debug;

/**
 * 调试文本静态门面（doc/Engine_DebugText_Design.md）——DebugDrawText 子系统的
 * <b>唯一使用方式</b>，形态对齐 {@code Input} 先例（子系统 init 绑定 / shutdown 解绑）。
 *
 * <p>语义（bgfx dbgText 同款）：字高 16px、行距 18px（行间 2px 空隙）、col 单位 = 半角 8px（全角占 2 列）；
 * 内容<b>持续到手动 {@link #clear()}</b>——写一次一直在，动态内容帧覆写即可。
 * <b>不做 \n\r 转义换行</b>（2026-09-27 用户拍板）：换行只走两个 printf 变体。</p>
 *
 * <pre>
 * DebugText.clear();                                            // 自动行游标归 0
 * DebugText.printf(0, "FPS: " + Time.getFps(), DebugTextColor.LIGHT_GREEN);   // 固定行追加
 * DebugText.printf("自动叠行：写完换下一行");                     // 无参 = 自动叠行
 * </pre>
 *
 * <p>未初始化（子系统未注册/引擎未启动/已关闭）调用一律抛
 * {@link IllegalStateException}——静默丢弃会掩盖接线错误。v0.1 仅允许主线程调用
 * （编辑器侧 EDT 入口后置，见设计档 §6）。</p>
 */
public final class DebugText {

    /** 绑定的调试文本子系统（init 绑定 / shutdown 解绑） */
    private static DebugDrawTextSubsystem subsystem;

    private DebugText() {
    }

    /** 子系统绑定（DebugDrawTextSubsystem.init 调用） */
    static void bind(DebugDrawTextSubsystem subsystem) {
        DebugText.subsystem = subsystem;
    }

    /** 子系统解绑（DebugDrawTextSubsystem.shutdown 调用） */
    static void unbind() {
        DebugText.subsystem = null;
    }

    private static DebugDrawTextSubsystem debugText() {
        DebugDrawTextSubsystem s = subsystem;
        if (s == null) {
            throw new IllegalStateException(
                    "DebugText not initialized. Ensure DebugDrawTextSubsystem is registered and the engine has started.");
        }
        return s;
    }

    /** 清空全部调试文本；固定行游标与自动行指针一并归 0 */
    public static void clear() {
        debugText().clear();
    }

    public static void clear(int row) {
        debugText().clear(row);
    }

    /**
     * 在固定行打印，自动追加到当前行的文字后。
     *
     * @param row  第几行
     * @param txt  文本
     * @param attr 低 4 位前景 + 高 4 位背景（{@link DebugTextColor}）
     */
    public static void printf(int row, String txt, int attr) {
        debugText().printfRow(row, txt, attr);
    }

    /**
     * 自动换到下一行（写当前自动行，写完该行指针 +1；前景默认白色）。
     *
     * @param txt 文本
     */
    public static void printf(String txt) {
        debugText().printfLine(txt);
    }

    /**
     * 自动换到下一行，带前景色（彩色日志：红警告/黄提示等）。
     *
     * @param txt  文本
     * @param attr 低 4 位前景 + 高 4 位背景（{@link DebugTextColor}）
     */
    public static void printf(String txt, int attr) {
        debugText().printfLine(txt, attr);
    }
}
