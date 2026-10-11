package com.javelin.editor.menu.hierarchy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未实现占位命令（hierarchy 域）：Unity 菜单壳对齐——结构先行，功能后补
 * （设计档 Editor_Menu_Design §4）。
 *
 * <p>菜单结构一次对齐 Unity：未实现命令以本类注册，{@link #enabled()} 恒 false
 * 置灰显示（菜单项不可点）；execute 记 warn 返回（菜单路径因置灰不可达，仅桥
 * /command/execute 直触发可达）。功能落地时以同 id 实类替换 EditorMenuSetup
 * 里的注册行，菜单结构不动。「命令未注册即组装时 warn 跳过」机制保留，用于
 * 真实漏注册场景，不再承担"暂未实现"语义。</p>
 *
 * @author javelin
 * @create 2026/10/1
 */
public class UnimplementedMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnimplementedMenuCommand.class);

    private final String id;
    private final String label;
    private final String shortcut;
    private final String icon;

    /**
     * @param id       命令 id（Unity 对齐命名，如 "hierarchy.copy"）
     * @param label    显示文本
     * @param shortcut 快捷键文本（仅显示，可空）
     */
    public UnimplementedMenuCommand(String id, String label, String shortcut) {
        this(id, label, shortcut, null);
    }

    /**
     * @param id       命令 id（Unity 对齐命名，如 "hierarchy.copy"）
     * @param label    显示文本
     * @param shortcut 快捷键文本（仅显示，可空）
     * @param icon     图标名（classpath /icon/{name}.svg，可空 = 无图标）
     */
    public UnimplementedMenuCommand(String id, String label, String shortcut, String icon) {
        if (id == null || id.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("命令 id 与 label 不能为空: " + id);
        }
        this.id = id;
        this.label = label;
        this.shortcut = shortcut;
        this.icon = icon;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String shortcut() {
        return shortcut;
    }

    @Override
    public String icon() {
        return icon;
    }

    /** 恒置灰：占位命令不可用（未实现项显示但灰，Unity 壳对齐） */
    @Override
    public Boolean enabled() {
        return false;
    }

    @Override
    public void execute() {
        LOGGER.warn("命令未实现（占位注册）: {}", id);
    }
}
