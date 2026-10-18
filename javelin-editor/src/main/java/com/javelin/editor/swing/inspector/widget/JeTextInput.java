package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JTextField;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;

/**
 * 文本输入（STRING 通道 + 节点头 name 行）：enter/blur 提交、值真变才回调
 * （原样字符串无解析）。编辑判定=焦点内且文本与已提交值不等（面板重建保护）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class JeTextInput extends JTextField implements FieldEditor {

    private final Consumer<String> commit;
    private String lastCommitted;

    public JeTextInput(String initial, Consumer<String> commit) {
        super(initial != null ? initial : "");
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

    /** 提交（enter/blur）：值真变才回调（原样文本） */
    private void commitNow() {
        String text = getText();
        if (text.equals(lastCommitted)) {
            return;
        }
        lastCommitted = text;
        commit.accept(text);
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
        String text = wireValue != null ? wireValue : "";
        if (text.equals(getText())) {
            return;
        }
        setText(text);
        lastCommitted = text;
    }

    @Override
    public boolean isEditing() {
        return hasFocus() && !getText().equals(lastCommitted);
    }
}
