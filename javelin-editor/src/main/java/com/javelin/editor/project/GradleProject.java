package com.javelin.editor.project;


import com.javelin.core.scene.GameScene;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.settings.*;
import com.javelin.editor.EditorEnv;
import com.javelin.editor.console.ConsoleOut;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.settings.ProjectSettings;
import com.javelin.editor.util.FileUtil;
import com.javelin.editor.util.StringUtil;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author: javelin
 * @create: 2026/4/4
 * @Version 1.0
 **/
public class GradleProject extends Project {


    private static final Logger LOGGER = LoggerFactory.getLogger(GradleProject.class);

    public GradleProject(File rootDir) {
        super(rootDir);
    }

    @Override
    public void compile() {
        LOGGER.info("开始编译:{}", rootDir);
        runTask("compileJava", null);
    }

    @Override
    public void nativeBuild(ConsoleOut out) {
        File file = nativeBuildOutDir();
        FileUtil.delete(file);
        runTask("nativeCompile", out);
    }

    @Override
    public void jarBuild(ConsoleOut out) {
        long start = System.currentTimeMillis();
        out.info("==== jar 打包开始（项目: " + rootDir.getAbsolutePath() + "）====");
        out.info("---- 阶段 1/5: 清理 native 产物目录 ----");
        File file = nativeBuildOutDir();
        FileUtil.delete(file);
        out.info("已清理: " + file.getAbsolutePath());
        out.info("---- 阶段 2/5: dumpRuntimeJars（导出运行时依赖清单）----");
        runTask("dumpRuntimeJars", out);
        out.info("---- 阶段 3/5: build（compileJava 编译 + jar 打包）----");
        runTask("build", out);
        out.info("---- 阶段 4/5: jlink 裁剪游戏运行时 ----");
        dumpRuntimeByJlink(out);
        out.info("---- 阶段 5/5: 组装产物（build/jar）----");
        try {
            creatOutputJarApp(out);
            out.info("==== jar 打包完成，总耗时 " + (System.currentTimeMillis() - start) + "ms ====");
        } catch (IOException e) {
            out.error("产物组装失败: " + e.getMessage());
        }
    }

    @Override
    public File nativeBuildOutDir() {
        return new File(rootDir, "build/native/nativeCompile");
    }

    /**
     * 运行 Gradle 任务。
     *
     * @param task 任务名称
     * @param out  输出行回调，为 {@code null} 时不输出日志
     */
    public void runTask(String task, ConsoleOut out) {
        long start = System.currentTimeMillis();
        if (out != null) {
            out.info(">> Gradle 任务: " + task + "（开始）");
        }
        try (ProjectConnection connection = getConnection()) {
            BuildLauncher launcher = connection.newBuild().forTasks(task);
            if (out != null) {
                // stdout=info（任务执行行/编译常规输出）、stderr=error（编译报错行——
                // 分级后前端日志区红字，打包过程失败点一眼可判）
                launcher.setStandardOutput(new LineConsumerOutputStream(out::info));
                launcher.setStandardError(new LineConsumerOutputStream(out::error));
            }
            launcher.run();
        } catch (Exception e) {
            LOGGER.error("执行任务失败: {}", task, e);
            if (out != null) {
                out.error("<< Gradle 任务失败: " + task + " —— " + e.getMessage());
            }
            throw new RuntimeException(e);
        }
        if (out != null) {
            out.info("<< Gradle 任务: " + task + " 完成，耗时 " + (System.currentTimeMillis() - start) + "ms");
        }
    }

