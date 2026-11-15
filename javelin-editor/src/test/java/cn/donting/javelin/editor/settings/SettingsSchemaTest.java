package cn.donting.javelin.editor.settings;

import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.settings.GraphSetting;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.LayerSettings;
import cn.donting.javelin.core.settings.SceneSetting;
import cn.donting.javelin.core.settings.Setting;
import cn.donting.javelin.core.settings.SettingSelect;
import cn.donting.javelin.core.settings.TagSettings;
import cn.donting.javelin.core.settings.WindowSetting;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.inspector.InspectorValueType;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.plugin.EditorLangTestBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明式设置 schema 单测（注解驱动）：页清单从设置根对象派生、每页按 {@code @SettingGroup}/
 * {@code @InsField}/{@code @SettingHint}/{@code @SettingSelect}/{@code @SettingRestart} 展开成
 * 「分组 → 行」、持久化清单排除只读字段、回写落设置实例、坏声明即抛。
 *
 * <p>页根 = {@link GraphSetting}（图形）、{@link WindowSetting}（窗口）、{@link LayerSettings}
 * （图层）、{@link TagSettings}（标签）、{@link SceneSetting}（场景），声明序即导航序。</p>
 */
public class SettingsSchemaTest extends EditorLangTestBase {

    /** 页清单：五页按 JavelinSetting 字段声明序（图形在前），窗口/场景页只写不读，图层/标签页即时生效 */
    @Test
    public void pagesComeFromSettingsRootInDeclarationOrder() {
        List<SettingsSchema> pages = SettingsSchema.pagesOf(new JavelinSetting());
        assertEquals(5, pages.size());
        assertEquals(List.of("projectSettings.category.graphics", "projectSettings.category.window",
                        "projectSettings.category.layers", "projectSettings.category.tags",
                        "projectSettings.category.scene"),
                List.of(pages.get(0).labelKey(), pages.get(1).labelKey(),
                        pages.get(2).labelKey(), pages.get(3).labelKey(), pages.get(4).labelKey()));
        assertEquals(List.of("graphSettings", "windowSettings", "layerSettings", "tagSettings", "sceneSetting"),
                List.of(pages.get(0).fieldName(), pages.get(1).fieldName(),
                        pages.get(2).fieldName(), pages.get(3).fieldName(), pages.get(4).fieldName()));
        assertTrue(pages.get(0).editorStartup());
        assertFalse(pages.get(1).editorStartup(), "窗口页是编辑器运行环境：只写不读");
        assertTrue(pages.get(2).editorStartup(), "图层页即时生效：改名经 LayerAndTagManger 投影注册表");
        assertTrue(pages.get(3).editorStartup(), "标签页即时生效：增删经 LayerAndTagManger 同步注册表");
        assertFalse(pages.get(4).editorStartup(), "场景页只写不读：启动场景由打包运行时消费");
        assertTrue(pages.get(0).root() instanceof GraphSetting);
        assertTrue(pages.get(1).root() instanceof WindowSetting);
        assertTrue(pages.get(2).root() instanceof LayerSettings);
        assertTrue(pages.get(3).root() instanceof TagSettings);
        assertTrue(pages.get(4).root() instanceof SceneSetting);
    }

    /** 图层页：字符串数组按 STRING_LIST 定长下发（带层号、不可增删），整表 setter 回写 */
    @Test
    public void layerPageUsesFixedStringList() throws TipException {
        LayerSettings layers = new LayerSettings();
        layers.getLayerNames()[0] = "Default";
        SettingsSchema.Item names = findItem(SettingsSchema.of(layers), "layerNames");
        assertEquals(InspectorValueType.STRING_LIST.name(), names.vo.valueType);
        assertFalse(names.vo.listResizable, "数组型定长：层号不可移位");
        String[] wire = names.vo.value.split("\n", -1);
        assertEquals(31, wire.length, "槽位数即数组长度");
        assertEquals("Default", wire[0]);

        StringBuilder edited = new StringBuilder();
        for (int i = 0; i < 31; i++) {
            if (i > 0) {
                edited.append('\n');
            }
            if (i == 0) {
                edited.append("Default");
            } else if (i == 30) {
                edited.append("Ground");
            }
        }
        names.write(edited.toString());
        assertEquals(31, layers.getLayerNames().length, "长度仍为 31");
        assertEquals("Ground", layers.getLayerNames()[30]);
    }

