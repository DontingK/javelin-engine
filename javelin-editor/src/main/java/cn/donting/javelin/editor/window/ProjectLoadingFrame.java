package cn.donting.javelin.editor.window;

import cn.donting.javelin.core.settings.AntialiasingMode;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.asset.BuiltinAssetReleaser;
import cn.donting.javelin.editor.asset.ProjectAssetLibManager;
import cn.donting.javelin.editor.importer.AssetImportPipeline;
import cn.donting.javelin.editor.meta.MetaManager;
import cn.donting.javelin.editor.plugin.PluginManager;
import cn.donting.javelin.editor.project.GradleProject;
import cn.donting.javelin.editor.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;

/**
 * 项目加载窗口（Swing 版）。
 *
 * <p>去 Web 化迁移（2026-10-17，Editor_Swing_Design §9 M6 项提前）：替换 CEF
 * projectLoading 页——文案 + 项目名 + 不确定进度条与 web 页对齐。资产导入与引擎
 * 启动引导链（Engine Main 线程：MetaManager → 资产管线 → 内置资源释放 →
 * JavelinEditor.start）随迁移原样保留，仅换宿主窗口。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class ProjectLoadingFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ProjectLoadingFrame.class);

    public ProjectLoadingFrame(File projectDir) {
        setTitle("Javelin Editor");
        setSize(400, 300);
        setUndecorated(true);
        setLocationRelativeTo(null);
        setContentPane(buildContent(projectDir));
        initAssets(projectDir);
    }

    /** 内容：垂直居中——"正在加载项目" + 项目名 + 不确定进度条（web loading 页对齐） */
    private JComponent buildContent(File projectDir) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(new EmptyBorder(0, 60, 0, 60));
        content.add(Box.createVerticalGlue());
        JLabel textLabel = new JLabel("正在加载项目");
        textLabel.setFont(textLabel.getFont().deriveFont(Font.BOLD, 16f));
        textLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(textLabel);
        content.add(Box.createVerticalStrut(8));
        JLabel nameLabel = new JLabel(projectDir.getName());
        nameLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(nameLabel);
        content.add(Box.createVerticalStrut(16));
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(progressBar);
        content.add(Box.createVerticalGlue());
        return content;
    }

    /**
     * 资产导入 + 引擎启动（Engine Main 线程——与编辑器帧循环线程同名衔接旧启动链）。
     */
    private void initAssets(File projectDir) {
        ThreadUtil.run("Engine Main", () -> {
            try {
                GradleProject gradleProject = new GradleProject(projectDir);
                // 插件装载（editor.core + 项目级 Plugins/ 目录）——先于资产初始化，
                // 为将来插件贡献导入器留位；editor.core 失败随本 catch fail-fast
                PluginManager.loadAll(new File(gradleProject.getRootDir(), "Plugins"));
                MetaManager metaManager = new MetaManager(gradleProject);
                ProjectAssetLibManager assetManager = new ProjectAssetLibManager(gradleProject.getLibrary());
                AssetImportPipeline assetImportPipeline = new AssetImportPipeline(gradleProject, metaManager);
                BuiltinAssetReleaser builtinAssetReleaser = new BuiltinAssetReleaser(gradleProject, metaManager, assetImportPipeline);
                builtinAssetReleaser.releaseAll();
                assetImportPipeline.refresh();
                assetImportPipeline.startAndWait();
                assetManager.loadIndex();
                // 最短展示 2s 防窗口一闪而过（沿用 CEF 版行为）
                ThreadUtil.sleep(2000);

                SwingUtilities.invokeAndWait((Runnable) () -> dispose());
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
}
