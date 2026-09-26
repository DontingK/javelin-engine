package com.javelin.core.appstate;

import com.javelin.core.JavelinEngine;
import com.javelin.core.subsystem.Subsystem;

/**
 * AppState 子系统 —— 将 {@link AppState} 控制层桥接进 {@link Subsystem} 调度体系。
 *
 * <p>作为 Subsystem 注册到 {@link com.javelin.core.subsystem.SubsystemManager}
 * （JavelinEngine.registerSubsystem 默认注册），在 earlyUpdate 相位——
 * 即每帧 scene.update 之前——驱动 {@link AppStateManager}：
 * 先处理挂卸队列（帧边界生效），再逐个调用启用状态的 update。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 * // JavelinEngine 子类中（默认已注册，通常无需自建）
 * appStateSubsystem.getStateManager().attach(new MyGameState());
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
            stateManager.update(deltaTime);
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