    /** 标签页：List 型可增删（listResizable）但名字不可改（listRenamable=false，@InsTagList），整表回写走 setTags */
    @Test
    public void tagPageUsesResizableStringList() throws TipException {
        TagSettings tags = new TagSettings();
        tags.add("Untagged");
        SettingsSchema.Item item = findItem(SettingsSchema.of(tags), "tags");
        assertEquals(InspectorValueType.STRING_LIST.name(), item.vo.valueType);
        assertTrue(item.vo.listResizable, "List 型可增删");
        assertFalse(item.vo.listRenamable, "Tag 以名字为身份：面板只增删、不给改名");
        assertEquals("Untagged", item.vo.value);

        item.write("Untagged\nEnemy");
        assertEquals(List.of("Untagged", "Enemy"), tags.getTags());
    }

    /** 场景页：guid 串字段经 @SettingAsset 下发为资产选择器（存 guid，None 清空） */
    @Test
    public void scenePageUsesGuidAssetPicker() throws TipException {
        SceneSetting scene = new SceneSetting();
        scene.setDefaultSceneGuid("guid-scene");
        SettingsSchema.Item item = findItem(SettingsSchema.of(scene), "defaultSceneGuid");
        assertEquals(InspectorValueType.GUID_ASSET.name(), item.vo.valueType);
        assertEquals(GameScene.class, item.vo.assetType);
        assertEquals("guid-scene", item.vo.value);

        item.write("guid-other");
        assertEquals("guid-other", scene.getDefaultSceneGuid());
        item.write(null);
        assertNull(scene.getDefaultSceneGuid(), "None 清空为 null");
    }

    /** 只写不读的页绑项目文件实例：页值来自文件，编辑不碰编辑器活实例 */
    @Test
    public void detachedPagesBindToProjectRoot() throws TipException {
        JavelinSetting live = new JavelinSetting();
        JavelinSetting project = new JavelinSetting();
        project.getWindowSettings().setWidth(1600);

        List<SettingsSchema> pages = SettingsSchema.pagesOf(live, project);
        assertSame(live.getGraphSettings(), pages.get(0).root(), "图形页绑编辑器活实例");
        assertSame(project.getWindowSettings(), pages.get(1).root(), "窗口页绑项目文件实例");
        assertEquals("1600", findItem(pages.get(1), "width").vo.value, "窗口页显示的是文件里的值");

        findItem(pages.get(1), "width").write("1920");
        assertEquals(1920, project.getWindowSettings().getWidth(), "编辑落到项目文件那份");
        assertEquals(1080, live.getWindowSettings().getWidth(), "编辑器活实例不受窗口页编辑影响");
    }

