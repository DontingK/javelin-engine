package com.javelin.editor.menu;

import com.javelin.editor.window.cef.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 顶栏菜单 Swing 弹窗（设计档 Editor_Menu_Design §5.1）。
 *
 * <p>web 顶栏标签点击 → /menu/open 传 web 坐标（x = 标签左缘、y = 顶栏下缘）——
 * CEF 组件铺满 contentPane，web 坐标即 contentPane 坐标（同 rectChange 口径），
 * 直接 popup.show(contentPane, x, y)。Swing heavyweight popup 天然浮于视口 Canvas
 * （挖洞遮挡约束下的顶栏渲染形态）。显示前（popupMenuWillBecomeVisible）逐项求值
 * enabled/checked。桥线程调用，invokeLater 跳 EDT 弹窗，永不阻塞。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuPopupFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(MenuPopupFactory.class);

    /** 对齐 web 浅色主题（style.css --editor-* 色板；hover 高亮留 LAF 默认，不做深度定制） */
    private static final Color POPUP_BG = new Color(0xFF, 0xFF, 0xFF);      // --editor-surface
    private static final Color POPUP_FG = new Color(0x34, 0x3A, 0x40);      // --editor-text
    private static final Color POPUP_BORDER = new Color(0xDE, 0xE2, 0xE6);  // --editor-border

    private MenuPopupFactory() {
        // 静态门面禁止实例化
    }

    /**
     * 弹出顶栏菜单。
     *
     * @param menuId 菜单域 id
     * @param x      web 坐标 x（标签左缘）
     * @param y      web 坐标 y（顶栏下缘）
     */
    public static void show(String menuId, int x, int y) {
        if (menuId == null || menuId.isBlank()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = MainFrame.getInstance();
            if (frame == null || frame.getCefBrowser() == null) {
                LOGGER.warn("主窗口未就绪，菜单弹出跳过: {}", menuId);
                return;
            }
            MenuDef def = MenuRegistry.get(menuId);
            if (def == null) {
                LOGGER.warn("菜单域未注册: {}", menuId);
                return;
            }
            build(def).show(frame.getContentPane(), x, y);
        });
    }

    /** 构建弹出菜单：项构建（含即时状态）+ 显示前状态刷新钩子 */
    private static JPopupMenu build(MenuDef def) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(POPUP_BG);
        popup.setBorder(BorderFactory.createLineBorder(POPUP_BORDER));
        List<ItemBinding> bindings = new ArrayList<>();
        for (MenuDef.MenuEntry entry : def.getEntries()) {
            if (entry.isSeparator()) {
                popup.addSeparator();
                continue;
            }
            Command command = CommandService.get(entry.getCommandId());
            if (command == null) {
                LOGGER.warn("菜单项命令未注册，跳过: {}#{}", def.getId(), entry.getCommandId());
                continue;
            }
            JMenuItem item = command.isCheckable()
                    ? new JCheckBoxMenuItem(command.getLabel())
                    : new JMenuItem(command.getLabel());
            item.setBackground(POPUP_BG);
            item.setForeground(POPUP_FG);
            item.addActionListener(e -> CommandService.execute(command.getId()));
            refresh(item, command);
            popup.add(item);
            bindings.add(new ItemBinding(item, command));
        }
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                for (ItemBinding binding : bindings) {
                    refresh(binding.item, binding.command);
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        return popup;
    }

    /** 单项状态求值：置灰 + 勾选态 */
    private static void refresh(JMenuItem item, Command command) {
        item.setEnabled(command.isEnabled());
        if (command.isCheckable() && item instanceof JCheckBoxMenuItem) {
            ((JCheckBoxMenuItem) item).setSelected(command.isChecked());
        }
    }

    /** 菜单项与命令的绑定（显示前批量刷新用） */
    private static final class ItemBinding {
        final JMenuItem item;
        final Command command;

        ItemBinding(JMenuItem item, Command command) {
            this.item = item;
            this.command = command;
        }
    }
}
