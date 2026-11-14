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
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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

    /** 说明行（次要色）——标签按可用宽度软换行，长提示不撑出横向滚动条 */
    private static JLabel hintLabel(String text) {
        return new HintLabel(text);
    }

    /** 表单行：label 固定列宽 + 控件占余宽（4px 列间隔，行高收紧） */
    private static JPanel row(String name, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel label = new JLabel(name);
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
     * 说明行标签：宽度随面板拉伸，按可用宽度用 HTML 软换行。
     *
     * <p>JLabel 自身不换行——长提示（尤其英文文案）会把文本撑出可视区被裁掉。容器宽度由
     * {@link FormPane} 钉在视口宽度上，这里按该宽度重排即可（宽度不变则跳过，避免布局回声）。</p>
     */
    private static final class HintLabel extends JLabel {

        /** 最小换行宽度（窗口很窄时仍留可读宽度） */
        private static final int MIN_WRAP_WIDTH = 120;

        private final String rawText;
        private int wrapWidth = -1;

        private HintLabel(String rawText) {
            this.rawText = rawText;
            setText(rawText);
            setForeground(UIManager.getColor("Label.disabledForeground"));
            setBorder(new EmptyBorder(0, 0, 2, 0));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setVerticalAlignment(SwingConstants.TOP);
            setMinimumSize(new Dimension(0, 0));
            refreshMaxSize();
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    applyWrap(getWidth());
                }
            });
        }

        /**
         * 只放开宽度、高度钉在首选高度：宽度放开才能被 BoxLayout 拉到面板宽度去换行；
         * 高度若也放开，纵向富余空间会被摊给它（内容不满一屏时行下留大空档，照 row 行同款）。
         */
        private void refreshMaxSize() {
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        }

        private void applyWrap(int width) {
            if (width <= 0 || width == wrapWidth) {
                return;
            }
            wrapWidth = width;
            setText("<html><body style='width:" + Math.max(MIN_WRAP_WIDTH, width - 4) + "px'>"
                    + escapeHtml(rawText) + "</body></html>");
            refreshMaxSize();
        }

        /** HTML 转义（提示文案含 & < > 时按字面量显示） */
        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
