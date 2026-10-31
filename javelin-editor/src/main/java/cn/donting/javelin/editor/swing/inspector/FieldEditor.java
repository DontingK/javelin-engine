package cn.donting.javelin.editor.swing.inspector;

import javax.swing.JComponent;

/**
 * Inspector 字段行控件契约（{@link FieldRowFactory} 产出）。
 *
 * <p>实现=控件本体（editorComponent 返回自身）。{@link #setValue} 为外源刷新通道
 * （NodeTransformChangeEvent 定点刷 / 面板重建）：编辑中（焦点内且文本已改）跳过、
 * 值等价跳过——防打断输入与事件回声。{@link #isEditing} 供面板重建保护
 * （防结构变更重拉吞掉正在输入的值）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public interface FieldEditor {

    /** 控件本体（加入字段行两列布局的控件列） */
    JComponent editorComponent();

    /** 外源刷新（wire 串；编辑中/值等价由实现侧跳过） */
    void setValue(String wireValue);

    /** 编辑中判定（焦点内且文本与已提交值不等） */
    boolean isEditing();
}
