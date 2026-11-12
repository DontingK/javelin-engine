package cn.donting.javelin.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动参数（进程命令行）单点持有。
 *
 * <p>用法：各 main 入口首行 {@code LaunchArgs.parse(args)} 装入；引擎启动期读取覆盖设置
 * （如 {@code --debug=true} 覆盖 {@code JavelinSetting.engineDebug}）。支持
 * {@code --key=value}；无值的 {@code --flag} 视为 {@code true}；键名不带 {@code --} 前缀。</p>
 */
public final class LaunchArgs {

    private static final Logger log = LoggerFactory.getLogger(LaunchArgs.class);
    /** 解析结果（parse 时整体替换，volatile 保证 main 与引擎线程间的可见性） */
    private static volatile Map<String, String> values = Map.of();

    private LaunchArgs() {
    }

    /**
     * 解析 main 的 args 装入（替换上一次结果）。
     *
     * <p>{@code --k=v} 取 {@code v}；{@code --k} 取 {@code "true"}；非 {@code --} 开头的参数忽略。</p>
     *
     * @param args 进程启动参数（可为 null）
     */
    public static void parse(String[] args) {
        if (args == null || args.length == 0) {
            values = Map.of();
            return;
        }
        Map<String, String> parsed = new HashMap<>();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) {
                parsed.put(body, "true");
            } else {
                parsed.put(body.substring(0, eq), body.substring(eq + 1));
            }
        }
        values = Map.copyOf(parsed);
        log.info("启动参数：{}",String.join(" ",args));
    }

    /**
     * 取参数原值。
     *
     * @param key 键名（不带 {@code --}）
     * @return 原值；未提供返回 null
     */
    public static String get(String key) {
        return values.get(key);
    }

    /**
     * 取参数原值。
     *
     * @param key          键名（不带 {@code --}）
     * @param defaultValue 未提供时的默认值
     * @return 原值或默认值
     */
    public static String get(String key, String defaultValue) {
        String v = values.get(key);
        return v != null ? v : defaultValue;
    }

    /**
     * 取布尔参数。
     *
     * @param key          键名（不带 {@code --}）
     * @param defaultValue 未提供时的默认值
     * @return 布尔值；未提供返回默认值（{@code --k} 视为 true，{@code --k=false} 为 false）
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        return v != null ? Boolean.parseBoolean(v) : defaultValue;
    }

    /**
     * 是否提供了该参数。
     *
     * @param key 键名（不带 {@code --}）
     * @return 命令行是否含该键
     */
    public static boolean has(String key) {
        return values.containsKey(key);
    }
}
