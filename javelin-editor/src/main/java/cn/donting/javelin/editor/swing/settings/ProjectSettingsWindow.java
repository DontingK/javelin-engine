package cn.donting.javelin.editor.swing.settings;

import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.RenderSettings;
import cn.donting.javelin.core.render.pipeline.PostProcessSetup;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.PostProcessSettings;
import cn.donting.javelin.core.settings.SettingManager;
import cn.donting.javelin.core.settings.ShadowQuality;
import cn.donting.javelin.core.settings.ShadowSettings;
import cn.donting.javelin.core.settings.SsaoSettings;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.inspector.widget.ColorField;
import cn.donting.javelin.editor.swing.inspector.widget.InsFormat;
import cn.donting.javelin.editor.swing.inspector.widget.JeNumberInput;
import cn.donting.javelin.editor.swing.inspector.widget.JeSelect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.function.Consumer;

/**
 * 项目设置窗口（对标 Unity {@code Edit → Project Settings → Graphics → HDRP Global Settings}）。
 *
 * <p><b>非模态</b>（可停留、边调边看视口）；左栏分类导航 + 右栏内容（{@code ProjectPanel}
 * 分栏先例），首版只填「图形 | 后处理」一页，骨架预留多分类。改完<b>即时生效</b>（后处理
 * 开关/参数经 {@code PostProcessSetup} 下发、背景色经管线清屏下发），并 load-modify-save
 * 写盘到项目 {@code Game/javelinSettings.settings}（编辑器下次启动读回，见
 * {@code ProjectLoadingFrame.loadEditorSetting}）。</p>
 *
 * @author javelin
 * @create 2026/11/14
 */
