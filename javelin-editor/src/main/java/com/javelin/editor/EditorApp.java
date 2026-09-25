package com.javelin.editor;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.asset.BuiltinAssetReleaser;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.window.cef.MainFrame;
import com.javelin.editor.window.cef.ProjectFrame;

public class EditorApp extends JavelinEngine {

    private final JavelinSetting setting;
    private final MetaManager metaManager;
    private final AssetManager assetManager;
    private final AssetImportPipeline assetImportPipeline;
    private final BuiltinAssetReleaser builtinAssetReleaser;

    public EditorApp(JavelinSetting setting,
                     MetaManager metaManager,
                     AssetManager assetManager,
                     AssetImportPipeline assetImportPipeline,
                     BuiltinAssetReleaser builtinAssetReleaser) {
        super(setting);
        this.setting=setting;
        this.metaManager=metaManager;
        this.assetManager=assetManager;
        this.assetImportPipeline=assetImportPipeline;
        this.builtinAssetReleaser=builtinAssetReleaser;
    }

    @Override
    protected AssetManager createAssetManager() {
        return this.assetManager;
    }

    @Override
    protected Window createWindow() {
        return new MainFrame(setting);
    }

    public static void main(String[] args) {
        ProjectFrame projectWindow = new ProjectFrame();
        projectWindow.setSize(1280, 720);
        projectWindow.setVisible(true);
    }
}
