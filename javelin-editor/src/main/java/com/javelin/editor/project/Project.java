package com.javelin.editor.project;


import com.javelin.editor.util.FileUtil;

import java.io.File;
import java.util.function.Consumer;

/**
 * @author: javelin
 * @create: 2026/4/4
 * @Version 1.0
 **/
public abstract class Project {

    protected final File rootDir;

    public Project(File rootDir) {
        this.rootDir = rootDir;
    }

    public abstract void compile();


    public File getRootDir() {
        return rootDir;
    }

    /**
     * 编辑器数据目录
     * @return
     */
    public File getEditorDir() {
        return new File(rootDir, ".javelin");
    }


    /**
     * 资源目录
     * @return
     */
    public File getAssetsDir() {
        //资源
        return new File(rootDir, "Assets");
    }

    /**
     * Library 目录
     * @return
     */
    public File getLibrary() {
        //资源
        return new File(rootDir, "Library");
    }

    public File getGameDir() {
        return new File(rootDir, "Game");
    }

    /**
     * 需要监听的资源文件目录，包括源码
     * @return
     */
    public File[] getWatchAssetDir() {
        return new File[]{
                new File(rootDir, "src/main/java"),
                new File(rootDir, "Assets"),
                new File(rootDir, ".javelin/assets"),
        };
    }

    public File getSrcMainJava() {
        return new File(rootDir, "src/main/java");
    }
    /**
     * 获取文件相对于项目根目录的路径
     *
     * @param file 文件
     * @return 相对路径（使用 / 分隔）
     */
    public String getRelativePath(File file) {
      return FileUtil.relativePath(rootDir,file);
    }

    public File getScrDir() {
        return new File(rootDir, "src");
    }

    /**
     *
     */
    public abstract void nativeBuild(Consumer<String> out);
    public abstract File nativeBuildOutDir();
}
