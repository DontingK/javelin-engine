package com.javelin.editor.jcef.method;


import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.MenuListPar;
import com.javelin.editor.jcef.domain.par.MenuOpenPar;
import com.javelin.editor.jcef.domain.vo.MenuItemVo;
import com.javelin.editor.menu.Command;
import com.javelin.editor.menu.CommandService;
import com.javelin.editor.menu.MenuDef;
import com.javelin.editor.menu.MenuPopupFactory;
import com.javelin.editor.menu.MenuRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单桥方法：/menu/list（结构拉取）、/menu/open（Swing 弹窗，顶栏/面板右键共用——
 * 菜单渲染统一 Swing，Web 浮层菜单会被视口 Canvas 盖）。
 * 命令执行见 {@link com.javelin.editor.jcef.method.CommandMethod}（设计档 Editor_Menu_Design §6）。
 *
 * @author javelin
 * @create 2026/9/28
 **/
@BridgeMethod("/menu")
public class MenuMethod {

    private static final Logger LOGGER = LoggerFactory.getLogger(MenuMethod.class);

    /**
     * 菜单结构拉取：menuId 省略 = 顶栏（仅 id/label）；指定域 = 项列表
     * （含即时求值的 enabled/checked。v0.4 菜单统一 Swing 后右键不再走此拉取
     * ——Swing 弹窗显示前 Java 侧自求值；分支保留作协议面，供将来 Web 直触发
     * 入口（工具按钮等）取状态）。子菜单递归填 children（结构项：label +
     * children，无命令 id）。
     */
    @BridgeMethod("/list")
    public List<MenuItemVo> list(MenuListPar par) {
        if (par == null || par.menuId == null || par.menuId.isBlank()) {
            List<MenuItemVo> tops = new ArrayList<>();
            for (MenuDef def : MenuRegistry.topMenus()) {
                MenuItemVo vo = new MenuItemVo();
                vo.id = def.getId();
                vo.label = def.getLabel();
                tops.add(vo);
            }
            return tops;
        }
        MenuDef def = MenuRegistry.get(par.menuId);
        if (def == null) {
            LOGGER.warn("菜单域未注册: {}", par.menuId);
            return new ArrayList<>();
        }
        return toVos(def.getId(), def.getEntries());
    }

    /**
     * 菜单弹出（顶栏标签/面板右键共用，web 坐标，Java 侧 EDT 弹 Swing JPopupMenu
     * ——菜单渲染统一 Swing，heavyweight popup 天然浮于视口 Canvas 之上）。
     * 坐标语义：顶栏=标签左缘/顶栏下缘，右键=鼠标点。
     */
    @BridgeMethod("/open")
    public void open(MenuOpenPar par) {
        if (par == null) {
            return;
        }
        MenuPopupFactory.show(par.menuId, par.x, par.y);
    }

    /** 命令 → VO（即时求值） */
    private static MenuItemVo toVo(Command command) {
        MenuItemVo vo = new MenuItemVo();
        vo.id = command.getId();
        vo.label = command.getLabel();
        vo.shortcut = command.getShortcut();
        vo.enabled = command.isEnabled();
        vo.checkable = command.isCheckable();
        vo.checked = command.isChecked();
        return vo;
    }

    /** 条目列表 → VO 列表（递归：子菜单填 children，即时求值） */
    private static List<MenuItemVo> toVos(String path, List<MenuDef.MenuEntry> entries) {
        List<MenuItemVo> items = new ArrayList<>();
        for (MenuDef.MenuEntry entry : entries) {
            if (entry.isSeparator()) {
                MenuItemVo vo = new MenuItemVo();
                vo.separator = true;
                items.add(vo);
                continue;
            }
            if (entry.isSubmenu()) {
                MenuItemVo vo = new MenuItemVo();
                vo.label = entry.getSubmenuLabel();
                vo.children = toVos(path + ">" + entry.getSubmenuLabel(), entry.getChildren());
                items.add(vo);
                continue;
            }
            Command command = CommandService.get(entry.getCommandId());
            if (command == null) {
                LOGGER.warn("菜单项命令未注册，跳过: {}#{}", path, entry.getCommandId());
                continue;
            }
            items.add(toVo(command));
        }
        return items;
    }
}
