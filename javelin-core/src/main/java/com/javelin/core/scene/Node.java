package com.javelin.core.scene;

import com.javelin.core.obj.ObjectId;
import com.javelin.core.reflective.ReflectiveFactory;
import com.javelin.core.scene.component.Transform;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景节点（容器，Unity 心智）。
 *
 * <p>Node 持有必有且唯一的 {@link Transform} 组件（构造时自动挂载），
 * 子节点层级经 Transform 的父子关系表达。
 * 裸创建（new Node()）无场景归属、不注册、不触发任何回调（见场景设计文档 2.2 节）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class Node extends ObjectId implements Serializable {

    private String name;
    private boolean active = true;
    private Layer layer;
    private final List<Tag> tags;

    private final Transform transform;
    private final List<Component> components;

    /** 归属场景（GameScene.add 设置，remove 清除；package-private，由 GameScene 维护） */
    Scene scene;

    public Node() {
        layer = Layer.DEFAULT;
        tags = new ArrayList<>();
        components = new ArrayList<>();
        transform = addComponent(Transform.class);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * 节点自身的激活状态（不影响子节点自身状态；层级生效见 isActiveInHierarchy）。
     *
     * <p>已挂入场景的节点翻转激活状态会级联触发子树组件的
     * onEnable/onDisable（由 GameScene 驱动，设计文档 4 节）。</p>
     */
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (scene != null) {
            asGameScene().onActiveToggled(this);
        }
    }

    /**
     * 自身及全部祖先均激活时才为 true（沿 Transform 父链向上检查）。
     */
    public boolean isActiveInHierarchy() {
        Node node = this;
        while (node != null) {
            if (!node.active) {
                return false;
            }
            Transform parent = node.transform.getParent();
            node = parent == null ? null : parent.getNode();
        }
        return true;
    }

    /**
     * 必有且唯一的变换。
     */
    public Transform getTransform() {
        return transform;
    }

    /**
     * 添加组件（经 ReflectiveFactory 零反射创建，组件类须 @Reflective + 公开无参构造）。
     *
     * <p>生命周期回调（onAwake/onEnable 等）由场景挂入与主循环驱动，
     * 裸节点上添加不触发回调。</p>
     *
     * <p>Transform 由构造时自动持有，不允许重复添加。</p>
     */
    public <T extends Component> T addComponent(Class<T> type) {
        if (type == Transform.class && transform != null) {
            throw new IllegalStateException("Transform 由 Node 构造时自动持有，不可重复添加");
        }
        T component = ReflectiveFactory.newInstance(type);
        component.setNode(this);
        components.add(component);
        if (scene != null) {
            asGameScene().onComponentAttached(this, component);
        }
        return component;
    }

    /**
     * 组件列表（package-private，GameScene 生命周期驱动用；外部经 getComponent 访问）。
     */
    List<Component> components() {
        return components;
    }

    /**
     * 归属场景（未挂入为 null）。
     */
    public Scene getScene() {
        return scene;
    }

    /**
     * 父变换变化联动（Transform.setParent 调用；引擎内部，勿手动调用）。
     *
     * <p>挂到已属场景的父节点下时，本节点及裸子树自动加入该场景
     * （经 GameScene.attachSubtree 做归属检查 + 注册 + onAwake/onEnable）；
     * 摘到场景外（父节点不在本场景）时整棵子树退出场景
     * （经 GameScene.detachSubtree 注销，与 remove 同一条路径）。
     * 已属场景的节点不允许经 setParent 跨场景迁移。</p>
     */
    public void onParentChanged() {
        Scene parentScene = resolveParentScene();
        if (parentScene == scene) {
            return;
        }
        if (scene != null && parentScene != null) {
            throw new IllegalStateException(
                    "节点已归属场景[" + scene.getName() + "]，不能挂到其他场景的节点下");
        }
        if (parentScene != null) {
            ((GameScene) parentScene).attachSubtree(this);
        } else {
            asGameScene().detachSubtree(this);
        }
    }

    private Scene resolveParentScene() {
        Transform parent = transform.getParent();
        return parent == null ? null : parent.getNode().scene;
    }

    /**
     * 场景内部钩子入口（生命周期/注册表联动在 GameScene 上；
     * GameScene 是 Scene 目前唯一实现）。
     */
    private GameScene asGameScene() {
        return (GameScene) scene;
    }

    /**
     * 获取第一个匹配类型的组件，没有则返回 null（线性扫描，组件少，够用）。
     */
    public <T extends Component> T getComponent(Class<T> type) {
        for (Component component : components) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
        }
        return null;
    }

    /**
     * 获取全部匹配类型的组件（返回新列表，修改不影响节点）。
     */
    public <T extends Component> List<T> getComponents(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : components) {
            if (type.isInstance(component)) {
                result.add(type.cast(component));
            }
        }
        return result;
    }

    public Layer getLayer() {
        return layer;
    }

    public void setLayer(Layer layer) {
        this.layer = layer;
    }

    public List<Tag> getTags() {
        return tags;
    }

    @Override
    public void write(OutputCapsule output) {

    }

    @Override
    public void read(InputCapsule input) {

    }
}
