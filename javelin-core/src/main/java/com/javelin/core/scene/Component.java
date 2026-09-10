package com.javelin.core.scene;

import com.javelin.core.obj.ObjectId;

/**
 * 组件抽象基类 —— 附加到单个 {@link Node}，管理该节点的局部数据和行为。
 *
 * <p>与 {@link com.javelin.core.subsystem.Subsystem} 的区别：
 * Component 属于单个节点，Subsystem 是全局单例。</p>
 *
 * <p><strong>生命周期</strong>（状态由 Node/GameScene 驱动，子类按需重写回调）：</p>
 * <pre>
 *   addComponent        → onAttach → onEnable（节点处于激活层级时）
 *   每帧 GameScene.update → onUpdate（处于激活执行状态时）
 *   setEnabled(false)   → onDisable
 *   setActive(false)    → onDisable（节点层级失活）
 *   removeComponent     → onDisable（如已激活）→ onDetach
 *   node.destroy()      → onDisable（如已激活）→ onDetach（帧末统一执行）
 * </pre>
 *
 * <p><strong>顺序保证</strong>：onAttach 必先于首个 onEnable；
 * onDisable 必先于 onDetach；同一帧内新增的组件从下一帧开始 onUpdate。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class Component extends ObjectId {

    /** 所属节点（attach 后非空） */
    private Node node;

    /** 组件自身开关（默认开启） */
    private boolean enabled = true;

    /** onAttach 是否已调用 */
    private boolean attached = false;

    /** onEnable 是否已调用（激活执行状态） */
    private boolean activated = false;

    protected Component() {
    }

    /**
     * 获取所属节点，未 attach 时返回 null。
     */
    public Node node() {
        return node;
    }

    /**
     * 组件自身开关。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置组件开关。
     * <p>仅当节点处于激活层级中时触发 onEnable/onDisable 状态迁移。</p>
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        updateActivation();
    }

    /**
     * 组件当前是否处于激活执行状态（已 attach && enabled && 节点层级激活）。
     * <p>GameScene.update 只对处于该状态的组件调用 onUpdate。</p>
     */
    public boolean isActivated() {
        return activated;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命周期管理（由 Node/GameScene 侧调用，package-private）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 附加到节点：调 onAttach，随后按激活层级补 onEnable。
     */
    final void attach(Node node) {
        this.node = node;
        onAttach(node);
        attached = true;
        updateActivation();
    }

    /**
     * 从节点摘除：如已激活先调 onDisable，再调 onDetach。
     */
    final void detach() {
        if (activated) {
            activated = false;
            onDisable();
        }
        onDetach();
        attached = false;
        node = null;
    }

    /**
     * 节点激活层级状态变化时由 Node 调用，刷新激活状态。
     */
    final void onHierarchyActivationChanged() {
        updateActivation();
    }

    /**
     * 依据当前条件迁移激活状态，保证 onEnable/onDisable 成对且不重复。
     * <p>激活状态翻转时通知所属 GameScene（{@link GameScene#markCacheDirty()}），
     * 使组件缓存快照过期。</p>
     */
    private void updateActivation() {
        boolean shouldActivate = attached && enabled && node != null && node.activeInHierarchy();
        boolean changed = false;
        if (shouldActivate && !activated) {
            activated = true;
            onEnable();
            changed = true;
        } else if (!shouldActivate && activated) {
            activated = false;
            onDisable();
            changed = true;
        }
        if (changed && node != null && node.scene() != null) {
            node.scene().markCacheDirty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命周期回调（子类按需重写）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 组件加入节点时调用一次。
     */
    protected void onAttach(Node node) {
    }

    /**
     * 组件进入激活执行状态时调用（onAttach 之后）。
     */
    protected void onEnable() {
    }

    /**
     * 每帧更新（处于激活执行状态时由 GameScene.update 调用）。
     */
    protected void onUpdate(float deltaTime) {
    }

    /**
     * 组件退出激活执行状态时调用（onDetach 之前）。
     */
    protected void onDisable() {
    }

    /**
     * 组件从节点移除或节点销毁时调用一次。
     */
    protected void onDetach() {
    }
}
