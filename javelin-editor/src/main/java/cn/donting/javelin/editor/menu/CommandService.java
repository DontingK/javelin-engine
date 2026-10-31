package cn.donting.javelin.editor.menu;

import cn.donting.javelin.editor.plugin.EditorLang;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令注册表与执行入口（设计档 Editor_Menu_Design §3.4）。
 *
 * <p>execute 统一包裹异常：记 error 日志 + tinyfd 错误弹窗（对齐桥接层惯例）；
 * 未知命令记 warn 返回 false。注册重复 id 抛异常（对齐桥层 Duplicate url 口径）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class CommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandService.class);

    private static final Map<String, Command> COMMANDS = new ConcurrentHashMap<>();

    private CommandService() {
        // 静态门面禁止实例化
    }

    /**
     * 注册命令。
     *
     * @param command 命令
     * @throws IllegalStateException 命令 id 重复
     */
    public static void register(Command command) {
        if (command == null) {
            throw new IllegalArgumentException(EditorLang.text("menu.command.null"));
        }
        if (COMMANDS.putIfAbsent(command.getId(), command) != null) {
            throw new IllegalStateException(EditorLang.text("menu.command.duplicateId", command.getId()));
        }
    }

    /**
     * 注册命令（类形态：实现 {@link MenuCommand}，适配为内部数据模型）。
     *
     * <p>enabled/checked 方法每次求值即时调用（null = 不启用该判定）。
     * 与 register(Command)（lambda 快捷通道）并存。</p>
     *
     * @param menuCommand 类形态命令
     * @throws IllegalStateException 命令 id 重复
     */
    public static void register(MenuCommand menuCommand) {
        if (menuCommand == null) {
            throw new IllegalArgumentException(EditorLang.text("menu.menuCommand.null"));
        }
        Command.Builder builder = Command.of(menuCommand.id(), menuCommand.label())
                .action(menuCommand::execute);
        if (menuCommand.shortcut() != null) {
            builder.shortcut(menuCommand.shortcut());
        }
        if (menuCommand.enabled() != null) {
            builder.enabled(menuCommand::enabled);
        }
        if (menuCommand.checked() != null) {
            builder.checked(menuCommand::checked);
        }
        if (menuCommand.icon() != null) {
            builder.icon(menuCommand.icon());
        }
        register(builder.build());
    }

    /**
     * 注销命令。
     *
     * @param id 命令 id
     */
    public static void unregister(String id) {
        if (id != null) {
            COMMANDS.remove(id);
        }
    }

    /**
     * 按 id 查命令。
     *
     * @param id 命令 id
     * @return 命令，未注册返回 null
     */
    public static Command get(String id) {
        return id == null ? null : COMMANDS.get(id);
    }

    /**
     * 执行命令：异常隔离（记日志 + tinyfd 弹窗）。
     *
     * @param id 命令 id
     * @return 是否找到并执行（命令抛异常返回 false）
     */
    public static boolean execute(String id) {
        Command command = get(id);
        if (command == null) {
            LOGGER.warn("命令未注册: {}", id);
            return false;
        }
        try {
            command.getAction().run();
            return true;
        } catch (Exception e) {
            LOGGER.error("命令执行出错 [{}]", id, e);
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            TinyFileDialogs.tinyfd_messageBox(EditorLang.text("common.error"), message, "ok", "error", 1);
            return false;
        }
    }
}
