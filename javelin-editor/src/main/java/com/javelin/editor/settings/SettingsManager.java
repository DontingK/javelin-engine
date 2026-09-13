package com.javelin.editor.settings;

import com.javelin.core.serialize.Serializable;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import com.javelin.core.settings.GameSettings;
import com.javelin.editor.project.Project;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.serializable.TextImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * 设置管理器。
 *
 * <p>负责项目设置（{@link ProjectSettings}）和游戏设置（{@link GameSettings}）的加载与保存。
 * 编辑器全局设置（{@link EditorSettings}）由本类提供加载入口，但由 {@link com.javelin.editor.ProjectManagerJFrame}
 * 负责写入，以避免项目运行期间覆盖新建项目目录等全局偏好。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class SettingsManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsManager.class);

    private static final String PROJECT_SETTINGS_FILE = "project.settings";
    private static final String GAME_SETTINGS_FILE = "game.settings";
    private static final String EDITOR_SETTINGS_FILE = "editor.settings";
    private static final File EDITOR_SETTINGS_DIR = new File(System.getProperty("user.home"), ".javelin");
    private static final File EDITOR_SETTINGS_PATH = new File(EDITOR_SETTINGS_DIR, EDITOR_SETTINGS_FILE);

    private final Project project;

    private ProjectSettings projectSettings;
    private GameSettings gameSettings;
    private EditorSettings editorSettings;

    /**
     * @param project 项目实例
     */
    public SettingsManager(Project project) {
        this.project = project;
        loadSettings();
//        EditorEventBus.getInstance().register(SceneLoadedEvent.class, event -> {
//            File sceneFilePath = event.getSceneFilePath();
//            String path = FileUtil.relativePath(project.getAssetsDir(), sceneFilePath);
//            projectSettings.setDefaultOpenScene(path);
//            saveProjectSettings();
//        });
    }

    /**
     * 保存编辑器全局设置到磁盘。
     *
     * @param settings 编辑器设置实例
     */
    public void saveEditorSettings(EditorSettings settings) {
        if (!EDITOR_SETTINGS_DIR.exists()) {
            EDITOR_SETTINGS_DIR.mkdirs();
        }
        try {
            new TextExporter(EDITOR_SETTINGS_PATH).export(settings);
            LOGGER.debug("保存编辑器设置: {}", EDITOR_SETTINGS_PATH);
        } catch (IOException e) {
            LOGGER.error("保存编辑器设置失败: {}", EDITOR_SETTINGS_PATH, e);
        }
    }


    private void loadSettings() {
        this.projectSettings = loadOrCreate(ProjectSettings.class, PROJECT_SETTINGS_FILE, project.getEditorDir());
        this.gameSettings = loadOrCreate(GameSettings.class, GAME_SETTINGS_FILE, project.getGameDir());
        this.editorSettings = loadOrCreate(EditorSettings.class, EDITOR_SETTINGS_FILE, EDITOR_SETTINGS_DIR);
    }

    private <T extends Serializable> T loadOrCreate(Class<T> type, String fileName, File root) {
        File file = new File(root, fileName);
        if (file.exists()) {
            try {
                return new TextImporter(file).importData(type);
            } catch (Exception e) {
                try {
                    return new BinaryImporter(file).importData(type);
                } catch (Exception e2) {
                    LOGGER.error("加载 {} 失败: {}, 将创建默认设置", fileName, file, e);
                }
            }
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("无法创建设置实例: " + type, e);
        }
    }

    public void saveProjectSettings() {
        File file = new File(project.getEditorDir(), PROJECT_SETTINGS_FILE);
        try {
            new TextExporter(file).export(projectSettings);
            LOGGER.debug("保存项目设置: {}", file);
        } catch (IOException e) {
            LOGGER.error("保存项目设置失败: {}", file, e);
        }
    }

    public void saveGameSettings() {
        File file = new File(project.getGameDir(), GAME_SETTINGS_FILE);
        try {
            new BinaryExporter(file).export(gameSettings);
            LOGGER.debug("保存游戏设置: {}", file);
        } catch (IOException e) {
            LOGGER.error("保存游戏设置失败: {}", file, e);
        }
    }

    public void saveEditorSettings() {
        File file = new File(EDITOR_SETTINGS_DIR, EDITOR_SETTINGS_FILE);
        try {
            new TextExporter(file).export(editorSettings);
            LOGGER.debug("保存编辑器设置: {}", file);
        } catch (IOException e) {
            LOGGER.error("保存编辑器设置失败: {}", file, e);
        }
    }

    public ProjectSettings getProjectSettings() {
        return projectSettings;
    }

    public GameSettings getGameSettings() {
        return gameSettings;
    }

    public EditorSettings getEditorSettings() {
        return editorSettings;
    }
}
