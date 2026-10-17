package com.javelin.editor.inspector;

import com.javelin.core.inspector.InsEnumLabel;
import com.javelin.core.inspector.InsFloatRange;
import com.javelin.core.inspector.InsIntRange;
import com.javelin.core.inspector.InsField;
import com.javelin.core.inspector.InsLayerMask;
import com.javelin.core.inspector.InsRotation;
import com.javelin.core.assets.Asset;
import com.javelin.core.math.Color;
import com.javelin.core.math.Rect;
import com.javelin.core.obj.ObjectId;
import com.javelin.core.scene.Component;
import com.javelin.core.scene.Layer;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.Tag;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.core.settings.SettingManager;
import com.javelin.editor.exception.TipException;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.jcef.domain.vo.ComponentInspectorVo;
import com.javelin.editor.jcef.domain.vo.InspectorFieldVo;
import com.javelin.editor.jcef.domain.vo.NodeInspectorVo;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inspector 反射器：可检视字段清单收集（getter 读）+ 值回写（setter 写）。
 *
 * <p>访问铁律：读走 getter、写走 setter——Transform 的 setLocalXxx 内含值拷贝 +
 * markDirty 脏传播，裸 Field.set 会绕过（localToWorld 矩阵不重算）；字段定位可以
 * 反射，值通道不裸写字段（ReflectUtil.setField 通道因此不用于 Inspector）。</p>
 *
 * <p>字段清单判据：@InsField 显式标记（opt-in 闸门，未标记一律不展示）+
 * public getter/setter 齐备 + 类型可映射到 {@link InspectorValueType}——
 * 已标记字段条件不满足时构建即抛错（显式意图不静默吞）。Quaternionf 还需
 * @InsRotation 才按旋转展示。</p>
 *
 * <p>线程约束：须在引擎主线程执行（经 JavelinEngine.submitTask 帧边界提交，
 * 由 InspectorMethod 保证——桥线程与 mainLoop 的场景访问无竞争）。</p>
 *
 * <p>v0.2 收集面 = 节点头（name/active/layer/tags）+ 组件白名单
 * （{@link #INSPECTABLE_COMPONENTS}，v0.2 放开 Camera，v0.3 放开 MeshRenderer）
 * 字段分组；值类型通道
 * 放开 ENUM/COLOR/RECT/LAYER_MASK（枚举选项随 VO 下发，显示名走
 * {@link InsEnumLabel}）；v0.3 放开 GUID_ASSET（Asset 引用型字段只读展示：
 * MeshRenderer.mesh/material 首批，赋值通道待项目面板拖拽资产落地）。
 * Collider 组件暂缓。可空值对象（Camera.backgroundColor/
 * viewportRect）：序列化传 null（前端控件兜底显示），编辑回写即物化显式值。</p>
 *
 * @author javelin
 * @create 2026/10/9
 **/
public final class InspectorReflector {

    /** Layer 注册表长度（Layer.of 索引合法区间 [0, 31)） */
    private static final int LAYER_COUNT = 31;

    /** Unity 内建默认 Tag（空 tag 节点的显示值，注册表常驻选项） */
    private static final String UNTAGGED = "Untagged";

    /** Tag 注册表名缓存（TagSettings 一次性加载避免逐次快照磁盘 IO；Layer 静态注册表同口径） */
    private static List<String> TAG_OPTIONS;

    /** Layer 名缓存（LayerSettings 一次性直读避免逐次快照磁盘 IO；与 Layer 静态注册表同口径） */
    private static List<String> LAYER_NAMES;

    /** 浮点显示精度：最多 4 位小数（与前端 JeNumberInput precision 默认值同口径） */
    private static final int DISPLAY_DECIMALS = 4;

    /** 字段元数据缓存（类 → 属性清单；类结构运行期不变，首次访问构建一次） */
    private static final ConcurrentHashMap<Class<?>, List<PropMeta>> PROP_CACHE = new ConcurrentHashMap<>();

    /**
     * 组件白名单（扩展点）：白名单序 = 面板分组序（Transform 必需恒在首位）。
     * 收集经 getComponent 取首个实例——节点单组件假设，同节点多实例组件形态
     * 留组件实例面板再议。v0.1 仅 Transform，v0.2 放开 Camera，v0.3 放开
     * MeshRenderer（mesh/material 走 GUID_ASSET 只读，§wire 契约见 serialize）。
     */
    private static final List<Class<? extends Component>> INSPECTABLE_COMPONENTS =
            List.of(Transform.class, Camera.class, MeshRenderer.class);

    private InspectorReflector() {
    }

    /**
     * 组装节点 Inspector 快照：节点头（name/active/layer 索引/layerNames/tags + tag 注册表名）
     * + 组件白名单（{@link #INSPECTABLE_COMPONENTS}）字段分组。
     */
    public static NodeInspectorVo collect(Node node) {
        NodeInspectorVo vo = new NodeInspectorVo();
        vo.ready = true;
        vo.objId = node.getId();
        vo.nodeName = node.getName() != null ? node.getName() : "";
        vo.active = node.isActive();
        vo.layer = node.getLayer() != null ? node.getLayer().getValue() : 0;
        vo.layerNames = layerNames();
        vo.tags = tagNames(node);
        vo.tagNames = tagOptions();
        vo.components = new ArrayList<>();
        for (Class<? extends Component> type : INSPECTABLE_COMPONENTS) {
            Component component = node.getComponent(type);
            if (component != null) {
                vo.components.add(collectComponent(component, type == Transform.class));
            }
        }
        return vo;
    }

    /**
     * 回写字段值：按 PropMeta 声明的值类型解析 value 字符串，经 setter 写入宿主。
     * 解析失败/字段缺失/类型不匹配抛 TipException（桥层转 400 提示弹窗）。
     * 节点头 Tag 走 TAG 专用分支（单选替换式，"Untagged"=清空，不经 PropMeta）。
     */
    public static void apply(ObjectId host, String fieldName, String valueType, String value) {
        InspectorValueType declared = parseType(valueType);
        if (declared == InspectorValueType.TAG) {
            applyTag(host, value);
            return;
        }
        PropMeta prop = findProp(host, fieldName);
        if (prop.valueType != declared) {
            throw new TipException("值类型不匹配: " + host.getClass().getSimpleName() + "."
                    + fieldName + " 期望 " + prop.valueType + "，实收 " + valueType);
        }
        prop.set(host, parseValue(prop, value));
    }

    /**
     * 节点头 Tag 单选回写：替换式语义（Unity 口径单 tag）——非 "Untagged" 时
     * tags 置为单元素 [Tag.of(value)]，"Untagged" 清空（空列表显示侧即 Untagged）。
     */
    private static void applyTag(ObjectId host, String value) {
        if (!(host instanceof Node)) {
            throw new TipException("Tag 回写宿主须为 Node: " + host.getClass().getSimpleName());
        }
        if (value == null || value.isBlank()) {
            throw new TipException("Tag 值不能为空");
        }
        Node node = (Node) host;
        List<Tag> tags = node.getTags();
        if (tags == null) {
            throw new TipException("节点 tags 未初始化: " + node.getName());
        }
        tags.clear();
        if (!UNTAGGED.equals(value)) {
            tags.add(Tag.of(value));
        }
    }

    // ── 收集侧 ───────────────────────────────────────────────

    /** 组装组件分组：折叠头（显示名/启用态/必需标志）+ 字段行（enabled 提升到分组头，不入行） */
    private static ComponentInspectorVo collectComponent(Component component, boolean required) {
        ComponentInspectorVo vo = new ComponentInspectorVo();
        vo.objId = component.getId();
        vo.name = componentLabel(component.getClass());
        vo.enabled = component.isEnabled();
        vo.required = required;
        vo.fields = new ArrayList<>();
        for (PropMeta prop : propsOf(component.getClass())) {
            if ("enabled".equals(prop.fieldName)) {
                continue;
            }
            vo.fields.add(fieldVo(component, prop));
        }
        return vo;
    }

    private static InspectorFieldVo fieldVo(ObjectId host, PropMeta prop) {
        InspectorFieldVo vo = new InspectorFieldVo();
        vo.objId = host.getId();
        vo.fieldName = prop.fieldName;
        vo.name = prop.displayName;
        vo.valueType = prop.valueType.name();
        Object current = prop.get(host);
        vo.value = serialize(current, prop);
        if (prop.valueType == InspectorValueType.GUID_ASSET) {
            vo.assetName = guidAssetLabel(current);
        }
        vo.range = prop.range;
        vo.options = prop.options;
        return vo;
    }

    /** 值 → 序列化字符串（valueType 决定格式；null 原样传 null，RECT 例外见内） */
    private static String serialize(Object value, PropMeta prop) {
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
                Vector3f euler = new Vector3f();
                ((Quaternionf) value).getEulerAnglesXYZ(euler);
                return toDegrees(euler.x) + "," + toDegrees(euler.y) + "," + toDegrees(euler.z);
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
                String guid = ((Asset) value).getGuid();
                return guid != null && !guid.isBlank() ? guid : "";
            default:
                // 暂缓类型不应出现在收集面（类型映射已挡）
                return null;
        }
    }

    /**
     * GUID 资产显示名：guid 经 {@link JavelinEditor#getAssetManager()} 反查源路径
     * 取尾名去后缀（Unity 资产名口径）；反查失败兜底 guid 原串。内存创建（guid 空）
     * /非 Asset 值返回 null——内存态文案由前端按 value 空串标记渲染。
     */
    private static String guidAssetLabel(Object value) {
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

    private static String toDegrees(float radians) {
        return formatFloat((float) Math.toDegrees(radians));
    }

    /** 浮点显示串：四舍五入到 {@value #DISPLAY_DECIMALS} 位并去尾零（0.30000012 → "0.3"） */
    private static String formatFloat(float value) {
        return new BigDecimal(Float.toString(value))
                .setScale(DISPLAY_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Layer 名列表（SettingManager 直读投影，索引=层号；未命名槽位=空串，前端下拉只显示有名槽位） */
    private static List<String> layerNames() {
        if (LAYER_NAMES == null) {
            String[] settings = SettingManager.loadLayerSettings().getLayerNames();
            List<String> names = new ArrayList<>(LAYER_COUNT);
            for (int i = 0; i < LAYER_COUNT; i++) {
                String name = i < settings.length ? settings[i] : null;
                names.add(name != null && !name.isBlank() ? name : "");
            }
            LAYER_NAMES = names;
        }
        return LAYER_NAMES;
    }

    private static List<String> tagNames(Node node) {
        List<String> names = new ArrayList<>();
        if (node.getTags() != null) {
            for (Tag tag : node.getTags()) {
                if (tag != null && tag.getName() != null) {
                    names.add(tag.getName());
                }
            }
        }
        return names;
    }

    /** Tag 注册表名列表（TagSettings 投影，null/空白剔除；"Untagged" 兜底常驻） */
    private static List<String> tagOptions() {
        if (TAG_OPTIONS == null) {
            List<String> names = new ArrayList<>();
            for (String name : SettingManager.loadTagSettings().getTags()) {
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            if (!names.contains(UNTAGGED)) {
                names.add(0, UNTAGGED);
            }
            TAG_OPTIONS = names;
        }
        return TAG_OPTIONS;
    }

    /** 组件显示名（@InsField 贴 TYPE，兜底类简单名） */
    private static String componentLabel(Class<?> type) {
        InsField label = type.getAnnotation(InsField.class);
        if (label != null && !label.label().isBlank()) {
            return label.label();
        }
        return type.getSimpleName();
    }

    // ── 字段清单构建 ─────────────────────────────────────────

    private static List<PropMeta> propsOf(Class<?> clazz) {
        return PROP_CACHE.computeIfAbsent(clazz, InspectorReflector::buildProps);
    }

    /**
     * 类 → 可检视属性清单：沿类层级收集声明字段，@InsField 标记的才进清单
     * （判据见 buildProp）。getter 按 getX/isX（boolean）查找；setter 单参数按
     * setX 查找——同名重载时按参数类型与字段类型装箱等价解析（findSetter）。
     */
    private static List<PropMeta> buildProps(Class<?> clazz) {
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
                PropMeta prop = buildProp(field, zeroParam, oneParam);
                if (prop != null) {
                    props.add(prop);
                }
            }
        }
        return props;
    }

    private static PropMeta buildProp(Field field, Map<String, Method> zeroParam, Map<String, List<Method>> oneParam) {
        if (!field.isAnnotationPresent(InsField.class)) {
            return null;
        }
        String owner = field.getDeclaringClass().getSimpleName() + "." + field.getName();
        String cap = capitalize(field.getName());
        Method getter = zeroParam.containsKey("get" + cap) ? zeroParam.get("get" + cap)
                : zeroParam.get("is" + cap);
        Method setter = findSetter(oneParam.get("set" + cap), field.getType());
        if (getter == null || setter == null) {
            throw new IllegalStateException("InsField 字段缺公开访问器（须 getX/isX + 参数类型匹配的 setX）: " + owner);
        }
        InspectorValueType type = mapValueType(field);
        if (type == null) {
            throw new IllegalStateException("InsField 字段类型不可检视: " + owner
                    + "（类型 " + field.getType().getSimpleName() + "；Quaternionf 还须 @InsRotation）");
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
        }
        return new PropMeta(field.getName(), displayName(field), type, getter, setter, rangeOf(field),
                enumType, options);
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

    /** 枚举显示名：实现 {@link InsEnumLabel} 用 getLabel()，否则（含空/白返回）兜底常量名 */
    private static String enumLabel(Enum<?> constant) {
        if (constant instanceof InsEnumLabel labeled) {
            String label = labeled.getLabel();
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return constant.name();
    }

    /**
     * 字段类型 → 值类型；不可映射返回 null（即不展示）。
     * Asset 引用型字段（Mesh/Material 等）→ GUID_ASSET 只读展示（赋值通道待
     * 项目面板拖拽资产）。暂缓：Collider 组件。
     */
    private static InspectorValueType mapValueType(Field field) {
        Class<?> type = field.getType();
        if (type == int.class || type == Integer.class) {
            // @InsLayerMask 标记的 int 按层位掩码（勾选式多选）展示，否则普通整数
            return field.isAnnotationPresent(InsLayerMask.class) ? InspectorValueType.LAYER_MASK
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
        if (Asset.class.isAssignableFrom(type)) {
            return InspectorValueType.GUID_ASSET;
        }
        return null;
    }

    private static String displayName(Field field) {
        InsField label = field.getAnnotation(InsField.class);
        if (label != null && !label.label().isBlank()) {
            return label.label();
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

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ── 回写侧 ───────────────────────────────────────────────

    private static PropMeta findProp(ObjectId host, String fieldName) {
        for (PropMeta prop : propsOf(host.getClass())) {
            if (prop.fieldName.equals(fieldName)) {
                return prop;
            }
        }
        throw new TipException("无可检视字段: " + host.getClass().getSimpleName() + "." + fieldName);
    }

    private static InspectorValueType parseType(String valueType) {
        try {
            return InspectorValueType.valueOf(valueType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new TipException("未知 Inspector 值类型: " + valueType);
        }
    }

    /** 值字符串 → 类型对象（按值类型分支；失败抛 TipException） */
    private static Object parseValue(PropMeta prop, String value) {
        if (value == null) {
            throw new TipException("字段值不能为空: " + prop.fieldName);
        }
        switch (prop.valueType) {
            case STRING:
                return value;
            case BOOLEAN:
                if ("true".equals(value) || "false".equals(value)) {
                    return Boolean.valueOf(value);
                }
                throw new TipException("布尔值须为 true/false: " + value);
            case INT:
                int i = parseInt(value);
                if (prop.setter.getParameterTypes()[0] == Layer.class) {
                    if (i < 0 || i >= LAYER_COUNT) {
                        throw new TipException("Layer 索引越界: " + i);
                    }
                    return Layer.of(i);
                }
                return i;
            case FLOAT:
                try {
                    return Float.parseFloat(value.trim());
                } catch (NumberFormatException e) {
                    throw new TipException("浮点值非法: " + value);
                }
            case VEC3_F:
                float[] xyz = parseFloats(value, 3);
                return new Vector3f(xyz[0], xyz[1], xyz[2]);
            case ROTATION:
                float[] deg = parseFloats(value, 3);
                return new Quaternionf().rotationXYZ(
                        (float) Math.toRadians(deg[0]),
                        (float) Math.toRadians(deg[1]),
                        (float) Math.toRadians(deg[2]));
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
            case GUID_ASSET:
                // v0.1 只读通道：赋值待项目面板拖拽资产落地（Inspector_Design 遗留）
                throw new TipException("GUID 资产字段暂不支持字符串回写（赋值走拖拽，待落地）: "
                        + prop.fieldName);
            default:
                throw new TipException("暂不支持的回写类型: " + prop.valueType);
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new TipException("整数值非法: " + value);
        }
    }

    /** 枚举常量回解（valueOf 的 IllegalArgumentException 转口径；选项外值即此路径） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(PropMeta prop, String value) {
        try {
            return Enum.valueOf((Class) prop.enumType, value.trim());
        } catch (IllegalArgumentException e) {
            throw new TipException("未知枚举值: " + prop.fieldName + " = " + value);
        }
    }

    private static float[] parseFloats(String value, int count) {
        String[] parts = value.split(",");
        if (parts.length != count) {
            throw new TipException("数值格式非法（须 " + count + " 个逗号分隔分量）: " + value);
        }
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new TipException("数值分量非法: " + value);
            }
        }
        return result;
    }

    // ── 属性元数据 ───────────────────────────────────────────

    /** 可检视属性元数据：字段定位（getter/setter）+ 值类型 + 注解元数据（构建后只读） */
    private static final class PropMeta {
        final String fieldName;
        final String displayName;
        final InspectorValueType valueType;
        final Method getter;
        final Method setter;
        final InspectorFieldVo.Range range;
        /** 枚举类（ENUM 回写 Enum.valueOf 用；其他类型 null） */
        final Class<?> enumType;
        /** 枚举选项（ENUM 字段随 VO 下发；其他类型 null） */
        final List<InspectorFieldVo.Option> options;

        PropMeta(String fieldName, String displayName, InspectorValueType valueType,
                 Method getter, Method setter, InspectorFieldVo.Range range,
                 Class<?> enumType, List<InspectorFieldVo.Option> options) {
            this.fieldName = fieldName;
            this.displayName = displayName;
            this.valueType = valueType;
            this.getter = getter;
            this.setter = setter;
            this.range = range;
            this.enumType = enumType;
            this.options = options;
        }

        Object get(Object host) {
            try {
                return getter.invoke(host);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Inspector 读取失败: " + fieldName, e);
            }
        }

        void set(Object host, Object value) {
            try {
                setter.invoke(host, value);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Inspector 回写失败: " + fieldName, e);
            }
        }
    }
}
