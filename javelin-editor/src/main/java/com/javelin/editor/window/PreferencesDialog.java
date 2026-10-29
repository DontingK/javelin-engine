package com.javelin.editor.window;

import com.javelin.editor.JavelinEditor;
import com.javelin.editor.i18n.I18n;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.settings.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编辑器首选项对话框（语言设置首批，后续设置项按需扩行）。
 *
 * <p>语言选项源 = I18n 语言注册表（插件 setup 期 registerLanguage 写入，注册序）；
 * 确定 = 写回 EditorSettings 并存盘，全部配置修改走「重启生效」口径
 * （2026-10-29 拍板——I18n 语言启动期一次设定，运行中不热切）。</p>
 *
 * @author javelin
 * @create 2026/10/29
 */
public class PreferencesDialog extends JDialog {

    private final JComboBox<String> languageCombo = new JComboBox<>();
    /** combo 选中下标 ↔ 注册语言 fileName 的并行映射（displayName 展示用） */
    private final List<String> languageFileNames = new ArrayList<>();
    /** 打开时生效的语言（确定时比对是否有变更） */
    private final String currentLanguage;

    public PreferencesDialog() {
        super(MainFrame.getInstance(), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setTitle(EditorLang.text("preferences.dialog.title"));
        // 回显 = 生效语言：当前语言标识在注册表中即它本身，否则实际展示走
        // I18n 查表逐键回退默认（如首启系统语言无语言包），回显默认语言
        Map<String, String> languages = I18n.languages();
        currentLanguage = languages.containsKey(I18n.language())
                ? I18n.language() : I18n.DEFAULT_LANGUAGE;
        setLocationRelativeTo(getOwner());
        buildContent(languages);
        pack();
    }

    // ---------- 界面装配 ----------

    private void buildContent(Map<String, String> languages) {
        JPanel root = new JPanel(new BorderLayout(0, 2));
        root.setBorder(new EmptyBorder(2, 2, 2, 2));
        root.add(buildFormPane(languages), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(root, BorderLayout.CENTER);
    }

    /** 表单区：语言行（label 列 4px 间隔，行距 2px——Inspector 例外间隔同口径） */
    private JComponent buildFormPane(Map<String, String> languages) {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.PAGE_AXIS));
        form.add(formRow(EditorLang.text("preferences.language.label"), languageCombo));
        String selected = null;
        for (Map.Entry<String, String> entry : languages.entrySet()) {
            languageCombo.addItem(entry.getValue());
            languageFileNames.add(entry.getKey());
            if (entry.getKey().equals(currentLanguage)) {
                selected = entry.getValue();
            }
        }
        if (selected != null) {
            languageCombo.setSelectedItem(selected);
        }
        return form;
    }

    /** 表单行：label 固定列宽 + 控件占余宽 */
    private JPanel formRow(String label, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel labelComp = new JLabel(label);
        labelComp.setPreferredSize(new Dimension(80, labelComp.getPreferredSize().height));
        row.add(labelComp, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    /** 底部按钮行：确定（右对齐，窗右 2px 内边距口径） */
    private JComponent buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        JButton okButton = new JButton(EditorLang.text("preferences.ok"));
        okButton.addActionListener(e -> onOk());
        footer.add(okButton);
        return footer;
    }

    // ---------- 行为 ----------

    /** 确定：语言有变才写盘，随后提示重启生效 */
    private void onOk() {
        int index = languageCombo.getSelectedIndex();
        if (index < 0 || index >= languageFileNames.size()) {
            dispose();
            return;
        }
        String selected = languageFileNames.get(index);
        if (selected.equals(currentLanguage)) {
            dispose();
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            JOptionPane.showMessageDialog(this, EditorLang.text("preferences.editor.missing"),
                    EditorLang.text("preferences.dialog.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        SettingsManager settingsManager = editor.getSettingsManager();
        settingsManager.getEditorSettings().setLanguage(selected);
        settingsManager.saveEditorSettings();
        dispose();
        JOptionPane.showMessageDialog(getOwner(), EditorLang.text("preferences.restart.hint"),
                EditorLang.text("preferences.dialog.title"), JOptionPane.INFORMATION_MESSAGE);
    }
}
