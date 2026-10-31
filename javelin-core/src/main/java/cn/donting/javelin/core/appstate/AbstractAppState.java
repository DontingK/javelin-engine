package cn.donting.javelin.core.appstate;

import cn.donting.javelin.core.JavelinEngine;

/**
 * 应用状态抽象基类。
 *
 * <p>提供 {@link AppState} 的默认实现，子类只需重写需要的方法；
 * 编辑器侧的编辑状态继承 {@code AbsEditorAppState}（javelin-editor）二次收窄引擎类型。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class AbstractAppState implements AppState {

    /**
     * 是否启用。
     */
    protected boolean enabled = true;

    /**
     * 所属状态管理器。
     */
    protected AppStateManager stateManager;

    /**
     * 引擎实例。
     */
    protected JavelinEngine engine;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        this.stateManager = stateManager;
        this.engine = engine;
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        this.stateManager = null;
        this.engine = null;
    }

    @Override
    public void onEnable() {
        this.enabled = true;
    }

    @Override
    public void onDisable() {
        this.enabled = false;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void update(float deltaTime) {
    }
}
