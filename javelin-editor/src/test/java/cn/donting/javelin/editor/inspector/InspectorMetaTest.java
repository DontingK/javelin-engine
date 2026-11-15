package cn.donting.javelin.editor.inspector;

import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFieldGroup;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsIntRange;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.settings.ShadowQuality;
import cn.donting.javelin.core.settings.SsaoSettings;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.plugin.EditorLangTestBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InspectorMeta} 单测：字段清单/通道映射/范围/枚举选项、严格 vs 宽容两种口径、
 * wire 值序列化与回解、setter 重载按字段类型解析、通用属性拷贝。
 *
 * <p>本类同时是 Inspector 反射层的回归网（2026-11-14 从 {@code InspectorReflector}
 * 提取公共件时补齐——该层此前无单测）。文案经 EditorLang 解析，故装 zh_CN 语言包。</p>
 */
public class InspectorMetaTest extends EditorLangTestBase {

    /** 测试夹具：覆盖各通道 + 一个不可映射的嵌套设置对象（strict 抛 / lenient 跳） */
    public static final class Fixture {
        @InsField(label = "test.intField")
        @InsIntRange(min = 1, max = 8, step = 2)
        private int count = 3;
        @InsField(label = "test.floatField")
        @InsFloatRange(min = 0f, max = 2f, step = 0.25f)
        private float ratio = 0.5f;
        @InsField(label = "test.boolField")
        private boolean enabled = true;
        @InsField(label = "test.enumField")
        private ShadowQuality quality = ShadowQuality.SOFT_LOW;
        @InsField(label = "test.stringField")
        private String title = "hi";
        @InsField(label = "test.colorField")
        private Color tint = new Color(0.1f, 0.2f, 0.3f, 1.0f);
        @InsField(label = "test.nested")
        private SsaoSettings nested = new SsaoSettings();

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public float getRatio() {
            return ratio;
        }

        public void setRatio(float ratio) {
            this.ratio = ratio;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ShadowQuality getQuality() {
            return quality;
        }

        public void setQuality(ShadowQuality quality) {
            this.quality = quality;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Color getTint() {
            return tint;
        }

        public void setTint(Color tint) {
            this.tint = tint;
        }

        public SsaoSettings getNested() {
            return nested;
        }

        public void setNested(SsaoSettings nested) {
            this.nested = nested;
        }
    }

    /** 重载 setter 夹具：字符串重载若被误命中会写 -1，可判 0/1 命中（findSetter 口径） */
    public static final class Overloaded {
        @InsField(label = "test.maskField")
        private int mask = 0;

        public int getMask() {
            return mask;
        }

        public void setMask(int mask) {
            this.mask = mask;
        }

        @SuppressWarnings("unused")
        public void setMask(String mask) {
            this.mask = -1;
        }
    }

    /** 缺 setter 夹具：accessor 缺失是真 bug，两种口径都抛 */
    public static final class MissingAccessor {
        @InsField(label = "test.orphanField")
        private float orphan = 1f;

        public float getOrphan() {
            return orphan;
        }
    }

    @Test
    public void mapsChannelsAndRanges() {
        List<InspectorMeta.PropMeta> props = InspectorMeta.propsOf(Fixture.class, false);
        assertEquals(6, props.size(), "不可映射的嵌套设置对象应被跳过");

        InspectorMeta.PropMeta count = InspectorMeta.findPropOrNull(Fixture.class, "count");
        assertNotNull(count);
        assertEquals(InspectorValueType.INT, count.valueType);
        assertEquals(1f, count.range.min, 1e-6f);
        assertEquals(8f, count.range.max, 1e-6f);
        assertEquals(2f, count.range.step, 1e-6f);

        InspectorMeta.PropMeta ratio = InspectorMeta.findPropOrNull(Fixture.class, "ratio");
        assertEquals(InspectorValueType.FLOAT, ratio.valueType);
        assertEquals(0.25f, ratio.range.step, 1e-6f);

        assertEquals(InspectorValueType.BOOLEAN,
                InspectorMeta.findPropOrNull(Fixture.class, "enabled").valueType);
        assertEquals(InspectorValueType.STRING,
                InspectorMeta.findPropOrNull(Fixture.class, "title").valueType);
        assertEquals(InspectorValueType.COLOR,
                InspectorMeta.findPropOrNull(Fixture.class, "tint").valueType);
        assertNull(InspectorMeta.findPropOrNull(Fixture.class, "nested"),
                "不可映射字段在宽容口径下不入清单");
    }

    /** 枚举选项随元数据下发，显示名走 InsEnumLabel（编辑器侧解析 i18n 键） */
    @Test
    public void enumOptionsCarryTranslatedLabels() {
        InspectorMeta.PropMeta quality = InspectorMeta.findPropOrNull(Fixture.class, "quality");
        assertEquals(InspectorValueType.ENUM, quality.valueType);
        assertEquals(3, quality.options.size());
        assertEquals("HARD", quality.options.get(0).value);
        assertEquals(EditorLang.text("projectSettings.shadowQuality.hard"), quality.options.get(0).label);
        assertEquals(ShadowQuality.class, quality.enumType);
    }

    /** 声明序即行序（设置面板/检查器同口径） */
    @Test
    public void annotatedFieldsKeepDeclarationOrder() {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.Field field : InspectorMeta.annotatedFields(Fixture.class)) {
            names.add(field.getName());
        }
        assertEquals(List.of("count", "ratio", "enabled", "quality", "title", "tint", "nested"), names);
    }

