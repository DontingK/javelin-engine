package com.javelin.editor.menu;

import com.javelin.editor.window.cef.MainFrame;
import com.javelin.editor.swing.SvgIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * 菜单 Swing 弹窗（顶栏/面板右键共用，设计档 Editor_Menu_Design §5——v0.4 起菜单
 * 渲染统一 Swing：Web 浮层菜单会被视口 Canvas 盖，按位置判定不可靠）。
 *
 * <p>web 顶栏标签点击或面板右键 → /menu/open 传 web 坐标（顶栏 = 标签左缘/
 * 顶栏下缘，右键 = 鼠标点）——CEF 组件铺满 contentPane，web 坐标即 contentPane
 * 坐标（同 rectChange 口径），直接 popup.show(contentPane, x, y)。Swing
 * heavyweight popup 天然浮于视口 Canvas（挖洞遮挡约束下唯一的菜单渲染形态）。
 * 显示前（popupMenuWillBecomeVisible）逐项求值 enabled/checked。桥线程调用，
 * invokeLater 跳 EDT 弹窗，永不阻塞。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuPopupFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(MenuPopupFactory.class);

    /** 对齐 web 浅色主题（style.css --editor-* 默认档；深色档 html[data-theme=dark] 切换落地时
     *  此三常量须改读主题——Swing 不读 CSS；hover 高亮留 LAF 默认，不做深度定制） */
    private static final Color POPUP_BG = new Color(0xFF, 0xFF, 0xFF);      // --editor-surface
    private static final Color POPUP_FG = new Color(0x33, 0x37, 0x3D);      // --editor-text
    private static final Color POPUP_BORDER = new Color(0xD0, 0xD4, 0xD8);  // --editor-border
    /** 菜单最小宽度（px）：项少/字短时垫宽（顶层与子菜单同口径），可调 */
    private static final int MIN_WIDTH = 140;
    /** 菜单图标尺寸（px，菜单行标准档） */
    private static final int ICON_SIZE = 16;

    private MenuPopupFactory() {
        // 静态门面禁止实例化
    }

    /**
     * 弹出菜单（顶栏/面板右键共用）。
     *
     * @param menuId 菜单域 id（顶栏菜单 id 或右键域如 ctx.hierarchy）
     * @param x      web 坐标 x（顶栏=标签左缘、右键=鼠标 x）
     * @param y      web 坐标 y（顶栏=顶栏下缘、右键=鼠标 y）
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

    /** 构建弹出菜单：项构建（含即时状态）+ 显示前状态刷新钩子（子菜单分层刷新） */
    private static JPopupMenu build(MenuDef def) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(POPUP_BG);
        popup.setBorder(BorderFactory.createLineBorder(POPUP_BORDER));
        addEntries(popup, def.getEntries(), def.getId());
        widen(popup);
        return popup;
    }

    /**
     * 向弹窗容器填装条目：顶层容器 = JPopupMenu，子菜单 = JMenu.getPopupMenu() 递归
     * 嵌套（子菜单浮层同由 PopupFactory 决定重量级，浮于视口 Canvas 无虞）。每层挂
     * 各自的显示前刷新钩子——子菜单项在该层打开瞬间求值 enabled/checked，瞬态状态
     * 显示瞬间算一次，天然新鲜。子项全未注册的空壳子菜单跳过不显示（命令未注册
     * 不算错误，容忍分批落地）。
     */
    private static void addEntries(JPopupMenu popup, List<MenuDef.MenuEntry> entries, String path) {
        List<ItemBinding> bindings = new ArrayList<>();
        for (MenuDef.MenuEntry entry : entries) {
            if (entry.isSeparator()) {
                popup.addSeparator();
                continue;
            }
            if (entry.isSubmenu()) {
                JMenu menu = new JMenu(entry.getSubmenuLabel());
                menu.setBackground(POPUP_BG);
                menu.setForeground(POPUP_FG);
                String subPath = path + ">" + entry.getSubmenuLabel();
                addEntries(menu.getPopupMenu(), entry.getChildren(), subPath);
                if (menu.getPopupMenu().getComponentCount() == 0) {
                    continue;
                }
                widen(menu.getPopupMenu());
                popup.add(menu);
                continue;
            }
            Command command = CommandService.get(entry.getCommandId());
            if (command == null) {
                LOGGER.warn("菜单项命令未注册，跳过: {}#{}", path, entry.getCommandId());
                continue;
            }
            JMenuItem item = command.isCheckable()
                    ? new JCheckBoxMenuItem(command.getLabel())
                    : new JMenuItem(command.getLabel());
            item.setBackground(POPUP_BG);
            item.setForeground(POPUP_FG);
            accelerator(item, command);
            icon(item, command);
            item.addActionListener(e -> CommandService.execute(command.getId()));
            refresh(item, command);
            popup.add(item);
            bindings.add(new ItemBinding(item, command));
        }
        if (bindings.isEmpty()) {
            return;
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
    }

    /**
     * 最小宽度垫宽：Swing 弹窗按 preferred 尺寸开窗（setMinimumSize 不参与开窗
     * 尺寸计算），preferred 宽不足 MIN_WIDTH 时直接改写 preferred 宽，高度取当前
     * 自然值（条目集合启动期固定，瞬态 enabled/checked 求值不改尺寸，无漂移）。
     */
    private static void widen(JPopupMenu popup) {
        Dimension pref = popup.getPreferredSize();
        if (pref.width < MIN_WIDTH) {
            popup.setPreferredSize(new Dimension(MIN_WIDTH, pref.height));
        }
    }

    /**
     * 快捷键显示：shortcut 文本设为 accelerator——UI 右对齐显示；绑定在菜单项自身
     * WHEN_IN_FOCUSED_WINDOW InputMap（BasicMenuItemUI.updateAcceleratorBinding），
     * 仅弹窗显示期间生效、收起即无宿主，无全局触发（「shortcut 第一期仅显示」）。
     * 解析失败记 warn 跳过（不影响菜单项本身）。
     */
    private static void accelerator(JMenuItem item, Command command) {
        if (command.getShortcut() == null || command.getShortcut().isBlank()) {
            return;
        }
        KeyStroke stroke = toKeyStroke(command.getShortcut());
        if (stroke != null) {
            item.setAccelerator(stroke);
        }
    }

    /**
     * 菜单项图标（/icon/{name}.svg 经 SvgIcon 渲染）。勾选命令不配——勾选标记
     * 与图标同槽渲染会挤占（Unity 口径勾选项无图标）；资源缺失不设置（不占空位，
     * 也避免 SvgIcon 缺资源时逐帧 paint 警告）。
     */
    private static void icon(JMenuItem item, Command command) {
        String name = command.getIcon();
        if (name == null || command.isCheckable()) {
            return;
        }
        SvgIcon svgIcon = SvgIcon.of(name, ICON_SIZE);
        if (svgIcon.isAvailable()) {
            item.setIcon(svgIcon);
        }
    }

    /** shortcut 显示文本 → KeyStroke（"Ctrl+C" → "ctrl C" 口径，键名转 VK 名） */
    private static KeyStroke toKeyStroke(String shortcut) {
        try {
            String[] tokens = shortcut.split("\\+");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length - 1; i++) {
                switch (tokens[i].trim().toLowerCase()) {
                    case "ctrl", "control" -> sb.append("ctrl ");
                    case "shift" -> sb.append("shift ");
                    case "alt" -> sb.append("alt ");
                    case "meta" -> sb.append("meta ");
                    default -> throw new IllegalArgumentException("未知修饰键: " + tokens[i]);
                }
            }
            sb.append(keyName(tokens[tokens.length - 1].trim()));
            return KeyStroke.getKeyStroke(sb.toString());
        } catch (Exception e) {
            LOGGER.warn("快捷键文本无法解析为 KeyStroke，跳过显示: {}", shortcut);
            return null;
        }
    }

    /** 键名 → KeyEvent VK 名（映射常用缩写，其余原样大写：C/F2/Enter） */
    private static String keyName(String key) {
        return switch (key.toLowerCase()) {
            case "del", "delete" -> "DELETE";
            case "ins", "insert" -> "INSERT";
            case "esc", "escape" -> "ESCAPE";
            case "space" -> "SPACE";
            case "pgup", "pageup" -> "PAGE_UP";
            case "pgdn", "pagedown" -> "PAGE_DOWN";
            default -> key.toUpperCase();
        };
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
