package cn.donting.javelin.core.scene;

import cn.donting.javelin.core.settings.LayerSettings;
import cn.donting.javelin.core.settings.TagSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer / Tag 注册表单测：层名投影、标签增删、删除标签时的场景引用摘除、内建 Untagged 保护。
 */
public class LayerAndTagMangerTest {

    @Test
    void applyLayersProjectsNamesByIndex() {
        LayerSettings settings = new LayerSettings();
        settings.getLayerNames()[0] = "Default";
        settings.getLayerNames()[3] = "Ground";
        LayerAndTagManger.applyLayers(settings);

        assertEquals("Default", Layer.of(0).getName());
        assertEquals("Ground", Layer.of(3).getName());
        assertEquals("", Layer.of(4).getName(), "未命名槽位为空串");
    }

    @Test
    void addAndRemoveTagKeepRegistryInSync() {
        TagSettings settings = new TagSettings();
        settings.add(Tag.UNTAGGED);

        assertTrue(LayerAndTagManger.addTag(settings, " Enemy "), "首尾空白剔除后新增");
        assertFalse(LayerAndTagManger.addTag(settings, "Enemy"), "重名不重复新增");
        assertTrue(LayerAndTagManger.isTagRegistered("Enemy"));

        LayerAndTagManger.removeTag(settings, "Enemy", null);
        assertFalse(LayerAndTagManger.isTagRegistered("Enemy"), "删除后注册表同步出表");
        assertFalse(settings.getTags().contains("Enemy"), "设置清单同步删除");
    }

    @Test
    void removeTagStripsReferencesFromSceneTree() {
        TagSettings settings = new TagSettings();
        settings.add(Tag.UNTAGGED);
        LayerAndTagManger.addTag(settings, "Enemy");

        GameScene scene = new GameScene("Test Scene");
        Node root = new Node();
        root.setName("root");
        scene.add(root);
        Node child = new Node();
        child.setName("child");
        child.getTransform().setParent(root.getTransform());
        root.getTags().add(Tag.of("Enemy"));
        child.getTags().add(Tag.of("Enemy"));

        assertEquals(2, LayerAndTagManger.removeTag(settings, "Enemy", scene), "返回摘掉的引用处数");
        assertTrue(root.getTags().isEmpty());
        assertTrue(child.getTags().isEmpty());
    }

    @Test
    void untaggedIsProtectedFromRemoval() {
        TagSettings settings = new TagSettings();
        settings.add(Tag.UNTAGGED);
        assertThrows(IllegalArgumentException.class,
                () -> LayerAndTagManger.removeTag(settings, Tag.UNTAGGED, null));
    }

    @Test
    void unregisteredTagIsRejectedByOf() {
        assertThrows(IllegalStateException.class, () -> Tag.of("never-registered-tag"));
    }
}
