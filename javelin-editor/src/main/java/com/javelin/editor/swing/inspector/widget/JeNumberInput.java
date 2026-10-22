package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;

/**
 * 数字输入（INT/FLOAT 通道及 VectorRow 段）：浮点显示 4 位小数（对齐收集侧
 * formatFloat 口径）、有 range 硬钳 [min,max]。提交=输入即提交（可解析即回写，
 * 不动显示文本保光标）；非法中间态（"-"、"0."、空串）跳过等 enter/blur 回滚
 * 归一显示。label 按住左键左右拖调值（{@link ScrubDragger}；每像素步长默认
 * 整 1/浮 0.1，{@link #setScrubStep} 可覆盖）。值数值等价不回调
 * （"1.0" 与 "1" 不重复上报）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class JeNumberInput extends JTextField implements FieldEditor {

    private final boolean integer;
    private final InspectorFieldVo.Range range;
    private final Consumer<String> commit;
    private String lastCommitted;
    /** 拖动调值每像素步长（0=禁用；默认整 1/浮 0.1，range 字段由使用方覆盖为注解 step） */
    private float scrubStep;
    /** 程序化 setText 抑制文档监听（防外源刷新被当输入回声提交） */
    private boolean suppress;

    public JeNumberInput(String initial, boolean integer, InspectorFieldVo.Range range,
            Consumer<String> commit) {
        super(initial != null && !initial.isBlank() ? initial.trim() : "0");
        this.integer = integer;
        this.range = range;
        this.commit = commit;
        this.scrubStep = integer ? 1 : 0.1f;
        this.lastCommitted = getText();
        addActionListener(e -> commitNow());
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitNow();
            }
        });
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                commitLive();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                commitLive();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    /** 输入即提交（文档变化）：可解析即钳制回写；不动显示文本保光标，非法中间态跳过 */
    private void commitLive() {
        if (suppress) {
            return;
        }
        Float parsed = parse(getText());
        if (parsed == null) {
            return;
        }
        String normalized = normalize(parsed);
        if (equivalent(lastCommitted, normalized)) {
            lastCommitted = normalized;
            return;
        }
        lastCommitted = normalized;
        commit.accept(normalized);
    }

    /** 终提交（enter/blur）：解析失败回滚旧值；成功归一显示 + 值真变才回调 */
    private void commitNow() {
        Float parsed = parse(getText());
        if (parsed == null) {
            setTextQuietly(lastCommitted);
            return;
        }
        String normalized = normalize(parsed);
        setTextQuietly(normalized);
        if (equivalent(lastCommitted, normalized)) {
            lastCommitted = normalized;
            return;
        }
        lastCommitted = normalized;
        commit.accept(normalized);
    }

    /** 程序化 setText（回滚/归一显示/外源刷新），跳过文档监听 */
    private void setTextQuietly(String text) {
        suppress = true;
        try {
            setText(text);
        } finally {
            suppress = false;
        }
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
        setTextQuietly(normalized);
        lastCommitted = normalized;
    }

    @Override
    public boolean isEditing() {
        return hasFocus() && !equivalent(lastCommitted, getText());
    }

    /** 已提交值（归一/已钳制态；VectorRow 组包用——live 输入期显示文本仍是原始态） */
    public String committedText() {
        return lastCommitted;
    }

    /** 拖动步长覆盖（0=禁用拖动调值；range 字段应传注解 step） */
    public void setScrubStep(float step) {
        this.scrubStep = step;
    }

    /** 当前可解析值（scrub 拖动起点；非法中间态 null） */
    Float currentValue() {
        return parse(getText());
    }

    /** scrub 拖动每像素步长 */
    float getScrubStep() {
        return scrubStep;
    }

    /** 拖动调值落点（label scrub）：钳制归一后终提交——拖动期间逐次调用=实时应用 */
    void scrubTo(float value) {
        setTextQuietly(normalize(value));
        commitNow();
    }
}
