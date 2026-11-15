package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.core.scene.Tag;
import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 标签清单行（STRING_LIST + @InsTagList）：名字只读、逐行删除、底部「+」弹框新增。
 *
 * <p>Tag 以名字为身份，改名等于另一个标签（已引用节点不会跟着变），故这里**不提供行内编辑**。
 * 内建 {@link Tag#UNTAGGED} 的删除按钮禁用。任一增删即整表回写（wire = 元素逐行拼接，
 * 空表为 null，与 {@code STRING_LIST} 同一格式）。</p>
 *
 * @author javelin
 * @create 2026/11/15
 */
public class TagListField extends JPanel implements FieldEditor {

    /** 行间/控件间距（间距规范 ≤2） */
    private static final int ROW_GAP = 2;

    private final Consumer<String> commit;
    private final JPanel rowsPanel = new JPanel();
    private final List<String> names = new ArrayList<>();

    public TagListField(InspectorFieldVo vo, Consumer<String> commit) {
        this.commit = commit;
        setLayout(new BorderLayout(0, ROW_GAP));
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.PAGE_AXIS));
        add(rowsPanel, BorderLayout.CENTER);
        add(buttonRow(), BorderLayout.SOUTH);
        rebuild(split(vo.value));
    }

    // ── 装配 ─────────────────────────────────────────────────

    /** 单条标签行：只读名字 + 行尾删除按钮（Untagged 禁止删除） */
    private JPanel row(int index, String name) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel label = new JLabel(name);
        row.add(label, BorderLayout.CENTER);

        JButton remove = plainButton("\u00d7", EditorLang.text("tagList.removeHint"));
        boolean locked = Tag.UNTAGGED.equals(name);
        remove.setEnabled(!locked);
        if (locked) {
            remove.setToolTipText(EditorLang.text("tagList.untaggedLocked"));
        }
        remove.addActionListener(e -> {
            List<String> next = new ArrayList<>(names);
            next.remove(index);
            rebuild(next);
            syncRowHeight();
            commitAll();
        });
        row.add(remove, BorderLayout.EAST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** 新增按钮条（弹框输入名字，重名/空名拒绝） */
    private JPanel buttonRow() {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, ROW_GAP, 0));
        JButton add = plainButton("+", EditorLang.text("tagList.addHint"));
        add.addActionListener(e -> {
            String input = JOptionPane.showInputDialog(this,
                    EditorLang.text("tagList.addPrompt"),
                    EditorLang.text("tagList.addTitle"), JOptionPane.PLAIN_MESSAGE);
            if (input == null) {
                return;
            }
            String name = input.trim();
            if (name.isEmpty()) {
                showError(EditorLang.text("tagList.nameEmpty"));
                return;
            }
            if (names.contains(name)) {
                showError(EditorLang.text("tagList.duplicate", name));
                return;
            }
            List<String> next = new ArrayList<>(names);
            next.add(name);
            rebuild(next);
            syncRowHeight();
            commitAll();
        });
        strip.add(add);
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        return strip;
    }

    /** 无边框紧凑按钮（尺寸按文字） */
    private static JButton plainButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        return button;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message,
                EditorLang.text("tagList.addTitle"), JOptionPane.WARNING_MESSAGE);
    }

    // ── 数据 ─────────────────────────────────────────────────

    /** 重建全部行（增删后用；names 已是新状态） */
    private void rebuild(List<String> elements) {
        names.clear();
        names.addAll(elements);
        rowsPanel.removeAll();
        for (int i = 0; i < names.size(); i++) {
            rowsPanel.add(row(i, names.get(i)));
        }
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    /** 整表提交（空表交 null，与 parseValue 的空列表语义对齐） */
    private void commitAll() {
        if (names.isEmpty()) {
            commit.accept(null);
            return;
        }
        commit.accept(String.join("\n", names));
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
        List<String> elements = split(wireValue);
        if (elements.equals(names)) {
            return;
        }
        rebuild(elements);
        syncRowHeight();
    }

    /** 名字只读，本控件没有编辑中态 */
    @Override
    public boolean isEditing() {
        return false;
    }
}
