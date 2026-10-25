package com.javelin.editor.swing.inspector;

import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.texture.Image;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.serializable.TextImporter;
import com.javelin.editor.swing.SvgIcon;
import com.javelin.editor.swing.inspector.widget.AssetInput;
import com.javelin.editor.swing.inspector.widget.CollapsiblePanel;
import com.javelin.editor.swing.inspector.widget.JeSelect;
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
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 纹理检查器内容件（.texture 键）：TextImporter 后台线程读 .texture 文本源
 * （TextureImporter 同款解析链）、EDT 组装。「纹理」单分组（固定不可折叠：
 * 文件/图/GUID/过滤模式/环绕模式——采样两下拉不值独立分组，合组拍板
 * 2026-10-25）。**可编辑**：图行
 * AssetInput 换绑（None 清空合法，即时写回重导）；采样两下拉提交=写内存实例
 * + 防抖持久化（.mat 同款合并阀，换选/摘除前 flushPending 兜底）。
 *
 * <p>编辑载体 = 文本解析实例（与 .mat v0.11 缓存共享实例分歧，拍板 2026-10-25）：
 * Texture2D 冻结语义（上传后 setImage/setFilterMode/setWrapMode 抛）使共享
 * 已上传实例不可编辑；渲染侧 materialSet 按材质缓存烤死贴图绑定与 sampler，
 * 写实例也非即时联动——即时联动统一归资产热重载批次（场景实例指针换绑）。</p>
 *
 * <p>线程契约：onSelection（EDT）起后台加载线程，代际守卫（点击前进即 +1，
 * 晚到的旧结果丢弃）；解析失败显示错误态不静默。</p>
 *
 * @author javelin
 * @create 2026/10/25
 */
