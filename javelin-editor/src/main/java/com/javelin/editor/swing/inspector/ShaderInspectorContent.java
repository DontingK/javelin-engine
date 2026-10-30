package com.javelin.editor.swing.inspector;

import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.PassState;
import com.javelin.core.render.material.Shader;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.swing.inspector.widget.InsFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;

/**
 * 着色器检查器内容件（.shader 键，只读首期 2026-10-25，Unity 诊断台口径）：后台
 * 线程经文件头/meta 解 guid → loadGuid 读 Library 产物，EDT 组装——文件/Shader/
 * GUID 头 + 属性声明表 + Pass 状态表。产物缺失=导入失败诊断态不静默（对应
 * Unity 编译错误区）；线程契约同 MaterialInspectorContent（代际守卫，晚到的
 * 旧结果丢弃）。
 *
 * @author javelin
 * @create 2026/10/25
 */
public class ShaderInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderInspectorContent.class);

    private final JLabel statusLabel =
            new JLabel(EditorLang.text("shader.inspector.empty"), SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件（同文件重复 onSelection 跳过；代际守卫基准） */
    private File currentFile;
    /** 加载代际（选中前进即 +1，晚到的旧结果丢弃） */
    private int generation;

    public ShaderInspectorContent() {
        setLayout(new BorderLayout(0, 0));
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            statusLabel.setForeground(dim);
        }
        add(statusLabel, BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public void onSelection(InspectorSelection selection) {
        File file = selection.file();
        if (file == null || file.equals(currentFile)) {
            return;
        }
        currentFile = file;
        int gen = ++generation;
        showStatus(EditorLang.text("shader.inspector.loading"));
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过着色器检查器加载");
            return;
        }
        AssetManager assetManager = editor.getAssetManager();
        Thread loader = new Thread(() -> load(file, editor, assetManager, gen), "shader-inspector");
        loader.setDaemon(true);
        loader.start();
    }

    /** 后台线程加载：guid 解析（伴生 .meta 单源，2026-10-29 拍板）→ Library 产物（纯 IO） */
    private void load(File file, JavelinEditor editor, AssetManager assetManager, int gen) {
        AssetMeta meta = editor.getMetaManager().getMeta(file);
        String guid = (meta != null) ? meta.getGuid() : null;
        final String resolvedGuid = guid;
        if (resolvedGuid == null || resolvedGuid.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    showStatus(EditorLang.text("shader.inspector.noGuid"));
                }
            });
            return;
        }
        Shader shader = assetManager.loadGuid(resolvedGuid, Shader.class);
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) {
                if (shader == null) {
                    showStatus(EditorLang.text("shader.inspector.missingProduct"));
                } else if (!shader.isValid()) {
                    // 编译失败 stub 产物（字段全空，拍板 2026-10-27）——坏 shader 标记
                    showStatus(EditorLang.text("shader.inspector.broken"));
                } else {
                    build(shader, file, resolvedGuid);
                }
            }
        });
    }

    /** 组装展示（EDT）：头信息 + 属性声明表 + Pass 状态表 */
    private void build(Shader shader, File file, String guid) {
        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(row(EditorLang.text("shader.inspector.file"), file.getName()));
        content.add(row("Shader", String.valueOf(shader.getShaderName())));
        content.add(row("GUID", guid));
        if (!shader.getProperties().isEmpty()) {
            content.add(Box.createVerticalStrut(2));
            content.add(sectionLabel(EditorLang.text("shader.inspector.properties")));
            for (MaterialProperty property : shader.getProperties()) {
                content.add(row(property.getName(), describe(property)));
            }
        }
        if (!shader.getPasses().isEmpty()) {
            content.add(Box.createVerticalStrut(2));
            content.add(sectionLabel("Pass"));
            for (var pass : shader.getPasses()) {
                content.add(row(pass.getName(), passStateSummary(pass.getState())));
            }
        }
        mount(content);
    }

    /** 属性描述串：类型 + 默认值/区间/贴图占位（MaterialBlock 打包同源数据） */
    private static String describe(MaterialProperty property) {
        return switch (property.getType()) {
            case TEXTURE_2D -> EditorLang.text("shader.inspector.textureProp",
                    property.getTexturePlaceholder());
            case RANGE -> "Range [" + InsFormat.formatFloat(property.getMin())
                    + ", " + InsFormat.formatFloat(property.getMax())
                    + "] = " + InsFormat.formatFloat(property.getDefaultValues()[0]);
            case FLOAT -> "Float = " + InsFormat.formatFloat(property.getDefaultValues()[0]);
            default -> property.getType()
                    + (property.getDefaultValues() != null
                    ? " (" + InsFormat.join(property.getDefaultValues()) + ")"
                    : " (" + EditorLang.text("shader.inspector.defaultMissing") + ")");
        };
    }

    /** Pass 状态摘要（PassState 底稿；场景材质前向 PSO 会被 surfaceType 覆盖） */
    private static String passStateSummary(PassState state) {
        if (state == null) {
            return EditorLang.text("shader.inspector.defaultState");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Cull ").append(state.getCull());
        sb.append(" · ZWrite ").append(state.isDepthWrite() ? "On" : "Off");
        sb.append(" · ZTest ").append(state.getDepthTest());
        if (state.isBlendEnabled()) {
            sb.append(" · Blend ").append(state.getSrcColor()).append(' ').append(state.getDstColor());
        } else {
            sb.append(" · Blend Off");
        }
        return sb.toString();
    }

    /** 字段行：label 列 + 值列（label/控件列 4px 间隔例外口径） */
    private static JComponent row(String name, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(new JLabel(name));
        row.add(new JLabel(value));
        return row;
    }

    /** 分组标签 */
    private static JComponent sectionLabel(String text) {
        JLabel label = new JLabel(text);
        Font font = label.getFont().deriveFont(Font.BOLD);
        label.setFont(font);
        return label;
    }

    /** 挂载内容（替换状态文案） */
    private void mount(JComponent content) {
        if (scrollPane == null) {
            remove(statusLabel);
            scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            add(scrollPane, BorderLayout.CENTER);
        } else {
            scrollPane.setViewportView(content);
        }
        revalidate();
        repaint();
    }

    /** 状态文案（加载中/诊断态/空态） */
    private void showStatus(String text) {
        if (scrollPane != null) {
            remove(scrollPane);
            scrollPane = null;
            add(statusLabel, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
        statusLabel.setText(text);
    }
}
