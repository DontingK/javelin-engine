package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;

/**
 * 文本输入（STRING 通道 + 节点头 name 行）：默认 enter/blur 提交、值真变才回调
 * （原样字符串无解析）；需要输入即应用的使用方可选挂 {@code onInputChanged}
 * （null 不挂=只终提交；挂了则每键回调且已提交值同步跟进——blur 不再重复）。
 * 编辑判定=焦点内且文本与已提交值不等（面板重建保护）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class JeTextInput extends JTextField implements FieldEditor {

    private final Consumer<String> commit;
    private final Consumer<String> onInputChanged;
    private String lastCommitted;
    /** 程序化 setText 抑制文档监听（防外源刷新被当输入回声提交） */
    private boolean suppress;

    public JeTextInput(String initial, Consumer<String> commit) {
        this(initial, commit, null);
    }

    /**
     * @param onInputChanged 输入即变化回调（可选；null=仅 enter/blur 终提交）
     */
    public JeTextInput(String initial, Consumer<String> commit, Consumer<String> onInputChanged) {
        super(initial != null ? initial : "");
        this.commit = commit;
        this.onInputChanged = onInputChanged;
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
                inputChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                inputChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    /** 输入即变化（文档变化）：使用方挂了 onInputChanged 才回调（程序化 setText 跳过） */
    private void inputChanged() {
        if (suppress) {
            return;
        }
        if (onInputChanged == null) {
            return;
        }
        String text = getText();
        if (text.equals(lastCommitted)) {
            return;
        }
        lastCommitted = text;
        onInputChanged.accept(text);
    }

    /** 终提交（enter/blur）：值真变才回调（原样文本） */
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
        suppress = true;
        try {
            setText(text);
        } finally {
            suppress = false;
        }
        lastCommitted = text;
    }

    @Override
    public boolean isEditing() {
        return hasFocus() && !getText().equals(lastCommitted);
    }
}
