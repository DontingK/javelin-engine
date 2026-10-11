package com.javelin.editor.menu;

/**
 * 命令根契约（类形态，设计档 Editor_Menu_Design §3.1）。
 *
 * <p>命令以类实现本接口（简单命令只需 id/label/execute，可选项全走 default）；
 * 域归属经三个域基类表达，按域分包（menu.top / menu.hierarchy / menu.project，
 * 基类与该域命令同包），经 {@link CommandService#register(MenuCommand)} 适配注册。
 * lambda 快捷通道（Command.of + register(Command)）并存。</p>
 *
 * <p>线程契约：execute 可能在 EDT 或桥线程被调，动 Swing/CEF 的内部自行跳 EDT，
 * 动场景的走引擎帧队列。命令不持有 panel/Swing/web 上下文引用。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public interface MenuCommand {

    /** 命令稳定标识（桥协议锚点，如 "tools.devtools"） */
    String id();

    /** 显示文本 */
    String label();

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
