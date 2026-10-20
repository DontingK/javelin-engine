package com.javelin.editor.plugin;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 第三方插件 classloader（每 jar 一实例）：parent-first 委托（宿主依赖
 * 版本优先，插件不得以冲突版本覆盖）；v1 无 unload，生命周期与进程同寿。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class PluginClassLoader extends URLClassLoader {

    /**
     * 构造装载器。
     *
     * @param jar    插件 jar 文件（必须存在且是文件）
     * @param parent 宿主 classloader（parent-first 委托目标）
     * @throws IllegalArgumentException jar 不存在或不是文件
     */
    public PluginClassLoader(File jar, ClassLoader parent) {
        super(jarUrl(jar), parent);
    }

    private static URL[] jarUrl(File jar) {
        if (jar == null || !jar.exists() || !jar.isFile()) {
            throw new IllegalArgumentException("插件 jar 不存在或不是文件: " + jar);
        }
        try {
            return new URL[]{jar.toURI().toURL()};
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("插件 jar URL 非法: " + jar, e);
        }
    }
}
