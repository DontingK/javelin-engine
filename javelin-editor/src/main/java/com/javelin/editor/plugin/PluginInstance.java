package com.javelin.editor.plugin;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * 插件装载实例（不可变）——第三方装载入口 + 结果账本。load(File) 封装
 * classloader → ServiceLoader → 实例化 → 元数据校验管线，全程不抛（失败
 * 转 FAILED 并关闭 classloader 释放 jar 句柄）；editor.core 无 File 路径，
 * 宿主自行发现后经 of() 包装，校验失败直接抛（本体 fail-fast）。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class PluginInstance {

    /** namespace 约定：点分小写，每段 [a-z][a-z0-9]* */
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$");

    /** version 约定：语义化版本 主.次.修订，可选预发布后缀（如 1.2.0-SNAPSHOT） */
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[\\w.-]+)?$");

    private final String source;
    private final Plugin plugin;
    private final ClassLoader classLoader;
    private final PluginState state;
    private final String namespace;
    private final String error;

    private PluginInstance(String source, Plugin plugin, ClassLoader classLoader,
                           PluginState state, String namespace, String error) {
        this.source = source;
        this.plugin = plugin;
        this.classLoader = classLoader;
        this.state = state;
        this.namespace = namespace;
        this.error = error;
    }

    /**
     * 第三方装载入口（全程不抛，失败转 FAILED）。
     *
     * @param jar    插件 jar 文件
     * @param parent 宿主 classloader（parent-first 委托目标）
     * @return LOADED 或 FAILED 实例（原因见 {@link #error()}）
     * @throws IllegalArgumentException 参数为 null
     */
    public static PluginInstance load(File jar, ClassLoader parent) {
        if (jar == null) {
            throw new IllegalArgumentException("插件 jar 不能为 null");
        }
        if (parent == null) {
            throw new IllegalArgumentException("宿主 classloader 不能为 null");
        }
        String source = jar.getPath();
        if (!jar.exists() || !jar.isFile()) {
            return failed(source, "jar 不存在或不是文件");
        }
        PluginClassLoader classLoader = null;
        try {
            classLoader = new PluginClassLoader(jar, parent);
            Plugin plugin = discover(classLoader);
            return of(source, plugin, classLoader);
        } catch (ServiceConfigurationError | RuntimeException e) {
            closeQuietly(classLoader);
            return failed(source, e.toString());
        }
    }

    /**
     * 包装宿主已发现的插件（editor.core 路径，主 classloader 无 File）。
     *
     * @param source       来源标识（editor.core 用 "classpath"）
     * @param plugin       插件实例
     * @param classLoader  装载该插件的 classloader
     * @return LOADED 实例
     * @throws IllegalArgumentException 来源/元数据为空或格式非法
     */
    public static PluginInstance of(String source, Plugin plugin, ClassLoader classLoader) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("插件来源不能为空");
        }
        if (plugin == null) {
            throw new IllegalArgumentException("插件实例不能为 null");
        }
        if (classLoader == null) {
            throw new IllegalArgumentException("插件 classloader 不能为 null");
        }
        String namespace = plugin.namespace();
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "插件 namespace 非点分小写格式: " + namespace + "（来源 " + source + "）");
        }
        if (plugin.name() == null) {
            throw new IllegalArgumentException("插件 name 不能为 null（来源 " + source + "）");
        }
        String version = plugin.version();
        if (version == null || !SEMVER.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "插件 version 非语义化格式: " + version + "（来源 " + source + "）");
        }
        return new PluginInstance(source, plugin, classLoader, PluginState.LOADED, namespace, null);
    }

    /** 发现并实例化插件（一个 jar 一个插件约定，零个/多个声明即失败） */
    private static Plugin discover(PluginClassLoader classLoader) {
        Iterator<Plugin> providers = ServiceLoader.load(Plugin.class, classLoader).iterator();
        if (!providers.hasNext()) {
            throw new IllegalStateException("jar 内无 Plugin 声明（META-INF/services 缺失或为空）");
        }
        Plugin plugin = providers.next();
        if (providers.hasNext()) {
            throw new IllegalStateException("jar 内多于一个 Plugin 声明（一个 jar 一个插件约定）");
        }
        return plugin;
    }

    /** 装载后失败的账目（同包 PluginManager 记 namespace 冲突 / setup 失败用） */
    static PluginInstance failed(String source, String error) {
        return new PluginInstance(source, null, null, PluginState.FAILED, null,
                error == null ? "未知原因" : error);
    }

    /** 释放失败路径的 classloader（Windows 下未关闭会锁住 jar 文件） */
    private static void closeQuietly(PluginClassLoader classLoader) {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 来源标识（第三方 = jar 路径；editor.core = "classpath"） */
    public String source() {
        return source;
    }

    /** 插件实例（FAILED 时为 null） */
    public Plugin plugin() {
        return plugin;
    }

    /** 装载该插件的 classloader（FAILED 时为 null） */
    public ClassLoader classLoader() {
        return classLoader;
    }

    /** 装载状态 */
    public PluginState state() {
        return state;
    }

    /** 插件命名空间（FAILED 时为 null） */
    public String namespace() {
        return namespace;
    }

    /** 失败原因（LOADED 时为 null） */
    public String error() {
        return error;
    }

    @Override
    public String toString() {
        return "PluginInstance[" + (namespace == null ? "?" : namespace)
                + ", " + state + ", source=" + source + "]";
    }
}
