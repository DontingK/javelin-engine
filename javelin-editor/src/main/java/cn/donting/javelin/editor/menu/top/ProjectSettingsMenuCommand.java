package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.editor.swing.settings.ProjectSettingsWindow;
import cn.donting.javelin.editor.window.MainFrame;

import javax.swing.SwingUtilities;

/**
 * 项目设置命令（编辑域）：弹非模态「项目设置」窗口（对标 Unity Edit → Project Settings）。
 *
 * <p>窗口改完即时生效并写盘；非模态，命令可能在非 EDT 上下文触发，统一 invokeLater 弹窗
 * （对齐 PreferencesMenuCommand 线程契约）。</p>
 *
 * @author javelin
 * @create 2026/11/14
 */
public final class ProjectSettingsMenuCommand extends TopMenuCommand {

    @Override
    public String id() {
        return "edit.projectSettings";
    }

    @Override
    public void execute() {
        SwingUtilities.invokeLater(() -> ProjectSettingsWindow.show(MainFrame.getInstance()));
    }
}
