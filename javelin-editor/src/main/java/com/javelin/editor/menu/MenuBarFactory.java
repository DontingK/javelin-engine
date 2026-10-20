package com.javelin.editor.menu;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.List;

/**
 * 顶栏 {@code JMenuBar} 构建（Editor_Swing_Design §4.1——MenuRegistry 单源，
 * 命令域零改动，项构建复用 {@link MenuPopupFactory} 的弹窗装填逻辑）。
 *
 * <p>须在 {@code EditorMenuSetup} 装配后调用（启动时序：{@code initialize}
 * 建窗早于 {@code onInitialize} 菜单注册——故由 MainFrame.installMenuBar
 * 在菜单注册后驱动，Editor_Swing_Design §4.1）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class MenuBarFactory {

    private MenuBarFactory() {
        // 静态工厂禁止实例化
    }

    /**
     * 构建顶栏菜单（MenuRegistry.topMenus 单源；壳域全未注册跳过，容忍分批落地）。
     *
     * @return 菜单条
     */
    public static JMenuBar build() {
        JMenuBar bar = new JMenuBar();
        for (MenuDef def : MenuRegistry.topMenus()) {
            JMenu menu = new JMenu(def.getLabel().text());
            List<MenuDef.MenuEntry> entries = def.getEntries();
            MenuPopupFactory.fill(menu.getPopupMenu(), entries, def.getId());
            MenuPopupFactory.widen(menu.getPopupMenu());
            if (menu.getPopupMenu().getComponentCount() == 0) {
                continue;
            }
            bar.add(menu);
        }
        return bar;
    }
}
