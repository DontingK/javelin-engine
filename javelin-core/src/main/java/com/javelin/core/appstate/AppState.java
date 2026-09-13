package com.javelin.core.appstate;

import com.javelin.core.JavelinEngine;

/**
 * 应用状态接口。
 *
 * <p>提供模块化的全局状态生命周期管理，适合实现：游戏流程控制、
 * 相机控制器、编辑器 Gizmo、UI 状态机等全局模块。</p>
 *
 * <p>生命周期顺序：</p>
 * <ol>
 *     <li>{@link #onAttach(AppStateManager, JavelinEngine)} — 被附加到管理器</li>
 *     <li>{@link #onEnable()} — 启用（可选，默认自动启用）</li>
 *     <li>{@link #earlyUpdate(float)} → {@link #update(float)} → {@link #lateUpdate(float)} — 每帧更新</li>
 *     <li>{@link #onDisable()} — 禁用（可选）</li>
 *     <li>{@link #onDetach(AppStateManager)} — 从管理器分离</li>
 * </ol>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public interface AppState {

    /**
     * 被附加到管理器时调用。
     *
     * <p>此时引擎已初始化完成，可安全访问渲染器、场景等资源。</p>
     *
     * @param stateManager 所属状态管理器
     * @param engine       引擎实例
     */
    void onAttach(AppStateManager stateManager, JavelinEngine engine);

    /**
     * 从管理器分离时调用。
     *
     * <p>此时状态已完成清理，应释放所有持有的资源。</p>
     *
     * @param stateManager 所属状态管理器
     */
    void onDetach(AppStateManager stateManager);

    /**
     * 启用状态。
     *
     * <p>禁用时 update 不再被调用，但状态仍保留在管理器中。</p>
     */
    void onEnable();

    /**
     * 禁用状态。
     */
    void onDisable();

    /**
     * 检查是否处于启用状态。
     *
     * @return true 如果已启用
     */
    boolean isEnabled();

    /**
     * 早期更新。
     *
     * <p>在 scene.update() 之前执行，仅当 {@link #isEnabled()} 返回 true 时调用。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    void earlyUpdate(float deltaTime);

    /**
     * 正常更新。
     *
     * <p>在 scene.update() 之后执行，仅当 {@link #isEnabled()} 返回 true 时调用。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    void update(float deltaTime);

    /**
     * 晚期更新。
     *
     * <p>在 update() 之后执行，仅当 {@link #isEnabled()} 返回 true 时调用。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    void lateUpdate(float deltaTime);
}