public final class ProjectSettingsWindow extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSettingsWindow.class);

    private static final String CARD_GRAPHICS = "graphics";

    /** 当前设置窗（单实例——再点替换旧窗；仅 EDT 访问） */
    private static ProjectSettingsWindow current;

    /**
     * 打开设置窗（EDT 调用）——镜像已有窗口则先关掉重建（保证回显最新值）。
     *
     * @param owner 宿主窗口（可为 null）
     */
    public static void show(Window owner) {
        if (current != null && current.isDisplayable()) {
            current.dispose();
        }
        current = new ProjectSettingsWindow(owner);
        current.setVisible(true);
    }

    private ProjectSettingsWindow(Window owner) {
        super(owner, EditorLang.text("projectSettings.title"), ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildContent();
        setSize(560, 420);
        setMinimumSize(new Dimension(420, 300));
        setLocationRelativeTo(owner);
        // 写盘收口在关窗（改动时已即时生效；避免数字框键入即提交 / 拖动调值逐次落盘）
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                persist();
            }
        });
    }

    // ---------- 界面装配 ----------

    private void buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 2));
        root.setBorder(new EmptyBorder(2, 2, 2, 2));

        JList<String> categories = new JList<>(new DefaultListModel<>());
        ((DefaultListModel<String>) categories.getModel())
                .addElement(EditorLang.text("projectSettings.category.graphics"));
        categories.setSelectedIndex(0);
        JScrollPane navScroll = new JScrollPane(categories);
        navScroll.setPreferredSize(new Dimension(132, 0));
        navScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel cards = new JPanel(new CardLayout());
        cards.add(graphicsPage(), CARD_GRAPHICS);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navScroll, cards);
        split.setDividerSize(2);       // 紧凑分隔条
        split.setDividerLocation(132);
        split.setResizeWeight(0.0);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        setContentPane(root);
    }

    /** 「图形」页：渲染管线（只读/重启） + 背景清屏色 + 后处理（开关 + Bloom/Tonemap 参数）。 */
    private JComponent graphicsPage() {
        JavelinEditor editor = JavelinEditor.getInstance();
        JavelinSetting setting = editor != null ? editor.setting() : new JavelinSetting();
        PostProcessSettings pps = setting.getPostProcessSettings();

        JPanel pane = new JPanel();
        pane.setLayout(new BoxLayout(pane, BoxLayout.PAGE_AXIS));
        pane.setBorder(new EmptyBorder(2, 4, 2, 4));

        // —— 渲染管线 ——
        pane.add(sectionLabel("projectSettings.section.pipeline"));
        pane.add(formRow("projectSettings.pipeline",
                new JLabel(EditorLang.text("projectSettings.pipelineValue"))));
        pane.add(hintLabel(EditorLang.text("projectSettings.pipeline.restartHint")));

        // —— 背景清屏色（即时） ——
        Color bg = setting.getBackgroundColor();
        ColorField backgroundField = new ColorField(
                InsFormat.join(bg.r, bg.g, bg.b, bg.a), this::onBackgroundChanged);
        pane.add(formRow("projectSettings.background", backgroundField));

        // —— 后处理 ——
        pane.add(sectionLabel("projectSettings.section.postProcessing"));

        JCheckBox postProcessCheck = new JCheckBox();
        postProcessCheck.setSelected(isNodeEnabled(editor, PostProcessSetup.TONEMAP));
        postProcessCheck.addActionListener(e -> {
            boolean on = postProcessCheck.isSelected();
            if (!applyToggle(editor, PostProcessSetup.TONEMAP, on)) {
                postProcessCheck.setSelected(!on);
            }
        });
        pane.add(formRow("projectSettings.postProcessing", postProcessCheck));

        JCheckBox bloomCheck = new JCheckBox();
        bloomCheck.setSelected(isNodeEnabled(editor, PostProcessSetup.BLOOM));
        bloomCheck.addActionListener(e -> {
            boolean on = bloomCheck.isSelected();
            if (!applyToggle(editor, PostProcessSetup.BLOOM, on)) {
                bloomCheck.setSelected(!on);
            }
        });
        pane.add(formRow("projectSettings.bloom", bloomCheck));

        JCheckBox ssaoCheck = new JCheckBox();
        ssaoCheck.setSelected(isNodeEnabled(editor, PostProcessSetup.SSAO));
        ssaoCheck.addActionListener(e -> {
            boolean on = ssaoCheck.isSelected();
            if (!applyToggle(editor, PostProcessSetup.SSAO, on)) {
                ssaoCheck.setSelected(!on);
            }
        });
        pane.add(formRow("projectSettings.ssao", ssaoCheck));

        pane.add(formRow("projectSettings.bloomThreshold",
                numberInput(pps.getBloomThreshold(), 0f, 10f, 0.05f,
                        v -> apply(() -> RenderSettings.setBloomThreshold(v)))));
        pane.add(formRow("projectSettings.bloomKnee",
                numberInput(pps.getBloomKnee(), 0f, 2f, 0.05f,
                        v -> apply(() -> RenderSettings.setBloomKnee(v)))));
        pane.add(formRow("projectSettings.bloomIntensity",
                numberInput(pps.getBloomIntensity(), 0f, 5f, 0.05f,
                        v -> apply(() -> RenderSettings.setBloomIntensity(v)))));
        pane.add(formRow("projectSettings.tonemapKnee",
                numberInput(pps.getTonemapKnee(), 0f, 1f, 0.05f,
                        v -> apply(() -> RenderSettings.setTonemapKnee(v)))));

        // —— SSAO 参数（开关见上方后处理组）——
        SsaoSettings ssao = pps.getSsao();
        pane.add(formRow("projectSettings.ssaoRadius",
                numberInput(ssao.getRadius(), 0.01f, 5f, 0.05f,
                        v -> apply(() -> RenderSettings.setSsaoRadius(v)))));
        pane.add(formRow("projectSettings.ssaoBias",
                numberInput(ssao.getBias(), 0f, 0.5f, 0.005f,
                        v -> apply(() -> RenderSettings.setSsaoBias(v)))));
        pane.add(formRow("projectSettings.ssaoIntensity",
                numberInput(ssao.getIntensity(), 0f, 2f, 0.05f,
                        v -> apply(() -> RenderSettings.setSsaoIntensity(v)))));

        // —— 阴影（Engine_Shadow_Quality_Design §3.7；写入经 RenderSettings 门面）——
        pane.add(sectionLabel("projectSettings.section.shadow"));
        ShadowSettings shadow = setting.getShadowSettings();

        JeSelect qualitySelect = new JeSelect(qualityOptions(), shadow.getQuality().name(),
                value -> apply(() -> RenderSettings.setShadowQuality(ShadowQuality.valueOf(value))));
        pane.add(formRow("projectSettings.shadowQuality", qualitySelect));

        pane.add(formRow("projectSettings.shadowStrength",
                numberInput(shadow.getStrength(), 0f, 1f, 0.05f,
                        v -> apply(() -> RenderSettings.setShadowStrength(v)))));
        pane.add(formRow("projectSettings.shadowPcfRadius",
                numberInput(shadow.getPcfRadius(), 0f, 4f, 0.1f,
                        v -> apply(() -> RenderSettings.setShadowPcfRadius(v)))));
        pane.add(formRow("projectSettings.shadowDepthOffset",
                numberInput(shadow.getRefDepthOffset(), 0f, 1f, 0.01f,
                        v -> apply(() -> RenderSettings.setShadowRefDepthOffset(v)))));
        pane.add(formRow("projectSettings.shadowBiasConstant",
                numberInput(shadow.getBiasConstant(), 0f, 10f, 0.1f,
                        v -> apply(() -> RenderSettings.setShadowBiasConstant(v)))));
        pane.add(formRow("projectSettings.shadowBiasSlope",
                numberInput(shadow.getBiasSlope(), 0f, 10f, 0.1f,
                        v -> apply(() -> RenderSettings.setShadowBiasSlope(v)))));
        pane.add(hintLabel(EditorLang.text("projectSettings.shadowBiasHint")));

        // —— CSM 级联（四期阴影质量分级 §4.8）——
        JeSelect cascadeSelect = new JeSelect(cascadeOptions(), String.valueOf(shadow.getCascadeCount()),
                value -> apply(() -> RenderSettings.setShadowCascadeCount(Integer.parseInt(value))));
        pane.add(formRow("projectSettings.shadowCascades", cascadeSelect));
        pane.add(formRow("projectSettings.shadowDistance",
                numberInput(shadow.getShadowDistance(), 5f, 1000f, 5f,
                        v -> apply(() -> RenderSettings.setShadowDistance(v)))));
        pane.add(formRow("projectSettings.shadowCascadeLambda",
                numberInput(shadow.getCascadeLambda(), 0f, 1f, 0.05f,
                        v -> apply(() -> RenderSettings.setShadowCascadeLambda(v)))));
        pane.add(hintLabel(EditorLang.text("projectSettings.shadowCascadeHint")));

        pane.add(javax.swing.Box.createVerticalGlue());
        return pane;
    }

    /** 阴影品质档下拉选项（value = 枚举名，label 走语言包） */
    private static java.util.List<InspectorFieldVo.Option> qualityOptions() {
        java.util.List<InspectorFieldVo.Option> options = new java.util.ArrayList<>();
        addQualityOption(options, ShadowQuality.HARD, "projectSettings.shadowQuality.hard");
        addQualityOption(options, ShadowQuality.SOFT_LOW, "projectSettings.shadowQuality.softLow");
        addQualityOption(options, ShadowQuality.SOFT_HIGH, "projectSettings.shadowQuality.softHigh");
        return options;
    }

    /** CSM 级联数下拉（value = "1"/"2"/"4"，label 走语言包） */
    private static java.util.List<InspectorFieldVo.Option> cascadeOptions() {
        java.util.List<InspectorFieldVo.Option> options = new java.util.ArrayList<>();
        for (int n : new int[]{1, 2, 4}) {
            InspectorFieldVo.Option option = new InspectorFieldVo.Option();
            option.value = String.valueOf(n);
            option.label = EditorLang.text("projectSettings.shadowCascades." + n);
            options.add(option);
        }
        return options;
    }

    private static void addQualityOption(java.util.List<InspectorFieldVo.Option> options,
                                         ShadowQuality quality, String labelKey) {
        InspectorFieldVo.Option option = new InspectorFieldVo.Option();
        option.value = quality.name();
        option.label = EditorLang.text(labelKey);
        options.add(option);
    }

    /** 分组标题（加粗小标题 + 上下留白） */
    private static JLabel sectionLabel(String key) {
        JLabel label = new JLabel(EditorLang.text(key));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(new EmptyBorder(4, 0, 2, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** 说明行（次要色） */
    private static JLabel hintLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        label.setBorder(new EmptyBorder(0, 0, 2, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** 表单行：label 固定列宽 + 控件占余宽（4px 列间隔，行高收紧） */
    private static JPanel formRow(String labelKey, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel label = new JLabel(EditorLang.text(labelKey));
        label.setPreferredSize(new Dimension(120, label.getPreferredSize().height));
        row.add(label, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** 浮点输入（复用检查器叶子件；4 位小数口径 + 硬钳 + 拖动调值） */
    private static JeNumberInput numberInput(float value, float min, float max, float step,
                                             Consumer<Float> onCommit) {
        InspectorFieldVo.Range range = new InspectorFieldVo.Range();
        range.min = min;
        range.max = max;
        range.step = step;
        JeNumberInput input = new JeNumberInput(InsFormat.formatFloat(value), false, range, text -> {
            try {
                onCommit.accept(Float.parseFloat(text));
            } catch (NumberFormatException ignored) {
                // 控件保证可解析（归一化串），保险兜底
            }
        });
        input.setScrubStep(step);
        return input;
    }

    // ---------- 行为（即时生效 + 写盘） ----------

    /** 链上是否已挂某节点（编辑器/管线未就绪 → false） */
    private static boolean isNodeEnabled(JavelinEditor editor, String id) {
        if (editor == null) {
            return false;
        }
        PostProcessSetup setup = editor.postProcessSetup();
        RenderPipeline pipeline = editor.renderPipeline();
        return setup != null && pipeline != null
                && setup.isEnabled(pipeline.postProcess(), id);
    }

    /** 门面调用包错误显性化（红线 15：异常弹框，不静默吞） */
    private void apply(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOGGER.error("渲染设置应用失败", e);
            showError(EditorLang.text("projectSettings.writeFailed", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 后处理/Bloom/SSAO 开关：经 {@link RenderSettings} 门面即时切链（内部同步 setting + 下发
     * 组合根）。失败弹框 → 由调用方回滚勾选。
     */
    private boolean applyToggle(JavelinEditor editor, String id, boolean on) {        if (editor == null) {
            showError(EditorLang.text("projectSettings.editorMissing"));
            return false;
        }
        try {
            if (PostProcessSetup.TONEMAP.equals(id)) {
                RenderSettings.setPostProcessingEnabled(on);
            } else if (PostProcessSetup.BLOOM.equals(id)) {
                RenderSettings.setBloomEnabled(on);
            } else if (PostProcessSetup.SSAO.equals(id)) {
                RenderSettings.setSsaoEnabled(on);
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("项目设置开关应用失败: id={} on={}", id, on, e);
            showError(EditorLang.text("projectSettings.writeFailed", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /** 背景清屏色：经 {@link RenderSettings} 门面（内部同步 setting + 下发管线/后处理两处） */
    private void onBackgroundChanged(String wire) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            showError(EditorLang.text("projectSettings.editorMissing"));
            return;
        }
        float[] parts = InsFormat.split(wire, 4);
        if (parts == null) {
            return;
        }
        try {
            RenderSettings.setBackgroundColor(new Color(parts[0], parts[1], parts[2], parts[3]));
        } catch (RuntimeException e) {
            LOGGER.error("背景清屏色应用失败", e);
            showError(EditorLang.text("projectSettings.writeFailed", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 写盘（本页管理字段 load-modify-save 进项目 {@code Game/javelinSettings.settings}）：
     * 只覆写图形/后处理字段，文件里的非本页字段（标题/尺寸/vsync…）原样保留。
     */
    private void persist() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        try {
            JavelinSetting live = editor.setting();
            File file = new File(editor.getProject().getGameDir(), SettingManager.JAVELIN_SETTINGS_FILE);
            JavelinSetting fileSetting = file.exists()
                    ? new BinaryImporter(file).importData(JavelinSetting.class)
                    : new JavelinSetting();
            fileSetting.setBackgroundColor(live.getBackgroundColor());
            // 后处理设置（三效果开关 + 参数）整体覆写（开关 2026-11-14 已收进 PostProcessSettings）
            fileSetting.setPostProcessSettings(live.getPostProcessSettings());
            fileSetting.setShadowSettings(live.getShadowSettings());
            new BinaryExporter(file).export(fileSetting);
        } catch (Exception e) {
            LOGGER.error("项目设置写盘失败", e);
            showError(EditorLang.text("projectSettings.writeFailed", String.valueOf(e.getMessage())));
        }
    }

    /** 错误显性化（红线 15）：弹错误框，不静默消化异常态 */
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message,
                EditorLang.text("projectSettings.title"), JOptionPane.ERROR_MESSAGE);
    }
}
