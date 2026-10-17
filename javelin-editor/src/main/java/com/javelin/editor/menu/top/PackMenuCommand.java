package com.javelin.editor.menu.top;

import com.javelin.editor.window.PackDialog;

import javax.swing.SwingUtilities;

/**
 * 打包命令（构建域）：弹 Swing 打包对话框（2026-10-17 随去 Web 化由 CEF pack.html
 * 迁移，Editor_Swing_Design §9 M6 项提前），配置打包选项（构建形态/启动场景/
 * 构建模式/窗口元信息）并驱动构建（构建在对话框后台线程，日志区实时展示）。
 *
 * <p>模态 setVisible 阻塞至对话框关闭；命令可能在非 EDT 上下文触发，统一
 * invokeLater 弹窗（对齐旧 CEF Dialog 线程契约）。</p>
 *
 * @author javelin
 * @version 1.1
 * @create 2026/10/11
 */
public final class PackMenuCommand extends TopMenuCommand {

    @Override
    public String id() {
        return "build.pack";
    }

    @Override
    public String label() {
        return "打包";
    }

    @Override
    public void execute() {
        SwingUtilities.invokeLater(() -> {
            PackDialog dialog = new PackDialog();
            dialog.setVisible(true);
        });
    }
}