public class TextureInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureInspectorContent.class);

    private static final String EMPTY_TEXT = "未选中纹理";
    private static final String LOADING_TEXT = "加载中…";
    /** 采样编辑持久化防抖间隔（毫秒——下拉提交的写盘合并阀） */
    private static final int FLUSH_DELAY_MS = 500;

    private final JLabel statusLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件（同文件重复 onSelection 跳过；代际守卫基准） */
    private File currentFile;
    /** 加载代际（选中前进即 +1，晚到的旧结果丢弃） */
    private int generation;
    /** 当前编辑载体（文本解析私有实例，见类注释） */
    private Texture2D texture;
    /** 图行输入（空串拒绝回滚场景用） */
    private AssetInput imageInput;
    /** 采样编辑持久化防抖定时器（单发 restart 合并；懒建） */
    private Timer flushTimer;

    public TextureInspectorContent() {
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
        flushPending();
        reload(file);
    }

    @Override
    public void onDetach() {
        flushPending();
    }

    /** 加载/重载入口：代际前进 + 加载态 + 后台线程读源（EDT 调用） */
    private void reload(File file) {
        currentFile = file;
        int gen = ++generation;
        showStatus(LOADING_TEXT);
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过纹理检查器加载");
            return;
        }
        AssetManager assetManager = editor.getAssetManager();
        Thread loader = new Thread(() -> load(file, assetManager, gen), "texture-inspector");
        loader.setDaemon(true);
        loader.start();
    }

    /** 后台线程加载：.texture 文本源解析（image=@guid 引用解靠 assetManager） */
    private void load(File file, AssetManager assetManager, int gen) {
        Texture2D texture;
        try {
            texture = new TextImporter(file, assetManager).importData(Texture2D.class);
        } catch (Exception e) {
            LOGGER.warn("纹理文件解析失败: {}", file.getName(), e);
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    showStatus("纹理解析失败: " + e.getMessage());
                }
            });
            return;
        }
        Texture2D loaded = texture;
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) {
                build(loaded, file);
            }
        });
    }

    /** 组装展示（EDT）：纹理信息（固定分组）+ 采样（可折叠分组） */
    private void build(Texture2D texture, File file) {
        this.texture = texture;
        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(infoSection(texture, file));
        mount(content);
    }

    /** 纹理分组（固定不可折叠——Unity 纹理头信息口径）：文件（暗）/ 图 / GUID（暗）/ 采样两下拉 */
    private JComponent infoSection(Texture2D texture, File file) {
        FieldGrid grid = new FieldGrid();
        grid.addDim("文件", file.getName());
        grid.add("图", imageInput());
        if (texture.getGuid() != null && !texture.getGuid().isBlank()) {
            grid.addDim("GUID", texture.getGuid());
        }
        grid.add("过滤模式", filterSelect(texture));
        grid.add("环绕模式", wrapSelect(texture));
        CollapsiblePanel section = new CollapsiblePanel("纹理", null, titleIcon("texture"), grid);
        section.setCollapsible(false);
        return section;
    }

    /** 过滤模式下拉（渲染侧建 Sampler 消费，Engine_Material_Design §4.2） */
    private JComponent filterSelect(Texture2D texture) {
        List<InspectorFieldVo.Option> filters = new ArrayList<>(3);
        filters.add(enumOption(Texture2D.FilterMode.POINT.name(), "点采样"));
        filters.add(enumOption(Texture2D.FilterMode.BILINEAR.name(), "双线性"));
        filters.add(enumOption(Texture2D.FilterMode.TRILINEAR.name(), "三线性"));
        return new JeSelect(filters, texture.getFilterMode().name(), this::applyFilter);
    }

    /** 环绕模式下拉 */
    private JComponent wrapSelect(Texture2D texture) {
        List<InspectorFieldVo.Option> wraps = new ArrayList<>(2);
        wraps.add(enumOption(Texture2D.WrapMode.REPEAT.name(), "重复"));
        wraps.add(enumOption(Texture2D.WrapMode.CLAMP.name(), "钳定"));
        return new JeSelect(wraps, texture.getWrapMode().name(), this::applyWrap);
    }

    /** 图行输入（拖放/选择窗换绑；当前值=Image guid，None 合法） */
    private JComponent imageInput() {
        AssetInput input = new AssetInput(Image.class, this::onImageCommit);
        Image image = texture != null ? texture.getImage() : null;
        input.setValue(image != null ? image.getGuid() : null);
        this.imageInput = input;
        return input;
    }

    /**
     * 图换绑提交（EDT，即时持久化——单发动作不走防抖，.mat Shader 行同款）：
     * None 清空合法（纹理壳走渲染占位）；guid 经 loadGuid 解实例校验存在；
     * 空串=内存创建语义对纹理无意义拒绝回滚。写编辑载体（文本私有实例未上传
     * 不触冻结）→ TextExporter 即时写回 → submitImport 重导；场景实例指针随
     * 热重载批次换。
     */
    private void onImageCommit(String newGuid) {
        Texture2D texture = this.texture;
        File file = this.currentFile;
        if (texture == null || file == null) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        Image oldImage = texture.getImage();
        String oldGuid = oldImage != null ? oldImage.getGuid() : null;

        if (newGuid != null && newGuid.isBlank()) {
            LOGGER.warn("纹理不支持内存创建语义，拒绝空串提交");
            imageInput.setValue(oldGuid);
            return;
        }
        Image image = null;
        if (newGuid != null) {
            if (editor == null) {
                imageInput.setValue(oldGuid);
                return;
            }
            image = editor.getAssetManager().loadGuid(newGuid, Image.class);
            if (image == null) {
                LOGGER.warn("Image 加载失败（Library 缺产物？），拒绝换绑: guid={}", newGuid);
                imageInput.setValue(oldGuid);
                return;
            }
        }
        // 防抖尾改先落盘：换绑从盘面最新态出发
        flushPending();
        try {
            texture.setImage(image);
            new TextExporter(file).export(texture);
        } catch (Exception e) {
            // 写回失败=盘面即权威，内存态作废直接从盘面重载
            LOGGER.error("纹理写回失败，从盘面重载: {}", file, e);
            reload(file);
            return;
        }
        editor.getAssetImportPipeline().submitImport(file);
    }

    /** 过滤模式提交（EDT）：写内存实例 + 防抖持久化 */
    private void applyFilter(String wire) {
        Texture2D texture = this.texture;
        if (texture == null || wire == null) {
            return;
        }
        try {
            texture.setFilterMode(Texture2D.FilterMode.valueOf(wire));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("过滤模式值非法: {}", wire);
            return;
        }
        scheduleFlush();
    }

    /** 环绕模式提交（EDT）：写内存实例 + 防抖持久化 */
    private void applyWrap(String wire) {
        Texture2D texture = this.texture;
        if (texture == null || wire == null) {
            return;
        }
        try {
            texture.setWrapMode(Texture2D.WrapMode.valueOf(wire));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("环绕模式值非法: {}", wire);
            return;
        }
        scheduleFlush();
    }

    // ------------------------------------------------------------------ 持久化

    /** 防抖触发（下拉提交的合并；单发定时器 restart 滚动续期） */
    private void scheduleFlush() {
        if (flushTimer == null) {
            flushTimer = new Timer(FLUSH_DELAY_MS, e -> flushNow());
            flushTimer.setRepeats(false);
        }
        flushTimer.restart();
    }

    /** 挂起写盘落定（换选/摘除前调用——防丢尾改） */
    private void flushPending() {
        if (flushTimer != null && flushTimer.isRunning()) {
            flushTimer.stop();
            flushNow();
        }
    }

    /** 写盘 + 重导（EDT 纯 IO 小体量先例；失败=盘面即权威从盘面重载） */
    private void flushNow() {
        Texture2D texture = this.texture;
        File file = this.currentFile;
        if (texture == null || file == null) {
            return;
        }
        try {
            new TextExporter(file).export(texture);
        } catch (Exception e) {
            LOGGER.error("纹理写回失败，从盘面重载: {}", file, e);
            reload(file);
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor != null) {
            editor.getAssetImportPipeline().submitImport(file);
        }
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

    /** 枚举选项 VO（value=回写值、label=显示名） */
    private static InspectorFieldVo.Option enumOption(String value, String label) {
        InspectorFieldVo.Option option = new InspectorFieldVo.Option();
        option.value = value;
        option.label = label;
        return option;
    }

    /**
     * 两列字段行网格（label 列左对齐、列宽自适应组内最长；控件列填满——材质
     * 检查器同口径；行距 2px、label/控件列 4px 间隔例外口径）。
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
