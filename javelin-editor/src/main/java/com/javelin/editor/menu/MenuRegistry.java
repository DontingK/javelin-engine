package com.javelin.editor.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单结构注册表：声明式单源（设计档 Editor_Menu_Design §4）。
 *
 * <p>启动期一次性构建（EditorMenuSetup），不做动态增删。菜单域两类：
 * 顶栏菜单（id 不带 "ctx." 前缀，供 /menu/list 顶栏渲染）与右键域
 * （"ctx." 前缀，供面板右键拉取）。顶栏显示序 = 注册序（LinkedHashMap）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuRegistry {

    /** 菜单域：id → def（LinkedHashMap 保注册序 = 顶栏显示序） */
    private static final Map<String, MenuDef> MENUS = new LinkedHashMap<>();

    private MenuRegistry() {
        // 静态门面禁止实例化
    }

    /**
     * 注册菜单域并返回链式构建器。
     *
     * @param id    菜单域 id（右键域以 "ctx." 前缀约定）
     * @param label 顶栏显示标签
     * @return 构建器
     * @throws IllegalStateException 菜单 id 重复
     */
    public static synchronized MenuBuilder menu(String id, String label) {
        if (id == null || id.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("菜单 id 与 label 不能为空");
        }
        if (MENUS.containsKey(id)) {
            throw new IllegalStateException("重复菜单 id: " + id);
        }
        MenuDef def = new MenuDef(id, label);
        MENUS.put(id, def);
        return new MenuBuilder(def.getEntries());
    }

    /**
     * 按 id 查菜单域。
     *
     * @param id 菜单域 id
     * @return 菜单域，未注册返回 null
     */
    public static synchronized MenuDef get(String id) {
        return id == null ? null : MENUS.get(id);
    }

    /**
     * 顶栏菜单（排除 "ctx." 右键域），按注册序。
     *
     * @return 顶栏菜单列表（拷贝）
     */
    public static synchronized List<MenuDef> topMenus() {
        List<MenuDef> tops = new ArrayList<>();
        for (MenuDef def : MENUS.values()) {
            if (!def.getId().startsWith("ctx.")) {
                tops.add(def);
            }
        }
        return tops;
    }
}
