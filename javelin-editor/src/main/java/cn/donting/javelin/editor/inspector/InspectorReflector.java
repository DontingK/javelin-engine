package cn.donting.javelin.editor.inspector;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.inspector.InsHeader;
import cn.donting.javelin.core.obj.ObjectId;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Tag;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.core.settings.SettingManager;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspector 反射器：可检查字段清单收集（getter 读）+ 值回写（setter 写）。
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
 * 由 M4 Swing 检查器面板保证——面板读写均走帧队列，与 mainLoop 的场景访问
 * 串行无竞争）。</p>
 *
 * <p>收集面 = 节点头（name/active/layer/tags）+ @InsHeader 闸门组件分组
 * （v0.4 增量：遍历 node.getComponents() 挂载序，贴 @InsHeader 的组件才进
 * 面板——旧组件白名单退役）；v0.2~v0.3 通道沿革：ENUM/COLOR/RECT/
 * LAYER_MASK 放开（枚举选项随 VO 下发，显示名走 {@link cn.donting.javelin.core.inspector.InsEnumLabel}）、
 * GUID_ASSET（Asset 引用型字段，AssetInput 可编辑——选择窗/拖拽/None 清空，
 * 回写 guid 经 loadGuid 解实例）。
 * Collider 组件暂缓（未贴 @InsHeader 即不入面板）。可空值对象
 * （Camera.backgroundColor/viewportRect）：序列化传 null（前端控件兜底
 * 显示），编辑回写即物化显式值。</p>
 *
 * <p>字段清单/通道映射/wire 读写已提取到 {@link InspectorMeta}（2026-11-14）——
 * 与设置面板共用一份映射表；本类只管节点快照组装与节点域回写特例（Tag / 资产列表）。</p>
 *
 * @author javelin
 * @create 2026/10/9
 **/
public final class InspectorReflector {

    /** Unity 内建默认 Tag（空 tag 节点的显示值，注册表常驻选项） */
    private static final String UNTAGGED = "Untagged";

    /** Tag 注册表名缓存（TagSettings 一次性加载避免逐次快照磁盘 IO；Layer 静态注册表同口径） */
    private static List<String> TAG_OPTIONS;

    private InspectorReflector() {
    }

    /**
     * 组装节点 Inspector 快照：节点头（name/active/layer 索引/layerNames/tags + tag 注册表名）
     * + @InsHeader 闸门组件分组（挂载序，Transform 构造首挂恒首位；未贴注解组件不进面板）。
     */
    public static NodeInspectorVo collect(Node node) {
        NodeInspectorVo vo = new NodeInspectorVo();
        vo.ready = true;
        vo.node = node;
        vo.nodeName = node.getName() != null ? node.getName() : "";
        vo.active = node.isActive();
        vo.layer = node.getLayer() != null ? node.getLayer().getValue() : 0;
        vo.layerNames = InspectorMeta.layerNames();
        vo.tags = tagNames(node);
        vo.tagNames = tagOptions();
        vo.components = new ArrayList<>();
        for (Component component : node.getComponents()) {
            if (!component.getClass().isAnnotationPresent(InsHeader.class)) {
                continue; // @InsHeader 组件闸门（v0.4 增量）：未贴注解的组件不进面板
            }
            vo.components.add(collectComponent(component, component instanceof Transform));
        }
        return vo;
    }

    /**
     * 回写字段值：按 PropMeta 声明的值类型解析 value 字符串，经 setter 写入宿主。
     * 解析失败/字段缺失/类型不匹配抛 TipException（面板层状态栏提示+控件回滚）。
     * 节点头 Tag 走 TAG 专用分支（单选替换式，"Untagged"=清空，不经 PropMeta）。
     */
    public static void apply(ObjectId host, String fieldName, String valueType, String value) throws TipException {
        InspectorValueType declared = parseType(valueType);
        if (declared == InspectorValueType.TAG) {
            applyTag(host, value);
            return;
        }
        if (declared == InspectorValueType.GUID_ASSET_LIST) {
            applyAssetList(host, fieldName, value);
            return;
        }
        InspectorMeta.PropMeta prop = InspectorMeta.findProp(host.getClass(), fieldName);
        if (prop.valueType != declared) {
            throw new TipException(EditorLang.key("inspector.apply.typeMismatch",
                    host.getClass().getSimpleName(), fieldName, prop.valueType, valueType));
        }
        prop.set(host, InspectorMeta.parseValue(prop, value));
    }

