package com.javelin.core.scene;

import com.javelin.ast.annotation.Reflective;
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
@Reflective
public class Node extends ObjectId implements Serializable {

    private String name;
    private boolean active = true;
    private Layer layer;
    // 非 final：序列化反序列化需回写（红线 9）
    private List<Tag> tags;

    private Transform transform;
    // 类型用 ArrayList 直通（红线 10：序列化集合字段直传字段本体，不经包装）
    private ArrayList<Component> components;

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
     * <p>翻转后子树组件的 onEnable/onDisable 由 GameScene.update 状态机在
     * <b>下帧</b>联动（帧驱动生命周期，2026-10-02 拍板，设计文档 4 节）。</p>
     */
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
     * <p>生命周期回调统一由 GameScene.update 状态机驱动（已挂入的下帧
     * awake/enable，帧驱动拍板 2026-10-02）。</p>
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
            asGameScene().onComponentAttached(component);
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

    /**
     * 序列化数据面：name/active/components（含 Transform）/transform + layer、tags 投影。
     *
     * <p>layer 存索引（Layer 是启动时从设置构建的静态注册表，用户可改层名——
     * 索引稳定）、tags 存名字符串列表（Tag 为私有构造驻留值对象，非对象图节点）——
     * 红线 10 辨析：投影字段不涉及对象图引用同一性，read 侧 Layer.of/Tag.of
     * 为静态查表，不依赖嵌套对象 populate，时序安全。</p>
     *
     * <p>{@code scene}（场景归属）落盘为<b>根对象引用</b>——框架对「引用到导出根
     * 的 Asset 实例」走对象 id 而非 GUID（图内根引用语义，2026-10-02 拍板，见
     * TextExporter/BinaryExporter.isRoot），read 直接恢复归属，无需任何收尾修复。
     * {@code ObjectId.id}（进程内自增）不落盘：每次加载重新分配（编辑器选中/命令
     * 按运行时 id 反查，树拉取自然对齐）。子树导出（copy/paste）时 scene 引用
     * 指向旧场景实例（非导出根）——走 GUID 查不到恢复为 null，粘贴 setParent
     * 挂接时经 attachSubtree 全新挂入，行为自洽。</p>
     */
    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("active", active);
        output.write("layer", layer != null ? layer.getValue() : 0);
        ArrayList<String> tagNames = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            tagNames.add(tag.getName());
        }
        output.writeArrayString("tagNames", tagNames);
        // components 含 Transform（构造时自动挂载）；transform 字段与列表元素为
        // 同一实例，框架 identity 去重后落盘同一 id 引用
        output.write("components", components);
        output.write("transform", transform);
        // 场景归属：根引用走 id（导出根=GameScene 时 @ref:0 / TYPE_OBJECT）
        output.write("scene", scene);
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        active = input.readBoolean("active");
        layer = readLayer(input.readInt("layer"));
        ArrayList<String> names = input.readArrayString("tagNames");
        tags = new ArrayList<>();
        if (names != null) {
            for (String tagName : names) {
                if (tagName != null) {
                    tags.add(Tag.of(tagName));
                }
            }
        }
        ArrayList<Component> componentList = input.readArrayList("components", Component.class);
        components = componentList != null ? componentList : new ArrayList<>();
        transform = input.readSerializable("transform", Transform.class);
        scene = input.readSerializable("scene", GameScene.class);
    }

    /**
     * 索引读回（Layer.of(int) 越界抛 AIOOBE，兜底 DEFAULT；layers 数组长度 31）。
     */
    private static Layer readLayer(int index) {
        if (index >= 0 && index < 31) {
            return Layer.of(index);
        }
        return Layer.DEFAULT;
    }
}
