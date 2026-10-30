package com.javelin.editor.menu;

import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.plugin.EditorLang;

import java.util.function.Supplier;

/**
 * 编辑器命令：菜单/右键/快捷键/按钮四类触发入口的统一动作载体（设计档 Editor_Menu_Design §3）。
 *
 * <p>命令不持有 panel/Swing 上下文引用；需要上下文的命令经域基类（如
 * HierarchyMenuCommand，随场景树任务落地）读 Java 侧全局选中状态。线程契约：
 * 可能在 EDT 或引擎线程被调，动 Swing 的命令内部自行跳 EDT，动场景的走引擎帧队列。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class Command {

    /** 命令稳定标识（如 "tools.grid"） */
    private final String id;
    /** 显示文本键（渲染点 text() 解析） */
    private final I18nKey label;
    /** 快捷键文本（仅显示，第一期不接全局触发；可为 null） */
    private final String shortcut;
    /** 执行体 */
    private final Runnable action;
    /** 置灰判定（null = 恒可用） */
    private final Supplier<Boolean> enabled;
    /** 勾选态（null = 非勾选命令） */
    private final Supplier<Boolean> checked;
    /** 菜单项图标名（classpath /icon/{name}.svg；null = 无图标） */
    private final String icon;

    private Command(Builder builder) {
        this.id = builder.id;
        this.label = builder.label;
        this.shortcut = builder.shortcut;
        this.action = builder.action;
        this.enabled = builder.enabled;
        this.checked = builder.checked;
        this.icon = builder.icon;
    }

    /**
     * 创建命令构建器。
     *
     * @param id    命令 id（非空）
     * @param label 显示文本键（非空）
     * @return 构建器
     */
    public static Builder of(String id, I18nKey label) {
        return new Builder(id, label);
    }

    public String getId() {
        return id;
    }

    public I18nKey getLabel() {
        return label;
    }

    public String getShortcut() {
        return shortcut;
    }

    public Runnable getAction() {
        return action;
    }

    /** 置灰判定（无 supplier 恒可用） */
    public boolean isEnabled() {
        return enabled == null || enabled.get();
    }

    /** 是否勾选命令（决定菜单项渲染形态：JCheckBoxMenuItem / web checked） */
    public boolean isCheckable() {
        return checked != null;
    }

    /** 当前勾选态（非勾选命令恒 false） */
    public boolean isChecked() {
        return checked != null && checked.get();
    }

    /** 菜单项图标名（null = 无图标） */
    public String getIcon() {
        return icon;
    }

    /**
     * 命令构建器。
     */
    public static final class Builder {
        private final String id;
        private final I18nKey label;
        private String shortcut;
        private Runnable action;
        private Supplier<Boolean> enabled;
        private Supplier<Boolean> checked;
        private String icon;

        private Builder(String id, I18nKey label) {
            if (id == null || id.isBlank() || label == null) {
                throw new IllegalArgumentException(EditorLang.text("menu.command.idLabelRequired"));
            }
            this.id = id;
            this.label = label;
        }

        public Builder shortcut(String shortcut) {
            this.shortcut = shortcut;
            return this;
        }

        public Builder action(Runnable action) {
            this.action = action;
            return this;
        }

        public Builder enabled(Supplier<Boolean> enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder checked(Supplier<Boolean> checked) {
            this.checked = checked;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Command build() {
            if (action == null) {
                throw new IllegalArgumentException(EditorLang.text("menu.command.actionRequired", id));
            }
            return new Command(this);
        }
    }
}