    /**
     * 资产列表回写（GUID_ASSET_LIST 通道）：fieldName 编码序号（materials[i]），
     * guid → 元素实例（同 GUID_ASSET 三态：空串=内存创建不可回写、未索引拒绝防
     * 静默置 null），替换列表 i 位（缺位补 null）→ 整列表 setter（读 getter 写
     * setter 铁律，不裸写字段）。
     */
    private static void applyAssetList(ObjectId host, String fieldName, String value) throws TipException {
        int bracket = fieldName.lastIndexOf('[');
        if (bracket < 0 || !fieldName.endsWith("]")) {
            throw new TipException(EditorLang.key("inspector.apply.listNameMissingIndex", fieldName));
        }
        int index = InspectorMeta.parseInt(fieldName.substring(bracket + 1, fieldName.length() - 1));
        String base = fieldName.substring(0, bracket);
        InspectorMeta.PropMeta prop = InspectorMeta.findProp(host.getClass(), base);
        if (prop.valueType != InspectorValueType.GUID_ASSET_LIST) {
            throw new TipException(EditorLang.key("inspector.apply.listTypeMismatch",
                    host.getClass().getSimpleName(), base, prop.valueType));
        }
        // guid 三态与 GUID_ASSET 分支同口径：null=None 清空合法（该位 null → 渲染侧
        // DefaultMaterial 兜底）；空串=内存创建不可回写；未索引拒绝防静默置 null
        Asset asset;
        if (value == null) {
            asset = null;
        } else {
            String guid = value.trim();
            if (guid.isEmpty()) {
                throw new TipException(EditorLang.key("inspector.apply.inMemoryAsset", base));
            }
            JavelinEditor editor = JavelinEditor.getInstance();
            asset = editor != null ? editor.getAssetManager().loadGuid(guid, prop.assetType) : null;
            if (asset == null) {
                throw new TipException(EditorLang.key("inspector.apply.assetNotFound", base));
            }
        }
        // 整列表 setter 提交（防御性拷贝在 setter 内）；反射泛型擦除，raw 通道
        @SuppressWarnings({"unchecked", "rawtypes"})
        List list = new ArrayList((List) prop.get(host));
        while (list.size() <= index) {
            list.add(null);
        }
        list.set(index, asset);
        prop.set(host, list);
    }

    /**
     * 节点头 Tag 单选回写：替换式语义（Unity 口径单 tag）——非 "Untagged" 时
     * tags 置为单元素 [Tag.of(value)]，"Untagged" 清空（空列表显示侧即 Untagged）。
     */
    private static void applyTag(ObjectId host, String value) throws TipException {
        if (!(host instanceof Node)) {
            throw new TipException(EditorLang.key("inspector.apply.tagHostNotNode",
                    host.getClass().getSimpleName()));
        }
        if (value == null || value.isBlank()) {
            throw new TipException(EditorLang.key("inspector.apply.tagEmpty"));
        }
        Node node = (Node) host;
        List<Tag> tags = node.getTags();
        if (tags == null) {
            throw new TipException(EditorLang.key("inspector.apply.tagsUninitialized", node.getName()));
        }
        tags.clear();
        if (!UNTAGGED.equals(value)) {
            tags.add(Tag.of(value));
        }
    }

    // ── 收集侧 ───────────────────────────────────────────────

    /** 组装组件分组：折叠头（显示名/图标/启用态/必需标志）+ 字段行（enabled 提升到分组头，不入行） */
    private static ComponentInspectorVo collectComponent(Component component, boolean required) {
        ComponentInspectorVo vo = new ComponentInspectorVo();
        vo.component = component;
        vo.name = componentLabel(component.getClass());
        vo.icon = componentIcon(component.getClass());
        vo.enabled = component.isEnabled();
        vo.required = required;
        vo.fields = new ArrayList<>();
        for (InspectorMeta.PropMeta prop : InspectorMeta.propsOf(component.getClass())) {
            if ("enabled".equals(prop.fieldName)) {
                continue;
            }
            if (prop.valueType == InspectorValueType.GUID_ASSET_LIST) {
                // 资产列表展开为 N 行（每行一个元素），行控件/回显/回写全复用 GUID_ASSET 面
                vo.fields.addAll(assetListFieldVos(component, prop));
                continue;
            }
            vo.fields.add(InspectorMeta.fieldVo(component, prop));
        }
        return vo;
    }

    /**
     * 资产列表字段展开：N = max(1, 列表长度) 行（列表长度由 setMesh/setMaterials 按
     * 子网格数对齐——行数即子网格数，未赋 mesh 兜 1；场景加载后不强制对齐，渲染侧
     * 按缺失兜底语义处理）。fieldName 编码序号（materials[i]）供回写定位；
     * 行 value/assetName 走元素级 guid 三态。
     */
    private static List<InspectorFieldVo> assetListFieldVos(ObjectId host, InspectorMeta.PropMeta prop) {
        List<?> current = (List<?>) prop.get(host);
        int count = Math.max(1, current == null ? 0 : current.size());
        List<InspectorFieldVo> vos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object element = current != null && i < current.size() ? current.get(i) : null;
            InspectorFieldVo vo = new InspectorFieldVo();
            vo.fieldName = prop.fieldName + "[" + i + "]";
            vo.name = prop.displayName + " " + i;
            vo.valueType = prop.valueType.name();
            vo.value = element == null ? null : InspectorMeta.guidWire(element);
            vo.assetName = element == null ? null : InspectorMeta.guidAssetLabel(element);
            vo.assetType = prop.assetType;
            vos.add(vo);
        }
        return vos;
    }

    /** 组件头显示名（@InsHeader.label 贴 TYPE，i18n 键经 EditorLang 解析；空串兜底类简单名） */
    private static String componentLabel(Class<?> type) {
        InsHeader header = type.getAnnotation(InsHeader.class);
        if (header != null && !header.label().isBlank()) {
            return EditorLang.text(header.label());
        }
        return type.getSimpleName();
    }

    /** 组件头图标名（@InsHeader.icon 贴 TYPE，未贴/空串无图标） */
    private static String componentIcon(Class<?> type) {
        InsHeader header = type.getAnnotation(InsHeader.class);
        return header != null ? header.icon() : "";
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

    private static InspectorValueType parseType(String valueType) throws TipException {
        try {
            return InspectorValueType.valueOf(valueType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new TipException(EditorLang.key("inspector.apply.unknownValueType", valueType));
        }
    }
}
