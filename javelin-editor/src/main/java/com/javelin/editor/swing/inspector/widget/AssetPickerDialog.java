package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.JavelinEditor;
import com.javelin.editor.file.FileType;
import com.javelin.editor.file.FileTypeRegistry;
import com.javelin.editor.project.Project;
import com.javelin.editor.swing.SvgIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资产选择窗（AssetInput 配套，Unity ObjectField 拾取器口径）：模态列当前类型
 * 全部已导入**用户可选**资产（后台 listAssetPaths 拉取；.javelin/assets 的 core
 * 组内置原始体/默认着色器照常列出，editor 组编辑器内部资源过滤不列）+ 首项 None
 * （清空合法），搜索框按
 * 显示名过滤；双击条目或「确定」提交、「取消」/Esc 放弃。选择结果经
 * {@link #pick} 返回——返回 null = 取消；{@link Selection} 的 guid 为 null = 选中
 * None（清空）。
 *
 * @author javelin
 * @create 2026/10/25
 */
public final class AssetPickerDialog extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssetPickerDialog.class);

    private static final int ICON_SIZE = 16;
    private static final Dimension PREFERRED_SIZE = new Dimension(360, 420);

    /** 选择结果（guid null = 选中 None 清空） */
    public static final class Selection {

        private final String guid;

        private Selection(String guid) {
            this.guid = guid;
        }

        /** 选中资产的 GUID；None 项返回 null（语义=清空） */
        public String guid() {
            return guid;
        }
    }

    /** 列表条目：guid null = None 项 */
    private static final class Entry {

        private final String guid;
        private final String name;

        Entry(String guid, String name) {
            this.guid = guid;
            this.name = name;
        }
    }

    private final String suffix;
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    /** 全量条目（None 恒首，过滤基准） */
    private List<Entry> entries = new ArrayList<>();
    /** 提交结果（null = 未提交=取消） */
    private Selection result;

    private AssetPickerDialog(Window owner, String suffix, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.suffix = suffix;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 2));

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        add(searchField, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EntryRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    confirmSelection();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.add(Box.createHorizontalGlue());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton("确定");
        ok.addActionListener(e -> confirmSelection());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(2));
        buttons.add(ok);
        add(buttons, BorderLayout.SOUTH);

        setPreferredSize(PREFERRED_SIZE);
        pack();
        setLocationRelativeTo(owner);

        model.addElement(new Entry(null, "None"));
        loadEntries();
    }

    /**
     * 弹出选择（模态阻塞）：后台拉当前类型全部已导入资产后组装列表。
     *
     * @param owner 宿主窗口
     * @param suffix 资产扩展名（含点号，如 {@code .shader}）
     * @param title 窗口标题
     * @return 选择结果；null = 取消（Selection.guid 为 null = 选中 None 清空）
     */
    public static Selection pick(Window owner, String suffix, String title) {
        AssetPickerDialog dialog = new AssetPickerDialog(owner, suffix, title);
        dialog.setVisible(true);
        return dialog.result;
    }

    /** 后台线程拉列表（listAssetPaths 重载 index 纯 IO）→ EDT 组装 */
    private void loadEntries() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，资产选择窗列表为空");
            return;
        }
        Thread loader = new Thread(() -> {
            List<Entry> loaded = new ArrayList<>();
            for (String path : editor.getAssetManager().listAssetPaths(suffix)) {
                if (isEditorAsset(editor, path)) {
                    continue;
                }
                String guid = editor.getAssetManager().guidOfPath(path);
                if (guid == null || guid.isEmpty()) {
                    LOGGER.warn("索引条目缺 guid，跳过: {}", path);
                    continue;
                }
                loaded.add(new Entry(guid, tailName(path)));
            }
            SwingUtilities.invokeLater(() -> {
                entries = loaded;
                applyFilter();
            });
        }, "asset-picker-loader");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 编辑器自有资产判定（仅 .javelin/assets **editor 组**——gizmo/网格辅助线等
     * 编辑器内部资源，不摆进用户选择列表；core 组内置原始体/默认着色器照常可选，
     * Unity builtin 口径）。索引键相对各自监视根，editor 组键以 "editor/" 起头——
     * 以 .javelin/assets 下存在同名文件坐实（防用户 Assets/editor/ 撞名误伤；
     * 后台线程调用，纯文件存在性 IO）。
     */
    private static boolean isEditorAsset(JavelinEditor editor, String path) {
        if (!path.startsWith("editor/")) {
            return false;
        }
        Project project = editor.getProject();
        return project != null && new File(project.getEditorDir(), "assets/" + path).isFile();
    }

    /** 应用搜索过滤（None 恒首；显示名包含匹配、忽略大小写） */
    private void applyFilter() {
        String keyword = searchField.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        model.addElement(new Entry(null, "None"));
        for (Entry entry : entries) {
            if (entry.name.toLowerCase(Locale.ROOT).contains(keyword)) {
                model.addElement(entry);
            }
        }
        if (model.size() > 1) {
            // 有关键字时优先选首个匹配，无关键字保持 None（当前值高亮后置）
            list.setSelectedIndex(keyword.isEmpty() ? 0 : 1);
        } else {
            list.setSelectedIndex(0);
        }
    }

    /** 提交当前选中条目（None 项=清空合法） */
    private void confirmSelection() {
        Entry selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        result = new Selection(selected.guid);
        dispose();
    }

    /** 路径尾名（最后一个 / 段） */
    private static String tailName(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    /** 条目渲染：图标（查 FileTypeRegistry，None 无图标）+ 显示名，None 暗色 */
    private static final class EntryRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof Entry entry) {
                setIcon(entry.guid == null ? null : typeIcon(entry.name));
                setText(entry.name);
                if (!selected && entry.guid == null) {
                    Color dim = UIManager.getColor("Label.disabledForeground");
                    if (dim != null) {
                        setForeground(dim);
                    }
                }
            }
            return this;
        }

        /** 条目图标（未注册类型=unknown 兜底） */
        private static SvgIcon typeIcon(String fileName) {
            FileType type = FileTypeRegistry.find(fileName);
            SvgIcon icon = SvgIcon.of(type != null ? type.icon() : "unknown", ICON_SIZE);
            return icon.isAvailable() ? icon : null;
        }
    }
}
