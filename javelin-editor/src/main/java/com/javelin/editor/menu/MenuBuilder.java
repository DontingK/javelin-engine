package com.javelin.editor.menu;

/**
 * 菜单构建器：{@link MenuRegistry#menu(String, String)} 的链式组装入口。
 *
 * <p>item 只存命令 id；命令未注册不算错误（组装/拉取时 warn 跳过，容忍分批落地）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuBuilder {

    private final MenuDef def;

    MenuBuilder(MenuDef def) {
        this.def = def;
    }

    /**
     * 追加命令项。
     *
     * @param commandId 命令 id
     * @return this
     */
    public MenuBuilder item(String commandId) {
        def.getEntries().add(MenuDef.MenuEntry.command(commandId));
        return this;
    }

    /**
     * 追加分隔符。
     *
     * @return this
     */
    public MenuBuilder separator() {
        def.getEntries().add(MenuDef.MenuEntry.separator());
        return this;
    }
}
