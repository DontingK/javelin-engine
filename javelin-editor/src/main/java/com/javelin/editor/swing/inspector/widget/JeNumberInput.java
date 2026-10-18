package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JTextField;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;

/**
 * 数字输入（INT/FLOAT 通道及 VectorRow 段）：浮点显示 4 位小数（对齐收集侧
 * formatFloat 口径）、有 range 硬钳 [min,max]、非法输入 blur 回滚、enter/blur
 * 提交且值数值等价不回调（"1.0" 与 "1" 不重复上报）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class JeNumberInput extends JTextField implements FieldEditor {

    private final boolean integer;
    private final InspectorFieldVo.Range range;
    private final Consumer<String> commit;
    private String lastCommitted;

    public JeNumberInput(String initial, boolean integer, InspectorFieldVo.Range range,
            Consumer<String> commit) {
        super(initial != null && !initial.isBlank() ? initial.trim() : "0");
        this.integer = integer;
        this.range = range;
        this.commit = commit;
        this.lastCommitted = getText();
        addActionListener(e -> commitNow());
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitNow();
            }
        });
    }

    /** 提交（enter/blur）：解析失败回滚旧值；成功归一显示 + 值真变才回调 */
    private void commitNow() {
        Float parsed = parse(getText());
        if (parsed == null) {
            setText(lastCommitted);
            return;
        }
        String normalized = normalize(parsed);
        setText(normalized);
        if (equivalent(lastCommitted, normalized)) {
            lastCommitted = normalized;
            return;
        }
        lastCommitted = normalized;
        commit.accept(normalized);
    }

    private Float parse(String text) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** range 硬钳（min/max 注解投影；null 无约束） */
    private float clamp(float value) {
        if (range != null) {
            value = Math.max(range.min, Math.min(range.max, value));
        }
        return value;
    }

    /** 解析值 → 归一显示串（整数直取整；浮点 4 位小数口径） */
    private String normalize(float value) {
        float clamped = clamp(value);
        return integer ? String.valueOf((int) clamped) : InsFormat.formatFloat(clamped);
    }

    private boolean equivalent(String a, String b) {
        Float fa = parse(a);
        Float fb = parse(b);
        return fa != null && fb != null && Float.compare(fa, fb) == 0;
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        if (isEditing()) {
            return;
        }
        Float parsed = wireValue != null ? parse(wireValue) : null;
        if (parsed == null) {
            return;
        }
        String normalized = normalize(parsed);
        if (equivalent(getText(), normalized)) {
            lastCommitted = normalized;
            return;
        }
        setText(normalized);
        lastCommitted = normalized;
    }

    @Override
    public boolean isEditing() {
        return hasFocus() && !equivalent(lastCommitted, getText());
    }
}
