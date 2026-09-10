package com.javelin.core.scene;

import com.javelin.core.obj.ObjectId;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 场景节点 —— Unity GameObject 同构对象，承载组件与子级层级。
 *
 * <p>一个 Node 恰好持有一个 {@link Transform}（构造时自动创建并作为组件附加，
 * 不可手动添加/移除），可附加多个其他类型的 {@link Component}
 * （每个具体组件类型最多一个实例）。</p>
 *
 * <p><strong>销毁安全</strong>：{@link #destroy()} 不立即拆除，而是标记后挂到
 * 所属 GameScene 的待销毁队列，由 {@link GameScene#update(float)} 在帧末统一处理，
 * 允许在 onUpdate 中销毁任意节点（包括自身）。</p>
 *
 * <p>线程模型：仅主线程操作，不做内部加锁。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class Node extends ObjectId {

    private String name;

    /** 自身激活开关（默认开启） */
    private boolean active = true;

    /** 节点变换（构造时创建） */
    private final Transform transform;

    /** 已附加的组件 */
    private final ArrayList<Component> components = new ArrayList<>();

    /** 所属场景（由 GameScene.addRoot 或层级挂接时设置） */
    private GameScene scene;

    /** 是否已请求销毁 */
    private boolean destroyed = false;

    public Node() {
        this("Node");
    }

    public Node(String name) {
        this.name = name;
        this.transform = new Transform();
        components.add(transform);
        transform.attach(this);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  基本属性
    // ═══════════════════════════════════════════════════════════════════════

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Transform transform() {
        return transform;
    }

    /**
     * 获取所属场景，未加入场景时返回 null。
     */
    public GameScene scene() {
        return scene;
    }

    /**
     * 是否已请求销毁（destroy 调用后即为 true，实际拆除在帧末进行）。
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  组件管理
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取第一个匹配类型的组件（支持父类/接口查询），不存在时返回 null。
     */
    @SuppressWarnings("unchecked")
    public <T extends Component> T getComponent(Class<T> type) {
        for (Component c : components) {
            if (type.isInstance(c)) {
                return (T) c;
            }
        }
        return null;
    }

    /**
     * 是否已附加指定类型的组件。
     */
    public boolean hasComponent(Class<? extends Component> type) {
        return getComponent(type) != null;
    }

    /**
     * 获取只读组件列表视图。
     */
    public List<Component> getComponents() {
        return Collections.unmodifiableList(components);
    }

    /**
     * 附加组件。
     *
     * <p>每个具体组件类型最多一个实例（Unity 语义）。
     * 附加时依次调用 onAttach、（节点处于激活层级时）onEnable。</p>
     *
     * @param component 组件实例
     * @return component 本身（便于链式写法）
     * @throws IllegalStateException 如果该具体类型的组件已存在
     */
    public <T extends Component> T addComponent(T component) {
        if (component == null) {
            throw new NullPointerException("component is null");
        }
        if (component instanceof Transform) {
            throw new IllegalStateException("Transform 由 Node 构造时自动创建，不能手动添加");
        }
        Class<?> type = component.getClass();
        for (Component c : components) {
            if (c.getClass() == type) {
                throw new IllegalStateException(
                    "节点已包含组件 " + type.getSimpleName() + "，每个具体类型最多一个实例");
            }
        }
        components.add(component);
        component.attach(this);
        registerInScene(scene, component);
        return component;
    }

    /**
     * 按类型移除组件，存在则移除并触发 onDisable（如已激活）→ onDetach。
     *
     * @return 被移除的组件，不存在时返回 null
     */
    public <T extends Component> T removeComponent(Class<T> type) {
        T component = getComponent(type);
        if (component != null) {
            removeComponent(component);
        }
        return component;
    }

    /**
     * 移除指定组件实例。
     *
     * @return true 如果组件确实属于本节点并被移除
     */
    public boolean removeComponent(Component component) {
        if (component == transform) {
            throw new IllegalStateException("Transform 是节点固有组件，不能移除");
        }
        if (components.remove(component)) {
            unregisterFromScene(scene, component);
            component.detach();
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  层级管理
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取父节点，无父级时返回 null。
     */
    public Node getParent() {
        Transform p = transform.parent();
        return p == null ? null : p.node();
    }

    /**
     * 添加子节点（等价于 child.setParent(this)）。
     */
    public void addChild(Node child) {
        if (child == null) {
            throw new NullPointerException("child is null");
        }
        child.transform().setParent(this.transform);
    }

    /**
     * 移除子节点（仅当 child 确为本节点的子级时生效），子节点变为游离节点。
     */
    public void removeChild(Node child) {
        if (child != null && child.getParent() == this) {
            child.transform().setParent(null);
        }
    }

    /**
     * 获取子节点列表（新建列表，不与内部状态共享）。
     */
    public List<Node> children() {
        int count = transform.getChildCount();
        ArrayList<Node> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(transform.getChild(i).node());
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  激活状态
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 自身激活开关。
     */
    public boolean activeSelf() {
        return active;
    }

    /**
     * 层级激活状态：自身与所有祖先的 active 取与。
     * <p>GameScene.update 只更新处于激活状态的节点及其组件。</p>
     */
    public boolean activeInHierarchy() {
        Node n = this;
        while (n != null) {
            if (!n.active) {
                return false;
            }
            Transform p = n.transform.parent();
            n = (p == null) ? null : p.node();
        }
        return true;
    }

    /**
     * 设置激活状态。
     * <p>状态变化会刷新自身及所有子孙的组件 onEnable/onDisable。</p>
     */
    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        onHierarchyChanged();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  销毁
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 请求销毁本节点及其整棵子树。
     *
     * <p>不在调用点立即拆除：已加入场景的节点进入 GameScene 待销毁队列，
     * 由 {@link GameScene#update(float)} 在帧末统一处理；未加入场景的节点立即拆除。</p>
     */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        GameScene s = scene;
        if (s != null) {
            s.enqueueDestroy(this);
        } else {
            destroyNow();
        }
    }

    /**
     * 立即拆除整棵子树：递归销毁子节点，组件按 onDisable（如已激活）→ onDetach
     * 顺序摘除，最后脱离父级。由 GameScene 帧末统一调用，或游离节点的 destroy 直接调用。
     */
    void destroyNow() {
        List<Node> children = children();
        for (Node child : children) {
            child.destroyNow();
        }
        // 销毁不走 removeComponent，必须显式注销相机/可渲染对象缓存，否则缓存泄漏
        if (scene != null) {
            for (Component c : components) {
                unregisterFromScene(scene, c);
            }
        }
        for (Component c : new ArrayList<>(components)) {
            c.detach();
        }
        components.clear();
        transform.removeFromParent();
        scene = null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  层级状态同步（package-private，Transform/GameScene 侧调用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 层级结构或激活状态变化后调用：
     * 同步场景引用并刷新自身及所有子孙的组件激活状态。
     */
    void onHierarchyChanged() {
        Node parent = getParent();
        GameScene parentScene = (parent != null) ? parent.scene : this.scene;
        syncScene(parentScene);
        syncActivation();
    }

    /**
     * 组件附加到节点/挂入场景时，向场景注册需缓存的组件（Camera/Renderable）。
     */
    private static void registerInScene(GameScene scene, Component c) {
        if (scene == null) return;
        if (c instanceof Camera camera) scene.registerCamera(camera);
        if (c instanceof Renderable r) scene.registerRenderable(r);
    }

    /**
     * 组件从节点摘除/脱离场景/销毁时，从场景注销缓存的组件（Camera/Renderable）。
     */
    private static void unregisterFromScene(GameScene scene, Component c) {
        if (scene == null) return;
        if (c instanceof Camera camera) scene.unregisterCamera(camera);
        if (c instanceof Renderable r) scene.unregisterRenderable(r);
    }

    /**
     * 同步本节点及子孙的场景引用；场景切换时同步本节点及子孙的
     * 相机/可渲染对象缓存注册/注销。
     */
    void syncScene(GameScene scene) {
        if (this.scene == scene) {
            return;
        }
        GameScene oldScene = this.scene;
        this.scene = scene;
        for (Component c : components) {
            unregisterFromScene(oldScene, c);
            registerInScene(scene, c);
        }
        int count = transform.getChildCount();
        for (int i = 0; i < count; i++) {
            Node child = transform.getChild(i).node();
            if (child != null) {
                child.syncScene(scene);
            }
        }
    }

    /**
     * 刷新本节点及子孙的组件激活状态。
     */
    private void syncActivation() {
        for (Component c : components) {
            c.onHierarchyActivationChanged();
        }
        int count = transform.getChildCount();
        for (int i = 0; i < count; i++) {
            Node child = transform.getChild(i).node();
            if (child != null) {
                child.syncActivation();
            }
        }
    }
}
