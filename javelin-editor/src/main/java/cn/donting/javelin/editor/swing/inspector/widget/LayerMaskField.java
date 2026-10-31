package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPopupMenu;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 层掩码字段（LAYER_MASK 通道，如 Camera.cullingMask）：值摘要按钮 + 勾选式
 * 弹层（JPopupMenu 内 JCheckBox 列表——非 JCheckBoxMenuItem，勾选不关弹层可
 * 连续多选；点外部关闭）。层名选项随 VO 下发（value=层索引串）；勾选实时组包
 * 位掩码回写。弹层为浮层布局不受 2px 间距限。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class LayerMaskField extends JButton implements FieldEditor {

    /** 摘要显示的勾选层名上限（超出显示省略号） */
    private static final int SUMMARY_LIMIT = 3;

    private final List<InspectorFieldVo.Option> options;
    private final Consumer<String> commit;
    private int mask;

    public LayerMaskField(String wireValue, List<InspectorFieldVo.Option> options, Consumer<String> commit) {
        this.options = options != null ? options : List.of();
        this.commit = commit;
        this.mask = parseIntSafe(wireValue);
        setFocusable(false);
        setMargin(new Insets(1, 2, 1, 2));
        setHorizontalAlignment(LEFT);
        updateText();
        addActionListener(e -> showLayerPopup());
    }

    private void showLayerPopup() {
        JPopupMenu popup = new JPopupMenu();
        for (InspectorFieldVo.Option option : options) {
            int bit = parseIntSafe(option.value);
            JCheckBox check = new JCheckBox(option.label);
            check.setSelected((mask & (1 << bit)) != 0);
            check.addItemListener(e -> {
                mask = e.getStateChange() == ItemEvent.SELECTED
                        ? mask | (1 << bit) : mask & ~(1 << bit);
                updateText();
                commit.accept(String.valueOf(mask));
            });
            popup.add(check);
        }
        popup.show(this, 0, getHeight());
    }

    /** 摘要：全选 Everything / 全空 Nothing / 勾选层名（上限截断） */
    private void updateText() {
        List<String> checked = new ArrayList<>();
        for (InspectorFieldVo.Option option : options) {
            int bit = parseIntSafe(option.value);
            if ((mask & (1 << bit)) != 0) {
                checked.add(option.label);
            }
        }
        if (checked.isEmpty()) {
            setText("Nothing");
            return;
        }
        if (checked.size() == options.size()) {
            setText("Everything");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(checked.size(), SUMMARY_LIMIT); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(checked.get(i));
        }
        if (checked.size() > SUMMARY_LIMIT) {
            sb.append('…');
        }
        setText(sb.toString());
    }

    private static int parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        this.mask = parseIntSafe(wireValue);
        updateText();
    }

    @Override
    public boolean isEditing() {
        return false;
    }
}
