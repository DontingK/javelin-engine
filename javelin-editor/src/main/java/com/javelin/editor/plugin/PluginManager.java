package com.javelin.editor.plugin;

import com.javelin.editor.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 插件管理器（宿主装载管线，静态门面）：主 classloader 发现（editor.core
 * 路径，失败 fail-fast）→ 第三方 jar 目录装载（失败/namespace 冲突/setup
 * 失败一律 warn 跳过）→ setup 驱动。进程内只装载一次；语言包注册待 i18n
 * 批次插入（setup 之前）。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class PluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);

    private static final List<PluginInstance> INSTANCES = new ArrayList<>();
    private static boolean loaded = false;

    private PluginManager() {
        // 静态门面禁止实例化
    }

    /**
     * 装载全部插件（进程一次，重复调抛 IllegalStateException）。
     *
     * @param pluginsDir 第三方插件目录（null / 不存在 = 仅 classpath 插件）
     */
    public static synchronized void loadAll(File pluginsDir) {
        if (loaded) {
            throw new IllegalStateException("插件已装载（loadAll 只允许调用一次）");
        }
        loaded = true;
        ClassLoader host = PluginManager.class.getClassLoader();
        Set<String> namespaces = new HashSet<>();
        loadClasspathPlugins(host, namespaces);
        loadJarPlugins(pluginsDir, host, namespaces);
    }

    /** 装载结果账本（含 FAILED 条目；顺序 = classpath 先、jar 名序） */
    public static synchronized List<PluginInstance> instances() {
        return new ArrayList<>(INSTANCES);
    }

    /** 按装载序依次在插件 classpath 查资源（classpath 插件先、jar 名序；FAILED 跳过） */
    public static synchronized URL findResource(String path) {
        for (PluginInstance instance : INSTANCES) {
            ClassLoader cl = instance.classLoader();
            if (cl == null) {
                continue;
            }
            URL url = cl.getResource(path);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /** classpath 发现（editor.core 路径）——任一环失败直接抛（本体 fail-fast） */
    private static void loadClasspathPlugins(ClassLoader host, Set<String> namespaces) {
        for (Plugin plugin : ServiceLoader.load(Plugin.class, host)) {
            PluginInstance instance = PluginInstance.of("classpath", plugin, host);
            addNamespace(namespaces, instance);
            I18n.registerBundle(instance.namespace(), instance.classLoader());
            plugin.setup(new PluginContext(instance.namespace()));
            INSTANCES.add(instance);
            LOGGER.info("插件已装载: {} v{}（classpath）", instance.namespace(), plugin.version());
        }
    }

    /** 第三方 jar 装载——失败/冲突/setup 失败 warn 跳过，编辑器照常运行 */
    private static void loadJarPlugins(File pluginsDir, ClassLoader host, Set<String> namespaces) {
        for (File jar : pluginJars(pluginsDir)) {
            PluginInstance instance = PluginInstance.load(jar, host);
            if (instance.state() == PluginState.FAILED) {
                LOGGER.warn("插件装载失败，已跳过: {}（{}）", jar.getPath(), instance.error());
                INSTANCES.add(instance);
                continue;
            }
            if (!namespaces.add(instance.namespace())) {
                LOGGER.warn("插件 namespace 冲突，已跳过: {}（{} 已被先装载插件占用）",
                        jar.getPath(), instance.namespace());
                closeQuietly(instance);
                INSTANCES.add(PluginInstance.failed(jar.getPath(),
                        "namespace 冲突: " + instance.namespace()));
                continue;
            }
            try {
                I18n.registerBundle(instance.namespace(), instance.classLoader());
            } catch (IllegalStateException e) {
                // 无语言包/包损坏 = 降级装载不拒：硬编码文案照常，用键则 !key! 兜底
                // ——editor.core 走 classpath 段无此宽容（宿主文案纪律 fatal）
                LOGGER.warn("插件 {} 语言包不可用，i18n 键将显示 !key! 兜底: {}",
                        instance.namespace(), e.getMessage());
            }
            try {
                instance.plugin().setup(new PluginContext(instance.namespace()));
                INSTANCES.add(instance);
                LOGGER.info("插件已装载: {} v{} by {}（{}）",
                        instance.namespace(), instance.plugin().version(),
                        instance.plugin().author(), jar.getName());
            } catch (RuntimeException | Error e) {
                LOGGER.warn("插件 setup 失败，已跳过: {}", instance.namespace(), e);
                INSTANCES.add(PluginInstance.failed(jar.getPath(), "setup 失败: " + e));
            }
        }
    }

    private static void addNamespace(Set<String> namespaces, PluginInstance instance) {
        if (!namespaces.add(instance.namespace())) {
            throw new IllegalStateException("插件 namespace 冲突: " + instance.namespace());
        }
    }

    /** 目录下全部插件 jar（不存在 = 空表；按文件名排序保证装载序确定） */
    private static List<File> pluginJars(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return List.of();
        }
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) {
            return List.of();
        }
        Arrays.sort(jars, Comparator.comparing(File::getName));
        return List.of(jars);
    }

    /** 释放被跳过插件的 classloader（setup 未执行、无注册引用，关闭安全） */
    private static void closeQuietly(PluginInstance instance) {
        try {
            ((PluginClassLoader) instance.classLoader()).close();
        } catch (IOException ignored) {
        }
    }
}
