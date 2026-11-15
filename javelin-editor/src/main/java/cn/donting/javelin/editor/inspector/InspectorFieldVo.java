package cn.donting.javelin.editor.inspector;

import cn.donting.javelin.core.assets.Asset;

import java.util.List;

/**
 * Inspector 字段 VO：单个可检查字段（面板渲染一行，值变更经
 * {@link InspectorReflector#apply} 回写）。
 *
 * @author javelin
 * @create 2026/10/9
 **/
public class InspectorFieldVo {
    /** 字段名（反射定位宿主上的字段；宿主 = 所在 ComponentInspectorVo.component） */
    public String fieldName;
    /** 显示名（@InsField.label()，空串兜底字段名） */
    public String name;
    /** 值类型 = {@link InspectorValueType} 常量名，决定控件选型与 value 解析 */
    public String valueType;
    /** 字段当前值（按 valueType 序列化的字符串） */
    public String value;
    /** 滑条范围（@InsIntRange/@InsFloatRange 投影；null = 无范围约束，渲染普通输入框） */
    public Range range;
    /** 枚举可选项（ENUM 类型字段；其他类型为 null） */
    public List<Option> options;
    /**
     * 资产显示名（GUID_ASSET 类型：guid 反查源路径尾名，反查失败兜底 guid 原串；
     * 内存创建（guid 空）为 null，面板按 value 空串标记渲染禁用态文案；其他类型 null）
     */
    public String assetName;
    /** 声明资产类型（GUID_ASSET 类型字段：AssetInput 接受范围与回写 loadGuid 类型；其他类型 null） */
    public Class<? extends Asset> assetType;
    /**
     * STRING_LIST 是否可增删（List 字段 true、数组字段 false——层号固定不可移位；
     * 其他值类型恒 false）
     */
    public boolean listResizable;
    /**
     * STRING_LIST 元素名是否可编辑（true = 普通可编辑行；false = 名字只读、只增删，
     * 来源 @InsTagList——Tag 以名字为身份，改名等于另一个标签）
     */
    public boolean listRenamable = true;
    /**
     * 条件子组的控制字段名（{@code @InsFieldGroup} 展开出来的行：null = 常显；
     * 非 null = 父字段当前值 ∈ {@link #showKeys} 时才显示）
     */
    public String parentField;
    /** 条件子组的显示条件值（父字段 wire 值集合；常显行为 null） */
    public List<String> showKeys;
    /** 滑条行（{@code @InsSlider}；range 由 {@link #range} 提供刻度） */
    public boolean slider;
    /** 条件子组的组头文案（仅组首行非 null：非 null 即在该行上方渲染可折叠组头） */
    public String groupLabel;
    /** 组头初始收起态（{@code @InsFieldGroup.collapsed()}；折叠态可在面板内点开） */
    public boolean groupCollapsed;

    /** 滑条范围元数据（INT 场景三值为注解整型原值，渲染取整由 valueType 决定） */
    public static class Range {
        /** 最小值 */
        public float min;
        /** 最大值 */
        public float max;
        /** 步长 */
        public float step;
    }

    /** 枚举选项 */
    public static class Option {
        /** 枚举常量名（回写值） */
        public String value;
        /** 显示名（@InsEnumLabel，未实现接口时为常量名） */
        public String label;
    }
}