    /** 严格口径（检查器）：已贴 @InsField 但类型不可映射即抛——显式意图不静默吞 */
    @Test
    public void strictModeThrowsOnUnmappableField() {
        assertThrows(IllegalStateException.class, () -> InspectorMeta.propsOf(Fixture.class, true));
    }

    /** accessor 缺失两种口径都抛（真 bug，不是可跳过的类型问题） */
    @Test
    public void missingAccessorAlwaysThrows() {
        assertThrows(IllegalStateException.class, () -> InspectorMeta.propsOf(MissingAccessor.class, false));
        assertThrows(IllegalStateException.class, () -> InspectorMeta.propsOf(MissingAccessor.class, true));
    }

    /** setter 同名重载按字段类型装箱等价解析（不随机命中） */
    @Test
    public void overloadedSetterResolvedByFieldType() throws TipException {
        Overloaded host = new Overloaded();
        InspectorMeta.PropMeta mask = InspectorMeta.findPropOrNull(Overloaded.class, "mask");
        mask.set(host, InspectorMeta.parseValue(mask, "7"));
        assertEquals(7, host.getMask());
    }

    /** wire 值往返：各通道 serialize → parseValue 回到等价对象 */
    @Test
    public void wireRoundTripPerChannel() throws TipException {
        Fixture host = new Fixture();
        assertEquals("0.5", InspectorMeta.serialize(host.getRatio(),
                InspectorMeta.findPropOrNull(Fixture.class, "ratio")));
        assertEquals("0.5", InspectorMeta.fieldVo(host,
                InspectorMeta.findPropOrNull(Fixture.class, "ratio")).value);

        InspectorMeta.PropMeta count = InspectorMeta.findPropOrNull(Fixture.class, "count");
        Object parsed = InspectorMeta.parseValue(count, "6");
        assertEquals(6, (Integer) parsed);

        InspectorMeta.PropMeta enabled = InspectorMeta.findPropOrNull(Fixture.class, "enabled");
        assertEquals(Boolean.TRUE, InspectorMeta.parseValue(enabled, "true"));
        assertThrows(TipException.class, () -> InspectorMeta.parseValue(enabled, "yes"));

        InspectorMeta.PropMeta quality = InspectorMeta.findPropOrNull(Fixture.class, "quality");
        assertEquals(ShadowQuality.SOFT_HIGH, InspectorMeta.parseValue(quality, "SOFT_HIGH"));
        assertThrows(TipException.class, () -> InspectorMeta.parseValue(quality, "NOPE"));

        InspectorMeta.PropMeta tint = InspectorMeta.findPropOrNull(Fixture.class, "tint");
        assertEquals("0.1,0.2,0.3,1", InspectorMeta.serialize(host.getTint(), tint));
        Color parsedColor = (Color) InspectorMeta.parseValue(tint, "0.4,0.5,0.6,1");
        assertEquals(0.4f, parsedColor.r, 1e-6f);
        assertEquals(0.6f, parsedColor.b, 1e-6f);

        InspectorMeta.PropMeta title = InspectorMeta.findPropOrNull(Fixture.class, "title");
        assertEquals("hello", InspectorMeta.parseValue(title, "hello"));
    }

