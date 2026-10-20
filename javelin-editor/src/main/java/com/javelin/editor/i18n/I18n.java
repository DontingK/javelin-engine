package com.javelin.editor.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * i18n 注册中心（静态门面）：按 namespace 装载插件语言包（约定路径
 * {@code lang/zh_cn.properties} 必备默认 + {@code lang/{语言}.properties}
 * 可选），显示时查表解析——缺失显示 "!全域名!" + 去重 warn，不抛异常。
 * 当前语言启动期一次设定（默认 zh_cn），须早于语言包注册；miss/used 采集
 * 供 i18n 报告（后续批次）。插值走 MessageFormat（{0} 序，文案中单引号
 * 需双写转义）。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class I18n {

    private static final Logger LOGGER = LoggerFactory.getLogger(I18n.class);

    /** 必备默认语言文件名（缺失即注册失败） */
    public static final String DEFAULT_LANGUAGE = "zh_cn";

    private static volatile String language = DEFAULT_LANGUAGE;
    private static boolean frozen;

    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();
    private static final Set<String> MISS = ConcurrentHashMap.newKeySet();
    private static final Set<String> USED = ConcurrentHashMap.newKeySet();

    private I18n() {
        // 静态门面禁止实例化
    }

    /**
     * 设定当前语言（启动期一次，须早于 registerBundle——注册时按该语言装载合并）。
     *
     * @param language 语言文件名（如 "zh_cn"、"en"）
     * @throws IllegalStateException 语言已随注册锁定后再次设定
     */
    public static synchronized void setLanguage(String language) {
        if (frozen) {
            throw new IllegalStateException("语言已在语言包注册后锁定");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("语言不能为空");
        }
        I18n.language = language;
    }

    /**
     * 注册插件语言包（宿主装载期调，setup 之前；namespace 重复注册抛）。
     *
     * @param namespace    插件命名空间
     * @param classLoader  插件 classloader（语言包来源）
     * @throws IllegalStateException 缺必备默认文件、文件加载失败或重复注册
     */
    public static synchronized void registerBundle(String namespace, ClassLoader classLoader) {
        frozen = true;
        Map<String, String> defaults = load(namespace, classLoader, DEFAULT_LANGUAGE);
        if (defaults == null) {
            throw new IllegalStateException(
                    "插件缺必备默认语言包: " + namespace + "（lang/" + DEFAULT_LANGUAGE + ".properties）");
        }
        Map<String, String> active = defaults;
        if (!DEFAULT_LANGUAGE.equals(language)) {
            Map<String, String> localized = load(namespace, classLoader, language);
            if (localized == null) {
                LOGGER.warn("插件 {} 无 {} 语言包，回退默认语言", namespace, language);
            } else {
                Map<String, String> merged = new HashMap<>(defaults);
                merged.putAll(localized);
                active = merged;
            }
        }
        if (BUNDLES.putIfAbsent(namespace, active) != null) {
            throw new IllegalStateException("语言包重复注册: " + namespace);
        }
    }

    /**
     * 解析文案（缺失显示 "!全域名!"，不抛异常）。
     *
     * @param namespace 命名空间
     * @param key       局部键
     * @param args      插值参数（可空）
     * @return 文案（或 "!全域名!" 兜底）
     */
    public static String get(String namespace, String key, Object... args) {
        String fullKey = namespace + "." + key;
        Map<String, String> bundle = BUNDLES.get(namespace);
        String pattern = bundle == null ? null : bundle.get(key);
        if (pattern == null) {
            if (MISS.add(fullKey)) {
                LOGGER.warn("i18n 缺失 key: {}（显示 !key! 兜底）", fullKey);
            }
            return "!" + fullKey + "!";
        }
        USED.add(fullKey);
        return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
    }

    /** 缺失 key 快照（报告/调试用） */
    public static Set<String> missKeys() {
        return Set.copyOf(MISS);
    }

    /** 已用 key 快照（死 key 差集的另一半） */
    public static Set<String> usedKeys() {
        return Set.copyOf(USED);
    }

    private static Map<String, String> load(String namespace, ClassLoader classLoader, String language) {
        String path = "lang/" + language + ".properties";
        try (InputStream stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>();
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("语言包加载失败: " + namespace + " " + path, e);
        }
    }
}
