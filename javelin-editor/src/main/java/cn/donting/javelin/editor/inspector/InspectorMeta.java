package cn.donting.javelin.editor.inspector;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.inspector.InsEnumLabel;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFieldGroup;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsIntRange;
import cn.donting.javelin.core.inspector.InsLayerMask;
import cn.donting.javelin.core.inspector.InsRotation;
import cn.donting.javelin.core.inspector.InsShadowResolution;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.inspector.InsTagList;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.math.Rect;
import cn.donting.javelin.core.scene.Layer;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.plugin.EditorLang;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可检查属性元数据：类 → 字段清单（通道映射 + getter/setter）+ wire 值读写。
 *
 * <p>这一层是 {@link InspectorReflector} 与设置面板（{@code SettingsSchema}）的共用底座——
 * 「@InsField 闸门 → 值类型通道 → 控件选型」只有一份映射表，两个消费方各自装配界面，
 * 不再各写一张。读走 getter、写走 setter 的铁律在此单点兑现（裸 Field.set 会绕过
 * Transform 的脏传播，见 {@link InspectorReflector} 类注释）。</p>
 *
 * <p><b>两种严格度</b>：{@code strict=true}（检查器口径）——已贴 @InsField 但类型不可
 * 映射时构建即抛错（显式意图不静默吞）；{@code strict=false}（设置面板口径）——不可映射
 * 字段跳过返回 null，因设置类里的嵌套设置对象字段本就靠这条路径识别为「子组」。
 * getter/setter 缺失在两种口径下都抛（是真 bug，不是可跳过的类型问题）。</p>
 *
 * @author javelin
 * @create 2026/11/14
 **/
public final class InspectorMeta {

    /** Layer 注册表长度（Layer.of 索引合法区间 [0, COUNT)） */
    private static final int LAYER_COUNT = Layer.COUNT;

    /** 浮点显示精度：最多 4 位小数（与前端 JeNumberInput precision 默认值同口径） */
    private static final int DISPLAY_DECIMALS = 4;

    /** 条件字段组最大嵌套深度（@InsFieldGroup 容器可再套容器；防自引用爆栈） */
    private static final int MAX_GROUP_DEPTH = 3;

    /** 嵌套字段路径分隔符（fieldName = "容器.子字段"，回写定位与 propsOf 查找同用） */
    public static final char NESTED_SEPARATOR = '.';

    /** 字段元数据缓存（类 → 属性清单；类结构运行期不变，首次访问构建一次）。严格/宽容各一份 */
    private static final ConcurrentHashMap<Class<?>, List<PropMeta>> STRICT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, List<PropMeta>> LENIENT_CACHE = new ConcurrentHashMap<>();

    private InspectorMeta() {
    }

    // ── 字段清单 ─────────────────────────────────────────────

    /** 类 → 可检查属性清单（检查器口径：不可映射即抛） */
    public static List<PropMeta> propsOf(Class<?> clazz) {
        return propsOf(clazz, true);
    }

    /**
     * 类 → 可检查属性清单：沿类层级收集声明字段，@InsField 标记的才进清单。
     *
     * @param strict true = 已标记但类型不可映射即抛错；false = 跳过（设置面板口径）
     */
    public static List<PropMeta> propsOf(Class<?> clazz, boolean strict) {
        ConcurrentHashMap<Class<?>, List<PropMeta>> cache = strict ? STRICT_CACHE : LENIENT_CACHE;
        return cache.computeIfAbsent(clazz, c -> buildProps(c, strict, "", null, null, null, null,
                false, 0));
    }

