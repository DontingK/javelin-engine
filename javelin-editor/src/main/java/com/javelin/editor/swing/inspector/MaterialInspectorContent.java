package com.javelin.editor.swing.inspector;

import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.MaterialTextureEntry;
import com.javelin.core.render.material.MaterialValueEntry;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.serializable.TextImporter;
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
 * 材质检查器内容件（.mat 键，只读首期 2026-10-21）：TextImporter 后台线程读
 * .mat 文本源（MaterialImporter 同款解析链）、EDT 组装——文件/Shader/面型/GUID
 * 头 + 参数与贴图槽条目列表。可编辑通道（shader 驱动属性表 + 资产脏标 +
 * file.save 写回）后置独立批次拍板。
 *
 * <p>线程契约：onSelection（EDT）起后台加载线程，代际守卫（点击前进即 +1，
 * 晚到的旧结果丢弃）；解析失败显示错误态不静默。</p>
 *
 * @author javelin
 * @create 2026/10/21
 */
public class MaterialInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialInspectorContent.class);

    private static final String EMPTY_TEXT = "未选中材质";
    private static final String LOADING_TEXT = "加载中…";
    /** 未赋贴图槽显示（渲染侧按占位贴图约定解析） */
    private static final String PLACEHOLDER_TEXT = "无（占位贴图）";

    private final JLabel statusLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件（同文件重复 onSelection 跳过；代际守卫基准） */
    private File currentFile;
    /** 加载代际（选中前进即 +1，晚到的旧结果丢弃） */
    private int generation;

    public MaterialInspectorContent() {
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
        showStatus(LOADING_TEXT);
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过材质检查器加载");
            return;
        }
        AssetManager assetManager = editor.getAssetManager();
        Thread loader = new Thread(() -> load(file, assetManager, gen), "material-inspector");
        loader.setDaemon(true);
        loader.start();
    }

    /** 后台线程加载：.mat 文本源 → Material（纯 IO + guid 解引用，无引擎状态） */
    private void load(File file, AssetManager assetManager, int gen) {
        Material material;
        try {
            material = new TextImporter(file, assetManager).importData(Material.class);
        } catch (Exception e) {
            LOGGER.warn("材质文件解析失败: {}", file.getName(), e);
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    showStatus("材质解析失败: " + e.getMessage());
                }
            });
            return;
        }
        Material loaded = material;
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) {
                build(loaded, file);
            }
        });
    }

    /** 组装展示（EDT）：头信息 + 参数/贴图槽条目 */
    private void build(Material material, File file) {
        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(row("文件", file.getName()));
        content.add(row("Shader", material.getShader() != null
                ? material.getShader().getShaderName() : "缺失"));
        content.add(row("面型", String.valueOf(material.getSurfaceType())));
        content.add(row("GUID", String.valueOf(material.getGuid())));
        if (!material.getValues().isEmpty()) {
            content.add(Box.createVerticalStrut(2));
            content.add(sectionLabel("参数"));
            for (MaterialValueEntry entry : material.getValues()) {
                content.add(row(entry.getName(), InsFormat.join(entry.getData())));
            }
        }
        if (!material.getTextures().isEmpty()) {
            content.add(Box.createVerticalStrut(2));
            content.add(sectionLabel("贴图槽"));
            for (MaterialTextureEntry entry : material.getTextures()) {
                content.add(row(entry.getName(),
                        entry.getTexture() != null ? "已设置" : PLACEHOLDER_TEXT));
            }
        }
        mount(content);
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

    /** 状态文案（加载中/解析失败/空态） */
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
