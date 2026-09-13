package com.javelin.core.appstate;

import com.javelin.core.JavelinEngine;
import com.javelin.core.subsystem.Subsystem;

/**
 * AppState 子系统 —— 将 {@link AppState} 生命周期桥接到 {@link Subsystem} 体系。
 *
 * <p>作为 Subsystem 注册到 {@link com.javelin.core.subsystem.SubsystemManager}，
 * 在每个更新阶段将调用转发给所有已附加的 {@link AppState}。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 *   // 在 JavelinEngine 子类中
 *   var appStateSubsystem = new AppStateSubsystem();
 *   subsystemManager.register(appStateSubsystem);
 *
 *   // 附加 AppState
 *   appStateSubsystem.getStateManager().attach(new MyGameState());
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class AppStateSubsystem implements Subsystem {

    private AppStateManager stateManager;

    @Override
    public void init(JavelinEngine engine) {
        this.stateManager = new AppStateManager(engine);
    }

    @Override
    public void earlyUpdate(float deltaTime) {
        if (stateManager != null) {
            stateManager.earlyUpdate(deltaTime);
        }
    }

    @Override
    public void update(float deltaTime) {
        if (stateManager != null) {
            stateManager.update(deltaTime);
        }
    }

    @Override
    public void lateUpdate(float deltaTime) {
        if (stateManager != null) {
            stateManager.lateUpdate(deltaTime);
        }
    }

    @Override
    public void shutdown() {
        if (stateManager != null) {
            stateManager.cleanup();
            stateManager = null;
        }
    }

    /**
     * 获取内部的状态管理器，用于附加/分离 AppState。
     *
     * @return 状态管理器
     * @throws IllegalStateException 如果子系统尚未初始化
     */
    public AppStateManager getStateManager() {
        if (stateManager == null) {
            throw new IllegalStateException("AppStateSubsystem has not been initialized yet. " +
                 "Ensure it is registered before initAll() is called.");
        }
        return stateManager;
    }
}
