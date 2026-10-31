package cn.donting.javelin.editor.event;

import java.util.function.Consumer;

/**
 * 事件订阅句柄。
 *
 * <p>{@link EditorEventBus#subscribe(Class, Consumer)} 的返回值；不再需要接收事件时调用
 * {@link #dispose()} 退订（面板卸载、AppState onDetach 等时机）。dispose 幂等，
 * 重复调用无副作用，任意线程可调。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class Subscription {

    /** 订阅的事件类型（注册键） */
    final Class<?> eventType;
    /** 监听器（subscribe 侧已装收为 {@code Consumer<EditorEvent>}） */
    final Consumer<EditorEvent> listener;

    private boolean disposed;

    Subscription(Class<?> eventType, Consumer<EditorEvent> listener) {
        this.eventType = eventType;
        this.listener = listener;
    }

    /**
     * 退订：从总线注册表移除本订阅。
     *
     * <p>幂等：重复调用无副作用。任意线程可调。</p>
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        EditorEventBus.removeSubscription(this);
    }
}
