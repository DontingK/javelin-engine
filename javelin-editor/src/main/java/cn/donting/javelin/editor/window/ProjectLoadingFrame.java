package cn.donting.javelin.editor.window;

import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.SettingManager;
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

    /** 加载页最短可见时长（毫秒）：防窗口一闪而过，引导链已耗时计入其中 */
    private static final long MIN_DISPLAY_MS = 2000L;

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
            long startedAt = System.currentTimeMillis();
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
                // 防窗口一闪而过（沿用 CEF 版行为）：补足到最短可见时长，而非无条件多等 2s
                //（慢工程本已超时则不再叠加——原实现把「最短展示」写成了「额外 +2s」）
                long remainMs = MIN_DISPLAY_MS - (System.currentTimeMillis() - startedAt);
                if (remainMs > 0) {
                    ThreadUtil.sleep(remainMs);
                }

                SwingUtilities.invokeAndWait((Runnable) () -> dispose());
                // 项目设置窗口（Edit|项目设置…）管理的字段（图形|后处理）由项目文件驱动，
                // 编辑器启动读入；其余字段不随项目文件走（见 loadEditorSetting）
                JavelinSetting setting = loadEditorSetting(gradleProject);
                // 拍板（2026-11-13）：编辑器恒用混合（Deferred）管线——管线已拆成两个独立
                // 实现类、无运行期切档，编辑器显式指定（不读项目 javelinSettings 的档位字段，
                // 该字段只供打包运行时启动期选实现类）
                setting.setRenderPipeline(PipelineProfile.HYBRID);
                setting.setMaxFps(144);
                // 拍板（2026-10-17，Vulkan_Pitfalls #21 跨机器复发后）：编辑器恒关 vsync 走
                // IMMEDIATE + maxFps 限帧兜底——无 MAILBOX 的驱动会静默回退 FIFO，
                // 触发 DWM 踩线/错过双稳（细线/小字闪烁）
                setting.setVsync(false);
                JavelinEditor editorApp = new JavelinEditor(gradleProject, setting, metaManager, assetManager, assetImportPipeline, builtinAssetReleaser);
                editorApp.start();

            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
                showStartupError(ex);
            }
        });
    }

    /**
     * 读项目设置（图形|后处理）：把项目 {@code Game/javelinSettings.settings} 里由「项目设置」
     * 窗口管理的字段（背景清屏色、后处理/Bloom 开关、后处理参数）读入编辑器设置；其余字段
     * （窗口尺寸/标题、vsync、maxFps、engineDebug、管线档位）是编辑器运行环境或打包期的事，
     * 不随项目文件走。文件缺失/损坏回退默认（损坏不静默吞——warn 记录后回退）。
     */
    private static JavelinSetting loadEditorSetting(GradleProject project) {
        JavelinSetting setting = new JavelinSetting();
        File file = new File(project.getGameDir(), SettingManager.JAVELIN_SETTINGS_FILE);
        if (!file.exists()) {
            return setting;
        }
        try {
            JavelinSetting loaded = new BinaryImporter(file).importData(JavelinSetting.class);
            if (loaded.getBackgroundColor() != null) {
                setting.setBackgroundColor(loaded.getBackgroundColor());
            }
            setting.setPostProcessing(loaded.isPostProcessing());
            setting.setBloom(loaded.isBloom());
            if (loaded.getPostProcessSettings() != null) {
                setting.setPostProcessSettings(loaded.getPostProcessSettings());
            }
        } catch (Exception e) {
            log.warn("项目 javelinSettings 读取失败，回退编辑器默认: {}", file, e);
        }
        return setting;
    }

    /**
     * 启动链异常（含 engineDebug fail-fast 无校验层）：弹错误框让用户看见（红线 15），
     * 确认后关闭进程——加载窗已 dispose，无其他可见窗口可回退。
     */
    private void showStartupError(Throwable error) {
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                    "引擎启动失败\n\n" + error.getMessage(),
                    "Javelin Editor", JOptionPane.ERROR_MESSAGE));
        } catch (Exception e) {
            log.warn("启动失败弹框不可用（EDT 等待失败），直接关闭编辑器");
        }
        System.exit(1);
    }
}
