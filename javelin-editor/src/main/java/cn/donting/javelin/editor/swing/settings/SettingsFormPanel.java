package cn.donting.javelin.editor.swing.settings;

import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.settings.SettingsSchema;
import cn.donting.javelin.editor.swing.inspector.FieldEditor;
import cn.donting.javelin.editor.swing.inspector.FieldRowFactory;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.function.Consumer;

/**
 * 声明式设置表单：按 {@link SettingsSchema} 装配「分组标题 + 字段行 + 提示行」，
 * 行控件一律复用检查器叶子件（{@link FieldRowFactory}：数字/勾选/下拉/颜色…同款）。
 *
 * <p>回写链：控件提交 → {@link SettingsSchema.Item#write}（反射 setter 写设置字段）→
 * {@code onApplied}（把设置推给运行中的引擎）。异常不吞，交 {@code onError}（窗口层弹框）。</p>
 *
 * <p>尺寸口径与设置窗既有实现一致（label 列 120px、列间隔 4px、行高收紧）——设置窗是
 * 独立窗口，不受编辑器面板 ≤2px 紧凑度约束。</p>
 *
 * @author javelin
 * @create 2026/11/14
 */
public final class SettingsFormPanel {

    /** label 列宽（Inspector 两列布局同口径） */
    private static final int LABEL_WIDTH = 120;

    private SettingsFormPanel() {
    }

    /**
     * 装配设置表单。
     *
     * @param schema    页面声明（分组 + 行）
     * @param onApplied 行改动写回成功后的生效回调（推给运行中的引擎）
     * @param onError   写回/生效异常回调（窗口层显性化，红线 15）
     */
    public static JPanel build(SettingsSchema schema, Runnable onApplied, Consumer<Throwable> onError) {
        JPanel pane = new FormPane();
        pane.setBorder(new EmptyBorder(2, 4, 2, 4));
        for (SettingsSchema.Section section : schema.sections()) {
            pane.add(sectionLabel(section.labelKey));
            for (SettingsSchema.Entry entry : section.entries) {
                if (entry.hintKey != null) {
                    pane.add(hintLabel(EditorLang.text(entry.hintKey)));
                } else {
                    pane.add(formRow(entry.item, onApplied, onError));
                }
            }
        }
        pane.add(Box.createVerticalGlue());
        return pane;
    }

    /** 单个字段行：label + 声明式控件（只读项置禁用——启动期定型字段） */
    private static JPanel formRow(SettingsSchema.Item item, Runnable onApplied, Consumer<Throwable> onError) {
        FieldEditor editor = FieldRowFactory.create(item.vo, wire -> {
            try {
                item.write(wire);
                onApplied.run();
            } catch (RuntimeException | TipException e) {
                onError.accept(e);
            }
        });
        JComponent control = editor.editorComponent();
        if (item.readOnly) {
            control.setEnabled(false);
        }
        return row(item.vo.name, control);
    }

    /** 分组标题（加粗小标题） */
    private static JLabel sectionLabel(String key) {
        JLabel label = new JLabel(EditorLang.text(key));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(new EmptyBorder(4, 0, 2, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** 说明行（次要色）——只读文本区原生软换行，长提示不撑出横向滚动条 */
    private static JComponent hintLabel(String text) {
        return new HintArea(text);
    }

    /** 表单行：label 固定列宽 + 控件占余宽（4px 列间隔，行高收紧；label 顶对齐，多行列表控件不悬空） */
    private static JPanel row(String name, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel label = new JLabel(name);
        label.setVerticalAlignment(SwingConstants.TOP);
        label.setPreferredSize(new Dimension(LABEL_WIDTH, label.getPreferredSize().height));
        row.add(label, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * 表单容器（Scrollable 双向钉口径，照 {@code InsScrollablePanel} 先例）：**宽度跟随视口**——
     * 长提示换行、控件列收缩，永不出现水平滚动条；高度按 preferred（不满一屏不被视口拉满，
     * 否则行距被摊大）。
     */
    private static final class FormPane extends JPanel implements Scrollable {

        private FormPane() {
            setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * 说明行：只读软换行文本区（JTextArea 原生换行，不再用 HTML 手算宽度）。
     *
     * <p>HTML JLabel 的换行宽度要自己按像素算、高度又是按那个宽度取的快照，窗口/滚动条一变就
     * 容易算出半行被裁。JTextArea 的换行由视图按**实际宽度**决定，这里只把 preferred 高度改成
     * 按父容器当前宽度现算——布局每次取一次，行数与高度天然一致。</p>
     */
    private static final class HintArea extends JTextArea {

        private HintArea(String text) {
            super(text);
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            setBorder(null);
            setMargin(new Insets(0, 0, 2, 0));
            setFont(UIManager.getFont("Label.font"));
            setForeground(UIManager.getColor("Label.disabledForeground"));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        /** 按父容器当前宽度算换行后的高度（JTextArea 自身不会按宽度给 preferred） */
        @Override
        public Dimension getPreferredSize() {
            int width = getParent() != null ? getParent().getWidth() : getWidth();
            if (width > 0) {
                setSize(width, Short.MAX_VALUE);
            }
            Dimension size = super.getPreferredSize();
            return new Dimension(width > 0 ? width : size.width, size.height);
        }

        /** 宽度放开（由 BoxLayout 拉伸）、高度钉在当前换行结果 */
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /** 高度不可压：压扁就会裁行 */
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }
    }
}
