package com.javelin.core.scene;

import com.javelin.core.obj.ObjectId;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 组件抽象基类（Unity 心智）。
 *
 * <p>{@link Node} 是容器，Component 是挂在节点上的行为/数据。用户组件继承本类
 * 覆写生命周期回调，且组件类必须标 {@code @Reflective}（公开无参构造），
 * 由 ReflectiveFactory 零反射创建（GraalVM AOT，禁反射）。</p>
 *
 * <p>生命周期回调由场景挂入/主循环统一驱动（见场景设计文档第 3、4 节），
 * 裸节点上 addComponent 不触发任何回调。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class Component extends ObjectId implements Serializable {

    private Node node;
    private boolean enabled = true;
    /** onStart 是否已触发（每组件生命周期内只一次，由 GameScene.update 驱动） */
    private boolean started;

    /**
     * 所属节点（addComponent 时绑定，裸组件为 null）。
     */
    public Node getNode() {
        return node;
    }

    /**
     * 绑定所属节点。仅供 Node.addComponent 调用。
     */
    void setNode(Node node) {
        this.node = node;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 组件是否应当参与本帧更新：自身 enabled 且所在节点在层级中激活。
     */
    public boolean isActiveAndEnabled() {
        return enabled && node != null && node.isActiveInHierarchy();
    }

    /**
     * onStart 是否已触发（package-private，GameScene.update 驱动用）。
     */
    boolean isStarted() {
        return started;
    }

    void markStarted() {
        started = true;
    }

    /** 首次激活（挂入场景时）触发一次。 */
    protected void onAwake() {
    }

    /** 激活后触发（挂入场景、setActive(true) 恢复时）。 */
    protected void onEnable() {
    }

    /** 首次 onUpdate 前触发一次。 */
    protected void onStart() {
    }

    /** 每帧更新（仅 active 且 enabled 的组件，由逻辑线程主循环驱动）。 */
    protected void onUpdate(float dt) {
    }

    /** 失活时触发（setActive(false)、移出场景）。 */
    protected void onDisable() {
    }

    /** 销毁时触发（移出场景、场景卸载）。 */
    protected void onDestroy() {
    }

    @Override
    public void write(OutputCapsule output) {

    }

    @Override
    public void read(InputCapsule input) {

    }
}
