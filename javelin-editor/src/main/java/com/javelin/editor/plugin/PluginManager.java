package com.javelin.editor.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
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
 * 路径）→ 第三方 jar 目录装载 → setup 驱动。进程内只装载一次；语言包注册随
 * setup（registerPack 显式域+路径，EditorCorePlugin 同款）。
 *
 * <p>错误显性化（2026-10-29 拍板，原「第三方 warn 跳过」口径作废）：任何插件
 * 报错（本体/第三方、发现/装载/setup）一律弹错误框显示信息，用户确认后关闭
 * 编辑器（System.exit）——静默降级掩盖真问题。</p>
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

    /** classpath 发现（editor.core 路径）——任一环失败弹框 + 确认后关闭编辑器（错误显性化拍板） */
    private static void loadClasspathPlugins(ClassLoader host, Set<String> namespaces) {
        for (Plugin plugin : ServiceLoader.load(Plugin.class, host)) {
            PluginInstance instance = null;
            try {
                instance = PluginInstance.of("classpath", plugin, host);
                addNamespace(namespaces, instance);
                // 语言包注册随 setup（registerPack 显式域+路径，缺包即 setup 抛）
                plugin.setup(new PluginContext(instance.namespace(), instance.classLoader()));
                INSTANCES.add(instance);
                LOGGER.info("插件已装载: {} v{}（classpath）", instance.namespace(), plugin.version());
            } catch (RuntimeException | Error e) {
                abortLoading(instance == null ? plugin.getClass().getName() : instance.namespace(),
                        "classpath（本体）", e);
            }
        }
    }

    /** 第三方 jar 装载——任何环失败弹框 + 确认后关闭编辑器（错误显性化拍板） */
    private static void loadJarPlugins(File pluginsDir, ClassLoader host, Set<String> namespaces) {
        for (File jar : pluginJars(pluginsDir)) {
            PluginInstance instance = PluginInstance.load(jar, host);
            if (instance.state() == PluginState.FAILED) {
                abortLoading(instance.namespace() == null ? jar.getName() : instance.namespace(),
                        jar.getPath(), new IllegalStateException(instance.error()));
            }
            if (!namespaces.add(instance.namespace())) {
                abortLoading(instance.namespace(), jar.getPath(),
                        new IllegalStateException("namespace 冲突（已被先装载插件占用）"));
            }
            try {
                instance.plugin().setup(new PluginContext(instance.namespace(), instance.classLoader()));
                INSTANCES.add(instance);
                LOGGER.info("插件已装载: {} v{} by {}（{}）",
                        instance.namespace(), instance.plugin().version(),
                        instance.plugin().author(), jar.getName());
            } catch (RuntimeException | Error e) {
                abortLoading(instance.namespace(), jar.getPath(), e);
            }
        }
    }

    /**
     * 装载失败终点：弹错误框显示插件名 + 包路径 + 错误信息（EDT 阻塞至用户确认），
     * 确认后关闭编辑器（System.exit）。弹框文案硬编码中文——装载期语言包未注册
     * （随 setup），i18n 不可用；线程等待失败（极端时序）跳过弹框直接退出。
     *
     * @param namespace 插件命名空间（发现失败时为类名/jar 名）
     * @param location  插件包路径（排障定位；classpath 插件为标记文本）
     * @param error     装载错误
     */
    private static void abortLoading(String namespace, String location, Throwable error) {
        LOGGER.error("插件装载失败: {}（{}）", namespace, location, error);
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                    "插件装载失败\n\n插件: " + namespace + "\n路径: " + location
                            + "\n\n" + error.getMessage(),
                    "Javelin Editor", JOptionPane.ERROR_MESSAGE));
        } catch (Exception e) {
            LOGGER.warn("装载失败弹框不可用（EDT 等待失败），直接关闭编辑器");
        }
        System.exit(1);
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
}
