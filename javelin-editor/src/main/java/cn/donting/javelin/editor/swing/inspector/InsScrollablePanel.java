package cn.donting.javelin.editor.swing.inspector;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Rectangle;

/**
 * 检查器滚动内容容器（Scrollable 双向钉口径）：宽度跟随视口——控件列收缩 /
 * 换行布局窄面板永不水平滚动；高度按 preferred——不满一屏不被 viewport 拉伸
 * 占满（普通 JPanel 双向被拉满：高度摊大间隔、宽度致水平滚动条，2026-10-18
 * 实测修正）。Node/材质内容件共用。
 *
 * @author javelin
 * @create 2026/10/21
 */
class InsScrollablePanel extends JPanel implements Scrollable {

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 20;
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
