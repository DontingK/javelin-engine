package com.javelin.editor.swing.panel;

import com.javelin.editor.plugin.PluginContext;
import com.javelin.editor.swing.hierarchy.HierarchyPanel;
import com.javelin.editor.swing.inspector.InspectorPanel;
import com.javelin.editor.swing.project.ProjectPanel;
import com.javelin.editor.swing.viewport.ViewportTabs;
import com.javelin.editor.window.MainFrame;

/**
 * 面板集中显式装配（Editor_Swing_Design §5.2——对齐菜单/文件类型域先例：
 * 不做注解扫描，插件装载期一次注册）。接线在 EditorCorePlugin.setup——
 * 早于建窗，视口画布经 MainFrame 懒取（factory 求值发生在 DockRoot 装配，
 * 画布已建）。
 *
 * <p>五面板：层级（M2）/项目（M3）/检查器（M4）已实装，控制台占位（M2 收口），
 * 视口真身（单 Canvas 不 reparent，§6）。closable 口径（2026-10-17 拍板）：
 * 仅控制台可关，恢复入口整体后置 M5「窗口」域。标签条口径（2026-10-18
 * 复拍板）：常显、视口组除外（Unity 口径，tab 头即面板身份，见 DockTabs）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class EditorPanelSetup {

    /** 视口面板 id（Dock 弹性侧策略引用——含此面板一侧吸收容器尺寸变化） */
    public static final String VIEWPORT_PANEL_ID = "viewport";

    private static boolean setup;

    private EditorPanelSetup() {
        // 静态装配禁止实例化
    }

    /**
     * 装配面板注册表（幂等，经插件上下文收口）。
     *
     * @param context 插件上下文
     */
    public static void setup(PluginContext context) {
        if (setup) {
            return;
        }
        setup = true;
        context.registerPanel(PanelDescriptor.of("hierarchy", "层级")
                .minimumSize(200, 120)
                .factory(HierarchyPanel::new)
                .build());
        context.registerPanel(PanelDescriptor.of("project", "项目")
                .minimumSize(160, 120)
                .factory(ProjectPanel::new)
                .build());
        context.registerPanel(PanelDescriptor.of("console", "控制台")
                .closable(true)
                .minimumSize(160, 120)
                .factory(() -> new PlaceholderPanel("控制台面板", "M2"))
                .build());
        context.registerPanel(PanelDescriptor.of(VIEWPORT_PANEL_ID, "视口")
                .factory(() -> new ViewportTabs(MainFrame.getInstance().getCanvas()))
                .build());
        context.registerPanel(PanelDescriptor.of("inspector", "检查器")
                .minimumSize(260, 120)
                .factory(InspectorPanel::new)
                .build());
    }
}
