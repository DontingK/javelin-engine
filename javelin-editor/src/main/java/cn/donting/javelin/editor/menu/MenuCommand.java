package cn.donting.javelin.editor.menu;

import cn.donting.javelin.editor.i18n.I18nKey;

/**
 * 命令根契约（类形态，设计档 Editor_Menu_Design §3.1）。
 *
 * <p>命令以类实现本接口；域归属经三个域基类表达（menu.top / menu.hierarchy /
 * menu.project，基类提供 label 默认 = EditorLang.key(id())，editor.core 约定
 * label key 即命令 id；第三方命令自行提供带自身命名空间的 I18nKey）。
 * lambda 快捷通道（Command.of + register(Command)）并存。</p>
 *
 * <p>线程契约：execute 可能在 EDT 或引擎线程被调，动 Swing 的内部自行跳 EDT，
 * 动场景的走引擎帧队列。命令不持有 panel/Swing 上下文引用。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public interface MenuCommand {

    /** 命令稳定标识（如 "tools.grid"） */
    String id();

    /** 显示文本（i18n 文案键） */
    I18nKey label();

    /** 快捷键文本（仅显示，第一期不接全局触发；null = 无） */
    default String shortcut() {
        return null;
    }

    /** 执行体 */
    default void execute() {
    }

    /** 置灰判定（null = 恒可用） */
    default Boolean enabled() {
        return null;
    }

    /** 勾选态（null = 非勾选命令） */
    default Boolean checked() {
        return null;
    }

    /** 菜单项图标名（classpath /icon/{name}.svg，null = 无图标；勾选命令不配——勾选标记与图标同槽渲染） */
    default String icon() {
        return null;
    }
}