    /**
     * 字段清单构建（含 {@code @InsFieldGroup} 条件子组的递归展开）。
     *
     * @param prefix      嵌套字段名前缀（顶层空串；子组为 {@code "容器."}）
     * @param hostPath    嵌套容器字段路径（顶层 null = 宿主自身）
     * @param parentField 控制本组的字段名（顶层/非分组 null）
     * @param showKeys    显示条件值集合（非分组 null）
     * @param depth       嵌套深度（防 @InsFieldGroup 自引用）
     */
    private static List<PropMeta> buildProps(Class<?> clazz, boolean strict, String prefix,
                                             String hostPath, String parentField,
                                             List<String> showKeys, String groupLabel,
                                             boolean groupCollapsed, int depth) {
        if (depth > MAX_GROUP_DEPTH) {
            throw new IllegalStateException(EditorLang.text("inspector.build.groupDepthExceeded",
                    clazz.getSimpleName(), MAX_GROUP_DEPTH));
        }
        Map<String, Method> zeroParam = new HashMap<>();
        Map<String, List<Method>> oneParam = new HashMap<>();
        for (Method method : clazz.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0) {
                zeroParam.putIfAbsent(method.getName(), method);
            } else if (params.length == 1) {
                oneParam.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(method);
            }
        }
        List<PropMeta> props = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                PropMeta prop = buildProp(field, zeroParam, oneParam, strict, prefix,
                        hostPath, parentField, showKeys, groupLabel, groupCollapsed);
                if (prop != null) {
                    props.add(prop);
                }
                InsFieldGroup group = field.getAnnotation(InsFieldGroup.class);
                if (group != null) {
                    // 控制字段行之后紧跟其条件子组（声明序即面板序）
                    props.addAll(groupedProps(clazz, field, group, strict, prefix, hostPath, depth));
                }
            }
        }
        return props;
    }

    /**
     * {@code @InsFieldGroup} 展开：keys[i] 命中 → 显示 value[i] 指定的同宿主字段
     * （必须是嵌套对象容器，其内部 {@code @InsField} 字段成为子行；空串 = 不显示）。
     */
    private static List<PropMeta> groupedProps(Class<?> clazz, Field controller, InsFieldGroup group,
                                               boolean strict, String prefix, String hostPath, int depth) {
        String owner = clazz.getSimpleName() + "." + controller.getName();
        String[] keys = group.keys();
        String[] values = group.value();
        if (keys.length != values.length) {
            throw new IllegalStateException(EditorLang.text("inspector.build.groupLengthMismatch",
                    owner, keys.length, values.length));
        }
        // 同一容器可被多个 key 触发：按容器聚合显示条件
        Map<String, List<String>> byContainer = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            String container = values[i];
            if (container == null || container.isBlank()) {
                continue;
            }
            byContainer.computeIfAbsent(container.trim(), k -> new ArrayList<>()).add(keys[i]);
        }
        List<PropMeta> props = new ArrayList<>();
        String parentFieldName = prefix + controller.getName();
        // 组头（label 空 = 不渲染组头、子行平铺）
        String groupLabel = group.label().isBlank() ? null : EditorLang.text(group.label());
        for (Map.Entry<String, List<String>> entry : byContainer.entrySet()) {
            Field container = findField(clazz, entry.getKey());
            if (container == null) {
                throw new IllegalStateException(EditorLang.text("inspector.build.groupContainerMissing",
                        owner, entry.getKey()));
            }
            String childHostPath = hostPath == null
                    ? container.getName()
                    : hostPath + NESTED_SEPARATOR + container.getName();
            props.addAll(buildProps(container.getType(), strict, childHostPath + NESTED_SEPARATOR,
                    childHostPath, parentFieldName, entry.getValue(), groupLabel, group.collapsed(),
                    depth + 1));
        }
        return props;
    }

    /** 沿类层级按名查找声明字段（不存在返回 null） */
    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * 带任意指定注解的声明字段（声明序，父类在前；静态/synthetic 跳过）。
     *
     * @param annotations 命中任一即收（设置面板用 {@code InsField + SettingGroup} 的并集）
     */
    @SafeVarargs
    public static List<Field> fieldsAnnotatedWith(Class<?> clazz,
                                                  Class<? extends Annotation>... annotations) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                for (Class<? extends Annotation> annotation : annotations) {
                    if (field.isAnnotationPresent(annotation)) {
                        fields.add(field);
                        break;
                    }
                }
            }
        }
        return fields;
    }

    /**
     * 带 @InsField 的声明字段（声明序，父类在前；静态/synthetic 跳过）——
     * 供检查器判定「标量行 / 嵌套设置对象（子组）」。
     */
    public static List<Field> annotatedFields(Class<?> clazz) {
        return fieldsAnnotatedWith(clazz, InsField.class);
    }

    private static PropMeta buildProp(Field field, Map<String, Method> zeroParam,
                                      Map<String, List<Method>> oneParam, boolean strict,
                                      String prefix, String hostPath, String parentField,
                                      List<String> showKeys, String groupLabel,
                                      boolean groupCollapsed) {
        if (!field.isAnnotationPresent(InsField.class)) {
            return null;
        }
        String owner = field.getDeclaringClass().getSimpleName() + "." + field.getName();
        InspectorValueType type = mapValueType(field);
        if (type == null) {
            if (!strict) {
                return null;
            }
            throw new IllegalStateException(EditorLang.text("inspector.build.unmappableType",
                    owner, field.getType().getSimpleName()));
        }
        String cap = capitalize(field.getName());
        Method getter = zeroParam.containsKey("get" + cap) ? zeroParam.get("get" + cap)
                : zeroParam.get("is" + cap);
        // 列表通道 setter 放宽：参数 List 可从字段类型赋值（setMaterials(List) ← ArrayList 字段；
        // STRING_LIST 的 List 字段同理，数组字段仍走精确匹配）
        Method setter = type == InspectorValueType.GUID_ASSET_LIST
                || (type == InspectorValueType.STRING_LIST && !field.getType().isArray())
                ? findListSetter(oneParam.get("set" + cap), field.getType())
                : findSetter(oneParam.get("set" + cap), field.getType());
        if (getter == null || setter == null) {
            throw new IllegalStateException(EditorLang.text("inspector.build.missingAccessors", owner));
        }
        // @InsSlider 只支持 FLOAT（滑条提交浮点串，INT 字段回解必失败 → 配置错误当场抛）
        if (field.isAnnotationPresent(InsSlider.class) && type != InspectorValueType.FLOAT) {
            throw new IllegalStateException(EditorLang.text("inspector.build.sliderNotFloat", owner));
        }
        Class<?> enumType = null;
        List<InspectorFieldVo.Option> options = null;
        if (type == InspectorValueType.ENUM) {
            enumType = field.getType();
            options = new ArrayList<>();
            for (Object constant : enumType.getEnumConstants()) {
                InspectorFieldVo.Option option = new InspectorFieldVo.Option();
                option.value = ((Enum<?>) constant).name();
                option.label = enumLabel((Enum<?>) constant);
                options.add(option);
            }
        } else if (type == InspectorValueType.LAYER_MASK) {
            // 层名随 VO 下发（M4 勾选式弹层需要；value=层索引串、label=层名，
            // 空槽跳过——下拉只显示有名槽位，layerNames 同口径）
            options = new ArrayList<>();
            List<String> names = layerNames();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (!name.isBlank()) {
                    InspectorFieldVo.Option option = new InspectorFieldVo.Option();
                    option.value = String.valueOf(i);
                    option.label = name;
                    options.add(option);
                }
            }
        }
        // GUID_ASSET/GUID_ASSET_LIST 声明类型随元数据携带（AssetInput 接受范围与回写
        // loadGuid 类型；asSubclass 安全：mapValueType 已验 Asset 可赋型）
        Class<? extends Asset> assetType = type == InspectorValueType.GUID_ASSET
                ? field.getType().asSubclass(Asset.class)
                : type == InspectorValueType.GUID_ASSET_LIST ? assetListElementType(field) : null;
        return new PropMeta(prefix + field.getName(), displayName(field), type, getter, setter,
                rangeOf(field), enumType, options, assetType, field.getType(),
                !field.isAnnotationPresent(InsTagList.class), hostPath, parentField, showKeys,
                field.isAnnotationPresent(InsSlider.class), groupLabel, groupCollapsed);
    }

    /** 按字段名查元数据（未贴/未映射/不存在返回 null） */
    public static PropMeta findPropOrNull(Class<?> clazz, String fieldName) {
        for (PropMeta prop : propsOf(clazz, false)) {
            if (prop.fieldName.equals(fieldName)) {
                return prop;
            }
        }
        return null;
    }

    /** 按字段名查元数据（缺失抛 TipException，回写侧口径） */
    public static PropMeta findProp(Class<?> clazz, String fieldName) throws TipException {
        PropMeta prop = findPropOrNull(clazz, fieldName);
        if (prop == null) {
            throw new TipException(EditorLang.key("inspector.apply.propNotFound",
                    clazz.getSimpleName(), fieldName));
        }
        return prop;
    }

    /**
     * 按字段名 getter 读属性（读走 getter 铁律；嵌套设置对象也走这条路径取实例）。
     * 无零参 getter 或调用失败即抛——已声明的字段缺访问器是真 bug，不静默吞。
     */
    public static Object readProperty(Object host, String fieldName) {
        String cap = capitalize(fieldName);
        for (Method method : host.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && (method.getName().equals("get" + cap) || method.getName().equals("is" + cap))) {
                try {
                    return method.invoke(host);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException(EditorLang.text("inspector.reflect.readFailed",
                            fieldName), e);
                }
            }
        }
        throw new IllegalStateException(EditorLang.text("inspector.reflect.readFailed", fieldName));
    }

    /**
     * 按点路径 getter 读属性（{@code "taaSetting.sharpenStrength"}）：逐段走
     * {@link #readProperty}，中途实例为 null 即抛（容器为空是真异常，不静默兜底）。
     */
    public static Object readPath(Object host, String path) {
        Object node = host;
        for (String segment : path.split("\\" + NESTED_SEPARATOR)) {
            node = node != null ? readProperty(node, segment) : null;
            if (node == null) {
                throw new IllegalStateException(EditorLang.text("inspector.reflect.nestedNull", path));
            }
        }
        return node;
    }

    /**
     * 按字段名 setter 写属性（持久化通用拷贝用）：同名重载按值型装箱等价匹配
     * （同 {@code findSetter} 口径）；无匹配 setter 即抛。
     */
    public static void writeProperty(Object host, String fieldName, Object value) {
        String cap = capitalize(fieldName);
        Class<?> target = value != null ? wrap(value.getClass()) : null;
        for (Method method : host.getClass().getMethods()) {
            if (method.getParameterCount() != 1 || !method.getName().equals("set" + cap)) {
                continue;
            }
            if (target != null && wrap(method.getParameterTypes()[0]) != target) {
                continue;
            }
            try {
                method.invoke(host, value);
                return;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(EditorLang.text("inspector.reflect.writeFailed",
                        fieldName), e);
            }
        }
        throw new IllegalStateException(EditorLang.text("inspector.reflect.writeFailed", fieldName));
    }

    // ── VO / wire 值 ─────────────────────────────────────────

    /** 属性元数据 + 宿主 → 字段行 VO（值/范围/选项/资产显示名） */
    public static InspectorFieldVo fieldVo(Object host, PropMeta prop) {
        InspectorFieldVo vo = new InspectorFieldVo();
        vo.fieldName = prop.fieldName;
        vo.name = prop.displayName;
        vo.valueType = prop.valueType.name();
        Object current = prop.get(host);
        vo.value = serialize(current, prop);
        if (prop.valueType == InspectorValueType.GUID_ASSET) {
            vo.assetName = guidAssetLabel(current);
            vo.assetType = prop.assetType;
        }
        if (prop.valueType == InspectorValueType.STRING_LIST) {
            // List 型可增删，数组型定长（层号不可移位）；@InsTagList = 只增删不改名
            vo.listResizable = !prop.declaredType.isArray();
            vo.listRenamable = prop.listRenamable;
        }
        vo.range = prop.range;
        vo.options = prop.options;
        // 条件子组（@InsFieldGroup）：控件层据此按父字段当前值决定显隐
        vo.parentField = prop.parentField;
        vo.showKeys = prop.showKeys;
        vo.slider = prop.slider;
        vo.groupLabel = prop.groupLabel;
        vo.groupCollapsed = prop.groupCollapsed;
        return vo;
    }

    /** 值 → 序列化字符串（valueType 决定格式；null 原样传 null，RECT 例外见内） */
    public static String serialize(Object value, PropMeta prop) {
        if (value == null) {
            // RECT 可空遗留（旧档 viewportRect null=全屏，新相机默认已显式 (0,0,1,1)）：
            // 显示代理等价值而非 null——控件兜底是全 0 会误读，编辑即物化显式值
            if (prop.valueType == InspectorValueType.RECT) {
                return "0,0,1,1";
            }
            return null;
        }
        switch (prop.valueType) {
            case STRING:
                return (String) value;
            case SHADOW_RESOLUTION:
                // 值面与 INT 同构（像素值串）；复合控件只是展示层投影
            case INT:
                return value instanceof Layer ? String.valueOf(((Layer) value).getValue())
                        : String.valueOf(value);
            case FLOAT:
                return formatFloat(((Number) value).floatValue());
            case BOOLEAN:
                return String.valueOf(value);
            case VEC3_F:
                Vector3f v = (Vector3f) value;
                return formatFloat(v.x) + "," + formatFloat(v.y) + "," + formatFloat(v.z);
            case ROTATION:
                float[] euler = RotationMath.toDegrees((Quaternionf) value);
                return formatFloat(euler[0]) + "," + formatFloat(euler[1]) + "," + formatFloat(euler[2]);
            case ENUM:
                return ((Enum<?>) value).name();
            case COLOR:
                Color color = (Color) value;
                return formatFloat(color.r) + "," + formatFloat(color.g) + ","
                        + formatFloat(color.b) + "," + formatFloat(color.a);
            case RECT:
                Rect rect = (Rect) value;
                return formatFloat(rect.x) + "," + formatFloat(rect.y) + ","
                        + formatFloat(rect.width) + "," + formatFloat(rect.height);
            case LAYER_MASK:
                return String.valueOf(value);
            case GUID_ASSET:
                // 三态：字段无资产→null（前端 "None"）；guid 空（内存创建）→空串
                // （前端禁用态标记，不可回写）；有 guid→guid 原串（显示名另走 assetName）
                return guidWire(value);
            case STRING_LIST:
                // 元素逐行拼接（null 元素按空行）；空列表 → null（区别于「一个空元素」）
                return stringListWire(value);
            default:
                // 暂缓类型不应出现在收集面（类型映射已挡）
                return null;
        }
    }

    /** 值字符串 → 类型对象（按值类型分支；失败抛 TipException） */
    public static Object parseValue(PropMeta prop, String value) throws TipException {
        if (value == null) {
            if (prop.valueType == InspectorValueType.GUID_ASSET) {
                return null; // None=清空合法（Unity 口径：MeshRenderer 材质可空）
            }
            if (prop.valueType == InspectorValueType.STRING_LIST) {
                return emptyStringList(prop); // 空列表合法（标签页可删到空）
            }
            throw new TipException(EditorLang.key("inspector.apply.valueEmpty", prop.fieldName));
        }
        switch (prop.valueType) {
            case STRING:
                return value;
            case BOOLEAN:
                if ("true".equals(value) || "false".equals(value)) {
                    return Boolean.valueOf(value);
                }
                throw new TipException(EditorLang.key("inspector.apply.badBoolean", value));
            case SHADOW_RESOLUTION:
                // 值面与 INT 同构（组件 setter 自带钳制）；回调方是 int setter，Layer 特判不沾
            case INT:
                int i = parseInt(value);
                if (prop.setter.getParameterTypes()[0] == Layer.class) {
                    if (i < 0 || i >= LAYER_COUNT) {
                        throw new TipException(EditorLang.key("inspector.apply.layerIndexOutOfRange", i));
                    }
                    return Layer.of(i);
                }
                return i;
            case FLOAT:
                try {
                    return Float.parseFloat(value.trim());
                } catch (NumberFormatException e) {
                    throw new TipException(EditorLang.key("inspector.apply.badFloat", value));
                }
            case VEC3_F:
                float[] xyz = parseFloats(value, 3);
                return new Vector3f(xyz[0], xyz[1], xyz[2]);
            case ROTATION:
                float[] deg = parseFloats(value, 3);
                return RotationMath.fromDegrees(deg[0], deg[1], deg[2]);
            case ENUM:
                return enumConstant(prop, value);
            case COLOR:
                // Color 构造夹取 [0,1]；可空字段首次编辑即物化显式值
                float[] rgba = parseFloats(value, 4);
                return new Color(rgba[0], rgba[1], rgba[2], rgba[3]);
            case RECT:
                float[] xywh = parseFloats(value, 4);
                return new Rect(xywh[0], xywh[1], xywh[2], xywh[3]);
            case LAYER_MASK:
                return parseInt(value);
            case STRING_LIST:
                // 逐行解析（首尾空行保留：数组型长度即槽位数）；元素去首尾空白
                return parseStringList(prop, value);
            case GUID_ASSET:
                // guid wire → 声明类型实例（loadGuid 共享缓存，同 .scene 序列化口径）；
                // 空串=内存创建不可回写；guid 未索引即资产不存在，拒绝防静默置 null
                String guid = value.trim();
                if (guid.isEmpty()) {
                    throw new TipException(EditorLang.key("inspector.apply.inMemoryAsset", prop.fieldName));
                }
                JavelinEditor editor = JavelinEditor.getInstance();
                Asset asset = editor != null
                        ? editor.getAssetManager().loadGuid(guid, prop.assetType) : null;
                if (asset == null) {
                    throw new TipException(EditorLang.key("inspector.apply.assetNotFound", prop.fieldName));
                }
                return asset;
            default:
                throw new TipException(EditorLang.key("inspector.apply.unsupportedType", prop.valueType));
        }
    }

    public static int parseInt(String value) throws TipException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new TipException(EditorLang.key("inspector.apply.badInt", value));
        }
    }

    /** 枚举常量回解（valueOf 的 IllegalArgumentException 转口径；选项外值即此路径） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(PropMeta prop, String value) throws TipException {
        try {
            return Enum.valueOf((Class) prop.enumType, value.trim());
        } catch (IllegalArgumentException e) {
            throw new TipException(EditorLang.key("inspector.apply.unknownEnumValue",
                    prop.fieldName, value));
        }
    }

    private static float[] parseFloats(String value, int count) throws TipException {
        String[] parts = value.split(",");
        if (parts.length != count) {
            throw new TipException(EditorLang.key("inspector.apply.badComponentCount", count, value));
        }
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new TipException(EditorLang.key("inspector.apply.badComponent", value));
            }
        }
        return result;
    }

    // ── 辅助 ─────────────────────────────────────────────────

    /** 资产 guid wire 三态（GUID_ASSET/GUID_ASSET_LIST 共用）：null→guid 原串，空串=内存创建 */
    public static String guidWire(Object value) {
        String guid = ((Asset) value).getGuid();
        return guid != null && !guid.isBlank() ? guid : "";
    }

    /** 字符串列表 → wire（元素逐行拼接；空列表返回 null，单元素空串仍写成一行） */
    private static String stringListWire(Object value) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (value instanceof String[] array) {
            for (String element : array) {
                if (count++ > 0) {
                    sb.append('\n');
                }
                sb.append(element != null ? element : "");
            }
        } else if (value instanceof List<?> list) {
            for (Object element : list) {
                if (count++ > 0) {
                    sb.append('\n');
                }
                sb.append(element != null ? element : "");
            }
        }
        return count == 0 ? null : sb.toString();
    }

    /** wire → 字符串列表容器（按字段声明类型选 String[] / ArrayList） */
    private static Object parseStringList(PropMeta prop, String value) {
        String[] lines = value.split("\n", -1);
        ArrayList<String> elements = new ArrayList<>(lines.length);
        for (String line : lines) {
            elements.add(line.trim());
        }
        return prop.declaredType.isArray() ? elements.toArray(new String[0]) : elements;
    }

    /** 空字符串列表容器（按字段声明类型） */
    private static Object emptyStringList(PropMeta prop) {
        return prop.declaredType.isArray() ? new String[0] : new ArrayList<String>();
    }

    /**
     * GUID 资产显示名：guid 经 {@link JavelinEditor#getAssetManager()} 反查源路径
     * 取尾名去后缀（Unity 资产名口径）；反查失败兜底 guid 原串。内存创建（guid 空）
     * /非 Asset 值返回 null——内存态文案由前端按 value 空串标记渲染。
     */
    public static String guidAssetLabel(Object value) {
        if (!(value instanceof Asset asset)) {
            return null;
        }
        String guid = asset.getGuid();
        if (guid == null || guid.isBlank()) {
            return null;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        String path = editor != null ? editor.getAssetManager().pathOfGuid(guid) : null;
        if (path == null || path.isBlank()) {
            return guid;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String tail = path.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    /** 浮点显示串：四舍五入到 {@value #DISPLAY_DECIMALS} 位并去尾零（0.30000012 → "0.3"） */
    public static String formatFloat(float value) {
        return new BigDecimal(Float.toString(value))
                .setScale(DISPLAY_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Layer 名列表（Layer 注册表投影，索引=层号；空串=未命名槽位，前端下拉只显示有名槽位） */
    public static List<String> layerNames() {
        List<String> names = new ArrayList<>(LAYER_COUNT);
        for (int i = 0; i < LAYER_COUNT; i++) {
            names.add(Layer.of(i).getName());
        }
        return names;
    }

    /** 枚举显示名：实现 {@link InsEnumLabel} 的 getLabel() 返回 i18n 键经 EditorLang 解析，否则兜底常量名 */
    public static String enumLabel(Enum<?> constant) {
        if (constant instanceof InsEnumLabel labeled) {
            String label = labeled.getLabel();
            if (label != null && !label.isBlank()) {
                return EditorLang.text(label);
            }
        }
        return constant.name();
    }

    private static String displayName(Field field) {
        InsField label = field.getAnnotation(InsField.class);
        if (label != null && !label.label().isBlank()) {
            return EditorLang.text(label.label());
        }
        return field.getName();
    }

    /** 滑条范围（@InsIntRange/@InsFloatRange 投影；null = 无范围约束） */
    private static InspectorFieldVo.Range rangeOf(Field field) {
        InsIntRange intRange = field.getAnnotation(InsIntRange.class);
        if (intRange != null) {
            return range(intRange.min(), intRange.max(), intRange.step());
        }
        InsFloatRange floatRange = field.getAnnotation(InsFloatRange.class);
        if (floatRange != null) {
            return range(floatRange.min(), floatRange.max(), floatRange.step());
        }
        return null;
    }

    private static InspectorFieldVo.Range range(float min, float max, float step) {
        InspectorFieldVo.Range vo = new InspectorFieldVo.Range();
        vo.min = min;
        vo.max = max;
        vo.step = step;
        return vo;
    }

    /** 资产列表通道 setter：参数类型须可从字段类型赋值（List.isAssignableFrom(ArrayList)） */
    private static Method findListSetter(List<Method> candidates, Class<?> fieldType) {
        if (candidates == null) {
            return null;
        }
        for (Method method : candidates) {
            if (method.getParameterTypes()[0].isAssignableFrom(fieldType)) {
                return method;
            }
        }
        return null;
    }

    /**
     * setter 按字段类型解析：同名 setX 可能重载（如 setCullingMask(int) 与
     * setCullingMask(Layer...)），取参数类型与字段类型装箱等价的那个。
     * getMethods 返回顺序不保证——putIfAbsent 式取首个遇重载即随机命中，
     * Camera 接入实锤（cullingMask 双重载）改为显式匹配。零参 getter 无重载
     * （Java 方法不可仅按返回值区分），无需同款解析。
     */
    private static Method findSetter(List<Method> candidates, Class<?> fieldType) {
        if (candidates == null) {
            return null;
        }
        Class<?> target = wrap(fieldType);
        for (Method method : candidates) {
            if (wrap(method.getParameterTypes()[0]) == target) {
                return method;
            }
        }
        return null;
    }

    /** 基本类型装箱等价类（int→Integer 等；引用类型与数组原样返回） */
    private static Class<?> wrap(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    /**
     * 字段类型 → 值类型；不可映射返回 null（即不展示）。
     * Asset 引用型字段（Mesh/Material 等）→ GUID_ASSET（AssetInput 可编辑）；资产列表
     * 字段（List&lt;Asset 子类&gt;，泛型实参判据）→ GUID_ASSET_LIST。暂缓：Collider 组件。
     */
    private static InspectorValueType mapValueType(Field field) {
        Class<?> type = field.getType();
        if (type == int.class || type == Integer.class) {
            // @InsLayerMask 标记的 int 按层位掩码（勾选式多选）展示；@InsShadowResolution
            // 按阴影分辨率复合行（预设档下拉 + Custom 数字联动，Unity 口径）；否则普通整数
            if (field.isAnnotationPresent(InsLayerMask.class)) {
                return InspectorValueType.LAYER_MASK;
            }
            return field.isAnnotationPresent(InsShadowResolution.class)
                    ? InspectorValueType.SHADOW_RESOLUTION
                    : InspectorValueType.INT;
        }
        if (type == float.class || type == Float.class) {
            return InspectorValueType.FLOAT;
        }
        if (type == boolean.class || type == Boolean.class) {
            return InspectorValueType.BOOLEAN;
        }
        if (type == String.class) {
            return InspectorValueType.STRING;
        }
        if (type == Vector3f.class) {
            return InspectorValueType.VEC3_F;
        }
        if (type == Quaternionf.class) {
            // opt-in：仅 @InsRotation 标记的 Quaternionf 按旋转（欧拉角）展示
            return field.isAnnotationPresent(InsRotation.class) ? InspectorValueType.ROTATION : null;
        }
        if (type == Color.class) {
            return InspectorValueType.COLOR;
        }
        if (type == Rect.class) {
            return InspectorValueType.RECT;
        }
        if (type.isEnum()) {
            return InspectorValueType.ENUM;
        }
        if (type == Layer.class) {
            // 节点 layer：wire 为索引 int 字符串，读写经 Layer.of/getValue 换算
            return InspectorValueType.INT;
        }
        if (type == ArrayList.class || type == List.class) {
            // 资产列表通道（2026-10-29）：泛型实参为 Asset 子类才可检查；字符串列表
            // （2026-11-15，STRING_LIST）：泛型实参为 String；其余列表不映射
            if (assetListElementType(field) != null) {
                return InspectorValueType.GUID_ASSET_LIST;
            }
            return isStringElements(field) ? InspectorValueType.STRING_LIST : null;
        }
        if (type == String[].class) {
            // 字符串数组（TextRender/层名等 ASCII 清单）：STRING_LIST 定长形态
            return InspectorValueType.STRING_LIST;
        }
        if (Asset.class.isAssignableFrom(type)) {
            return InspectorValueType.GUID_ASSET;
        }
        return null;
    }

    /** 列表字段元素类型（泛型实参为 Asset 子类即资产列表；非参数化/非 Asset 返回 null） */
    private static Class<? extends Asset> assetListElementType(Field field) {
        if (!(field.getGenericType() instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        if (argument instanceof Class<?> cls && Asset.class.isAssignableFrom(cls)) {
            return cls.asSubclass(Asset.class);
        }
        return null;
    }

    /** 列表字段元素为 String（STRING_LIST 判据；非参数化/其他元素类型返回 false） */
    private static boolean isStringElements(Field field) {
        return field.getGenericType() instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] == String.class;
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ── 属性元数据 ───────────────────────────────────────────

    /** 可检视属性元数据：字段定位（getter/setter）+ 值类型 + 注解元数据（构建后只读） */
    public static final class PropMeta {
        public final String fieldName;
        public final String displayName;
        public final InspectorValueType valueType;
        public final Method getter;
        public final Method setter;
        public final InspectorFieldVo.Range range;
        /** 枚举类（ENUM 回写 Enum.valueOf 用；其他类型 null） */
        public final Class<?> enumType;
        /** 枚举选项（ENUM 字段随 VO 下发；其他类型 null） */
        public final List<InspectorFieldVo.Option> options;
        /** 声明资产类型（GUID_ASSET 字段；其他类型 null） */
        public final Class<? extends Asset> assetType;
        /** 字段声明类型（STRING_LIST 回写时选 String[] / List 容器；其他类型仅作诊断） */
        public final Class<?> declaredType;
        /** STRING_LIST 元素名是否可编辑（false = @InsTagList：名字只读、只增删） */
        public final boolean listRenamable;
        /** 嵌套容器字段路径（null = 字段直接在宿主上；非 null = 先取该路径的实例再读写） */
        public final String hostPath;
        /** 条件子组的控制字段名（null = 常显；非 null = 父字段值命中 {@link #showKeys} 才显示） */
        public final String parentField;
        /** 条件子组的显示条件值（{@code @InsFieldGroup.keys} 里指向本容器的那些值） */
        public final List<String> showKeys;
        /** 滑条行（{@code @InsSlider}：FLOAT 按「滑条 + 数字框」渲染，必须有范围） */
        public final boolean slider;
        /** 条件子组的组头文案（null = 无组头，子行平铺——{@code @InsFieldGroup.label()} 空时） */
        public final String groupLabel;
        /** 条件子组初始是否收起（{@code @InsFieldGroup.collapsed()}） */
        public final boolean groupCollapsed;

        private PropMeta(String fieldName, String displayName, InspectorValueType valueType,
                         Method getter, Method setter, InspectorFieldVo.Range range,
                         Class<?> enumType, List<InspectorFieldVo.Option> options,
                         Class<? extends Asset> assetType, Class<?> declaredType,
                         boolean listRenamable, String hostPath, String parentField,
                         List<String> showKeys, boolean slider, String groupLabel,
                         boolean groupCollapsed) {
            this.fieldName = fieldName;
            this.displayName = displayName;
            this.valueType = valueType;
            this.getter = getter;
            this.setter = setter;
            this.range = range;
            this.enumType = enumType;
            this.options = options;
            this.assetType = assetType;
            this.declaredType = declaredType;
            this.listRenamable = listRenamable;
            this.hostPath = hostPath;
            this.parentField = parentField;
            this.showKeys = showKeys;
            this.slider = slider;
            this.groupLabel = groupLabel;
            this.groupCollapsed = groupCollapsed;
        }

        /** 读写目标实例（嵌套字段先解引用容器；容器为空即抛，不静默兜底） */
        private Object target(Object host) {
            return hostPath == null ? host : readPath(host, hostPath);
        }

        public Object get(Object host) {
            try {
                return getter.invoke(target(host));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(EditorLang.text("inspector.reflect.readFailed", fieldName), e);
            }
        }

        public void set(Object host, Object value) {
            try {
                setter.invoke(target(host), value);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(EditorLang.text("inspector.reflect.writeFailed", fieldName), e);
            }
        }
    }
}
