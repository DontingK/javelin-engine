package com.javelin.editor.window.cef;

import com.javelin.core.settings.AntialiasingMode;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.asset.BuiltinAssetReleaser;
import com.javelin.editor.asset.ProjectAssetLibManager;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.project.GradleProject;
import com.javelin.editor.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;

public class ProjectLoadingFrame extends CefFrame {

    private static final Logger log = LoggerFactory.getLogger(ProjectLoadingFrame.class);
    private static ProjectLoadingFrame instance;

    public ProjectLoadingFrame(File projectDir) {
        loadURL("http://localhost:5173/page/projectLoading.html");
        instance = this;
        setSize(400, 300);
        setUndecorated(true);
        //屏幕居中显示
        setLocationRelativeTo(null);
        intAsset(projectDir);
    }

    private void intAsset(File projectDir) {
        ThreadUtil.run("Engine Main", () -> {
            try {
                GradleProject gradleProject = new GradleProject(projectDir);
                MetaManager metaManager = new MetaManager(gradleProject);
                ProjectAssetLibManager assetManager = new ProjectAssetLibManager(gradleProject.getLibrary());
                AssetImportPipeline assetImportPipeline = new AssetImportPipeline(gradleProject, metaManager, assetManager);
                BuiltinAssetReleaser builtinAssetReleaser = new BuiltinAssetReleaser(gradleProject, metaManager, assetImportPipeline);
                builtinAssetReleaser.releaseAll();
                assetImportPipeline.refresh();
                assetImportPipeline.startAndWait();
                assetManager.loadIndex();
                ThreadUtil.sleep(2000);

                SwingUtilities.invokeAndWait((Runnable) () -> getInstance().dispose());
                JavelinSetting setting = new JavelinSetting();
                setting.setAntialiasing(AntialiasingMode.MSAA_8);
                setting.setMaxFps(144);
                // 拍板（2026-10-17，Vulkan_Pitfalls #21 跨机器复发后）：编辑器恒关 vsync 走
                // IMMEDIATE + maxFps 限帧兜底——无 MAILBOX 的驱动会静默回退 FIFO，
                // 触发 DWM 踩线/错过双稳（细线/小字闪烁）
                setting.setVsync(false);
                JavelinEditor editorApp = new JavelinEditor(gradleProject, setting, metaManager, assetManager, assetImportPipeline, builtinAssetReleaser);
                editorApp.start();

            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        });
    }

    public static ProjectLoadingFrame getInstance() {
        return instance;
    }
}
