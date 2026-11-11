package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.render.texture.Image;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.SvgIcon;
import cn.donting.javelin.editor.swing.inspector.widget.CollapsiblePanel;
import cn.donting.javelin.editor.swing.inspector.widget.ImagePreview;
import cn.donting.javelin.editor.swing.inspector.widget.InsFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 图片检查器内容件（.png/.jpg/.jpeg 键，只读，Unity 图片资产口径）：后台线程经
 * 伴生 .meta 取 guid → {@code loadGuid} 读 Library 产物实例，EDT 组装——预览缩略图 +
 * 文件 / 尺寸 / 格式 / GUID / 文件大小。**图片检查器只展示、不编辑**（.png/.jpg 是
 * 导入源二进制，派生设置归 Texture2D 引用层）。产物缺失=导入失败诊断态不静默；
 * 线程契约同其它检查器（代际守卫，晚到的旧结果丢弃）。
 *
 * @author javelin
 * @create 2026/11/11
 */
public class ImageInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageInspectorContent.class);

    private static final String EMPTY_TEXT = EditorLang.text("image.inspector.empty");
    private static final String LOADING_TEXT = EditorLang.text("image.inspector.loading");
    /** 预览缩略图上限边长（px；等比缩放进此方框，保持宽高比） */
    private static final int PREVIEW_MAX = 128;

    private final JLabel statusLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件（同文件重复 onSelection 跳过；代际守卫基准） */
    private File currentFile;
    /** 加载代际（选中前进即 +1，晚到的旧结果丢弃） */
    private int generation;
    /** 当前已装载实例（同文件跳过判据；诊断态为 null 放行重试） */
    private Image image;

    public ImageInspectorContent() {
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
        if (file == null) {
            return;
        }
        // 同文件重选：已装载则跳过；停在诊断态时放行重试
        if (file.equals(currentFile) && image != null) {
            return;
        }
        reload(file);
    }

    /** 加载/重载入口：代际前进 + 加载态 + 后台线程取产物（EDT 调用） */
    private void reload(File file) {
        currentFile = file;
        image = null;
        int gen = ++generation;
        showStatus(LOADING_TEXT);
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过图片检查器加载");
            return;
        }
        Thread loader = new Thread(() -> load(file, editor, gen), "image-inspector");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 后台线程加载：伴生 .meta 取 guid（2026-10-29 拍板：guid 真值=伴生 meta）→
     * {@code loadGuid} 取缓存/Library 产物实例（红线 17：不解析源文件）；产物不可得
     * 显示诊断态，重导后重选本文件重试。
     */
    private void load(File file, JavelinEditor editor, int gen) {
        AssetMeta meta = editor.getMetaManager().getMeta(file);
        String guid = meta != null ? meta.getGuid() : null;
        if (guid == null || guid.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    showStatus(EditorLang.text("image.inspector.noGuid"));
                }
            });
            return;
        }
        Image loaded = editor.getAssetManager().loadGuid(guid, Image.class);
        if (loaded == null) {
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    showStatus(EditorLang.text("image.inspector.missingProduct"));
                }
            });
            return;
        }
        // 缩略图在后台线程算（大图解码/缩放不进 EDT）
        BufferedImage preview = ImagePreview.thumbnail(loaded, PREVIEW_MAX);
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) {
                build(loaded, file, guid, preview);
            }
        });
    }

    /** 组装展示（EDT）：单分组（固定不可折叠）——预览 + 文件 / 尺寸 / 格式 / GUID / 文件大小 */
    private void build(Image image, File file, String guid, BufferedImage preview) {
        this.image = image;
        FieldGrid grid = new FieldGrid();
        if (preview != null) {
            grid.add(EditorLang.text("image.inspector.preview"),
                    ImagePreview.viewable(preview, () -> ImagePreview.full(image), file.getName()));
        }
        grid.addDim(EditorLang.text("image.inspector.file"), file.getName());
        grid.addValue(EditorLang.text("image.inspector.size"),
                image.getWidth() + " × " + image.getHeight());
        grid.addValue(EditorLang.text("image.inspector.format"), String.valueOf(image.getFormat()));
        if (guid != null && !guid.isBlank()) {
            grid.addDim("GUID", guid);
        }
        grid.addValue(EditorLang.text("image.inspector.fileSize"), InsFormat.humanSize(file.length()));

        CollapsiblePanel section = new CollapsiblePanel(
                EditorLang.text("image.inspector.section"), null, titleIcon("image"), grid);
        section.setCollapsible(false);

        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(section);
        mount(content);
    }

    // ------------------------------------------------------------------ 布局件

    /** 分组头图标（16px，缺资源不占位——菜单域同口径） */
    private static Icon titleIcon(String name) {
        if (name == null) {
            return null;
        }
        SvgIcon icon = SvgIcon.of(name, 16);
        return icon.isAvailable() ? icon : null;
    }

    /**
     * 两列字段行网格（TextureInspectorContent.FieldGrid 同口径——label 列左对齐、列宽
     * 自适应组内最长；控件列填满；行距 2px、label/控件列 4px 间隔例外口径）。
     */
    private static final class FieldGrid extends JPanel {

        private int row;

        private FieldGrid() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }

        /** 常规行：label + 控件 */
        private void add(String label, JComponent control) {
            place(new JLabel(label), control, false);
        }

        /** 值行：label + 文本值（常规色） */
        private void addValue(String label, String value) {
            place(new JLabel(label), new JLabel(value), false);
        }

        /** 暗色行（诊断/占位：label 与值整体暗色） */
        private void addDim(String label, String value) {
            place(new JLabel(label), new JLabel(value), true);
        }

        private void place(JLabel label, JComponent control, boolean dim) {
            if (dim) {
                Color dimColor = UIManager.getColor("Label.disabledForeground");
                if (dimColor != null) {
                    label.setForeground(dimColor);
                    control.setForeground(dimColor);
                }
            }
            GridBagConstraints labelGbc = new GridBagConstraints();
            labelGbc.gridx = 0;
            labelGbc.gridy = row;
            labelGbc.anchor = GridBagConstraints.WEST;
            labelGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 4);
            add(label, labelGbc);

            GridBagConstraints controlGbc = new GridBagConstraints();
            controlGbc.gridx = 1;
            controlGbc.gridy = row;
            controlGbc.fill = GridBagConstraints.HORIZONTAL;
            controlGbc.weightx = 1;
            controlGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 0);
            add(control, controlGbc);
            row++;
        }
    }

    // ------------------------------------------------------------------ 壳件

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