    private void creatOutputJarApp(ConsoleOut out) throws IOException {
        File file = new File(rootDir, "build/jar");
        File runtimeDir = new File(file, "runtime");
        File dependencies = new File(rootDir, "build/dependencies.txt");
        File lib = new File(rootDir, "build/jar/lib");
        File gameJar = new File(rootDir, "build/libs/game.jar");
        FileUtil.delete(file);
        file.mkdirs();
        lib.mkdirs();
        out.info("产物目录: " + file.getAbsolutePath());

        if (!gameJar.exists()) {
            out.error("缺少主 jar: " + gameJar.getAbsolutePath() + "（build 任务未产出？）");
        } else {
            File mainTarget = new File(file, gameJar.getName());
            FileUtil.copy(gameJar, mainTarget);
            out.info("复制主 jar: " + gameJar.getAbsolutePath() + " -> " + mainTarget.getAbsolutePath());
        }

        if (!dependencies.exists()) {
            out.warn("依赖清单缺失: " + dependencies.getAbsolutePath() + "（dumpRuntimeJars 未产出？）");
        } else {
            List<String> strings = Files.readAllLines(dependencies.toPath());
            out.info("依赖清单: " + dependencies.getAbsolutePath() + "（" + strings.size() + " 行）");
            for (String string : strings) {
                if (StringUtil.isNotBlank(string)) {
                    File libJar = new File(string);
                    File libTarget = new File(lib, libJar.getName());
                    FileUtil.copy(libJar, libTarget);
                    out.info("复制依赖: " + string + " -> " + libTarget.getAbsolutePath());
                }
            }
        }
        // 运行时 = 阶段 4 的 jlink 裁剪产物（build/runtime 工作区），并入产物目录
        File runtimeStaging = new File(rootDir, "build/runtime");
        if (!runtimeStaging.exists()) {
            out.warn("运行时缺失（jlink 未产出？）: " + runtimeStaging.getAbsolutePath());
        } else {
            FileUtil.copy(runtimeStaging, runtimeDir);
            out.info("复制运行时: " + runtimeStaging.getAbsolutePath() + " -> " + runtimeDir.getAbsolutePath());
        }

        // Game/ 与 Library/ 原封不动并入产物（表单参数已先写入 Game → 复制即新值；
        // Library 含场景二进制产物，运行时按 guid 加载）
        File gameDir = new File(rootDir, "Game");
        if (!gameDir.exists()) {
            out.warn("Game 目录缺失: " + gameDir.getAbsolutePath());
        } else {
            File gameTarget = new File(file, "Game");
            FileUtil.copy(gameDir, gameTarget);
            out.info("复制 Game: " + gameDir.getAbsolutePath() + " -> " + gameTarget.getAbsolutePath());
        }
        File libraryDir = new File(rootDir, "Library");
        if (!libraryDir.exists()) {
            out.warn("Library 目录缺失: " + libraryDir.getAbsolutePath());
        } else {
            File libraryTarget = new File(file, "Library");
            FileUtil.copy(libraryDir, libraryTarget);
            out.info("复制 Library: " + libraryDir.getAbsolutePath() + " -> " + libraryTarget.getAbsolutePath());
        }

        createLaunchScript(out, file);

    }

    /**
     * 生成启动脚本（jar 形态）：产物根下 {@code {项目名}.bat}——jlink 运行时拉起
     * 游戏。{@code chcp 65001} 切 UTF-8 码页（JDK 18+ 输出默认 UTF-8，中文控制台
     * 默认 GBK——不对齐日志中文乱码）；{@code cd /d %~dp0} 钉死工作目录到脚本所在
     * 目录（Env.getRunDir 的 {@code ./} 即产物根，Game/ 与 Library/ 从此解析）。
     * classpath 用通配 {@code lib\*}（java 自行展开、manifest Class-Path 不支持
     * 通配）——`java -jar` 只挂 game.jar 自身会忽略 -cp，且主类 GameMain 在依赖
     * jar（javelin-core）里不在 game.jar 里，必须走 -cp + 主类全名。非零退出
     * pause 保留窗口供读崩溃日志。内容纯 ASCII（bat 按 ANSI 码页读，中文注释会乱）。
     *
     * @param out      输出回调
     * @param productDir 产物目录（build/jar）
     */
    private void createLaunchScript(ConsoleOut out, File productDir) throws IOException {
        File script = new File(productDir, rootDir.getName() + ".bat");
        String content = "@echo off\r\n"
                + "rem Javelin jar build launch script (jlink runtime + game.jar + lib)\r\n"
                + "chcp 65001 >nul\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + "\"%~dp0runtime\\bin\\java.exe\" -cp \"%~dp0game.jar;%~dp0lib\\*\" com.javelin.core.GameMain\r\n"
                + "if errorlevel 1 pause\r\n";
        Files.write(script.toPath(), content.getBytes(StandardCharsets.US_ASCII));
        out.info("启动脚本: " + script.getAbsolutePath());
    }

