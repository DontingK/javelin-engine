package com.javelin.editor.menu.top;

import com.javelin.editor.window.cef.MainFrame;

import javax.swing.SwingUtilities;

/**
 * 刷新页面命令（工具域）：重载 CEF 内的编辑器 web 页（CefBrowser.reload）。
 *
 * <p>引擎/场景侧不重启（Java 状态不动），web UI 全量重挂——面板走 pull-on-mount
 * 兜底重新拉取（场景树/Inspector 的启动竞态节流重试即为此设计）。开发期 web 界面
 * 状态异常时的恢复入口。动 CEF 跳 EDT（线程契约同 DevToolsMenuCommand）。</p>
 *
 * @author javelin
 * @create 2026/10/11
 */
public final class RefreshPageMenuCommand extends TopMenuCommand {

    @Override
    public String id() {
        return "tools.refresh";
    }

    @Override
    public String label() {
        return "刷新页面";
    }

    @Override
    public String shortcut() {
        return "F5";
    }

    @Override
    public void execute() {
        // 动 CEF 跳 EDT（线程契约，设计档 §3.3）
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = MainFrame.getInstance();
            if (frame != null && frame.getCefBrowser() != null) {
                frame.getCefBrowser().reload();
            }
        });
    }
}
