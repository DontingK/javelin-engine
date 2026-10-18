package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/**
 * 下拉（ENUM/Layer/Tag 通道）：选项 value/label 分离（label 显示、value 回写）；
 * 选择即时提交（下拉无编辑中间态）；程序化 setValue 抑制上报防回声。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class JeSelect extends JComboBox<InspectorFieldVo.Option> implements FieldEditor {

    private final Consumer<String> commit;
    private boolean suppressReport;

    public JeSelect(List<InspectorFieldVo.Option> options, String selectedValue, Consumer<String> commit) {
        super(new DefaultComboBoxModel<>(options != null
                ? options.toArray(new InspectorFieldVo.Option[0]) : new InspectorFieldVo.Option[0]));
        this.commit = commit;
        setRenderer(new SelectRenderer());
        selectByValue(selectedValue);
        addActionListener(e -> {
            if (suppressReport) {
                return;
            }
            if (getSelectedItem() instanceof InspectorFieldVo.Option option) {
                commit.accept(option.value);
            }
        });
    }

    /** 按 value 选中（无匹配静默——当前值不在选项表时由调用方补选项） */
    @SuppressWarnings("unchecked")
    private void selectByValue(String value) {
        DefaultComboBoxModel<InspectorFieldVo.Option> model =
                (DefaultComboBoxModel<InspectorFieldVo.Option>) getModel();
        if (value == null) {
            return;
        }
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).value.equals(value)) {
                model.setSelectedItem(model.getElementAt(i));
                return;
            }
        }
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        suppressReport = true;
        try {
            selectByValue(wireValue);
        } finally {
            suppressReport = false;
        }
    }

    @Override
    public boolean isEditing() {
        return false;
    }

    /** 选项 label 渲染（value 不显示） */
    private static final class SelectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof InspectorFieldVo.Option option) {
                setText(option.label);
            }
            return component;
        }
    }
}
