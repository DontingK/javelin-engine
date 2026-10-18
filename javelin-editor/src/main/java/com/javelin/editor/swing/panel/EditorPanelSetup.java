package com.javelin.editor.swing.panel;

import com.javelin.editor.swing.hierarchy.HierarchyPanel;
import com.javelin.editor.swing.viewport.ViewportTabs;
import com.javelin.editor.window.cef.CanvasSurface;

/**
 * 面板集中显式装配（Editor_Swing_Design §5.2——对齐 EditorMenuSetup 先例：
 * 不做注解扫描，启动期一次注册）。
 *
 * <p>M1 骨架：五占位 + 视口真身。viewport 即视口直挂通道（单 Canvas 不
 * reparent，§6）；层级面板已随 M2 实装（HierarchyPanel——原 /scene/tree 桥
 * 通道的 Java 直调形态）；其余四面板占位，随 M2~M4 逐个替换 factory 为实装组件。
 * closable 口径（2026-10-17 拍板）：仅控制台可关，恢复入口整体后置 M5「窗口」域。
 * 标签条口径（2026-10-18 复拍板）：常显、视口组除外——原「单面板不可关即
 * 无缝」条款作废（Unity 口径，tab 头即面板身份，见 DockTabs）。</p>
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
     * 装配面板注册表（幂等）。
     *
     * @param canvas Vulkan 视口画布（视口面板唯一真身——ViewportTabs 持有，
     *               Editor_Swing_Design §6）
     */
    public static void setup(CanvasSurface canvas) {
        if (setup) {
            return;
        }
        setup = true;
        PanelRegistry.register(PanelDescriptor.of("hierarchy", "层级")
                .minimumSize(200, 120)
                .factory(HierarchyPanel::new)
                .build());
        PanelRegistry.register(PanelDescriptor.of("project", "项目")
                .minimumSize(160, 120)
                .factory(() -> new PlaceholderPanel("项目面板", "M3"))
                .build());
        PanelRegistry.register(PanelDescriptor.of("console", "控制台")
                .closable(true)
                .minimumSize(160, 120)
                .factory(() -> new PlaceholderPanel("控制台面板", "M2"))
                .build());
        PanelRegistry.register(PanelDescriptor.of(VIEWPORT_PANEL_ID, "视口")
                .factory(() -> new ViewportTabs(canvas))
                .build());
        PanelRegistry.register(PanelDescriptor.of("inspector", "属性")
                .minimumSize(260, 120)
                .factory(() -> new PlaceholderPanel("属性面板", "M4"))
                .build());
    }
}
