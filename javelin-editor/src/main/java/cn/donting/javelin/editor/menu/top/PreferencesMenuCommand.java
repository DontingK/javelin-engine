package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.editor.window.PreferencesDialog;

import javax.swing.SwingUtilities;

/**
 * 编辑器首选项命令（文件域）：弹 Swing 首选项对话框（语言设置首批，2026-10-29），
 * 确定写回 EditorSettings 存盘、重启生效。
 *
 * <p>模态 setVisible 阻塞至对话框关闭；命令可能在非 EDT 上下文触发，统一
 * invokeLater 弹窗（对齐 PackDialog 线程契约）。</p>
 *
 * @author javelin
 * @create 2026/10/29
 */
public final class PreferencesMenuCommand extends TopMenuCommand {

    @Override
    public String id() {
        return "file.preferences";
    }

    @Override
    public void execute() {
        SwingUtilities.invokeLater(() -> {
            PreferencesDialog dialog = new PreferencesDialog();
            dialog.setVisible(true);
        });
    }
}
