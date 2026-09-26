package com.javelin.core.appstate;

import com.javelin.core.JavelinEngine;

/**
 * 应用状态接口 —— 控制程序层的统一契约。
 *
 * <p>AppState 是「控制程序」的模块：游戏流程、场景切换、相机控制器、
 * 编辑器全局状态（Gizmo、UI 状态机）等随程序阶段动态挂卸的模块。</p>
 *
 * <p>与底层 {@link com.javelin.core.subsystem.Subsystem}（物理/音频/输入等
 * 引擎级全局服务：启动前注册、初始化后结构冻结、<b>不可移除</b>、只可暂停）相对，
 * 本层面向程序控制：<b>运行时可动态 attach/detach</b>。两层经
 * {@link AppStateSubsystem} 桥接（AppStateSubsystem 本身是个 Subsystem，
 * 将本层调度并入子系统帧相位）。</p>
 *
 * <p>调度相位：每帧场景更新之前（AppStateSubsystem 的 earlyUpdate 相位），
 * 仅启用状态收到 {@link #update(float)} 心跳。挂卸操作线程安全，
 * 延迟到帧边界生效（attach 后下一帧才 onAttach、可 getState）。</p>
 *
 * <p>生命周期顺序：</p>
 * <ol>
 *     <li>{@link #onAttach(AppStateManager, JavelinEngine)} — 被附加到管理器（帧边界生效）</li>
 *     <li>{@link #onEnable()} — 启用（可选，默认自动启用）；{@link #onDisable()} — 禁用（可选）</li>
 *     <li>{@link #update(float)} — 每帧心跳（仅启用状态）</li>
 *     <li>{@link #onDetach(AppStateManager)} — 从管理器分离（帧边界生效）</li>
 * </ol>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public interface AppState {

    /**
     * 被附加到管理器时调用（帧边界，经挂卸队列延迟生效）。
     *
     * <p>此时引擎已初始化完成，可安全访问渲染器、场景等资源。</p>
     *
     * @param stateManager 所属状态管理器
     * @param engine       引擎实例
     */
    void onAttach(AppStateManager stateManager, JavelinEngine engine);

    /**
     * 从管理器分离时调用（帧边界生效）。
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
     * 每帧心跳：场景更新之前执行，仅当 {@link #isEnabled()} 返回 true 时调用。
     *
     * @param deltaTime 帧间隔（秒）
     */
    void update(float deltaTime);
}
