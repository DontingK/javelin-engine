package com.javelin.editor.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * i18n 注册中心（静态门面）：语言包注册制——插件 setup 期经 PluginContext 按
 * 「域 + 语言 + 文件路径」显式注册（2026-10-29 拍板，lang/{lang}.properties 固定
 * 路径规范废除：文件布局插件自定、一个语言包文件对应一个域，集中式语言包按域
 * 逐文件注册即可翻译其他插件）。显示时按当前语言查表、缺键逐键回退该域默认语言
 * （= 该域第一个注册的语言包，英文不特殊）——缺失显示 "!全域名!" + 去重 warn，
 * 不抛异常。当前语言启动期一次设定（重启生效口径）；miss/used 采集供 i18n 报告
 * （后续批次）。插值走自实现 {@code {}} 顺位替换（拍板 2026-10-27：弃
 * MessageFormat——{0} 编辑期不直观，且顺带消解其单引号转义坑）。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class I18n {

    private static final Logger LOGGER = LoggerFactory.getLogger(I18n.class);

    /** 编辑器启动的初始语言（缺省；缺键回退基线 = 各域第一个注册的语言包，不取本值） */
    public static final String DEFAULT_LANGUAGE = "en_US";

    /** 当前语言（启动期一次设定，重启生效口径） */
    private static volatile String language = DEFAULT_LANGUAGE;
    private static boolean frozen;

    /** 语言包注册表：域 → 语言 →（局部键 → 文案）——插件 setup 期 registerPack 写入 */
    private static final Map<String, Map<String, Map<String, String>>> PACKS = new ConcurrentHashMap<>();
    /** 各域默认语言（namespace → 基线语言）——该域第一个注册的语言包（装载序隐式声明，2026-10-29 拍板：缺键回退走域基线，英文不特殊） */
    private static final Map<String, String> DOMAIN_DEFAULTS = new ConcurrentHashMap<>();
    /** 已注册支持语言（fileName → 显示名，注册序）——语言设置菜单选项源 */
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();
    private static final Set<String> MISS = ConcurrentHashMap.newKeySet();
    private static final Set<String> USED = ConcurrentHashMap.newKeySet();

    private I18n() {
        // 静态门面禁止实例化
    }

    /**
     * 设定当前语言（启动期一次，二次调用抛——重启生效口径，不随注册冻结）。
     *
     * @param language 语言文件名（语言注册表键，如 "en_US"、"zh_CN"）
     * @throws IllegalStateException 语言已设定后再次设定
     */
    public static synchronized void setLanguage(String language) {
        if (frozen) {
            throw new IllegalStateException("语言已设定（setLanguage 启动期一次，重启生效）");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("语言不能为空");
        }
        frozen = true;
        I18n.language = language;
    }

    /** 当前语言文件名（I18n 语言标识，启动期设定后不变） */
    public static synchronized String language() {
        return language;
    }

    /**
     * 注册支持语言（插件 setup 期调，2026-10-29 拍板语言注册制——支持语言由插件
     * 提供，非枚举写死；新语言包随注册生效）。fileName 重复忽略，先注册者优先——
     * editor.core 恒先装载，不受第三方覆盖。
     *
     * @param fileName    语言文件名（lang/{fileName}.properties 约定成为历史，
     *                    fileName 仅作语言标识与注册表键）
     * @param displayName 设置菜单显示名（各语言自书）
     * @throws IllegalArgumentException 参数为空
     */
    public static synchronized void registerLanguage(String fileName, String displayName) {
        if (fileName == null || fileName.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("语言注册参数不能为空");
        }
        LANGUAGES.putIfAbsent(fileName, displayName);
    }

    /** 已注册语言快照（fileName → 显示名，注册序；语言设置菜单选项源） */
    public static synchronized Map<String, String> languages() {
        return Map.copyOf(LANGUAGES);
    }

    /**
     * 注册语言包（插件 setup 期经 PluginContext 调——「域 + 语言 + 文件路径」显式
     * 声明，文件布局插件自定；一个文件对应一个域，集中式语言包按域逐文件注册即可
     * 翻译其他插件）。同键后注册覆盖先注册（装载序语义）；该域第一个注册的语言包
     * 即该域默认语言（缺键逐键回退的基线，2026-10-29 拍板——英文不特殊，中文作者的
     * 插件先注册 zh_CN 即基线中文）。路径缺失抛——editor.core
     * 走本体 fail-fast，第三方 setup 失败 warn 跳过（分级在调用方）。
     *
     * @param namespace   目标域（键的命名空间，集中式语言包可指向其他插件域）
     * @param language    语言文件名（语言注册表键）
     * @param classLoader 语言包文件来源 classloader
     * @param path        语言包文件路径（classloader 资源路径，UTF-8 properties）
     * @throws IllegalArgumentException 参数为空
     * @throws IllegalStateException    文件不存在或加载失败
     */
    public static void registerPack(String namespace, String language, ClassLoader classLoader, String path) {
        if (namespace == null || namespace.isBlank() || language == null || language.isBlank()
                || path == null || path.isBlank()) {
            throw new IllegalArgumentException("语言包注册参数不能为空");
        }
        Map<String, String> entries = load(classLoader, path);
        DOMAIN_DEFAULTS.putIfAbsent(namespace, language);
        PACKS.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(language, k -> new ConcurrentHashMap<>())
                .putAll(entries);
    }

    /**
     * 解析文案（缺失显示 "!全域名!"，不抛异常）。
     *
     * <p>回退链 = 当前语言 → 该域默认语言（该域第一个注册的语言包，2026-10-29 拍板
     * ——缺键回退走域基线而非固定英文，中文作者的插件基线可为 zh_CN）。</p>
     *
     * @param namespace 命名空间
     * @param key       局部键
     * @param args      插值参数（可空）
     * @return 文案（或 "!全域名!" 兜底）
     */
    public static String get(String namespace, String key, Object... args) {
        String fullKey = namespace + "." + key;
        Map<String, Map<String, String>> packs = PACKS.get(namespace);
        Map<String, String> current = packs == null ? null : packs.get(language);
        String domainDefault = packs == null ? null : DOMAIN_DEFAULTS.get(namespace);
        Map<String, String> baseline = packs == null || domainDefault == null ? null : packs.get(domainDefault);
        String pattern = current == null ? null : current.get(key);
        if (pattern == null && baseline != null) {
            pattern = baseline.get(key);
        }
        if (pattern == null) {
            if (MISS.add(fullKey)) {
                LOGGER.warn("i18n 缺失 key: {}（显示 !key! 兜底）", fullKey);
            }
            return "!" + fullKey + "!";
        }
        USED.add(fullKey);
        return args == null || args.length == 0 ? pattern : interpolate(pattern, args);
    }

    /**
     * 占位插值：{@code {}} 顺位替换（拍板 2026-10-27，弃 MessageFormat）。参数多于
     * 占位符多余参数丢弃；占位符多于参数余下 {@code {}} 原样保留（文案错配显性可见）。
     */
    private static String interpolate(String pattern, Object[] args) {
        int idx = pattern.indexOf("{}");
        if (idx < 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int from = 0;
        for (Object arg : args) {
            idx = pattern.indexOf("{}", from);
            if (idx < 0) {
                break;
            }
            sb.append(pattern, from, idx).append(arg);
            from = idx + 2;
        }
        return sb.append(pattern, from, pattern.length()).toString();
    }

    /** 缺失 key 快照（报告/调试用） */
    public static Set<String> missKeys() {
        return Set.copyOf(MISS);
    }

    /** 已用 key 快照（死 key 差集的另一半） */
    public static Set<String> usedKeys() {
        return Set.copyOf(USED);
    }

    /** 读语言包文件（UTF-8 显式——load(InputStream) 至今按 spec 是 ISO-8859-1，中文必乱码） */
    private static Map<String, String> load(ClassLoader classLoader, String path) {
        try (InputStream stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("语言包文件不存在: " + path);
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>();
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("语言包加载失败: " + path, e);
        }
    }
}
