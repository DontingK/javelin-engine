package cn.donting.javelin.editor.swing.inspector.widget;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Polygon;

/**
 * 通用折叠面板（组件分组头）：头部 = 自绘箭头三角 + headerLeft 组件槽
 * （enabled 勾选挂此，自消费点击不透传）+ 可选标题图标（组件头 @InsHeader）
 * + 加粗标题；点击头部空白切换折叠
 * （content 显隐 + revalidate）。
 *
 * <p>头为标题区语义（v0.2 视觉重做，2026-10-18 用户派发「头不好看」两轮）：固定高
 * 22、分层走 FlatLaf 自身色语言——面板底=灰（@background #f2f2f2）、控件浮色=
 * lighten 5%（≈#fafafa），故<b>头=面板灰（Panel.background）、内容区提亮为控件
 * 浮色（Button.background）形成灰头/亮内容的标题条分层</b>（IntelliJ 检查器口径；
 * tabAreaBackground 在 FlatLightLaf 不存在，首轮取该键落空回退同色=无分层实锤）；
 * hover 取 {@code TabbedPane.hoverColor}（darken 7%，FlatLightLaf 确存）、底线
 * Separator.foreground——配色全 UIManager 键 + 兜底（零硬编码口径）。箭头为
 * 8×8 自绘三角（字符 ▸/▾ 字形跨字体不稳，首版字符方案随重做废弃）。折叠态跨
 * 面板重建保留由宿主（InspectorPanel 按组件名记忆）负责，本类无状态记忆。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class CollapsiblePanel extends JComponent {

    /** 头部固定高（tab 高 24 与控件高 18 之间取紧凑档） */
    private static final int HEADER_HEIGHT = 22;

    private final JComponent content;
    private final Header header;
    private boolean collapsed;
    /** 可折叠开关（false = 固定分组：点击不折叠、无 hover 反馈、无箭头指示） */
    private boolean collapsible = true;

    public CollapsiblePanel(String title, JComponent headerLeft, Icon titleIcon, JComponent content) {
        setLayout(new BorderLayout(0, 0));
        // 整组四周 1px 边框（卡片化分组；头部自带底线=头/内容分界线）
        setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, borderColor()));
        this.content = content;
        // 内容区提亮为控件浮色（FlatLaf @componentBackground lighten 5%）——
        // 灰头/亮内容分层；键缺失（非 FlatLaf 主题）兜底面板底同色无分层但不破
        Color panelBackground = uiColor("Panel.background", new Color(0xEE, 0xEE, 0xEE));
        Color liftedBackground = uiColor("Button.background", panelBackground);
        if (!liftedBackground.equals(panelBackground)) {
            content.setOpaque(true);
            content.setBackground(liftedBackground);
        }
        header = new Header(title, headerLeft, titleIcon);
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** 折叠切换（箭头朝向 + content 显隐；revalidate/repaint 由布局链生效） */
    public void setCollapsed(boolean collapse) {
        if (collapsed == collapse) {
            return;
        }
        collapsed = collapse;
        header.setCollapsedVisual(collapse);
        content.setVisible(!collapse);
        revalidate();
        repaint();
    }

    /** 可折叠开关（false = 固定分组：点击不折叠、无 hover、隐藏箭头） */
    public void setCollapsible(boolean collapsible) {
        if (this.collapsible == collapsible) {
            return;
        }
        this.collapsible = collapsible;
        header.arrow.setVisible(collapsible);
        if (!collapsible) {
            setCollapsed(false);
        }
        revalidate();
        repaint();
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static Color borderColor() {
        return uiColor("Separator.foreground", new Color(0xBB, 0xBB, 0xBB));
    }

    /**
     * 折叠头：标题区底色分层 + hover 反馈 + 点击空白切换；headerLeft（勾选框）
     * 消费自身点击不透传到头部的折叠切换。
     */
    private final class Header extends JPanel {

        private final Arrow arrow;
        private final Color baseBackground;
        private final Color hoverBackground;

        private Header(String title, JComponent headerLeft, Icon titleIcon) {
            setLayout(new FlowLayout(FlowLayout.LEFT, 2, 0));
            setOpaque(true);
            // 头=面板灰（FlatLightLaf @background #f2f2f2），分层靠内容区提亮实现
            baseBackground = uiColor("Panel.background", new Color(0xEE, 0xEE, 0xEE));
            hoverBackground = uiColor("TabbedPane.hoverColor", baseBackground);
            setBackground(baseBackground);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                    BorderFactory.createEmptyBorder(0, 2, 0, 2)));
            arrow = new Arrow();
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
            // AWT 鼠标事件（含 enter/exit）不冒泡：target 是子组件时 header 收不到
            // ——hover 与点击切换须逐组件挂（headerLeft 勾选除外，它消费自身点击）
            MouseAdapter headerListener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (collapsible) {
                        setCollapsed(!collapsed);
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    if (collapsible) {
                        setBackground(hoverBackground);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (collapsible) {
                        setBackground(baseBackground);
                    }
                }
            };
            addMouseListener(headerListener);
            arrow.addMouseListener(headerListener);
            titleLabel.addMouseListener(headerListener);
            add(arrow);
            if (headerLeft != null) {
                add(headerLeft);
            }
            if (titleIcon != null) {
                JLabel iconLabel = new JLabel(titleIcon);
                iconLabel.addMouseListener(headerListener);
                add(iconLabel);
            }
            add(titleLabel);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, HEADER_HEIGHT);
        }

        private void setCollapsedVisual(boolean collapse) {
            arrow.setCollapsed(collapse);
        }
    }

    /** 8×8 折叠指示三角：折叠右指 ▸、展开下指 ▼（自绘，色=Label.disabledForeground） */
    private static final class Arrow extends JComponent {

        private boolean collapsed;

        private Arrow() {
            setPreferredSize(new Dimension(8, 8));
            setOpaque(false);
        }

        private void setCollapsed(boolean collapse) {
            if (collapsed == collapse) {
                return;
            }
            collapsed = collapse;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color dim = UIManager.getColor("Label.disabledForeground");
                g2.setColor(dim != null ? dim : new Color(0x99, 0x99, 0x99));
                Polygon triangle = new Polygon();
                if (collapsed) {
                    // 右指：▾ 旋转 90°（闭合态——点开含义）
                    triangle.addPoint(2, 1);
                    triangle.addPoint(6, 4);
                    triangle.addPoint(2, 7);
                } else {
                    // 下指：展开态（内容已摊开）
                    triangle.addPoint(2, 2);
                    triangle.addPoint(6, 2);
                    triangle.addPoint(4, 6);
                }
                g2.fillPolygon(triangle);
            } finally {
                g2.dispose();
            }
        }
    }
}