    /**
     * jlink 裁剪游戏运行时到 {@code build/runtime}（工作区，组装阶段并入产物）。
     *
     * <p>模块清单由 jdeps 对 game.jar + 全部依赖 jar 分析得出（{@code jdk.unsupported}
     * 由 LWJGL 的 sun.misc.Unsafe 引入，缺了游戏起不来——jdeps 会自动带入）；产物只含
     * 所需模块（几十 MB），替代整 JDK 镜像（几百 MB 且带 javac/jmods 工具链——2026-10-11
     * 拍板：运行时只需 JRE 级）。工具取编辑器自身 JDK 的 bin（编辑器须运行于完整 JDK）。</p>
     *
     * @param out 输出回调
     */
    private void dumpRuntimeByJlink(ConsoleOut out) {
        File runtimeStaging = new File(rootDir, "build/runtime");
        FileUtil.delete(runtimeStaging);

        File gameJar = new File(rootDir, "build/libs/game.jar");
        if (!gameJar.exists()) {
            throw new IllegalStateException("缺少主 jar，无法分析运行时模块: " + gameJar.getAbsolutePath());
        }
        // jdeps 输入 = game.jar + 依赖清单全量（交叉引用可解析）
        List<File> jars = new ArrayList<>();
        jars.add(gameJar);
        File dependencies = new File(rootDir, "build/dependencies.txt");
        if (dependencies.exists()) {
            try {
                for (String line : Files.readAllLines(dependencies.toPath())) {
                    if (StringUtil.isNotBlank(line)) {
                        jars.add(new File(line.trim()));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("读取依赖清单失败: " + dependencies.getAbsolutePath(), e);
            }
        }
        List<String> jarPaths = jars.stream().map(File::getAbsolutePath).toList();

        // multi-release jar（logback 等）必须显式声明分析版本，否则 jdeps 退出码 2
        // （2026-10-11 实测：logback-core-1.5.13 触发）；版本取运行 JDK 大版本——
        // jdeps/jlink 取自同一 JDK，裁出的运行时即此版本形态
        String release = String.valueOf(Runtime.version().feature());
        String[] jdepsArgs = new String[jarPaths.size() + 4];
        jdepsArgs[0] = "--print-module-deps";
        jdepsArgs[1] = "--ignore-missing-deps";
        jdepsArgs[2] = "--multi-release";
        jdepsArgs[3] = release;
        for (int i = 0; i < jarPaths.size(); i++) {
            jdepsArgs[i + 4] = jarPaths.get(i);
        }
        String modules = runJdkTool(out, "jdeps", jdepsArgs);
        if (modules == null || modules.isBlank()) {
            throw new IllegalStateException("jdeps 未得出模块清单（分析输出为空）");
        }
        out.info("jdeps 模块清单: " + modules);

        runJdkTool(out, "jlink", "--add-modules", modules,
                "--output", runtimeStaging.getAbsolutePath(),
                "--strip-debug", "--no-man-pages", "--no-header-files", "--compress", "zip-6");
        out.info("运行时已裁剪: " + runtimeStaging.getAbsolutePath() + "（模块: " + modules + "）");
    }

    /**
     * 运行编辑器 JDK 的 bin 工具（jdeps/jlink），stderr 行流式转发、stdout 收集返回。
     *
     * @param out  输出回调（工具的警告/分析行转发用）
     * @param tool 工具名（jdeps / jlink）
     * @param args 参数
     * @return stdout 全文（jdeps 取模块清单）
     */
    private String runJdkTool(ConsoleOut out, String tool, String... args) {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        File exe = new File(new File(javaHome, "bin"), tool + (windows ? ".exe" : ""));
        if (!exe.exists()) {
            throw new IllegalStateException("JDK 工具不存在: " + exe.getAbsolutePath() + "（编辑器须运行于完整 JDK）");
        }
        List<String> command = new ArrayList<>();
        command.add(exe.getAbsolutePath());
        command.addAll(List.of(args));
        out.info(">> 运行 " + tool + " " + String.join(" ", args));
        try {
            Process process = new ProcessBuilder(command).start();
            StringBuilder stdout = new StringBuilder();
            // stderr 单独线程读（分析与告警行），防双流互等死锁
            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.info("[" + tool + "] " + line);
                    }
                } catch (IOException ignored) {
                    // 进程退出导致的流截断，非错误
                }
            }, "jdk-tool-stderr");
            errThread.setDaemon(true);
            errThread.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            errThread.join(2000);
            if (code != 0) {
                throw new IllegalStateException(tool + " 退出码 " + code + "（详见日志行）");
            }
            return stdout.toString().trim();
        } catch (IOException e) {
            throw new RuntimeException("运行 " + tool + " 失败: " + exe.getAbsolutePath(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("运行 " + tool + " 被中断", e);
        }
    }

    /**
     * 按行消费 Gradle 输出的输出流。
     */
    private static class LineConsumerOutputStream extends OutputStream {

        private final Consumer<String> consumer;
        private final StringBuilder buffer = new StringBuilder();

        LineConsumerOutputStream(Consumer<String> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void write(int b) {
            appendChar((char) (b & 0xFF));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            String str = new String(b, off, len, StandardCharsets.UTF_8);
            for (int i = 0; i < str.length(); i++) {
                appendChar(str.charAt(i));
            }
        }

        private void appendChar(char c) {
            if (c == '\n') {
                flushLine();
            } else if (c != '\r') {
                buffer.append(c);
            }
        }

        @Override
        public void flush() {
            flushLine();
        }

        private void flushLine() {
            if (!buffer.isEmpty()) {
                consumer.accept(buffer.toString());
                buffer.setLength(0);
            }
        }
    }

    public ProjectConnection getConnection() {
        return GradleConnector.newConnector()
                .forProjectDirectory(rootDir)
                .connect();
    }


    public static GradleProject createNewProject(String projectDir, String projectName,
                                                 String groupId, String artifactId) throws IOException {
        File projectDirFile = new File(projectDir, projectName);
        if (projectDirFile.exists()) {
            throw new RuntimeException("项目已存在");
        }
        projectDirFile.mkdirs();
        if (EditorEnv.isRelease()) {
            //从resources 读取
            throw new RuntimeException("请使用release版本");
        } else {
            //project-template复制
            FileUtil.copyContent(new File(EditorEnv.getEditorRuntimeDir(), "project-template"), projectDirFile);
        }
        File editorDir = new File(projectDirFile, ".javelin");
        editorDir.mkdirs();
        replaceVar(projectDirFile, groupId, artifactId);
        createDir(projectDirFile);
        createDefaultSettings(editorDir, projectDirFile);
        createDefaultScene(projectDirFile);
        return new GradleProject(projectDirFile);
    }

    private static void createDir(File projectDirFile) {
        FileUtil.createFileByPath(projectDirFile, "src", "main", "java").mkdirs();
        FileUtil.createFileByPath(projectDirFile, "src", "main", "resources").mkdirs();
        FileUtil.createFileByPath(projectDirFile, "src", "test", "java").mkdirs();
        FileUtil.createFileByPath(projectDirFile, "Assets").mkdirs();
        FileUtil.createFileByPath(projectDirFile, "Library").mkdirs();
        FileUtil.createFileByPath(projectDirFile, "Game").mkdirs();
    }

    private static void createDefaultScene(File projectDirFile) {
        try {
            File scenesDir = new File(projectDirFile, "Assets/scenes");
            scenesDir.mkdirs();
            File sceneFile = new File(scenesDir, "default.scene");
            GameScene scene = new GameScene("Default Scene");
            new TextExporter(sceneFile).export(scene);
        } catch (Exception e) {
            LOGGER.error("创建默认场景文件失败", e);
        }
    }


    public static void createDefaultSettings(File projectEditorDir, File projectDirFile) throws IOException {
        LOGGER.info("创建默认设置");
        //创建layer
        File settingDir = new File(projectDirFile, "Game");
        File file = new File(settingDir, SettingManager.LAYER_SETTINGS_FILE);
        LayerSettings layerSettings = new LayerSettings();
        layerSettings.getLayerNames()[0] = "Default";
        layerSettings.getLayerNames()[1] = "TransparentFX";
        layerSettings.getLayerNames()[2] = "Ignore Raycast";
        layerSettings.getLayerNames()[4] = "Water";
        layerSettings.getLayerNames()[5] = "UI";
        BinaryExporter binaryExporter = new BinaryExporter(file);
        binaryExporter.export(layerSettings);

        //创建tag
        TagSettings tagSettings = new TagSettings();
        tagSettings.add("Untagged");
        file = new File(settingDir, SettingManager.TAG_SETTINGS_FILE);
        binaryExporter = new BinaryExporter(file);
        binaryExporter.export(tagSettings);

        //创建javelin Setting
        JavelinSetting javelinSetting = new JavelinSetting();
        file = new File(settingDir, SettingManager.JAVELIN_SETTINGS_FILE);
        binaryExporter = new BinaryExporter(file);
        binaryExporter.export(javelinSetting);

        //创建game Setting
        GameSettings gameSettings = new GameSettings();
        file = new File(settingDir, SettingManager.GAME_SETTINGS_FILE);
        binaryExporter = new BinaryExporter(file);
        binaryExporter.export(gameSettings);

        //创建项目设置
        File projectSettingsFile = new File(projectEditorDir, "project.settings");
        ProjectSettings projectSettings = new ProjectSettings();
        projectSettings.setDefaultOpenScene("scenes/default.scene");
        try {
            new TextExporter(projectSettingsFile).export(projectSettings);
        } catch (Exception e) {
            LOGGER.error("创建项目设置文件失败", e);
        }

    }

    private static void replaceVar(File projectDir, String groupId, String artifactId) throws IOException {
        File file = new File(projectDir, "build.gradle");
        String buildGradle = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        buildGradle = buildGradle.replace("group = 'com.javelin'", "group = '" + groupId + "'");
        Files.writeString(file.toPath(), buildGradle, StandardCharsets.UTF_8);

        file = new File(projectDir, "settings.gradle");
        String settingsGradle = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        settingsGradle = settingsGradle.replace("project-template", artifactId);
        Files.writeString(file.toPath(), settingsGradle, StandardCharsets.UTF_8);
    }

}
