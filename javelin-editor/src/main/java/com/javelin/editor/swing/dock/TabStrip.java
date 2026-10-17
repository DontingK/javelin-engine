package com.javelin.editor.swing.dock;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 自绘标签条：{@code DockTabs}（面板标签）与 {@code ViewportTabs}（场景|游戏
 * 视图标签）共用（Editor_Swing_Design §5.3）。
 *
 * <p>紧凑贴行口径：条高 24、内边距 ≤2（间距规范）。活动项 = 亮字 + accent
 * 下划线（活动/非活动不靠底色区分——FlatLaf/IntelliJ 风格）；可关项右侧 ×
 * 点击回调。配色只依赖确定存在的 {@code UIManager} 键（Panel.background /
 * Label.foreground / Separator.foreground——Basic 与 FlatLaf、亮暗两档皆有；
 * 兜底为主题中性中灰，2026-10-17 实测修正：不确定键 tabAreaBackground 缺失
 * 时深色兜底让亮色主题下条区发暗）。命中区矩形在 paint 时缓存供鼠标判定。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class TabStrip extends JComponent {

    /** 条高（对齐场景视图工具栏规格） */
    private static final int HEIGHT = 24;
    /** 标签最小宽度（两字中文标签过窄的垫宽） */
    private static final int MIN_TAB_WIDTH = 64;
    /** 标签左右内边距（间距规范 ≤2） */
    private static final int PAD_X = 2;
    /** 关闭 × 命中区宽 */
    private static final int CLOSE_WIDTH = 16;

    /** 单个标签项（标题 + 可关标记；图标位预留） */
    public static final class TabItem {
        private final String title;
        private final boolean closable;

        public TabItem(String title, boolean closable) {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("标签标题不能为空");
            }
            this.title = title;
            this.closable = closable;
        }

        public String getTitle() {
            return title;
        }

        public boolean isClosable() {
            return closable;
        }
    }

    /** 标签条交互回调 */
    public interface Listener {
        /** 标签被点选 */
        void onTabSelected(int index);

        /** 关闭 × 被点击（仅可关项回调） */
        default void onTabClose(int index) {
        }
    }

    private final List<TabItem> items;
    private final Listener listener;
    private int activeIndex;
    /** paint 期缓存的命中区（宽度依赖字体度量，paint 是唯一可信计算点） */
    private final List<Rectangle2D> tabRects = new ArrayList<>();

    public TabStrip(List<TabItem> items, int activeIndex, Listener listener) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("标签条至少一项");
        }
        this.items = new ArrayList<>(items);
        this.listener = listener;
        this.activeIndex = Math.max(0, Math.min(items.size() - 1, activeIndex));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                hitTest(e.getX(), e.getY());
            }
        });
    }

    private void hitTest(int x, int y) {
        for (int i = 0; i < tabRects.size(); i++) {
            Rectangle2D rect = tabRects.get(i);
            if (!rect.contains(x, y)) {
                continue;
            }
            // × 命中区 = 标签右端 CLOSE_WIDTH 带内（仅可关项）
            if (items.get(i).isClosable() && x >= rect.getMaxX() - CLOSE_WIDTH) {
                listener.onTabClose(i);
            } else {
                if (i != activeIndex) {
                    activeIndex = i;
                    repaint();
                }
                listener.onTabSelected(i);
            }
            return;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();

            g2.setColor(uiColor("Panel.background", new Color(0xEE, 0xEE, 0xEE)));
            g2.fillRect(0, 0, width, height);

            FontMetrics fm = g2.getFontMetrics();
            tabRects.clear();
            int x = 0;
            for (int i = 0; i < items.size(); i++) {
                TabItem item = items.get(i);
                int tabWidth = Math.max(MIN_TAB_WIDTH, PAD_X + fm.stringWidth(item.getTitle()) + PAD_X
                        + (item.isClosable() ? CLOSE_WIDTH : 0));
                Rectangle2D rect = new Rectangle2D.Double(x, 0, tabWidth, height);
                boolean active = i == activeIndex;
                if (active) {
                    g2.setColor(uiColor("Panel.background", new Color(0xEE, 0xEE, 0xEE)));
                    g2.fillRect(x, 0, tabWidth, height - 1);
                }
                g2.setColor(active
                        ? uiColor("Label.foreground", new Color(0x33, 0x33, 0x33))
                        : uiColor("Label.disabledForeground", new Color(0x99, 0x99, 0x99)));
                g2.drawString(item.getTitle(), x + PAD_X,
                        (height - fm.getHeight()) / 2 + fm.getAscent());
                if (item.isClosable()) {
                    paintClose(g2, rect);
                }
                if (active) {
                    g2.setColor(accentColor());
                    g2.fillRect(x, height - 2, tabWidth, 2);
                }
                tabRects.add(rect);
                x += tabWidth;
            }

            // 条底分界线（与内容区分界）
            g2.setColor(uiColor("Separator.foreground", new Color(0xBB, 0xBB, 0xBB)));
            g2.drawLine(0, height - 1, width, height - 1);
        } finally {
            g2.dispose();
        }
    }

    /** 关闭 ×（× 字符按命中区中心绘制） */
    private void paintClose(Graphics2D g2, Rectangle2D rect) {
        FontMetrics fm = g2.getFontMetrics();
        String mark = "×";
        float cx = (float) (rect.getMaxX() - CLOSE_WIDTH / 2.0 - fm.stringWidth(mark) / 2.0);
        float cy = (float) ((rect.getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g2.setColor(uiColor("Label.disabledForeground", new Color(0x99, 0x99, 0x99)));
        g2.drawString(mark, cx, cy);
    }

    private static Color accentColor() {
        Color accent = uiColor("Component.accentColor", null);
        return accent != null ? accent
                : uiColor("TabbedPane.focus", new Color(0x4C, 0x7A, 0xD9));
    }

    /** UIManager 键取色（null 键或缺失回退 fallback） */
    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}
