package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.core.render.pipeline.PostProcessChain;
import cn.donting.javelin.core.render.pipeline.PostProcessSetup;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.SettingManager;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;

/**
 * Bloom 开关命令（工具域，checked = 链含 Bloom）：独立于后处理（Tonemap）开关，二者可分别开关。
 *
 * <p>运行期切换即时生效（链组成变化 → 图缓存下次构建失效，RHI 18.5），便于现场比对
 * 开/关画面；同时把开关写进项目 Game/{@code javelinSettings.settings}（load-改-save，
 * 后处理/渲染档位命令同口径）供下次启动沿用。装配失败与写盘失败均弹错误框（红线 15）。</p>
 *
 * @author javelin
 * @create 2026/11/13
 */
public final class BloomMenuCommand extends TopMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(BloomMenuCommand.class);

    @Override
    public String id() {
        return "tools.bloom";
    }

    @Override
    public Boolean checked() {
        RenderPipeline pipeline = pipeline();
        PostProcessSetup setup = setup();
        return pipeline != null && setup != null
                && setup.isEnabled(pipeline.postProcess(), PostProcessSetup.BLOOM);
    }

    @Override
    public void execute() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器未就绪，Bloom 开关不可用");
            return;
        }
        RenderPipeline pipeline = pipeline();
        PostProcessSetup setup = setup();
        if (pipeline == null || setup == null) {
            LOGGER.warn("编辑器渲染管线未就绪，Bloom 开关不可用");
            return;
        }
        PostProcessChain chain = pipeline.postProcess();
        boolean next = !setup.isEnabled(chain, PostProcessSetup.BLOOM);
        try {
            // 链装配要惰性创建 Bloom 并装载内置着色器产物——缺失/坏产物属异常态，弹框不静默
            setup.setEnabled(chain, PostProcessSetup.BLOOM, next);
        } catch (RuntimeException e) {
            LOGGER.error("Bloom 链装配失败: enabled={}", next, e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    MainFrame.getInstance(),
                    EditorLang.text("tools.bloom.loadFailed"),
                    EditorLang.text("tools.bloom"), JOptionPane.ERROR_MESSAGE));
            return;
        }
        editor.setting().setBloom(next);
        LOGGER.info("Bloom 链切至 {}", next ? "开" : "关");
        persist(editor, next);
    }

    /** 开关写盘（项目 Game/javelinSettings.settings；失败弹错误框，红线 15） */
    private void persist(JavelinEditor editor, boolean enabled) {
        try {
            File settingFile = new File(editor.getProject().getGameDir(),
                    SettingManager.JAVELIN_SETTINGS_FILE);
            JavelinSetting setting = settingFile.exists()
                    ? new BinaryImporter(settingFile).importData(JavelinSetting.class)
                    : new JavelinSetting();
            setting.setBloom(enabled);
            new BinaryExporter(settingFile).export(setting);
        } catch (Exception e) {
            LOGGER.error("Bloom 开关写盘失败: {}", enabled, e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    MainFrame.getInstance(),
                    EditorLang.text("tools.bloom.writeFailed", String.valueOf(e.getMessage())),
                    EditorLang.text("tools.bloom"), JOptionPane.ERROR_MESSAGE));
        }
    }

    /** 当前引擎渲染管线（编辑器未建返回 null——切换降级为 no-op） */
    private RenderPipeline pipeline() {
        JavelinEditor editor = JavelinEditor.getInstance();
        return editor == null ? null : editor.renderPipeline();
    }

    /** 后处理节点装配（组合根；编辑器未建返回 null） */
    private PostProcessSetup setup() {
        JavelinEditor editor = JavelinEditor.getInstance();
        return editor == null ? null : editor.postProcessSetup();
    }
}
