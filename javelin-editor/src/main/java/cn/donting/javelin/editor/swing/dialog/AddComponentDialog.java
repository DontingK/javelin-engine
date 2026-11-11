package cn.donting.javelin.editor.swing.dialog;

import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.editor.inspector.AddComponentVo;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.SvgIcon;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListSelectionModel;
import javax.swing.Icon;
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
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * 添加组件选择窗（节点检查器底部「添加组件」按钮配套，Unity Add Component 口径）：
 * 模态列候选组件（图标 + 检查器同源显示名），搜索框按显示名过滤；唯一性冲突项
 * 灰显并追因、不可选中；双击条目或「确定」提交、「取消」放弃。
 *
 * <p>候选清单由 {@link cn.donting.javelin.editor.inspector.AddComponentCatalog} 在
 * 引擎线程收集后传入（EDT 不读引擎对象图）；本窗只做选择，场景写入归调用方
 *（引擎帧边界 addComponent）。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public final class AddComponentDialog extends JDialog {

    private static final int ICON_SIZE = 16;
    private static final Dimension PREFERRED_SIZE = new Dimension(360, 420);

    private final List<AddComponentVo> candidates;
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<AddComponentVo> model = new DefaultListModel<>();
    private final JList<AddComponentVo> list = new JList<>(model);
    /** 提交结果（null = 取消） */
    private Class<? extends Component> result;

    private AddComponentDialog(Window owner, List<AddComponentVo> candidates, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.candidates = candidates;
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

        AddableSelectionModel selection = new AddableSelectionModel(this::isAddableAt);
        selection.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectionModel(selection);
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
        JButton cancel = new JButton(EditorLang.text("common.cancel"));
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton(EditorLang.text("common.ok"));
        ok.addActionListener(e -> confirmSelection());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(2));
        buttons.add(ok);
        add(buttons, BorderLayout.SOUTH);

        setPreferredSize(PREFERRED_SIZE);
        pack();
        setLocationRelativeTo(owner);
        applyFilter();
    }

    /**
     * 弹出选择（模态阻塞）。
     *
     * @param owner      宿主窗口
     * @param candidates 候选清单（引擎线程收集，含不可添加项）
     * @param title      窗口标题
     * @return 选中的组件类型；null = 取消
     */
    public static Class<? extends Component> pick(Window owner, List<AddComponentVo> candidates, String title) {
        AddComponentDialog dialog = new AddComponentDialog(owner, candidates, title);
        dialog.setVisible(true);
        return dialog.result;
    }

    /** 搜索过滤（显示名包含匹配、忽略大小写）→ 选中首个可添加项 */
    private void applyFilter() {
        String keyword = searchField.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (AddComponentVo candidate : candidates) {
            if (candidate.name.toLowerCase(Locale.ROOT).contains(keyword)) {
                model.addElement(candidate);
            }
        }
        selectFirstAddable();
    }

    /** 选中首个可添加项（无则清空——全灰显时不预选，防「确定」误提交） */
    private void selectFirstAddable() {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).addable) {
                list.setSelectedIndex(i);
                return;
            }
        }
        list.clearSelection();
    }

    private boolean isAddableAt(int index) {
        return index >= 0 && index < model.size() && model.get(index).addable;
    }

    /** 提交当前选中（空选中/不可添加项不提交） */
    private void confirmSelection() {
        AddComponentVo selected = list.getSelectedValue();
        if (selected == null || !selected.addable) {
            return;
        }
        result = selected.type;
        dispose();
    }

    /** 选择模型：唯一性冲突项拒绝选中（灰显可读，点击/方向键都跳过） */
    private static final class AddableSelectionModel extends DefaultListSelectionModel {

        private final IntPredicate addable;

        AddableSelectionModel(IntPredicate addable) {
            this.addable = addable;
        }

        @Override
        public void setSelectionInterval(int index0, int index1) {
            if (!addable.test(index0)) {
                return;
            }
            super.setSelectionInterval(index0, index1);
        }

        @Override
        public void addSelectionInterval(int index0, int index1) {
            if (!addable.test(index0)) {
                return;
            }
            super.addSelectionInterval(index0, index1);
        }
    }

    /** 条目渲染：图标 + 显示名；不可添加项灰显并追因（Unity 灰显口径） */
    private static final class EntryRenderer extends DefaultListCellRenderer {

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof AddComponentVo candidate) {
                setIcon(icon(candidate));
                setText(candidate.addable ? candidate.name : candidate.name + " " + candidate.reason);
                if (!selected && !candidate.addable) {
                    Color dim = UIManager.getColor("Label.disabledForeground");
                    if (dim != null) {
                        setForeground(dim);
                    }
                }
            }
            return this;
        }

        /** 组件头图标（未配/资源缺失不渲染——菜单域同口径） */
        private static Icon icon(AddComponentVo candidate) {
            if (candidate.icon == null || candidate.icon.isBlank()) {
                return null;
            }
            SvgIcon svg = SvgIcon.of(candidate.icon, ICON_SIZE);
            return svg.isAvailable() ? svg : null;
        }
    }
}
