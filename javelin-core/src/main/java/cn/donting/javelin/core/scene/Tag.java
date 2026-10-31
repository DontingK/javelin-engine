package cn.donting.javelin.core.scene;

import cn.donting.javelin.core.settings.LayerSettings;
import cn.donting.javelin.core.settings.SettingManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点标签。
 *
 * <p>提供一组预定义标签，也支持通过 {@link #of(String)} 创建自定义标签。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class Tag {

    private static final Map<String, Tag> TAGS = new HashMap<>();

    private final String name;


    static {
        LayerSettings layerSettings = SettingManager.loadLayerSettings();
        String[] layerNames = layerSettings.getLayerNames();
        for (String layerName : layerNames) {
            register(layerName);
        }
    }

    private Tag(String name) {
        this.name = name;
    }

    private static Tag register(String name) {
        Tag tag = new Tag(name);
        TAGS.put(name, tag);
        return tag;
    }

    /**
     * 获取或创建指定名称的标签。
     *
     * @param name 标签名称
     * @return 标签
     */
    public static Tag of(String name) {
        Tag tag = TAGS.get(name);
        if (tag != null) {
            return tag;
        }
        return register(name);
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