    /** 图形页分组/顺序（@SettingGroup 标量开组 + 嵌套对象整组） */
    @Test
    public void graphicsPageDeclaresSectionsInOrder() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        List<String> labels = new ArrayList<>();
        for (SettingsSchema.Section section : schema.sections()) {
            labels.add(section.labelKey);
        }
        assertEquals(List.of("projectSettings.section.pipeline", "projectSettings.section.background",
                "projectSettings.section.postProcessing", "projectSettings.section.ssao",
                "projectSettings.section.shadow"), labels);
        assertNull(schema.fieldName(), "of() 开的单页无设置根字段名");
    }

    /** 持久化清单：只含可写页根字段；@SettingRestart（管线档位）不在其中 */
    @Test
    public void managedRootsExcludeRestartField() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        assertEquals(List.of("backgroundColor", "postProcessSettings", "ssaoSettings", "shadowSettings"),
                schema.managedRoots());
    }

    /** 管线档位：@SettingRestart → 只读；@SettingHint → 行后提示位 */
    @Test
    public void restartFieldRowIsReadOnlyWithHint() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        SettingsSchema.Section pipeline = schema.sections().get(0);
        SettingsSchema.Item item = pipeline.entries.get(0).item;
        assertNotNull(item);
        assertEquals("renderPipeline", item.fieldName());
        assertTrue(item.readOnly);
        assertEquals(InspectorValueType.ENUM.name(), item.vo.valueType);
        assertEquals("HYBRID", item.vo.value);
        assertEquals(EditorLang.text("projectSettings.pipeline.hybrid"), item.vo.options.get(1).label);
        assertEquals("projectSettings.pipeline.restartHint", pipeline.entries.get(1).hintKey);
    }

    /** 标量字段：标签/范围/值全来自设置类注解（默认值 = 字段初始化器） */
    @Test
    public void scalarFieldRowCarriesAnnotationMetadata() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        SettingsSchema.Item background = findItem(schema, "backgroundColor");
        assertEquals(InspectorValueType.COLOR.name(), background.vo.valueType);
        assertEquals("0.05,0.05,0.08,1", background.vo.value);
        assertEquals(EditorLang.text("projectSettings.background"), background.vo.name);

        SettingsSchema.Item bloomThreshold = findItem(schema, "bloomThreshold");
        assertEquals(InspectorValueType.FLOAT.name(), bloomThreshold.vo.valueType);
        assertEquals(0f, bloomThreshold.vo.range.min, 1e-6f);
        assertEquals(10f, bloomThreshold.vo.range.max, 1e-6f);
        assertEquals(0.05f, bloomThreshold.vo.range.step, 1e-6f);
    }

    /** 后处理组：Tonemap/Bloom 两开关 + 三 Bloom 参数 + Tonemap 肩部（SSAO 已独立成组） */
    @Test
    public void postProcessGroupFlattensWithoutSsao() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        SettingsSchema.Section pp = section(schema, "projectSettings.section.postProcessing");
        assertEquals(List.of("tonemapEnabled", "bloomEnabled", "bloomThreshold",
                "bloomKnee", "bloomIntensity", "tonemapKnee"), rowNames(pp));
    }

    /** 环境光遮蔽组：开关随组走 + 半径/偏移/强度 */
    @Test
    public void ssaoGroupOwnsSwitchAndParams() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        SettingsSchema.Section ssao = section(schema, "projectSettings.section.ssao");
        assertEquals(List.of("enabled", "radius", "bias", "intensity"), rowNames(ssao));
        SettingsSchema.Item enabled = findItem(schema, "enabled");
        assertEquals(InspectorValueType.BOOLEAN.name(), enabled.vo.valueType);
        assertEquals(EditorLang.text("projectSettings.ssao"), enabled.vo.name);
    }

    /** 阴影组：字段序 + 两条提示位（@SettingHint 紧跟对应行） */
    @Test
    public void shadowSectionKeepsFieldOrderAndHintPositions() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        SettingsSchema.Section shadow = section(schema, "projectSettings.section.shadow");
        List<String> names = new ArrayList<>();
        for (SettingsSchema.Entry entry : shadow.entries) {
            names.add(entry.item != null ? entry.item.fieldName() : "#" + entry.hintKey);
        }
        assertEquals(List.of("quality", "strength", "pcfRadius", "refDepthOffset", "biasConstant",
                        "biasSlope", "#projectSettings.shadowBiasHint", "cascadeCount", "shadowDistance",
                        "cascadeLambda", "#projectSettings.shadowCascadeHint"),
                names);
    }

    /** 级联数走 @SettingSelect：值面按枚举通道下发（JeSelect），回写仍是原 int setter */
    @Test
    public void cascadeCountBecomesExplicitSelect() throws TipException {
        GraphSetting graph = new GraphSetting();
        SettingsSchema schema = SettingsSchema.of(graph);
        SettingsSchema.Item cascade = findItem(schema, "cascadeCount");
        assertEquals(InspectorValueType.ENUM.name(), cascade.vo.valueType);
        assertEquals(3, cascade.vo.options.size());
        assertEquals("2", cascade.vo.value);
        assertEquals("4", cascade.vo.options.get(2).value);
        assertEquals(EditorLang.text("projectSettings.shadowCascades.4"), cascade.vo.options.get(2).label);

        cascade.write("4");
        assertEquals(4, graph.getShadowSettings().getCascadeCount());
    }

    /** 回写走设置实例 setter（面板不持路由；下发由引擎 applyRenderSettings 单点负责） */
    @Test
    public void itemWriteUpdatesSettingsInstance() throws TipException {
        GraphSetting graph = new GraphSetting();
        SettingsSchema schema = SettingsSchema.of(graph);

        findItem(schema, "bloomThreshold").write("1.25");
        findItem(schema, "bloomEnabled").write("true");
        findItem(schema, "ssaoRadius").write("0.75");
        findItem(schema, "enabled").write("true");
        findItem(schema, "shadowStrength").write("0.4");

        assertEquals(1.25f, graph.getPostProcessSettings().getBloomThreshold(), 1e-6f);
        assertTrue(graph.getPostProcessSettings().isBloomEnabled());
        assertEquals(0.75f, graph.getSsaoSettings().getRadius(), 1e-6f);
        assertTrue(graph.getSsaoSettings().isEnabled());
        assertEquals(0.4f, graph.getShadowSettings().getStrength(), 1e-6f);
    }

    /** 窗口页：无 @SettingGroup → 单页默认组（标题 = 页名键）；vsync 只读 + 提示、不入持久化 */
    @Test
    public void windowPageUsesDefaultSectionAndRestartVsync() {
        SettingsSchema schema = SettingsSchema.of(new WindowSetting());
        assertEquals(1, schema.sections().size());
        SettingsSchema.Section section = schema.sections().get(0);
        assertEquals("projectSettings.category.window", section.labelKey);

        List<String> names = new ArrayList<>();
        for (SettingsSchema.Entry entry : section.entries) {
            if (entry.item != null) {
                names.add(entry.item.fieldName());
            }
        }
        assertEquals(List.of("width", "height", "title", "vsync", "maxFps"), names);

        SettingsSchema.Item vsync = findItem(schema, "vsync");
        assertTrue(vsync.readOnly);
        assertEquals(List.of("width", "height", "title", "maxFps"), schema.managedRoots(),
                "只读的 vsync 不入持久化清单");

        boolean hintAfterVsync = false;
        for (int i = 0; i < section.entries.size(); i++) {
            SettingsSchema.Entry entry = section.entries.get(i);
            if (entry.item != null && entry.item.fieldName().equals("vsync")) {
                SettingsSchema.Entry next = section.entries.get(i + 1);
                assertEquals("projectSettings.windowVsyncHint", next.hintKey, "vsync 提示紧跟在行后");
                hintAfterVsync = true;
            }
        }
        assertTrue(hintAfterVsync, "未找到 vsync 行");
    }

    /** 坏声明即抛（页根缺 @Setting / @SettingSelect 用在非 int / 字段既不可映射也非设置对象） */
    @Test
    public void badDeclarationThrows() {
        assertThrows(IllegalStateException.class, () -> SettingsSchema.of(new Object()));
        assertThrows(IllegalStateException.class,
                () -> SettingsSchema.of(new BadSelectPage()).sections());
        assertThrows(IllegalStateException.class,
                () -> SettingsSchema.of(new BadFieldPage()).sections());
    }

    /** 未声明字段不出现在任何分组（只声明才显示）；嵌套设置对象是分组容器、不成字段行 */
    @Test
    public void undeclaredFieldsAreNotShown() {
        SettingsSchema schema = SettingsSchema.of(new GraphSetting());
        for (String groupObject : List.of("postProcessSettings", "ssaoSettings", "shadowSettings")) {
            assertNull(itemOrNull(schema, groupObject), groupObject + " 是分组容器，不应成行");
        }
        for (String absent : List.of("engineDebug", "windowSettings", "maxFps")) {
            assertNull(itemOrNull(schema, absent), absent + " 不属于图形页，不应出现");
        }
    }

    // ── 坏声明夹具 ───────────────────────────────────────────

    /** @SettingSelect 只能标 int 字段 */
    @Setting(label = "test.page")
    public static final class BadSelectPage {
        @InsField(label = "test.field")
        @SettingSelect(values = {1, 2}, labelPrefix = "test")
        private String text = "x";

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    /** 已贴注解但既不映射也不成设置对象 */
    @Setting(label = "test.page")
    public static final class BadFieldPage {
        @InsField(label = "test.field")
        private Runnable task = () -> {
        };

        public Runnable getTask() {
            return task;
        }

        public void setTask(Runnable task) {
            this.task = task;
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private static List<String> rowNames(SettingsSchema.Section section) {
        List<String> names = new ArrayList<>();
        for (SettingsSchema.Entry entry : section.entries) {
            assertNotNull(entry.item, "本组不应有提示行");
            names.add(entry.item.fieldName());
        }
        return names;
    }

    private static SettingsSchema.Item findItem(SettingsSchema schema, String fieldName) {
        SettingsSchema.Item item = itemOrNull(schema, fieldName);
        if (item == null) {
            throw new AssertionError("未找到设置项: " + fieldName);
        }
        return item;
    }

    private static SettingsSchema.Item itemOrNull(SettingsSchema schema, String fieldName) {
        for (SettingsSchema.Section section : schema.sections()) {
            for (SettingsSchema.Entry entry : section.entries) {
                if (entry.item != null && entry.item.fieldName().equals(fieldName)) {
                    return entry.item;
                }
            }
        }
        return null;
    }

    private static SettingsSchema.Section section(SettingsSchema schema, String labelKey) {
        for (SettingsSchema.Section section : schema.sections()) {
            if (section.labelKey.equals(labelKey)) {
                return section;
            }
        }
        throw new AssertionError("未找到分组: " + labelKey);
    }
}
