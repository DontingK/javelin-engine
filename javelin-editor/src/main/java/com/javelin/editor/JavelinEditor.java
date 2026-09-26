package com.javelin.editor;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Scene;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.asset.BuiltinAssetReleaser;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.project.Project;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.serializable.TextImporter;
import com.javelin.editor.settings.EditorSettings;
import com.javelin.editor.settings.ProjectSettings;
import com.javelin.editor.settings.SettingsManager;
import com.javelin.editor.window.cef.MainFrame;
import com.javelin.editor.window.cef.ProjectFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class JavelinEditor extends JavelinEngine {

    private static final Logger log = LoggerFactory.getLogger(JavelinEditor.class);
    private final JavelinSetting setting;
    private final MetaManager metaManager;
    private final AssetManager assetManager;
    private final AssetImportPipeline assetImportPipeline;
    private final BuiltinAssetReleaser builtinAssetReleaser;
    private final SettingsManager settingsManager;
    private final Project project;
    private final EditorScene editorScene;

    public JavelinEditor(Project project,
                         JavelinSetting setting,
                         MetaManager metaManager,
                         AssetManager assetManager,
                         AssetImportPipeline assetImportPipeline,
                         BuiltinAssetReleaser builtinAssetReleaser) {
        super(setting);
        this.setting = setting;
        this.project = project;
        this.metaManager = metaManager;
        this.assetManager = assetManager;
        this.assetImportPipeline = assetImportPipeline;
        this.builtinAssetReleaser = builtinAssetReleaser;
        this.settingsManager = new SettingsManager(project);
        this.editorScene = new EditorScene();
    }

    @Override
    protected AssetManager createAssetManager() {
        return this.assetManager;
    }

    @Override
    protected Window createWindow() {
        return new MainFrame(setting);
    }


    public MetaManager getMetaManager() {
        return metaManager;
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    @Override
    protected Scene loadScene() {
        ProjectSettings projectSettings = settingsManager.getProjectSettings();
        String defaultOpenScene = projectSettings.getDefaultOpenScene();
        File file = new File(project.getAssetsDir(), defaultOpenScene);

        TextImporter textImporter = new TextImporter(file, assetManager);
        try {
            GameScene gameScene = textImporter.importData(GameScene.class);
            editorScene.setGameScene(gameScene, file);
            editorScene.setEditorScene(new GameScene("editor"));
            return editorScene;
        }catch (Exception ex){
            log.warn(ex.getMessage(),ex);
            return null;
        }
    }

    public static void main(String[] args) {
        ProjectFrame projectWindow = new ProjectFrame();
        projectWindow.setSize(1280, 720);
        projectWindow.setVisible(true);
    }
}
