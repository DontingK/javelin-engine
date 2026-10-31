package cn.donting.javelin.core.appstate;

import cn.donting.javelin.core.JavelinEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 应用状态管理器。
 *
 * <p>管理所有 {@link AppState}（控制程序层）的生命周期与帧调度。
 * 支持运行时动态附加/分离（与底层子系统「冻结不可移除」相对）：
 * 操作经线程安全队列延迟到帧边界处理，避免更新过程中修改状态列表——
 * 编辑器 UI/JS 桥线程可直接发起 attach/detach。</p>
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
     * <p>状态不会立即附加，而是加入队列，在下一帧调度时处理——
     * attach 后<b>同帧</b> {@link #getState} 返回 null，需等下一帧。</p>
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
     * <p>状态不会立即分离，而是加入队列，在下一帧调度时处理。</p>
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
     * <p>返回第一个匹配指定类型的状态实例。<b>注意延迟语义</b>：
     * {@link #attach} 后、挂卸队列处理前（同帧）查不到该状态。</p>
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
     * <p>分离并清理所有已附加的状态，通常在引擎关闭时调用。
     * 挂卸队列中尚未生效的 pending attach 被丢弃并告警
     * （它们从未 onAttach，故不触发任何回调）。</p>
     */
    public void cleanup() {
        AppState pending;
        while ((pending = attachQueue.poll()) != null) {
            LOGGER.warn("Cleanup discarded pending AppState (never attached): {}",
                    pending.getClass().getName());
        }
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
                LOGGER.info("AppState attached: {}", state.getClass().getName());
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
                LOGGER.debug("AppState detached: {}", state.getClass().getName());
            }
        }
    }

    // ========== 帧调度（包内可见，由 AppStateSubsystem 在 earlyUpdate 相位调用） ==========

    /**
     * 每帧调度：处理挂卸队列（帧边界）后，逐个调用启用状态的 update。
     */
    void update(float deltaTime) {
        processAttachQueue();
        processDetachQueue();

        for (AppState state : states) {
            if (state.isEnabled()) {
                state.update(deltaTime);
            }
        }
    }
}
