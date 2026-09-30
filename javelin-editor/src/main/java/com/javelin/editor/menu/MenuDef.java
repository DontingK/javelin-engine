package com.javelin.editor.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单域定义：顶栏菜单或右键域（设计档 Editor_Menu_Design §4）。
 *
 * <p>项列表为有序命令引用 + 分隔符，声明式单源——顺序/分割线一处说清。
 * 右键域 id 以 "ctx." 前缀约定（如 ctx.hierarchy）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class MenuDef {

    /** 菜单域 id（顶栏如 "tools"；右键域以 "ctx." 前缀约定） */
    private final String id;
    /** 顶栏显示标签（右键域不渲染标签） */
    private final String label;
    /** 有序项列表（组装期经 {@link MenuBuilder} 填充） */
    private final List<MenuEntry> entries = new ArrayList<>();

    MenuDef(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** 项列表（内部列表直通，组装期 MenuBuilder 写，读方按只读约定遍历） */
    public List<MenuEntry> getEntries() {
        return entries;
    }

    /**
     * 菜单项：命令引用或分隔符。
     */
    public static final class MenuEntry {
        /** 引用的命令 id；null = 分隔符 */
        private final String commandId;

        private MenuEntry(String commandId) {
            this.commandId = commandId;
        }

        /** 命令项 */
        public static MenuEntry command(String commandId) {
            return new MenuEntry(commandId);
        }

        /** 分隔符 */
        public static MenuEntry separator() {
            return new MenuEntry(null);
        }

        public String getCommandId() {
            return commandId;
        }

        public boolean isSeparator() {
            return commandId == null;
        }
    }
}
