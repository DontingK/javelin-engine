package com.javelin.editor.menu.top;

import com.javelin.editor.window.cef.MainFrame;

import javax.swing.SwingUtilities;

/**
 * 开发者工具命令（工具域）：打开 CEF DevTools——兼事件桥 ping/pong 自检的
 * 观察入口。
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class DevToolsMenuCommand extends TopMenuCommand {

    @Override
    public String id() {
        return "tools.devtools";
    }

    @Override
    public String label() {
        return "开发者工具";
    }

    @Override
    public void execute() {
        // 动 CEF 跳 EDT（线程契约，设计档 §3.3）
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = MainFrame.getInstance();
            if (frame != null && frame.getCefBrowser() != null) {
                frame.openDevTools();
            }
        });
    }
}
