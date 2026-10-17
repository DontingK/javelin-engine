package com.javelin.editor.inspector;

import java.util.List;

/**
 * 组件分组 VO：Inspector 快照中单个组件的可检视字段分组（折叠头信息 + 字段列表）。
 *
 * @author javelin
 * @create 2026/10/9
 **/
public class ComponentInspectorVo {
    /** 组件 id（ObjectCache 反查锚点；将来组件级操作——删除/重置等的定位符） */
    public int objId;
    /** 组件显示名（@InsField 贴 TYPE 的 label，兜底类简单名） */
    public String name;
    /** 组件启用态（Component.isEnabled/setEnabled；Unity 组件头部勾选框） */
    public boolean enabled;
    /** 必需组件（如 Transform）：不可禁用/移除，面板不渲染头部勾选 */
    public boolean required;
    /** 组件内可检视字段列表 */
    public List<InspectorFieldVo> fields;
}
