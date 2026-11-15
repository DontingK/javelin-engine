package cn.donting.javelin.core.scene;

import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.core.settings.LayerSettings;
import cn.donting.javelin.core.settings.TagSettings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Layer / Tag 注册表的唯一入口：设置 → 注册表投影、标签增删、已删除标签的场景引用摘除。
 *
 * <p>Layer 只投影层名（索引与实例不变）；Tag 只支持新增 / 删除，不支持改名。引擎启动投影
 * 一次，设置窗改动后按 {@link #applyLayers}/{@link #applyTags} 增量刷新。</p>
 */
public class LayerAndTagManger {

    private LayerAndTagManger() {
    }

    /**
     * 设置 → Layer 注册表：按索引覆盖层名（越界的设置项忽略，缺失槽位视为未命名）。
     *
     * @param settings 图层设置（长度与 {@link Layer#COUNT} 可不等，按较小者投影）
     */
    public static void applyLayers(LayerSettings settings) {
        String[] names = settings.getLayerNames();
        for (int i = 0; i < Layer.COUNT; i++) {
            String name = i < names.length && names[i] != null ? names[i] : "";
            Layer.layers[i].name = name;
        }
    }

    /**
     * 设置 → Tag 注册表：设置里的名字入表；设置里已消失的名字出表并从场景摘引用。
     *
     * @param settings 标签设置
     * @param scene    当前场景（null = 不摘引用，仅同步注册表）
     */
    public static void applyTags(TagSettings settings, GameScene scene) {
        // 内建标签常驻（旧工程文件里没有 Untagged 时也保持可用）
        Tag.register(Tag.UNTAGGED);
        for (String name : tagNames(settings)) {
            Tag.register(name);
        }
        for (String registered : Tag.names()) {
            if (Tag.UNTAGGED.equals(registered)) {
                continue;
            }
            if (!settings.getTags().contains(registered)) {
                Tag.unregister(registered);
                removeFromScene(registered, scene);
            }
        }
    }

    /**
     * 新增标签（设置与注册表同步）。
     *
     * @param settings 标签设置
     * @param name     标签名（首尾空白剔除）
     * @return true = 已新增；false = 该名字已在清单
     * @throws IllegalArgumentException 名字为空
     */
    public static boolean addTag(TagSettings settings, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("tag name must not be blank");
        }
        if (settings.getTags().contains(trimmed)) {
            return false;
        }
        settings.add(trimmed);
        Tag.register(trimmed);
        return true;
    }

    /**
     * 删除标签（设置、注册表、场景节点引用三处同步）。
     *
     * @param settings 标签设置
     * @param name     标签名
     * @param scene    当前场景（null = 不摘引用）
     * @return 摘掉的节点引用处数
     * @throws IllegalArgumentException 试图删除内建 {@link Tag#UNTAGGED}
     */
    public static int removeTag(TagSettings settings, String name, GameScene scene) {
        if (Tag.UNTAGGED.equals(name)) {
            throw new IllegalArgumentException(Tag.UNTAGGED + " is built-in and cannot be removed");
        }
        settings.remove(name);
        Tag.unregister(name);
        return removeFromScene(name, scene);
    }

    /**
     * Tag 名清单（设置声明序，空白项剔除）——检查器下拉与设置面板的显示来源。
     *
     * @param settings 标签设置
     * @return 标签名列表（新副本）
     */
    public static List<String> tagNames(TagSettings settings) {
        List<String> names = new ArrayList<>(settings.getTags().size());
        for (String name : settings.getTags()) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 该名字是否已注册。
     *
     * @param name 标签名
     */
    public static boolean isTagRegistered(String name) {
        return Tag.isRegistered(name);
    }

    // ── 场景引用摘除 ─────────────────────────────────────────

    /** 遍历场景树摘掉该标签（返回摘掉的引用处数；scene 为 null 直接 0） */
    private static int removeFromScene(String name, GameScene scene) {
        if (scene == null || name == null) {
            return 0;
        }
        int removed = 0;
        for (Node root : scene.getRootNodes()) {
            removed += removeFromNode(root, name);
        }
        return removed;
    }

    private static int removeFromNode(Node node, String name) {
        int removed = 0;
        List<Tag> tags = node.getTags();
        if (tags != null) {
            Iterator<Tag> iterator = tags.iterator();
            while (iterator.hasNext()) {
                Tag tag = iterator.next();
                if (tag != null && name.equals(tag.getName())) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        for (Transform child : node.getTransform()) {
            removed += removeFromNode(child.getNode(), name);
        }
        return removed;
    }
}
