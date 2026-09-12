package com.javelin.editor;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.asset.ProjectAssetLibManager;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.project.Project;
import com.javelin.editor.window.SwingWindow;

public class EditorJavelinEngine extends JavelinEngine {

    private final Project project;

    private final MetaManager metaManager;
    private final ProjectAssetLibManager assetManager;
    private final AssetImportPipeline assetImportPipeline;

    public EditorJavelinEngine(JavelinSetting setting,
                               Project project,
                               MetaManager metaManager,
                               ProjectAssetLibManager assetManager,
                               AssetImportPipeline assetImportPipeline) {
        super(setting);
        this.project = project;
        this.metaManager = metaManager;
        this.assetManager = assetManager;
        this.assetImportPipeline = assetImportPipeline;
    }

    public AssetImportPipeline getAssetImportPipeline() {
        return assetImportPipeline;
    }

    public Project getProject() {
        return project;
    }

    @Override
    protected Window createWindow() {
        return new SwingWindow(setting,this);
    }

    @Override
    protected AssetManager createAssetManager() {
        return assetManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }
}
