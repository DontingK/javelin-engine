package com.javelin.editor.project;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 最近打开项目管理器。
 *
 * <p>持久化存储最近打开的项目路径列表，存储在用户主目录下的 {@code .javelin/recent.properties}。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class RecentProjectManager {

    private static final File CONFIG_DIR = new File(System.getProperty("user.home"), ".javelin");
    private static final File RECENT_FILE = new File(CONFIG_DIR, "recent.properties");
    private static final int MAX_RECENT = 10;

    private final Properties properties;

    public RecentProjectManager() {
        this.properties = new Properties();
        load();
    }

    /**
     * 获取最近项目路径列表。
     *
     * @return 项目路径列表
     */
    public List<String> getRecentProjects() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String path = properties.getProperty("recent." + i);
            if (path == null || path.isEmpty()) {
                break;
            }
            if (new File(path).exists()) {
                list.add(path);
            }
        }
        return list;
    }

    /**
     * 添加项目到最近列表。
     *
     * @param projectDir 项目目录
     */
    public void addProject(String projectDir) {
        List<String> list = getRecentProjects();
        list.remove(projectDir);
        list.add(0, projectDir);
        properties.clear();
        for (int i = 0; i < Math.min(list.size(), MAX_RECENT); i++) {
            properties.setProperty("recent." + i, list.get(i));
        }
        save();
    }

    private void load() {
        if (!RECENT_FILE.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(RECENT_FILE)) {
            properties.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void save() {
        if (!CONFIG_DIR.exists()) {
            CONFIG_DIR.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(RECENT_FILE)) {
            properties.store(fos, "Recent Projects");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
