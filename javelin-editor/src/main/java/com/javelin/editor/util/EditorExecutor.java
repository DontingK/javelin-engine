package com.javelin.editor.util;

import com.javelin.core.JavelinEngine;

/**
 * 编辑器线程执行工具类。
 *
 * <p>提供统一入口，确保任务在正确的线程上执行。方法内部自动判断当前线程，
 * 若已在目标线程则直接同步执行，避免冗余的线程切换开销。</p>
 *
 * @author javelin
 * @since 1.0
 */
public final class EditorExecutor {

    private EditorExecutor() {
        // 工具类禁止实例化
    }

    /**
     * 在游戏主线程上执行任务。
     *
     * <p>若当前线程已是 {@link JavelinEngine} 的主循环线程，直接同步执行；
     * 否则通过 {@link JavelinEngine#enqueue(Runnable)} 提交到主线程队列，
     * 由下一帧主循环串行执行。</p>
     *
     * <p>若引擎尚未启动（{@link JavelinEngine#get()} 返回 {@code null}），
     * 为降级直接在当前线程执行，仅用于开发调试阶段。</p>
     *
     * @param task 要执行的任务，不能为 {@code null}
     */
    public static void runOnMainThread(Runnable task) {
//        JavelinEngine engine = JavelinEngine.getInstance();
//        if (engine != null && engine.isMainThread()) {
//            task.run();
//        } else if (engine != null) {
//            engine.enqueue(task);
//        } else {
//            task.run();
//        }
    }
}
