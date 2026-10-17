package com.javelin.editor.window;

import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import com.javelin.core.settings.GameSettings;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.settings.SettingManager;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.console.ConsoleOut;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.project.Project;
import com.javelin.editor.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * 打包对话框（Swing 版）。
 *
 * <p>去 Web 化迁移（2026-10-17，Editor_Swing_Design §9 M6 项提前）：替换 CEF pack.html
 * ——构建形态/启动场景/构建模式/窗口元信息表单 + 实时构建日志区，功能面对齐。构建执行
 * 由 /build/pack 桥改为 Java 直调（Project.jarBuild / aotBuild 构建形态分流、表单参数
 * 先持久化进 Game/ 设置——原 BuildMethod 逻辑随消费方消亡迁入本类，§7.1 桥退役原则）；
 * 日志流由 build.out.* 桥推送改为 ConsoleOut 回调 EDT 追加、自动滚底。</p>
 *
 * <p>线程口径：构建在后台线程执行（分钟级），日志行经 invokeLater 追加；构建中
 * 「开始打包」「取消」置灰（构建不可中断），X 关窗不拦（对齐 CEF 版 DISPOSE_ON_CLOSE
 * ——后台构建照常跑完，日志追加到已关窗无副作用）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class PackDialog extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(PackDialog.class);

    private static final String[] BUILD_FORM_OPTIONS = {"JAR", "AOT"};
    private static final String[] BUILD_MODE_OPTIONS = {"Debug", "Release"};

    private final JComboBox<String> buildFormCombo = new JComboBox<>(BUILD_FORM_OPTIONS);
    private final JComboBox<String> sceneCombo = new JComboBox<>();
    private final JComboBox<String> buildModeCombo = new JComboBox<>(BUILD_MODE_OPTIONS);
    private final JTextField titleField = new JTextField();
    private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(1280, 1, Integer.MAX_VALUE, 1));
    private final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(720, 1, Integer.MAX_VALUE, 1));
    private final JButton cancelButton = new JButton("取消");
    private final JButton packButton = new JButton("开始打包");
    private final JLabel statusLabel = new JLabel();
    private final JTextArea logArea = new JTextArea();
    /** 日志区（状态行 + 滚动日志）：构建触发前隐藏，出现即占余下空间（web 版同形态） */
    private final JPanel logPanel = new JPanel(new BorderLayout(0, 2));
    /** 构建模式行（仅 AOT 形态可见，JAR 形态隐藏——web 版同口径） */
    private JPanel buildModeRow;

    /** 构建执行中（按钮置灰/状态行口径） */
    private boolean packing;

    public PackDialog() {
        super(MainFrame.getInstance(), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setTitle("打包");
        // 日志区需要纵向空间（构建时占余下空间）
        setSize(560, 480);
        setLocationRelativeTo(getOwner());
        buildContent();
        loadScenes();
    }

    // ---------- 界面装配 ----------

    private void buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 2));
        root.setBorder(new EmptyBorder(2, 2, 2, 2));
        root.add(buildFormPane(), BorderLayout.NORTH);
        buildLogPanel();
        logPanel.setVisible(false);
        root.add(logPanel, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(root, BorderLayout.CENTER);
    }

    /** 表单区：构建形态/启动场景/构建模式 + 窗口元信息（行 = label 列 4px 间隔两栏） */
    private JComponent buildFormPane() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.PAGE_AXIS));
        form.add(formRow("构建形态", buildFormCombo));
        form.add(Box.createVerticalStrut(2));
        form.add(formRow("启动场景", sceneCombo));
        form.add(Box.createVerticalStrut(2));
        buildModeRow = formRow("构建模式", buildModeCombo);
        buildModeRow.setVisible(false);
        form.add(buildModeRow);
        form.add(Box.createVerticalStrut(2));
        JLabel sectionTitle = new JLabel("窗口元信息");
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD));
        form.add(sectionTitle);
        form.add(Box.createVerticalStrut(2));
        form.add(formRow("窗口标题", titleField));
        form.add(Box.createVerticalStrut(2));
        form.add(formRow("宽度", widthSpinner));
        form.add(Box.createVerticalStrut(2));
        form.add(formRow("高度", heightSpinner));
        buildFormCombo.addActionListener(e -> {
            boolean aot = "aot".equals(buildFormValue());
            buildModeRow.setVisible(aot);
            form.revalidate();
            form.repaint();
        });
        return form;
    }

    /** 表单行：label 固定列宽 + 控件占余宽（label-控件 4px 例外间隔，行距 2px） */
    private JPanel formRow(String label, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel labelComp = new JLabel(label);
        labelComp.setPreferredSize(new Dimension(60, labelComp.getPreferredSize().height));
        row.add(labelComp, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        // 行高钳制须在子件入列后取——先取是空面板高度 0，BoxLayout 会把整行压没
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private void buildLogPanel() {
        statusLabel.setBorder(new EmptyBorder(2, 2, 0, 2));
        logPanel.add(statusLabel, BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        cancelButton.addActionListener(e -> dispose());
        packButton.addActionListener(e -> onPack());
        footer.add(cancelButton);
        footer.add(packButton);
        return footer;
    }

    /** 启动场景下拉填充：资产索引 .scene 枚举，默认选中项目默认场景，不在列表则取第一项 */
    private void loadScenes() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        List<String> scenes = editor.getAssetManager().listAssetPaths(".scene");
        String defaultScene = editor.getSettingsManager().getProjectSettings().getDefaultOpenScene();
        for (String scene : scenes) {
            sceneCombo.addItem(scene);
        }
        if (defaultScene != null && scenes.contains(defaultScene)) {
            sceneCombo.setSelectedItem(defaultScene);
        } else if (!scenes.isEmpty()) {
            sceneCombo.setSelectedIndex(0);
        }
    }

    // ---------- 构建执行 ----------

    private String buildFormValue() {
        return buildFormCombo.getSelectedIndex() == 1 ? "aot" : "jar";
    }

    private String buildModeValue() {
        return buildModeCombo.getSelectedIndex() == 1 ? "release" : "debug";
    }

    /**
     * 开始打包：后台线程驱动构建链（构建形态分流 jar/aot），完成/失败回 EDT 收状态。
     */
    private void onPack() {
        if (packing) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            JOptionPane.showMessageDialog(this, "编辑器未启动，无法打包", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        packing = true;
        packButton.setEnabled(false);
        cancelButton.setEnabled(false);
        statusLabel.setText("构建中（Gradle 构建链，可能需要数分钟）…");
        logArea.setText("");
        logPanel.setVisible(true);
        getContentPane().revalidate();

        String buildForm = buildFormValue();
        String buildMode = buildModeValue();
        String windowTitle = titleField.getText();
        int windowWidth = (Integer) widthSpinner.getValue();
        int windowHeight = (Integer) heightSpinner.getValue();
        Object sceneItem = sceneCombo.getSelectedItem();
        String startupScene = sceneItem == null ? "" : sceneItem.toString();
        ConsoleOut consoleOut = new ConsoleOut() {
            @Override
            public void info(String txt) {
                appendLog(txt);
            }

            @Override
            public void error(String txt) {
                appendLog(txt);
            }

            @Override
            public void warn(String txt) {
                appendLog(txt);
            }
        };
        ThreadUtil.run("Pack Build", () -> {
            String errorMessage = null;
            try {
                appendLog("开始打包......");
                applyPackSettings(editor, windowTitle, windowWidth, windowHeight, startupScene);
                if ("aot".equals(buildForm)) {
                    editor.getProject().aotBuild(consoleOut, buildMode);
                } else {
                    editor.getProject().jarBuild(consoleOut);
                }
            } catch (Exception ex) {
                log.error("打包失败: {}", ex.getMessage(), ex);
                errorMessage = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                appendLog(errorMessage);
            }
            String failure = errorMessage;
            SwingUtilities.invokeLater(() -> {
                packing = false;
                packButton.setEnabled(true);
                cancelButton.setEnabled(true);
                statusLabel.setText(failure == null ? "打包完成" : "打包失败（详情见日志）");
                if (failure != null) {
                    JOptionPane.showMessageDialog(PackDialog.this, "打包失败: " + failure,
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
            });
        });
    }

    /** 日志行追加（构建线程回调 EDT hop）+ 自动滚底 */
    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.append("\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 表单参数持久化进项目 Game/ 设置（load-改-save；空值/非法值跳过不覆写）。
     *
     * <p>写入结果行经日志区反馈；产物按「Game/ 原封复制」取到新值。</p>
     */
    private void applyPackSettings(JavelinEditor editor, String windowTitle, int windowWidth,
                                   int windowHeight, String startupScene) {
        Project project = editor.getProject();
        try {
            File settingFile = new File(project.getGameDir(), SettingManager.JAVELIN_SETTINGS_FILE);
            JavelinSetting setting = settingFile.exists()
                    ? new BinaryImporter(settingFile).importData(JavelinSetting.class)
                    : new JavelinSetting();
            boolean changed = false;
            if (windowTitle != null && !windowTitle.isBlank()) {
                setting.setTitle(windowTitle);
                changed = true;
            }
            if (windowWidth > 0) {
                setting.setWidth(windowWidth);
                changed = true;
            }
            if (windowHeight > 0) {
                setting.setHeight(windowHeight);
                changed = true;
            }
            if (changed) {
                new BinaryExporter(settingFile).export(setting);
                appendLog("窗口设置已写入 Game/" + SettingManager.JAVELIN_SETTINGS_FILE
                        + "（标题=" + setting.getTitle() + " 宽=" + setting.getWidth()
                        + " 高=" + setting.getHeight() + "）");
            }
        } catch (Exception e) {
            appendLog("窗口设置写入失败: " + e.getMessage());
        }
        if (startupScene == null || startupScene.isBlank()) {
            return;
        }
        try {
            File sceneFile = new File(project.getAssetsDir(), startupScene);
            AssetMeta meta = editor.getMetaManager().getMeta(sceneFile);
            if (meta == null || meta.getGuid() == null) {
                appendLog("启动场景无元数据（未导入？），跳过写入: " + startupScene);
                return;
            }
            File gameFile = new File(project.getGameDir(), SettingManager.GAME_SETTINGS_FILE);
            GameSettings gameSettings = gameFile.exists()
                    ? new BinaryImporter(gameFile).importData(GameSettings.class)
                    : new GameSettings();
            gameSettings.setDefaultSceneGuid(meta.getGuid());
            new BinaryExporter(gameFile).export(gameSettings);
            appendLog("启动场景已写入 Game/" + SettingManager.GAME_SETTINGS_FILE
                    + "（" + startupScene + " → guid=" + meta.getGuid() + "）");
        } catch (Exception e) {
            appendLog("启动场景写入失败: " + e.getMessage());
        }
    }
}
