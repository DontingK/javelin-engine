package cn.donting.javelin.core.scene;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.assets.AbsAsset;
import cn.donting.javelin.core.render.Renderable;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Collider;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏场景（Scene 接口的默认实现，Unity 心智）。
 *
 * <p>持有 Transform 树根（rootNodes），挂入走两条路：scene.add(root) 提交裸树，
 * 或 transform.setParent 挂到已入场景的节点下自动加入（设计文档 3 节）；
 * 摘除（remove 或 setParent 摘到场景外）时递归整树 onDisable → onDestroy 退出场景，
 * 并维护组件注册表（components / cameras / renderables / colliders，增删 O(1)，
 * 不每帧遍历场景树，6.1 节）。</p>
 *
 * <p>渲染衔接（用户拍板 2026-09-25，接口组件视角）：getCameras/getRenderables
 * 返回组件列表，由引擎层每帧 pull 翻译成 RenderCamera/RenderObject 喂
 * RenderingSystem（push 注册路径其 javadoc 已预留）。</p>
 *
 * <p>生命周期（2026-10-02 拍板）：awake/enable/disable/start/update 由
 * {@link #update} 帧驱动状态机统一驱动（挂入/翻转下帧生效）；onDestroy 为
 * 摘出场景时的唯一即时触发点（Unity 卸载同步语义）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class GameScene extends AbsAsset implements Scene {

    private String name;
    // 非 final：序列化反序列化需回写（红线 9）；rootNodes 类型用 ArrayList 直通
    //（红线 10：序列化集合字段直传字段本体，不经包装）
    private ArrayList<Node> rootNodes = new ArrayList<>();
    /**
     * 全部已挂入组件（update 驱动顺序 ≈ 挂入顺序，设计文档 4 节）
     */
    // 类型用 ArrayList 直通（红线 10：序列化集合字段直传字段本体，不经包装）
    private ArrayList<Component> components = new ArrayList<>();
    private List<Camera> cameras = new ArrayList<>();
    private List<Renderable> renderables = new ArrayList<>();
    private List<Collider> colliders = new ArrayList<>();

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
     * 仅对本次新挂入的节点设置归属并注册组件。生命周期<b>不在此触发</b>——
     * 由 {@link #update} 帧驱动状态机统一驱动（挂入后下帧 awake/enable，
     * 2026-10-02 拍板）；已在本场景的节点跳过（幂等，不重复注册）。</p>
     */
    void attachSubtree(Node root) {
        List<Node> subtree = collectSubtree(root);
        for (Node node : subtree) {
            if (node.scene != null && node.scene != this) {
                throw new IllegalStateException(
                        "节点已归属场景[" + node.scene.getName() + "]: " + node.getName());
            }
        }
        for (Node node : subtree) {
            if (node.scene != null) {
                continue;
            }
            node.scene = this;
            for (Component component : node.components()) {
                register(component);
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
     * <p>生命周期收尾（<b>唯一脱离 update 状态机的即时触发点</b>，Unity 卸载同步
     * 语义，2026-10-02 拍板）：按通知态 onDisable（仅 notifyEnabled 的）→
     * 全部 onDestroy，注销组件注册表，清归属并重置生命周期状态——节点回到
     * 裸状态（awaked/notifyEnabled/started 全 false），可重新挂入且生命周期
     * 整轮重跑。</p>
     */
    void detachSubtree(Node root) {
        List<Node> subtree = collectSubtree(root);
        for (Node node : subtree) {
            for (Component component : node.components()) {
                if (component.notifyEnabled) {
                    component.notifyEnabled = false;
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
                resetLifecycle(component);
            }
            node.scene = null;
        }
        rootNodes.remove(root);
    }

    /**
     * 每帧驱动：生命周期状态机（三遍序，保持 Unity「Awake 全先于 Enable 全先于
     * Start/Update」的阶段序）。
     *
     * <p>遍 1 awake（不判激活——Unity: inactive 对象也执行 Awake）；
     * 遍 2 通知态对齐（应激活 = enabled 且节点层级激活，翻转触发 onEnable/
     * onDisable——setActive/setEnabled 的回调联动统一走这里，<b>下帧生效</b>，
     * 2026-10-02 拍板）；遍 3 start + update（仅通知态开着的）。</p>
     */
    @Override
    public void update(float tpf) {
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            if (!component.awaked) {
                component.awaked = true;
                component.onAwake();
            }
        }
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            if (component.isActiveAndEnabled()) {
                if (!component.notifyEnabled) {
                    component.notifyEnabled = true;
                    component.onEnable();
                }
            } else if (component.notifyEnabled) {
                component.notifyEnabled = false;
                component.onDisable();
            }
        }
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            if (!component.notifyEnabled) {
                continue;
            }
            if (!component.started) {
                component.started = true;
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
     * 主相机（Unity {@code Camera.main} 口径：首个<b>激活且 enabled</b> 的 isMain
     * 相机；无则 null——不按「首个相机」兜底）。
     */
    @Override
    public Camera getMainCamera() {
        for (int i = 0; i < cameras.size(); i++) {
            Camera camera = cameras.get(i);
            if (camera.isMain() && camera.isActiveAndEnabled()) {
                return camera;
            }
        }
        return null;
    }

    /**
     * 可渲染组件注册表（MeshRenderer；引擎层 pull 翻译用；返回内部列表，只读）。
     */
    @Override
    public List<Renderable> getRenderables() {
        return renderables;
    }

    /**
     * 碰撞体注册表（L1 物理查询遍历源，Physics 门面消费；返回内部列表，只读）。
     */
    @Override
    public List<Collider> getColliders() {
        return colliders;
    }

    // ═══ Node 联动钩子（package-private，同包调用） ═══

    /**
     * 已挂入节点上 addComponent：注册进组件注册表。生命周期由 update 状态机
     * 下帧驱动（原即时 Awake/Enable 语义随 2026-10-02 帧驱动拍板移除）。
     */
    void onComponentAttached(Component component) {
        register(component);
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
        if (component instanceof Collider) {
            colliders.add((Collider) component);
        }
    }

    private void unregister(Component component) {
        components.remove(component);
        cameras.remove(component);
        renderables.remove(component);
        colliders.remove(component);
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

    /** 摘出场景时重置生命周期状态（裸态可重挂，整轮重跑） */
    private static void resetLifecycle(Component component) {
        component.awaked = false;
        component.notifyEnabled = false;
        component.started = false;
    }

    @Override
    public void write(OutputCapsule output) {
        // guid/lastModified 内嵌自述（2026-10-01 拍板；管线真值源仍是伴生 .meta，
        // 编辑器加载时同步进本字段，双源一致）
        super.write(output);
        output.write("name", name);
        // 树根直传——Node 递归展开 Transform/Component 对象图，框架 identity
        // 去重保共享回链（node/parent 双向引用）
        output.write("rootNodes", rootNodes);
        // 注册表主表直传（update 顺序=序列化时点顺序；与 Node.components 引用同一
        // 批组件实例，identity 去重后共享保持）。cameras/renderables/colliders 为其
        // instanceof 分流投影，read 时重建（renderables 元素接口未继承 Serializable
        // 走不了列表泛型通道，分流表本身也是派生数据）
        output.write("components", components);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        name = input.readString("name");
        ArrayList<Node> roots = input.readArrayList("rootNodes", Node.class);
        rootNodes = roots != null ? roots : new ArrayList<>();
        // 注册表主表回写
        ArrayList<Component> componentList = input.readArrayList("components", Component.class);
        components = componentList != null ? componentList : new ArrayList<>();
        // cameras/renderables/colliders 分流表重建（register 的 instanceof 口径——元素
        // 实例在导入器列表填充阶段已存在，instanceof 不解引用元素字段、不依赖嵌套
        // populate，时序安全；红线 10 辨析见设计档 §9.3）
        cameras = new ArrayList<>();
        renderables = new ArrayList<>();
        colliders = new ArrayList<>();
        for (Component component : components) {
            if (component instanceof Camera) {
                cameras.add((Camera) component);
            }
            if (component instanceof Renderable) {
                renderables.add((Renderable) component);
            }
            if (component instanceof Collider) {
                colliders.add((Collider) component);
            }
        }
    }
}
