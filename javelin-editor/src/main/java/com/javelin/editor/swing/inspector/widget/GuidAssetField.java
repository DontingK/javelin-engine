package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.awt.Color;

/**
 * GUID 资产字段（GUID_ASSET 通道，v0.1 只读——赋值待项目面板拖拽资产落地）：
 * 三态展示——value=null 无资产 "None"、空串=内存创建 "（内存创建）"（禁用态
 * 文案）、非空=显示名（assetName=guid 反查源路径尾名，兜底 guid 原串）。
 * 空态/内存态暗色显示（Label.disabledForeground）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class GuidAssetField extends JTextField implements FieldEditor {

    private final String assetName;

    public GuidAssetField(String value, String assetName) {
        this.assetName = assetName;
        setEditable(false);
        apply(value);
    }

    private void apply(String value) {
        if (value == null) {
            setText("None");
            applyDimForeground();
        } else if (value.isBlank()) {
            setText("（内存创建）");
            applyDimForeground();
        } else {
            setText(assetName != null && !assetName.isBlank() ? assetName : value);
            applyNormalForeground();
        }
    }

    private void applyDimForeground() {
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            setForeground(dim);
        }
    }

    private void applyNormalForeground() {
        Color normal = UIManager.getColor("Label.foreground");
        if (normal != null) {
            setForeground(normal);
        }
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        apply(wireValue);
    }

    @Override
    public boolean isEditing() {
        return false;
    }
}
