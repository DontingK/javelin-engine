package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.core.render.pipeline.DefaultHybridRenderPipeline;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import cn.donting.javelin.core.settings.AntialiasingMode;
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
 * 渲染管线档位命令（工具域，checked = 混合档）：FORWARD ↔ HYBRID 运行期切换。
 *
 * <p>切换即时生效（管线列表组成变化 → 图缓存下次构建失效），便于同场景来回切、
 * 现场比对两路光照一致；同时把档位写进项目 Game/{@code javelinSettings.settings}
 * （load-改-save，PackDialog 同口径）供下次启动沿用。写盘失败弹错误框（红线 15）。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public final class RenderPipelineMenuCommand extends TopMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(RenderPipelineMenuCommand.class);

    @Override
    public String id() {
        return "tools.renderPipeline";
    }

    @Override
    public Boolean checked() {
        DefaultHybridRenderPipeline pipeline = pipeline();
        return pipeline != null && pipeline.profile() == PipelineProfile.HYBRID;
    }

    @Override
    public void execute() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器未就绪，渲染管线档位切换不可用");
            return;
        }
        DefaultHybridRenderPipeline pipeline = pipeline();
        if (pipeline == null) {
            LOGGER.warn("编辑器渲染管线未就绪，档位切换不可用");
            return;
        }
        PipelineProfile next = pipeline.profile() == PipelineProfile.HYBRID
                ? PipelineProfile.FORWARD : PipelineProfile.HYBRID;
        // HYBRID 不支持 MSAA（G-Buffer 多采样归四期）：档位在引擎启动期定采样档，
        // 运行期无法改——开 AA 时切 HYBRID 会致透明 Pass 走 MSAA 附件、与 G-Buffer/
        // 光照直写 1x 目标冲突。显性拒绝并说明（红线 15：不静默切出坏画面）
        if (next == PipelineProfile.HYBRID
                && editor.setting().getAntialiasing() != AntialiasingMode.OFF) {
            LOGGER.warn("HYBRID 档不支持 MSAA，档位未切换: antialiasing={}",
                    editor.setting().getAntialiasing());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    MainFrame.getInstance(),
                    EditorLang.text("tools.renderPipeline.aaEnabled"),
                    EditorLang.text("tools.renderPipeline"), JOptionPane.ERROR_MESSAGE));
            return;
        }
        pipeline.setProfile(next);
        editor.setting().setRenderPipeline(next);
        LOGGER.info("渲染管线档位切至 {}", next);
        persist(editor, next);
    }

    /** 档位写盘（项目 Game/javelinSettings.settings；失败弹错误框，红线 15） */
    private void persist(JavelinEditor editor, PipelineProfile profile) {
        try {
            File settingFile = new File(editor.getProject().getGameDir(),
                    SettingManager.JAVELIN_SETTINGS_FILE);
            JavelinSetting setting = settingFile.exists()
                    ? new BinaryImporter(settingFile).importData(JavelinSetting.class)
                    : new JavelinSetting();
            setting.setRenderPipeline(profile);
            new BinaryExporter(settingFile).export(setting);
        } catch (Exception e) {
            LOGGER.error("渲染管线档位写盘失败: {}", profile, e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    MainFrame.getInstance(),
                    EditorLang.text("tools.renderPipeline.writeFailed", String.valueOf(e.getMessage())),
                    EditorLang.text("tools.renderPipeline"), JOptionPane.ERROR_MESSAGE));
        }
    }

    /** 当前引擎渲染管线（编辑器未建/非混合实现返回 null——切换降级为 no-op） */
    private DefaultHybridRenderPipeline pipeline() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return null;
        }
        return editor.renderPipeline() instanceof DefaultHybridRenderPipeline p ? p : null;
    }
}
