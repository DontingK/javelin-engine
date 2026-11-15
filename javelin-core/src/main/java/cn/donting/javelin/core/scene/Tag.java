package cn.donting.javelin.core.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点标签：以名字为身份的不变值对象。
 *
 * <p>注册表（名字 → 实例）由 {@link LayerAndTagManger} 从设置投影维护：只支持新增 / 删除，
 * 不支持改名（改名等于另一个标签，已引用节点不会跟着变）。未注册的名字 {@link #of} 直接抛
 * {@link IllegalStateException}，不静默造实例。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class Tag {

    /** 内建默认标签：节点无标签时的显示值，不允许删除 */
    public static final String UNTAGGED = "Untagged";

    /** 注册表（名字 → 实例；包内可见，{@link LayerAndTagManger} 维护） */
    static final Map<String, Tag> TAGS = new ConcurrentHashMap<>();

    private final String name;

    private Tag(String name) {
        this.name = name;
    }

    /**
     * 取已注册的标签。
     *
     * @param name 标签名
     * @return 标签
     * @throws IllegalStateException 该名字未注册（设置里没有）
     */
    public static Tag of(String name) {
        Tag tag = TAGS.get(name);
        if (tag == null) {
            throw new IllegalStateException("tag not registered: " + name);
        }
        return tag;
    }

    /** 注册一个标签名（已存在即复用同一实例） */
    static Tag register(String name) {
        return TAGS.computeIfAbsent(name, Tag::new);
    }

    /** 注销标签名；返回该名字此前是否在册 */
    static boolean unregister(String name) {
        return TAGS.remove(name) != null;
    }

    /** 该名字是否已注册 */
    static boolean isRegistered(String name) {
        return name != null && TAGS.containsKey(name);
    }

    /** 在册标签名快照（无序，判定增删差集用；显示序一律取设置清单） */
    static List<String> names() {
        return new ArrayList<>(TAGS.keySet());
    }

    /**
     * 获取标签名称。
     *
     * @return 标签名称
     */
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Tag)) {
            return false;
        }
        return name.equals(((Tag) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
