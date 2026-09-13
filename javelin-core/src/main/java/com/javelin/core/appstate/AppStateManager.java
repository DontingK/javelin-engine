package com.javelin.core.appstate;

import com.javelin.core.JavelinEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 应用状态管理器。
 *
 * <p>管理所有 {@link AppState} 的生命周期，负责按顺序调度更新。</p>
 *
 * <p>支持在运行时动态附加/分离状态，操作通过线程安全队列延迟到帧边界执行，
 * 避免在更新过程中修改状态列表。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class AppStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppStateManager.class);

    /**
     * 引擎实例。
     */
    private final JavelinEngine engine;

    /**
     * 已附加的状态列表。
     */
    private final List<AppState> states = new ArrayList<>();

    /**
     * 待附加状态队列。
     */
    private final ConcurrentLinkedQueue<AppState> attachQueue = new ConcurrentLinkedQueue<>();

    /**
     * 待分离状态队列。
     */
    private final ConcurrentLinkedQueue<AppState> detachQueue = new ConcurrentLinkedQueue<>();

    /**
     * 构造状态管理器。
     *
     * @param engine 引擎实例
     */
    public AppStateManager(JavelinEngine engine) {
        this.engine = engine;
    }

    /**
     * 附加状态。
     *
     * <p>状态不会立即附加，而是加入队列，在下一帧开始时处理。</p>
     *
     * @param state 要附加的状态
     */
    public void attach(AppState state) {
        if (state == null) {
            return;
        }
        attachQueue.add(state);
    }

    /**
     * 分离状态。
     *
     * <p>状态不会立即分离，而是加入队列，在下一帧开始时处理。</p>
     *
     * @param state 要分离的状态
     */
    public void detach(AppState state) {
        if (state == null) {
            return;
        }
        detachQueue.add(state);
    }

    /**
     * 按类型查找状态。
     *
     * <p>返回第一个匹配指定类型的状态实例。</p>
     *
     * @param <T>        状态类型
     * @param stateClass 状态类
     * @return 匹配的状态实例，未找到时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T extends AppState> T getState(Class<T> stateClass) {
        for (AppState state : states) {
            if (stateClass.isAssignableFrom(state.getClass())) {
                return (T) state;
            }
        }
        return null;
    }

    /**
     * 检查管理器中是否包含指定状态。
     *
     * @param state 要检查的状态
     * @return true 如果状态已附加
     */
    public boolean hasState(AppState state) {
        return states.contains(state);
    }

    /**
     * 清理所有状态。
     *
     * <p>分离并清理所有已附加的状态，通常在引擎关闭时调用。</p>
     */
    public void cleanup() {
        for (AppState state : new ArrayList<>(states)) {
            detach(state);
        }
        processDetachQueue();
    }

    /**
     * 处理待附加队列。
     */
    void processAttachQueue() {
        AppState state;
        while ((state = attachQueue.poll()) != null) {
            if (!states.contains(state)) {
                states.add(state);
                state.onAttach(this, engine);
                LOGGER.info("AppState 已附加: {}", state.getClass().getName());
            }
        }
    }

    /**
     * 处理待分离队列。
     */
    void processDetachQueue() {
        AppState state;
        while ((state = detachQueue.poll()) != null) {
            if (states.remove(state)) {
                state.onDisable();
                state.onDetach(this);
                LOGGER.debug("AppState 已分离: {}", state.getClass().getName());
            }
        }
    }

    // ========== 更新调度（包内可见，由 AppStateSubsystem 调用） ==========

    void earlyUpdate(float deltaTime) {
        processAttachQueue();
        processDetachQueue();

        for (AppState state : states) {
            if (state.isEnabled()) {
                state.earlyUpdate(deltaTime);
            }
        }
    }

    void update(float deltaTime) {
        for (AppState state : states) {
            if (state.isEnabled()) {
                state.update(deltaTime);
            }
        }
    }

    void lateUpdate(float deltaTime) {
        for (AppState state : states) {
            if (state.isEnabled()) {
                state.lateUpdate(deltaTime);
            }
        }
    }
}
