package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.obj.ObjectId;
import cn.donting.javelin.core.scene.Node;

/**
 * Inspector 字段提交回调（面板统一收口，写入走 submitTask 帧边界）。
 *
 * <p>owner = 字段所属节点（引擎线程归属校验用——僵尸提交丢弃，引用化拍板口径）；
 * host = 回写宿主（节点头 = Node 自身、组件行 = Component，即
 * {@code InspectorReflector.apply} 的入参）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
@FunctionalInterface
public interface FieldCommit {

    /**
     * @param owner      字段所属节点（归属校验锚点）
     * @param host       回写宿主（Node / Component）
     * @param fieldName  字段名（反射定位；Tag 专用分支不使用）
     * @param valueType  值类型（{@code InspectorValueType} 常量名）
     * @param wireValue  值字符串（按值类型序列化口径）
     */
    void commit(Node owner, ObjectId host, String fieldName, String valueType, String wireValue);
}
