package com.javelin.editor.menu;

import com.javelin.editor.i18n.I18nKey;

import java.util.List;
import java.util.function.Consumer;

/**
 * 菜单构建器：{@link MenuRegistry#menu(String, I18nKey)} 的链式组装入口，
 * 亦作子菜单组装器（{@link #submenu(I18nKey, Consumer)} 降层复用）。
 *
 * <p>item 只存命令 id；命令未注册不算错误（组装/拉取时 warn 跳过，容忍分批落地）。
 * submenu 为结构项（label 键 + 子项列表），层级任意嵌套。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuBuilder {

    /** 组装目标列表：顶层 = 菜单域项列表，子菜单 = 该子菜单的项列表 */
    private final List<MenuDef.MenuEntry> target;

    MenuBuilder(List<MenuDef.MenuEntry> target) {
        this.target = target;
    }

    /**
     * 追加命令项。
     *
     * @param commandId 命令 id
     * @return this
     */
    public MenuBuilder item(String commandId) {
        target.add(MenuDef.MenuEntry.command(commandId));
        return this;
    }

    /**
     * 追加分隔符。
     *
     * @return this
     */
    public MenuBuilder separator() {
        target.add(MenuDef.MenuEntry.separator());
        return this;
    }

    /**
     * 追加子菜单：降层组装子项，consumer 返回后回到本层继续链式。
     *
     * @param label    子菜单显示标签键
     * @param children 子项组装（item/separator/submenu 均可用，层级任意嵌套）
     * @return this
     */
    public MenuBuilder submenu(I18nKey label, Consumer<MenuBuilder> children) {
        MenuDef.MenuEntry entry = MenuDef.MenuEntry.submenu(label);
        target.add(entry);
        children.accept(new MenuBuilder(entry.getChildren()));
        return this;
    }
}
