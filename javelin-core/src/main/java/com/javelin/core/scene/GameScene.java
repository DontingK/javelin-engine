package com.javelin.core.scene;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.Transform;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏场景（Scene 接口的默认实现，Unity 心智）。
 *
 * <p>持有 Transform 树根（rootNodes），挂入走两条路：scene.add(root) 提交裸树，
 * 或 transform.setParent 挂到已入场景的节点下自动加入（设计文档 3 节）；
 * 摘除（remove 或 setParent 摘到场景外）时递归整树 onDisable → onDestroy 退出场景，
 * 并维护组件注册表（components / cameras / renderables，增删 O(1)，
 * 不每帧遍历场景树，6.1 节）。</p>
 *
 * <p>渲染衔接（用户拍板 2026-09-25，接口组件视角）：getCameras/getRenderables
 * 返回组件列表，由引擎层每帧 pull 翻译成 RenderCamera/RenderObject 喂
 * RenderingSystem（push 注册路径其 javadoc 已预留）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class GameScene extends AbsAsset implements Scene {

    private String name;
    private final List<Node> rootNodes = new ArrayList<>();
    /**
     * 全部已挂入组件（update 驱动顺序 ≈ 挂入顺序，设计文档 4 节）
     */
    private final List<Component> components = new ArrayList<>();
    private final List<Camera> cameras = new ArrayList<>();
    private final List<Renderable> renderables = new ArrayList<>();

    public GameScene() {
    }

    public GameScene(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Transform 树根（无父节点的挂入节点；返回内部列表，只读）。
     */
    public List<Node> getRootNodes() {
        return rootNodes;
    }

    /**
     * 提交根节点：root 及其 Transform 子树整棵挂入本场景并记入树根（设计文档 3.2）。
     *
     * <p>典型用法：new Node() 裸搭层级 → scene.add(root)。
     * 子树也可经 transform.setParent 挂到已入场景的节点下自动加入
     * （Node.onParentChanged → attachSubtree，无需再 add）。</p>
     */
    public void add(Node root) {
        attachSubtree(root);
        if (!rootNodes.contains(root)) {
            rootNodes.add(root);
        }
    }

    /**
     * 子树挂入（setParent 自动入场景与 add 共用的内部路径）。
     *
     * <p>归属检查（子树任一节点已属其他场景 → IllegalStateException）；
     * 仅对本次新挂入的节点注册组件并触发生命周期：先整树 onAwake（层级序）
     * 再整树 onEnable（Unity 语义：Awake 全部先于 Enable）；
     * 已在本场景的节点跳过（幂等，不重复回调）。</p>
     */
    void attachSubtree(Node root) {
        List<Node> subtree = collectSubtree(root);
        for (Node node : subtree) {
            if (node.scene != null && node.scene != this) {
                throw new IllegalStateException(
                        "节点已归属场景[" + node.scene.getName() + "]: " + node.getName());
            }
        }
        List<Node> fresh = new ArrayList<>();
        for (Node node : subtree) {
            if (node.scene != null) {
                continue;
            }
            node.scene = this;
            for (Component component : node.components()) {
                register(component);
            }
            fresh.add(node);
        }
        // onAwake 对整棵树触发（Unity 语义： inactive 对象也执行 Awake）；
        // onEnable 仅对层级激活的节点
        for (Node node : fresh) {
            for (Component component : node.components()) {
                component.onAwake();
            }
        }
        for (Node node : fresh) {
            if (!node.isActiveInHierarchy()) {
                continue;
            }
            for (Component component : node.components()) {
                component.onEnable();
            }
        }
    }

    /**
     * 移除整棵子树（设计文档 3.3）：onDisable → onDestroy → 注销注册表，
     * 子树从原父节点摘下。与"setParent 摘到场景外"共用 detachSubtree 一条路径
     * （摘除即出场景：本引擎节点可裸存在，与 Unity 出生即入场景不同）。
     */
    public void remove(Node root) {
        if (root.scene != this) {
            throw new IllegalStateException("节点不在场景[" + name + "]: " + root.getName());
        }
        detachSubtree(root);
        root.getTransform().setParent(null);
    }

    /**
     * 整棵子树离开场景（remove 与 setParent 摘除共用的内部路径）。
     *
     * <p>层级序 onDisable（仅层级激活的节点）→ onDestroy，注销组件注册表，
     * 清除归属。调用后节点回到裸状态，可重新 add 或挂入其他场景。</p>
     */
    void detachSubtree(Node root) {
        List<Node> subtree = collectSubtree(root);
        for (Node node : subtree) {
            if (node.isActiveInHierarchy()) {
                for (Component component : node.components()) {
                    component.onDisable();
                }
            }
        }
        for (Node node : subtree) {
            for (Component component : node.components()) {
                component.onDestroy();
            }
        }
        for (Node node : subtree) {
            for (Component component : node.components()) {
                unregister(component);
            }
            node.scene = null;
        }
        rootNodes.remove(root);
    }

    /**
     * 每帧驱动：对 active 且 enabled 的组件先补 onStart（每组件仅一次）再 onUpdate。
     */
    @Override
    public void update(float tpf) {
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            if (!component.isActiveAndEnabled()) {
                continue;
            }
            if (!component.isStarted()) {
                component.markStarted();
                component.onStart();
            }
            component.onUpdate(tpf);
        }
    }

    /**
     * 相机组件注册表（引擎层 pull 翻译用；返回内部列表，只读）。
     */
    @Override
    public List<Camera> getCameras() {
        return cameras;
    }

    /**
     * 可渲染组件注册表（MeshRenderer；引擎层 pull 翻译用；返回内部列表，只读）。
     */
    @Override
    public List<Renderable> getRenderables() {
        return renderables;
    }

    // ═══ Node 联动钩子（package-private，同包调用） ═══

    /**
     * 已挂入节点上 addComponent：注册并触发 onAwake → onEnable（Unity AddComponent 语义）。
     */
    void onComponentAttached(Node node, Component component) {
        register(component);
        component.onAwake();
        if (node.isActiveInHierarchy()) {
            component.onEnable();
        }
    }

    /**
     * 已挂入节点 setActive 翻转：子树内逐节点比对翻转前后的层级生效状态，触发 onEnable/onDisable。
     */
    void onActiveToggled(Node toggled) {
        boolean oldSelfActive = !toggled.isActive();
        boolean ancestorsActive = true;
        Transform parent = toggled.getTransform().getParent();
        if (parent != null) {
            ancestorsActive = parent.getNode().isActiveInHierarchy();
        }
        fireActiveChanged(toggled, toggled, oldSelfActive, ancestorsActive, ancestorsActive);
    }

    private void fireActiveChanged(Node toggled, Node node, boolean oldSelfActive,
                                   boolean parentWasActive, boolean parentNowActive) {
        boolean was = parentWasActive && (node == toggled ? oldSelfActive : node.isActive());
        boolean now = parentNowActive && node.isActive();
        if (was && !now) {
            for (Component component : node.components()) {
                component.onDisable();
            }
        } else if (!was && now) {
            for (Component component : node.components()) {
                component.onEnable();
            }
        }
        for (Transform child : node.getTransform()) {
            fireActiveChanged(toggled, child.getNode(), oldSelfActive, was, now);
        }
    }

    // ═══ 内部 ═══

    private void register(Component component) {
        components.add(component);
        if (component instanceof Camera) {
            cameras.add((Camera) component);
        }
        if (component instanceof Renderable) {
            renderables.add((Renderable) component);
        }
    }

    private void unregister(Component component) {
        components.remove(component);
        cameras.remove(component);
        renderables.remove(component);
    }

    /**
     * 层级序（前序）收集节点及其 Transform 子树。
     */
    private static List<Node> collectSubtree(Node root) {
        List<Node> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Node node, List<Node> out) {
        out.add(node);
        for (Transform child : node.getTransform()) {
            collect(child.getNode(), out);
        }
    }

    @Override
    public void write(OutputCapsule output) {

    }

    @Override
    public void read(InputCapsule input) {

    }
}
