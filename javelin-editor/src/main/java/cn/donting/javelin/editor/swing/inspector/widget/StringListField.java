package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 字符串列表行（STRING_LIST 通道）：元素逐个 {@link JeTextInput} 竖排，任一行提交即整表回写
 * （wire = 元素逐行拼接，空表单为 null）。
 *
 * <p>两种形态：数组字段（如 {@code LayerSettings.layerNames}）= 定长、带序号列、不可增删
 * （层号不可移位）；List 字段 = 可增删，行尾 +/− 按钮驱动（{@code @InsTagList} 的标签清单另走
 * {@link TagListField}：名字只读、只增删）。
 * 结构变更后同步外层行最大高度（设置表单行按建表时的首选高度钉住，不同步会被压扁）。</p>
 *
 * @author javelin
 * @create 2026/11/15
 */
public class StringListField extends JPanel implements FieldEditor {

    /** 行间/控件间间距（间距规范 ≤2） */
    private static final int ROW_GAP = 2;
    /** 序号列宽（定长形态） */
    private static final int INDEX_WIDTH = 24;

    private final Consumer<String> commit;
    private final boolean indexed;
    private final boolean resizable;
    private final JPanel rowsPanel = new JPanel();
    private final List<JeTextInput> inputs = new ArrayList<>();

    public StringListField(InspectorFieldVo vo, Consumer<String> commit) {
        this.commit = commit;
        this.indexed = !vo.listResizable;
        this.resizable = vo.listResizable;
        setLayout(new BorderLayout(0, ROW_GAP));
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.PAGE_AXIS));
        add(rowsPanel, BorderLayout.CENTER);
        if (resizable) {
            add(buttonRow(), BorderLayout.SOUTH);
        }
        rebuild(split(vo.value));
    }

    // ── 装配 ─────────────────────────────────────────────────

    /** 单元素行：序号小标签（暗色）+ 文本输入 */
    private JPanel row(int index, String value) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        if (indexed) {
            JLabel label = new JLabel(String.valueOf(index));
            Color dim = UIManager.getColor("Label.disabledForeground");
            if (dim != null) {
                label.setForeground(dim);
            }
            label.setPreferredSize(new Dimension(INDEX_WIDTH, label.getPreferredSize().height));
            row.add(label, BorderLayout.WEST);
        }
        JeTextInput input = new JeTextInput(value, text -> commitAll());
        inputs.add(input);
        row.add(input, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** 增删按钮条（仅 List 形态） */
    private JPanel buttonRow() {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, ROW_GAP, 0));
        JButton add = plainButton("+", EditorLang.text("stringList.addHint"));
        add.addActionListener(e -> {
            List<String> elements = collect();
            elements.add("");
            rebuild(elements);
            syncRowHeight();
            commitAll();
        });
        JButton remove = plainButton("\u2212", EditorLang.text("stringList.removeHint"));
        remove.addActionListener(e -> {
            List<String> elements = collect();
            if (elements.isEmpty()) {
                return;
            }
            elements.remove(elements.size() - 1);
            rebuild(elements);
            syncRowHeight();
            commitAll();
        });
        strip.add(add);
        strip.add(remove);
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        return strip;
    }

    /** 无边框紧凑按钮（+/-，尺寸按文字） */
    private static JButton plainButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        return button;
    }

    // ── 数据 ─────────────────────────────────────────────────

    /** 重建全部行（结构变更用；先收集调用方数据再传入） */
    private void rebuild(List<String> elements) {
        rowsPanel.removeAll();
        inputs.clear();
        for (int i = 0; i < elements.size(); i++) {
            rowsPanel.add(row(i, elements.get(i)));
        }
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    /** 当前各行文本 */
    private List<String> collect() {
        List<String> elements = new ArrayList<>(inputs.size());
        for (JeTextInput input : inputs) {
            elements.add(input.getText());
        }
        return elements;
    }

    /** 整表提交（空表交 null，与 parseValue 的空列表语义对齐） */
    private void commitAll() {
        List<String> elements = collect();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(elements.get(i));
        }
        commit.accept(elements.isEmpty() ? null : sb.toString());
    }

    /** 结构变更后同步外层表单行高度（行最大高度按建表时首选高度钉死） */
    private void syncRowHeight() {
        Component parent = getParent();
        if (!(parent instanceof JComponent row)) {
            return;
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.revalidate();
        row.repaint();
    }

    /** wire → 元素清单（null=空表） */
    private static List<String> split(String wire) {
        List<String> elements = new ArrayList<>();
        if (wire == null) {
            return elements;
        }
        for (String line : wire.split("\n", -1)) {
            elements.add(line);
        }
        return elements;
    }

    // ── FieldEditor ──────────────────────────────────────────

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        if (isEditing()) {
            return;
        }
        List<String> elements = split(wireValue);
        if (elements.size() != inputs.size()) {
            rebuild(elements);
            syncRowHeight();
            return;
        }
        for (int i = 0; i < inputs.size(); i++) {
            inputs.get(i).setValue(elements.get(i));
        }
    }

    @Override
    public boolean isEditing() {
        for (JeTextInput input : inputs) {
            if (input.isEditing()) {
                return true;
            }
        }
        return false;
    }
}
