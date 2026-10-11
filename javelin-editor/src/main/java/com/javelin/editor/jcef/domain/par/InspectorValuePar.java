package com.javelin.editor.jcef.domain.par;


/**
 * /inspector/setValue 请求参数（Inspector 字段值回写）。
 *
 * @author javelin
 * @create 2026/10/8
 **/
public class InspectorValuePar {
    /** 字段宿主对象 id（Node 或 Component，ObjectCache 反查） */
    public int objId;

    /** 字段名（反射定位宿主上的字段） */
    public String fieldName;

    /** 值类型 = {@link com.javelin.editor.inspector.InspectorValueType} 常量名，决定 value 的解析方式 */
    public String valueType;

    /** 新值字符串（按 valueType 解析后反射回写） */
    public String value;
}
