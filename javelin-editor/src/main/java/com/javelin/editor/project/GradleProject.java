package com.javelin.editor.project;


import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Scene;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.settings.*;
import com.javelin.editor.EditorEnv;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.settings.ProjectSettings;
import com.javelin.editor.util.FileUtil;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public void nativeBuild(Consumer<String> out) {
        File file = nativeBuildOutDir();
        FileUtil.delete(file);
        runTask("nativeCompile", out);
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
    public void runTask(String task, Consumer<String> out) {
        try (ProjectConnection connection = getConnection()) {
            BuildLauncher launcher = connection.newBuild().forTasks(task);
            if (out != null) {
                OutputStream stream = new LineConsumerOutputStream(out);
                launcher.setStandardOutput(stream);
                launcher.setStandardError(stream);
            }
            launcher.run();
        } catch (Exception e) {
            LOGGER.error("执行任务失败: {}", task, e);
            throw new RuntimeException(e);
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
