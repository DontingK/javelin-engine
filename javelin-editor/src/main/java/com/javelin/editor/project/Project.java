package com.javelin.editor.project;


import com.javelin.editor.console.ConsoleOut;
import com.javelin.editor.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Consumer;

/**
 * @author: javelin
 * @create: 2026/4/4
 * @Version 1.0
 **/
public abstract class Project {

    private static final Logger log = LoggerFactory.getLogger(Project.class);
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
     * 原生构建（nativeCompile——AOT 形态的编译阶段）。
     *
     * @param out       输出回调（构建日志行）
     * @param buildMode 构建模式（debug/release，经 -PbuildMode 下发；空 = 模板默认 release）
     */
    public abstract void nativeBuild(ConsoleOut out, String buildMode);

    /**
     * JAR 打包（jlink JRE 运行时链，产物 Builds/jar）。
     */
    public abstract void jarBuild(ConsoleOut out);

    /**
     * AOT 打包（nativeCompile + 产物组装，产物 Builds/aot：exe + 外置 lib/*.dll +
     * Game/ + Library/ + bat）。
     *
     * @param out       输出回调（构建日志行）
     * @param buildMode 构建模式（debug/release，仅 AOT 形态消费；空 = release）
     */
    public abstract void aotBuild(ConsoleOut out, String buildMode);

    /**
     * nativeCompile 工作目录（build/native/nativeCompile）。
     */
    public abstract File nativeBuildOutDir();


}
