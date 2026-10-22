package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * 多段数字行（VEC3_F/ROTATION/RECT 通道复用）：N 段 {@link JeNumberInput} +
 * 前缀小标签（X/Y/Z/W/H——Unity 口径），段间 2px、行间 2px。**段宽等分自适应**
 * （GridBagLayout 等权列：前缀定宽、输入框 fill HORIZONTAL 吃掉所在列全部剩余宽
 * ——Unity 口径行宽填满，Flow 布局恒按 preferred 挤左的形态 2026-10-18 实测废弃）；
 * 每行段数可配——RECT 四段两行（X/Y + W/H，Web 版 v0.2 形态回归，2026-10-18
 * 用户拍板）、VEC3/ROTATION 单行。任一段提交即整行组包回写——组包读各段
 * committedText（已提交值，恒归一/已钳制；live 输入期段显示文本仍是原始态）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class VectorRow extends JPanel implements FieldEditor {

    /** 段间横向间距（间距规范 ≤2） */
    private static final int SEGMENT_GAP = 2;
    /** 行间纵向间距（多行形态，RECT 两行；间距规范 ≤2） */
    private static final int ROW_GAP = 2;

    private final JeNumberInput[] inputs;
    private final Consumer<String> commit;

    public VectorRow(String[] labels, String wireValue, boolean integer,
            InspectorFieldVo.Range range, Consumer<String> commit) {
        this(labels, wireValue, integer, range, commit, labels.length);
    }

    /**
     * @param labels         段前缀标签（X/Y/Z/W/H）
     * @param segmentsPerRow 每行段数（RECT=2 两行 X/Y+W/H；VEC3/ROTATION=段数单行）
     */
    public VectorRow(String[] labels, String wireValue, boolean integer,
            InspectorFieldVo.Range range, Consumer<String> commit, int segmentsPerRow) {
        this.commit = commit;
        this.inputs = new JeNumberInput[labels.length];
        float[] parts = InsFormat.split(wireValue, labels.length);
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 0);
        for (int i = 0; i < labels.length; i++) {
            int row = i / segmentsPerRow;
            int col = i % segmentsPerRow;
            boolean lastInRow = col == segmentsPerRow - 1 || i == labels.length - 1;
            String segment = parts != null ? InsFormat.formatFloat(parts[i]) : "0";
            gbc.gridy = row;
            gbc.gridx = col * 2;
            gbc.weightx = 0;
            gbc.insets = new Insets(row > 0 ? ROW_GAP : 0, col > 0 ? SEGMENT_GAP : 0, 0, 0);
            JLabel segLabel = segmentLabel(labels[i]);
            add(segLabel, gbc);
            gbc.gridx = col * 2 + 1;
            gbc.weightx = 1;
            gbc.insets = new Insets(row > 0 ? ROW_GAP : 0, 0, 0, lastInRow ? 0 : SEGMENT_GAP);
            JeNumberInput input = new JeNumberInput(segment, integer, range, s -> rowCommit());
            if (range != null) {
                // 拖动调值每像素步长跟随注解 step（无 range 走控件默认：整 1/浮 0.1）
                input.setScrubStep(range.step);
            }
            // 段 label 拖动调值（Unity scrub 口径，X/Y/Z 各管一段）
            ScrubDragger.attach(segLabel, input);
            inputs[i] = input;
            add(input, gbc);
        }
    }

    /** 前缀小标签（暗色——标识非字段名） */
    private static JLabel segmentLabel(String text) {
        JLabel label = new JLabel(text);
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            label.setForeground(dim);
        }
        return label;
    }

    /** 整行组包提交（读各段已提交值——非焦点段恒归一态、编辑段 live 已同步提交） */
    private void rowCommit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inputs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(inputs[i].committedText());
        }
        commit.accept(sb.toString());
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        float[] parts = InsFormat.split(wireValue, inputs.length);
        if (parts == null) {
            return;
        }
        for (int i = 0; i < inputs.length; i++) {
            // 编辑中的段由段自身跳过（Gizmo 拖拽不抢输入焦点所在段）
            inputs[i].setValue(InsFormat.formatFloat(parts[i]));
        }
    }

    @Override
    public boolean isEditing() {
        for (JeNumberInput input : inputs) {
            if (input.isEditing()) {
                return true;
            }
        }
        return false;
    }
}