    /** 未知字段名查元数据抛（回写侧口径）；宽容查询返 null */
    @Test
    public void unknownFieldLookupBehaviour() {
        assertThrows(TipException.class, () -> InspectorMeta.findProp(Fixture.class, "nope"));
        assertNull(InspectorMeta.findPropOrNull(Fixture.class, "nope"));
    }

    /** 通用属性读写（持久化 load-modify-save 用）：getter 读、setter 写、装箱等价匹配 */
    @Test
    public void readWritePropertyCopiesValues() {
        Fixture source = new Fixture();
        source.setCount(5);
        source.setTitle("copied");
        Fixture target = new Fixture();

        InspectorMeta.writeProperty(target, "count", InspectorMeta.readProperty(source, "count"));
        InspectorMeta.writeProperty(target, "title", InspectorMeta.readProperty(source, "title"));
        InspectorMeta.writeProperty(target, "nested", InspectorMeta.readProperty(source, "nested"));

        assertEquals(5, target.getCount());
        assertEquals("copied", target.getTitle());
        assertEquals(source.getNested(), target.getNested(), "嵌套设置对象按引用拷贝（同一实例语义）");

        assertThrows(IllegalStateException.class,
                () -> InspectorMeta.readProperty(target, "noSuchProperty"));
    }

