package com.javelin.editor.inspector;

import com.javelin.core.scene.Component;

import java.util.List;

/**
 * 组件分组 VO：Inspector 快照中单个组件的可检视字段分组（折叠头信息 + 字段列表）。
 *
 * @author javelin
 * @create 2026/10/9
 **/
public class ComponentInspectorVo {
    /** 组件引用（2026-10-18 引用化：回写锚点直持引用，亦为组内 {@link InspectorFieldVo} 字段行的宿主） */
    public Component component;
    /** 组件显示名（@InsHeader.label，兜底类简单名） */
    public String name;
    /** 组件头图标名（@InsHeader.icon，classpath /icon/{name}.svg；空串无图标） */
    public String icon;
    /** 组件启用态（Component.isEnabled/setEnabled；Unity 组件头部勾选框） */
    public boolean enabled;
    /** 必需组件（如 Transform）：不可禁用/移除，面板不渲染头部勾选 */
    public boolean required;
    /** 组件内可检视字段列表 */
    public List<InspectorFieldVo> fields;
}
