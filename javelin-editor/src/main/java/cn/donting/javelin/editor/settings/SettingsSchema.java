package cn.donting.javelin.editor.settings;

import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.serialize.Serializable;
import cn.donting.javelin.core.settings.Setting;
import cn.donting.javelin.core.settings.SettingAsset;
import cn.donting.javelin.core.settings.SettingGroup;
import cn.donting.javelin.core.settings.SettingHint;
import cn.donting.javelin.core.settings.SettingRestart;
import cn.donting.javelin.core.settings.SettingSelect;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.inspector.InspectorMeta;
import cn.donting.javelin.editor.inspector.InspectorValueType;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页 schema：把 {@code @Setting} 页根对象按注解展开成「分组 → 行」，供
 * {@link cn.donting.javelin.editor.swing.settings.SettingsFormPanel} 装配，并派生持久化字段清单。
 *
 * <p><b>声明面全在设置类</b>：{@link SettingGroup}（对象 = 整组；标量 = 由此开组；未贴的标量归
 * 最近一次开的分组，页面开头则开页面默认组）、{@link InsField}（行 + 标签键）、
 * {@link SettingHint}（行后提示行）、{@link SettingSelect}（int 离散值下拉）、
 * {@link SettingAsset}（guid 串字段 → 资产选择器，存 guid）、
 * {@link SettingRestart}（只读 + 不入持久化）。新增设置项只改设置类字段 + 注解，面板与持久化
 * 自动跟随。</p>
 *
 * <p><b>页清单</b>：{@link #pagesOf} 扫设置根对象里类型贴 {@link Setting} 的字段，声明序即导航序；
 * {@link Setting#editorStartup()} = false 的页只写不读（编辑器启动不跟随项目文件）。</p>
 *
 * @author javelin
 * @create 2026/11/14
 **/
public final class SettingsSchema {

    /** 嵌套设置对象最大展开深度（设置结构 ≤2 层；防环兜底） */
    private static final int MAX_DEPTH = 4;

    private final Object root;
    private final String labelKey;
    private final boolean editorStartup;
    private final String fieldName;
    private final List<Section> sections = new ArrayList<>();
    private final List<String> managed = new ArrayList<>();
    private Section current;
    /** 展开过程中累计的可写行数（判「嵌套组内是否有可写字段」用；随对象整体登记） */
    private int writableRows;

    private SettingsSchema(Object root, String labelKey, boolean editorStartup, String fieldName) {
        this.root = root;
        this.labelKey = labelKey;
        this.editorStartup = editorStartup;
        this.fieldName = fieldName;
    }

    // ── 页面发现 / 装配 ──────────────────────────────────────

    /**
     * 设置根对象 → 页清单（类型贴 {@link Setting} 的字段，声明序）。
     *
     * @param settingsRoot 设置根实例（如 {@code JavelinSetting}）
     */
    public static List<SettingsSchema> pagesOf(Object settingsRoot) {
        return pagesOf(settingsRoot, settingsRoot);
    }

    /**
     * 设置根对象 → 页清单，**按页的生效域分流绑定**：{@code editorStartup = true} 的页绑
     * {@code settingsRoot}（编辑器活实例，改即生效）；{@code editorStartup = false} 的页绑
     * {@code projectRoot} 上同名字段（项目文件那一份）——这类页的值只供打包运行时消费，
     * 编辑只落文件，不碰编辑器活实例（如窗口尺寸/帧率上限）。
     *
     * @param settingsRoot 编辑器活设置（页发现与常规页宿主）
     * @param projectRoot  项目文件设置（只写不读的页宿主）
     */
    public static List<SettingsSchema> pagesOf(Object settingsRoot, Object projectRoot) {
        if (settingsRoot == null || projectRoot == null) {
            throw new IllegalArgumentException("settings root must not be null");
        }
        List<SettingsSchema> pages = new ArrayList<>();
        for (Class<?> c = settingsRoot.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Setting setting = field.getType().getAnnotation(Setting.class);
                if (setting == null) {
                    continue;
                }
                Object host = setting.editorStartup() ? settingsRoot : projectRoot;
                Object pageRoot = InspectorMeta.readProperty(host, field.getName());
                if (pageRoot == null) {
                    throw new IllegalStateException(EditorLang.text("settings.schema.nullPage",
                            field.getName()));
                }
                SettingsSchema page = new SettingsSchema(pageRoot, setting.label(),
                        setting.editorStartup(), field.getName());
                page.expand(pageRoot, 1);
                pages.add(page);
            }
        }
        return pages;
    }

    /** 单个页根对象 → schema（页名取类上的 {@link Setting}） */
    public static SettingsSchema of(Object pageRoot) {
        if (pageRoot == null) {
            throw new IllegalArgumentException("settings page root must not be null");
        }
        Setting setting = pageRoot.getClass().getAnnotation(Setting.class);
        if (setting == null) {
            throw new IllegalStateException(EditorLang.text("settings.schema.notAPage",
                    pageRoot.getClass().getSimpleName()));
        }
        SettingsSchema page = new SettingsSchema(pageRoot, setting.label(),
                setting.editorStartup(), null);
        page.expand(pageRoot, 1);
        return page;
    }

    // ── 读取面 ───────────────────────────────────────────────

    /** 页名 i18n 键（导航项标题） */
    public String labelKey() {
        return labelKey;
    }

    /** 编辑器启动是否从项目文件读入本页（false = 只写不读） */
    public boolean editorStartup() {
        return editorStartup;
    }

    /** 页根在设置根对象上的字段名（{@link #of} 开的单页为 null） */
    public String fieldName() {
        return fieldName;
    }

    /** 页根实例（读写宿主） */
    public Object root() {
        return root;
    }

    /** 分组清单（声明序） */
    public List<Section> sections() {
        return sections;
    }

    /**
     * 本页持久化管理的页根字段名（声明序、去重）——写盘/读盘都按本清单通用拷贝。
     * 贴 {@link SettingRestart} 的字段不在其中（只读展示，不入持久化）。
     */
    public List<String> managedRoots() {
        List<String> unique = new ArrayList<>(managed.size());
        for (String name : managed) {
            if (!unique.contains(name)) {
                unique.add(name);
            }
        }
        return unique;
    }

    /**
     * 按分组恢复默认：组内每个可写行回填为「字段声明初值」。
     *
     * <p>默认值来源 = 该行宿主类的一个新实例（设置类按字段初值构造，{@code read} 的缺键默认
     * 同源）；每次点击新建，避免 {@code Color}/数组这类可变值被回填后被面板改坏原型。
     * {@link SettingRestart} 行（启动期定型、只读）不回填。</p>
     *
     * @param section 目标分组
     */
    public void reset(Section section) {
        for (Entry entry : section.entries) {
            Item item = entry.item;
            if (item == null || item.readOnly) {
                continue;
            }
            Object defaults = newDefaultInstance(item.host.getClass());
            Object value = InspectorMeta.readProperty(defaults, item.fieldName());
            InspectorMeta.writeProperty(item.host, item.fieldName(), value);
        }
    }

    /**
     * 同页刷新：沿用页元数据（页名 / 生效域 / 根字段名）重新展开一份 schema。
     * 恢复默认后重建表单用——行值与 {@link Item#vo} 都按宿主当前值重新派生。
     */
    public SettingsSchema refreshed() {
        SettingsSchema page = new SettingsSchema(root, labelKey, editorStartup, fieldName);
        page.expand(root, 1);
        return page;
    }

    private static Object newDefaultInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(EditorLang.text("settings.reset.defaultsFailed",
                    type.getSimpleName()), e);
        }
    }

    // ── 展开 ─────────────────────────────────────────────────

    /** 按注解展开：行来自 {@code @InsField} 字段，分组来自 {@code @SettingGroup} */
    private void expand(Object host, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Field field : InspectorMeta.fieldsAnnotatedWith(host.getClass(),
                InsField.class, SettingGroup.class)) {
            InspectorMeta.PropMeta prop = InspectorMeta.findPropOrNull(host.getClass(), field.getName());
            if (prop != null) {
                openGroup(field);
                addRow(host, prop, field, depth);
                continue;
            }
            Object nested = InspectorMeta.readProperty(host, field.getName());
            if (nested instanceof Serializable) {
                openGroup(field);
                int rowsBefore = writableRows;
                expand(nested, depth + 1);
                // 页根上的嵌套设置对象整体纳入持久化（登记对象名，而非其内部字段——那些
                // 不在页根上有 getter）；组内一个可写字段都没有（全只读）则不登记自身
                if (depth == 1 && writableRows > rowsBefore) {
                    managed.add(field.getName());
                }
                continue;
            }
            // 已声明却既不映射也不成对象：显式意图不静默吞
            throw new IllegalStateException(EditorLang.text("settings.schema.fieldNotMappable",
                    host.getClass().getSimpleName(), field.getName()));
        }
    }

    /** {@code @SettingGroup} 开新组；未贴则沿用当前组（页面开头时开页面默认组） */
    private void openGroup(Field field) {
        SettingGroup group = field.getAnnotation(SettingGroup.class);
        if (group != null) {
            current = new Section(group.label());
            sections.add(current);
            return;
        }
        if (current == null) {
            current = new Section(labelKey);
            sections.add(current);
        }
    }

    /** 一个设置项行：注解元数据 → 行 VO（下拉逃生口 / 只读）+ 行后提示；只在页根层记持久化 */
    private void addRow(Object host, InspectorMeta.PropMeta prop, Field field, int depth) {
        InspectorFieldVo vo = InspectorMeta.fieldVo(host, prop);
        SettingSelect select = field.getAnnotation(SettingSelect.class);
        if (select != null) {
            if (prop.valueType != InspectorValueType.INT) {
                throw new IllegalStateException(EditorLang.text("settings.schema.selectNeedsInt",
                        host.getClass().getSimpleName(), field.getName()));
            }
            // 值面按 ENUM 通道下发（FieldRowFactory 据此选 JeSelect）；回写仍是原 int setter
            vo.valueType = InspectorValueType.ENUM.name();
            vo.options = selectOptions(select);
        }
        boolean readOnly = field.isAnnotationPresent(SettingRestart.class);
        SettingAsset asset = field.getAnnotation(SettingAsset.class);
        boolean guidAsset = asset != null;
        if (guidAsset) {
            // guid 串字段按资产选择器下发（回写仍是原 String setter，存 guid）
            if (prop.valueType != InspectorValueType.STRING) {
                throw new IllegalStateException(EditorLang.text("settings.schema.assetNeedsString",
                        host.getClass().getSimpleName(), field.getName()));
            }
            vo.valueType = InspectorValueType.GUID_ASSET.name();
            vo.assetType = asset.value();
        }
        current.entries.add(Entry.row(new Item(host, prop, vo, readOnly, select != null, guidAsset)));
        if (!readOnly) {
            writableRows++;
            if (depth == 1) {
                managed.add(prop.fieldName);
            }
        }
        SettingHint hint = field.getAnnotation(SettingHint.class);
        if (hint != null) {
            current.entries.add(Entry.hint(hint.value()));
        }
    }

    /** 显式下拉选项（value = 值串，label = labelPrefix + "." + value 经语言包解析） */
    private static List<InspectorFieldVo.Option> selectOptions(SettingSelect select) {
        int[] values = select.values();
        List<InspectorFieldVo.Option> options = new ArrayList<>(values.length);
        for (int value : values) {
            InspectorFieldVo.Option option = new InspectorFieldVo.Option();
            option.value = String.valueOf(value);
            option.label = EditorLang.text(select.labelPrefix() + "." + value);
            options.add(option);
        }
        return options;
    }

    // ── 模型 ─────────────────────────────────────────────────

    /** 分组：标题键 + 行清单（字段行 / 提示行） */
    public static final class Section {
        public final String labelKey;
        public final List<Entry> entries = new ArrayList<>();

        private Section(String labelKey) {
            this.labelKey = labelKey;
        }

        /** 组内是否有可写行（全只读的组不给「恢复默认」按钮） */
        public boolean hasWritable() {
            for (Entry entry : entries) {
                if (entry.item != null && !entry.item.readOnly) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 分组内一行：字段行（item 非空）或提示行（hintKey 非空） */
    public static final class Entry {
        public final Item item;
        public final String hintKey;

        private Entry(Item item, String hintKey) {
            this.item = item;
            this.hintKey = hintKey;
        }

        static Entry row(Item item) {
            return new Entry(item, null);
        }

        static Entry hint(String key) {
            return new Entry(null, key);
        }
    }

    /** 设置项：宿主 + 字段元数据 + 行 VO（值/范围/选项）+ 只读标记 */
    public static final class Item {
        /** 字段宿主（页根对象或其嵌套设置对象） */
        public final Object host;
        /** 字段元数据（getter/setter/值类型/范围/选项） */
        public final InspectorMeta.PropMeta prop;
        /** 行 VO（FieldRowFactory 消费：值串/范围/选项/值类型） */
        public final InspectorFieldVo vo;
        /** 只读（{@link SettingRestart}：启动期定型） */
        public final boolean readOnly;
        /** 显式下拉逃生口（回写走 int setter，而非值类型通道） */
        private final boolean explicitSelect;
        /** 资产 guid 串行（{@link SettingAsset}：wire 是 guid，None 清空为 null） */
        private final boolean guidAsset;

        private Item(Object host, InspectorMeta.PropMeta prop, InspectorFieldVo vo,
                     boolean readOnly, boolean explicitSelect, boolean guidAsset) {
            this.host = host;
            this.prop = prop;
            this.vo = vo;
            this.readOnly = readOnly;
            this.explicitSelect = explicitSelect;
            this.guidAsset = guidAsset;
        }

        /** 字段名（宿主上的声明名；断言与排障用） */
        public String fieldName() {
            return prop.fieldName;
        }

        /** 回写字段值（wire 串经通道解析后走 setter；解析失败抛 TipException） */
        public void write(String wire) throws TipException {
            if (explicitSelect) {
                prop.set(host, InspectorMeta.parseInt(wire));
                return;
            }
            if (guidAsset) {
                // 资产选择器：wire 即 guid，None/空串 = 清空（字段置 null）
                prop.set(host, wire == null || wire.isBlank() ? null : wire.trim());
                return;
            }
            prop.set(host, InspectorMeta.parseValue(prop, wire));
        }
    }
}
