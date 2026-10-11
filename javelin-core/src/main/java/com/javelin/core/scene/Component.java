package com.javelin.core.scene;

import com.javelin.core.inspector.InsField;
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
 * <p>生命周期回调由 GameScene.update 帧驱动状态机统一驱动（挂入/启停下帧
 * 生效，见场景设计文档第 4 节）；onDestroy 为摘出场景时的唯一即时触发点
 *（2026-10-02 拍板）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class Component extends ObjectId implements Serializable {

    private Node node;
    // @InsField：分组头启用勾选的回写闸门（行渲染被 collectComponent 跳过）
    @InsField
    private boolean enabled = true;
    // ── 生命周期状态（运行态不落盘；由 GameScene.update 帧驱动状态机维护，
    //    2026-10-02 拍板：回调统一由 update 驱动，摘出场景时重置回裸态）──
    /** onAwake 是否已触发（每轮挂入只一次；不判激活——Unity: inactive 对象也 Awake） */
    boolean awaked;
    /** 通知态：onEnable 已触发且当前应激活（真值 = enabled 且节点层级激活） */
    boolean notifyEnabled;
    /** onStart 是否已触发（每轮挂入只一次） */
    boolean started;

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

    /** 首轮 update 触发一次（不判激活，Unity: inactive 对象也执行 Awake）。 */
    protected void onAwake() {
    }

    /** 通知态由否转是时触发（update 状态机对齐 enabled && 层级激活）。 */
    protected void onEnable() {
    }

    /** 首次 onUpdate 前触发一次。 */
    protected void onStart() {
    }

    /** 每帧更新（仅 active 且 enabled 的组件，由逻辑线程主循环驱动）。 */
    protected void onUpdate(float dt) {
    }

    /** 通知态由是转否时触发（update 状态机；摘出场景时即时收尾）。 */
    protected void onDisable() {
    }

    /** 销毁时触发（移出场景——即时，脱离 update 状态机的唯一例外）。 */
    protected void onDestroy() {
    }

    /**
     * 序列化数据面：{@code enabled}（用户数据）+ {@code node}（所属节点回链）。
     *
     * <p>node 回链双向落盘（框架 id 引用保 identity/循环）——反序列化后场景树遍历
     * （GameScene.collectSubtree 经 Transform.getNode）依赖它恢复。{@code started}
     * 为运行态不落盘（update 首帧按需补发 onStart）。</p>
     */
    @Override
    public void write(OutputCapsule output) {
        output.write("enabled", enabled);
        output.write("node", node);
    }

    @Override
    public void read(InputCapsule input) {
        enabled = input.readBoolean("enabled");
        node = input.readSerializable("node", Node.class);
    }
}
