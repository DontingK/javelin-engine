package com.javelin.editor.swing.inspector.widget;

import com.javelin.core.assets.Asset;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.GameScene;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.file.FileType;
import com.javelin.editor.file.FileTypeRegistry;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.meta.GuidHeaderManager;
import com.javelin.editor.swing.SvgIcon;
import com.javelin.editor.swing.inspector.FieldEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import java.awt.Insets;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 资产通用输入（Unity ObjectField 口径，.mat 联动第一项）：构造传入资产类型
 * 声明接受范围，值 = guid wire 串（null=None 清空合法 / 空串=内存创建 / 非空=guid，
 * GUID_ASSET 三态同构）。输入三通道——右侧按钮弹 {@link AssetPickerDialog} 选择窗、
 * 从项目面板右区拖文件入内（按扩展名过滤）、外部经 {@link #setValue} 刷新；提交走
 * 构造回调（null=None）。GUID_ASSET 通道与 .mat 检查器双宿主——只读前身
 * GuidAssetField 已随本件退役。
 *
 * @author javelin
 * @create 2026/10/25
 */
public class AssetInput extends JPanel implements FieldEditor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssetInput.class);

    private static final int ICON_SIZE = 16;

    /** 资产类型 → 扩展名（listAssetPaths 枚举与拖放过滤同源；资产管线落地新类型时补行） */
    private static final Map<Class<? extends Asset>, String> TYPE_SUFFIX = Map.of(
            Shader.class, ".shader",
            Mesh.class, ".smesh",
            Material.class, ".mat",
            GameScene.class, ".scene");

    /** 资产类型 → 选择窗标题（面板文案硬编码中文先例） */
    private static final Map<Class<? extends Asset>, String> TYPE_LABEL = Map.of(
            Shader.class, "选择着色器",
            Mesh.class, "选择网格",
            Material.class, "选择材质",
            GameScene.class, "选择场景");

    private final String suffix;
    private final String dialogTitle;
    private final Consumer<String> onCommit;
    /** 类型图标（同类型恒一，懒建） */
    private SvgIcon typeIcon;

    private final JLabel displayLabel = new JLabel();
    private final JButton pickButton = new JButton("…");

    /** 当前 guid wire 值（null=None / 空串=内存创建 / 非空=guid） */
    private String guid;

    public AssetInput(Class<? extends Asset> assetType, Consumer<String> onCommit) {
        super(new BorderLayout(1, 0));
        String resolvedSuffix = TYPE_SUFFIX.get(assetType);
        if (resolvedSuffix == null) {
            throw new IllegalArgumentException("未登记的资产类型: " + assetType.getName()
                    + "（TYPE_SUFFIX 补行后可用）");
        }
        this.suffix = resolvedSuffix;
        this.dialogTitle = TYPE_LABEL.get(assetType);
        this.onCommit = onCommit;

        // 显示区（类输入框外观：LAF border/底色单点取 UIManager，不硬编码色值）
        displayLabel.setBorder(BorderFactory.createCompoundBorder(
                UIManager.getBorder("TextField.border"),
                BorderFactory.createEmptyBorder(1, 2, 1, 2)));
        Color background = UIManager.getColor("TextField.inactiveBackground");
        if (background == null) {
            background = UIManager.getColor("TextField.background");
        }
        if (background != null) {
            displayLabel.setBackground(background);
            displayLabel.setOpaque(true);
        }
        add(displayLabel, BorderLayout.CENTER);

        pickButton.setMargin(new Insets(1, 4, 1, 4));
        pickButton.addActionListener(e -> openPicker());
        add(pickButton, BorderLayout.EAST);

        setTransferHandler(new AssetDropHandler());
        refreshDisplay();
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    /** 外源刷新（wire 串三态；值等价跳过防回声） */
    @Override
    public void setValue(String wireValue) {
        if (Objects.equals(wireValue, guid)) {
            return;
        }
        guid = wireValue;
        refreshDisplay();
    }

    @Override
    public boolean isEditing() {
        // 选择类控件无逐键编辑态，提交原子化（重建保护恒放行）
        return false;
    }

    /** 当前 guid wire 值（null=None / 空串=内存创建 / 非空=guid） */
    public String getGuid() {
        return guid;
    }

    /** 弹选择窗提交（取消不动值） */
    private void openPicker() {
        AssetPickerDialog.Selection selection = AssetPickerDialog.pick(
                SwingUtilities.getWindowAncestor(this), suffix, dialogTitle);
        if (selection != null) {
            commit(selection.guid());
        }
    }

    /** 拖放处理：按扩展名过滤，guid 双源解析（文件头优先、伴生 .meta 兜底） */
    private final class AssetDropHandler extends TransferHandler {

        @Override
        public boolean canImport(TransferSupport support) {
            return extractDroppedFile(support) != null;
        }

        @Override
        public boolean importData(TransferSupport support) {
            File file = extractDroppedFile(support);
            if (file == null) {
                return false;
            }
            String resolved = resolveGuid(file);
            if (resolved == null || resolved.isEmpty()) {
                LOGGER.warn("拖入文件解析不到 guid（未导入？），不提交: {}", file.getName());
                return false;
            }
            commit(resolved);
            return true;
        }

        /** 取拖入的首个 File 并按扩展名过滤（不匹配返回 null） */
        private File extractDroppedFile(TransferSupport support) {
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return null;
            }
            try {
                Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (data instanceof List<?> files && !files.isEmpty() && files.get(0) instanceof File file) {
                    return file.getName().toLowerCase(Locale.ROOT).endsWith(suffix) ? file : null;
                }
            } catch (Exception e) {
                LOGGER.warn("读取拖放数据失败", e);
            }
            return null;
        }

        /** File → guid：文件头（内置资源）优先、伴生 .meta（用户文件）兜底 */
        private String resolveGuid(File file) {
            String headerGuid = new GuidHeaderManager().extractGuid(file);
            if (headerGuid != null && !headerGuid.isEmpty()) {
                return headerGuid;
            }
            JavelinEditor editor = JavelinEditor.getInstance();
            if (editor == null) {
                return null;
            }
            AssetMeta meta = editor.getMetaManager().getMeta(file);
            return meta != null ? meta.getGuid() : null;
        }
    }

    /** 提交（EDT）：值等价跳过；null=None 清空合法；回调收 wire 串 */
    private void commit(String newGuid) {
        if (Objects.equals(newGuid, guid)) {
            return;
        }
        guid = newGuid;
        refreshDisplay();
        if (onCommit != null) {
            onCommit.accept(newGuid);
        }
    }

    /** 三态显示刷新：None/内存创建暗色无图标；有值=类型图标+显示名（pathOfGuid 反查尾名） */
    private void refreshDisplay() {
        if (guid == null) {
            displayLabel.setIcon(null);
            displayLabel.setText("None");
            applyDimForeground();
            return;
        }
        if (guid.isBlank()) {
            displayLabel.setIcon(null);
            displayLabel.setText("（内存创建）");
            applyDimForeground();
            return;
        }
        displayLabel.setIcon(typeIcon());
        displayLabel.setText(displayName());
        Color normal = UIManager.getColor("Label.foreground");
        if (normal != null) {
            displayLabel.setForeground(normal);
        }
    }

    /** 显示名：guid 反查源路径尾名（pathMap 内存查，无 IO），未命中退 guid 原串 */
    private String displayName() {
        JavelinEditor editor = JavelinEditor.getInstance();
        String path = editor != null ? editor.getAssetManager().pathOfGuid(guid) : null;
        if (path == null || path.isBlank()) {
            return guid;
        }
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    /** 类型图标（按扩展名查 FileTypeRegistry，懒建缓存） */
    private SvgIcon typeIcon() {
        if (typeIcon == null) {
            FileType type = FileTypeRegistry.find("x" + suffix);
            typeIcon = SvgIcon.of(type != null ? type.icon() : "unknown", ICON_SIZE);
        }
        return typeIcon;
    }

    private void applyDimForeground() {
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            displayLabel.setForeground(dim);
        }
    }
}
