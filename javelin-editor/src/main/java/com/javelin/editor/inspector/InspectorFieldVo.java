package com.javelin.editor.inspector;

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
