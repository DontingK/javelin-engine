package com.javelin.editor.menu;

import com.javelin.editor.i18n.I18nKey;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单域定义：顶栏菜单或右键域（设计档 Editor_Menu_Design §4）。
 *
 * <p>项列表为有序命令引用 + 分隔符 + 子菜单（层级任意嵌套），声明式单源——
 * 顺序/分割线/子菜单归属一处说清。右键域 id 以 "ctx." 前缀约定（如 ctx.hierarchy）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuDef {

    /** 菜单域 id（顶栏如 "tools"；右键域以 "ctx." 前缀约定） */
    private final String id;
    /** 顶栏显示标签键（右键域不渲染标签） */
    private final I18nKey label;
    /** 有序项列表（组装期经 {@link MenuBuilder} 填充） */
    private final List<MenuEntry> entries = new ArrayList<>();

    MenuDef(String id, I18nKey label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public I18nKey getLabel() {
        return label;
    }

    /** 项列表（内部列表直通，组装期 MenuBuilder 写，读方按只读约定遍历） */
    public List<MenuEntry> getEntries() {
        return entries;
    }

    /**
     * 菜单项：命令引用、分隔符或子菜单头。
     */
    public static final class MenuEntry {
        /** 引用的命令 id；null = 非命令项（分隔符/子菜单） */
        private final String commandId;
        /** 子菜单显示标签键；null = 非子菜单项 */
        private final I18nKey submenuLabel;
        /** 子菜单项列表（仅子菜单项非 null；组装期经 {@link MenuBuilder} 填充） */
        private final List<MenuEntry> children;

        private MenuEntry(String commandId, I18nKey submenuLabel, List<MenuEntry> children) {
            this.commandId = commandId;
            this.submenuLabel = submenuLabel;
            this.children = children;
        }

        /** 命令项 */
        public static MenuEntry command(String commandId) {
            return new MenuEntry(commandId, null, null);
        }

        /** 分隔符 */
        public static MenuEntry separator() {
            return new MenuEntry(null, null, null);
        }

        /**
         * 子菜单头：结构项（无命令 id），children 为空可变列表、组装期填充。
         *
         * @param label 子菜单显示标签键
         */
        public static MenuEntry submenu(I18nKey label) {
            if (label == null) {
                throw new IllegalArgumentException("子菜单 label 不能为空");
            }
            return new MenuEntry(null, label, new ArrayList<>());
        }

        public String getCommandId() {
            return commandId;
        }

        public I18nKey getSubmenuLabel() {
            return submenuLabel;
        }

        /** 子菜单项列表（仅子菜单项非 null；内部列表直通，组装期 MenuBuilder 写，读方按只读约定遍历） */
        public List<MenuEntry> getChildren() {
            return children;
        }

        public boolean isSeparator() {
            return commandId == null && submenuLabel == null;
        }

        public boolean isSubmenu() {
            return submenuLabel != null;
        }
    }
}