    /** 缺 getter 的声明字段（嵌套对象路径同理）即抛，不静默返 null */
    @Test
    public void readPropertyWithoutGetterThrows() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> InspectorMeta.readProperty(new Fixture(), "bogus"))
                .getMessage().contains("bogus"));
    }

    // ── @InsFieldGroup 条件子组 ─────────────────────────────

    /** 条件子组夹具：枚举控制器 + 嵌套参数容器（容器自身无 @InsField，只在命中时内联） */
    public static final class Grouped {

        public static final class Sub {
            @InsField(label = "test.subA")
            @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
            private float a = 0.25f;

            public float getA() {
                return a;
            }

            public void setA(float a) {
                this.a = a;
            }
        }

        @InsField(label = "test.mode")
        @InsFieldGroup(keys = {"HARD", "SOFT_HIGH"}, value = {"", "sub"})
        private ShadowQuality mode = ShadowQuality.HARD;

        private Sub sub = new Sub();

        public ShadowQuality getMode() {
            return mode;
        }

        public void setMode(ShadowQuality mode) {
            this.mode = mode;
        }

        public Sub getSub() {
            return sub;
        }

        public void setSub(Sub sub) {
            this.sub = sub;
        }
    }

    /** 长度不等的注解声明即构建期抛错（显式意图不静默吞） */
    public static final class BadGroup {
        @InsField(label = "test.badMode")
        @InsFieldGroup(keys = {"HARD"}, value = {})
        private ShadowQuality mode = ShadowQuality.HARD;

        public ShadowQuality getMode() {
            return mode;
        }

        public void setMode(ShadowQuality mode) {
            this.mode = mode;
        }
    }

    /** 带折叠组头的条件子组夹具（label i18n 键 + 默认收起） */
    public static final class LabeledGroup {

        public static final class Sub {
            @InsField(label = "test.labSubA")
            @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
            private float a = 1f;

            public float getA() {
                return a;
            }

            public void setA(float a) {
                this.a = a;
            }
        }

        @InsField(label = "test.labMode")
        @InsFieldGroup(keys = {"HARD"}, value = {"sub"}, label = "test.groupLabelKey",
                collapsed = true)
        private ShadowQuality mode = ShadowQuality.HARD;

        private Sub sub = new Sub();

        public ShadowQuality getMode() {
            return mode;
        }

        public void setMode(ShadowQuality mode) {
            this.mode = mode;
        }

        public Sub getSub() {
            return sub;
        }

        public void setSub(Sub sub) {
            this.sub = sub;
        }
    }

    /** 滑条标记贴到非 FLOAT 字段即构建期抛（滑条提交浮点串，INT 回解必失败） */
    public static final class SliderOnInt {
        @InsField(label = "test.sliderInt")
        @InsIntRange(min = 0, max = 8, step = 1)
        @cn.donting.javelin.core.inspector.InsSlider
        private int amount = 1;

        public int getAmount() {
            return amount;
        }

        public void setAmount(int amount) {
            this.amount = amount;
        }
    }

    /** 子组字段以「容器.字段」并入清单，携带控制字段与显示条件值 */
    @Test
    public void groupFieldsCarryParentAndShowKeys() {
        InspectorMeta.PropMeta subA = InspectorMeta.findPropOrNull(Grouped.class, "sub.a");
        assertNotNull(subA, "条件子组的嵌套字段应并入清单");
        assertEquals(InspectorValueType.FLOAT, subA.valueType);
        assertEquals("mode", subA.parentField);
        assertEquals(List.of("SOFT_HIGH"), subA.showKeys);
        assertEquals("sub", subA.hostPath);

        // 容器本身无 @InsField → 不产生独立行
        assertNull(InspectorMeta.findPropOrNull(Grouped.class, "sub"));
    }

    /** 嵌套字段读写走容器实例（get/set 同一条路径） */
    @Test
    public void groupFieldReadWriteTraversesContainer() throws TipException {
        Grouped host = new Grouped();
        InspectorMeta.PropMeta subA = InspectorMeta.findProp(Grouped.class, "sub.a");
        assertEquals(0.25f, (Float) subA.get(host), 1e-6f);
        subA.set(host, InspectorMeta.parseValue(subA, "0.6"));
        assertEquals(0.6f, host.getSub().getA(), 1e-6f);
    }

    /** 容器为空是异常态（不静默返 null / 不静默丢弃写入） */
    @Test
    public void nullContainerThrows() {
        Grouped host = new Grouped();
        host.setSub(null);
        InspectorMeta.PropMeta subA = InspectorMeta.findPropOrNull(Grouped.class, "sub.a");
        assertThrows(IllegalStateException.class, () -> subA.get(host));
    }

    /** keys/value 长度不一致 → 构建期抛 */
    @Test
    public void groupKeyValueLengthMismatchThrows() {
        assertThrows(IllegalStateException.class, () -> InspectorMeta.propsOf(BadGroup.class, false));
    }

    /** 组头文案与初始收起态随子字段下发；未给 label 的组保持平铺（groupLabel=null） */
    @Test
    public void groupHeaderLabelAndCollapsedFlow() {
        InspectorMeta.PropMeta labeled = InspectorMeta.findPropOrNull(LabeledGroup.class, "sub.a");
        assertNotNull(labeled);
        assertEquals(EditorLang.text("test.groupLabelKey"), labeled.groupLabel);
        assertTrue(labeled.groupCollapsed);

        InspectorMeta.PropMeta plain = InspectorMeta.findPropOrNull(Grouped.class, "sub.a");
        assertNull(plain.groupLabel, "未给 label 的子组不渲染组头（平铺）");
        assertFalse(plain.groupCollapsed);
    }

    /** @InsSlider 只允许 FLOAT（贴 INT 即构建期抛） */
    @Test
    public void sliderOnNonFloatThrows() {
        assertThrows(IllegalStateException.class,
                () -> InspectorMeta.propsOf(SliderOnInt.class, false));
    }
}
