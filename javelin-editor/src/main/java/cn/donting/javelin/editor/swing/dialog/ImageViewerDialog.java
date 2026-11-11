package cn.donting.javelin.editor.swing.dialog;

import cn.donting.javelin.editor.plugin.EditorLang;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

/**
 * 图片查看窗（预览件的「查看」按钮弹出）：可缩放/平移的图片查看器——滚轮或
 * +/− 按钮缩放（1.25 步进，5%~3200%）、左键拖拽平移、「适应窗口」（不放大超 1:1）、
 * 「1:1」复位；标题栏显示文件名，底部工具条实时显示缩放百分比。
 *
 * <p>非模态（浏览时编辑器照常操作）；同一时刻只保留一个查看窗（再点即替换旧窗，
 * 防窗口堆积）。大图由调用侧在后台线程产出（{@code ImagePreview.viewable} 的
 * fullLoader），本类只负责展示。</p>
 *
 * @author javelin
 * @create 2026/11/11
 */
public final class ImageViewerDialog extends JDialog {

    private static final double MIN_ZOOM = 0.05;
    private static final double MAX_ZOOM = 32.0;
    private static final double ZOOM_STEP = 1.25;
    /** 窗口内容默认边长上限（px；大图不铺满整屏） */
    private static final int VIEW_SIZE = 720;
    private static final int SCROLL_UNIT = 24;

    /** 当前查看窗（单实例——再点替换旧窗，防堆积；仅 EDT 访问） */
    private static ImageViewerDialog current;

    private final ZoomableImagePanel canvas;
    private final JScrollPane scrollPane;
    private final JLabel zoomLabel = new JLabel();

    /**
     * 弹出查看窗（EDT 调用）——镜像已有窗口则先关掉。
     *
     * @param owner 宿主窗口（可为 null）
     * @param title 标题（一般传文件名）
     * @param image 待查看图片（非 null）
     */
    public static void show(Window owner, String title, BufferedImage image) {
        if (image == null) {
            return;
        }
        if (current != null && current.isDisplayable()) {
            current.dispose();
        }
        current = new ImageViewerDialog(owner, title, image);
        current.setVisible(true);
    }

    private ImageViewerDialog(Window owner, String title, BufferedImage image) {
        super(owner, title, ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));

        canvas = new ZoomableImagePanel(image);
        scrollPane = new JScrollPane(canvas);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(SCROLL_UNIT);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(SCROLL_UNIT);
        add(scrollPane, BorderLayout.CENTER);
        add(toolbar(), BorderLayout.SOUTH);

        installInteractions();

        // 小图给紧凑窗、大图给上限窗（初始内容尺寸）
        int width = Math.max(360, Math.min(VIEW_SIZE, image.getWidth() + 40));
        int height = Math.max(280, Math.min(VIEW_SIZE, image.getHeight() + 60));
        setSize(width, height);
        setLocationRelativeTo(owner);
        // 初始适应窗口（须待布局/视口尺寸就绪）
        SwingUtilities.invokeLater(this::fitToWindow);
    }

    /** 底部工具条：[−][百分比][+] … [适应窗口][1:1] */
    private JComponent toolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.LINE_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        zoomLabel.setHorizontalAlignment(SwingConstants.CENTER);
        zoomLabel.setPreferredSize(new Dimension(56, 20));

        bar.add(toolButton("−", "image.viewer.zoomOut", () -> zoomBy(1 / ZOOM_STEP)));
        bar.add(Box.createHorizontalStrut(2));
        bar.add(zoomLabel);
        bar.add(Box.createHorizontalStrut(2));
        bar.add(toolButton("+", "image.viewer.zoomIn", () -> zoomBy(ZOOM_STEP)));
        bar.add(Box.createHorizontalGlue());
        bar.add(toolButton(EditorLang.text("image.viewer.fit"), null, this::fitToWindow));
        bar.add(Box.createHorizontalStrut(2));
        bar.add(toolButton("1:1", "image.viewer.actual", () -> setZoom(1.0)));
        updateZoomLabel();
        return bar;
    }

    /** 工具按钮（text=显示文本；tooltipKey 非空时取 i18n 作悬浮提示） */
    private static JButton toolButton(String text, String tooltipKey, Runnable action) {
        JButton button = new JButton(text);
        button.setMargin(new Insets(1, 6, 1, 6));
        if (tooltipKey != null) {
            button.setToolTipText(EditorLang.text(tooltipKey));
        }
        button.addActionListener(e -> action.run());
        return button;
    }

    /** 滚轮缩放 + 左键拖拽平移（拖拽用屏幕坐标算增量，避免视口移动后基准漂移） */
    private void installInteractions() {
        MouseAdapter handler = new MouseAdapter() {

            private Point dragAnchor;

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.getWheelRotation() != 0) {
                    zoomBy(Math.pow(ZOOM_STEP, -e.getWheelRotation()));
                    e.consume();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragAnchor = e.getLocationOnScreen();
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragAnchor = null;
                canvas.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragAnchor == null) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                JViewport viewport = scrollPane.getViewport();
                Point view = viewport.getViewPosition();
                view.translate(dragAnchor.x - now.x, dragAnchor.y - now.y);
                view.x = Math.max(0, view.x);
                view.y = Math.max(0, view.y);
                viewport.setViewPosition(view);
                dragAnchor = now;
            }
        };
        canvas.addMouseWheelListener(handler);
        canvas.addMouseMotionListener(handler);
        canvas.addMouseListener(handler);
    }

    private void zoomBy(double factor) {
        setZoom(canvas.zoom() * factor);
    }

    private void setZoom(double zoom) {
        canvas.setZoom(zoom);
        updateZoomLabel();
    }

    /** 适应窗口（不放大超 1:1——小图保持原尺寸居中） */
    private void fitToWindow() {
        Dimension extent = scrollPane.getViewport().getExtentSize();
        BufferedImage image = canvas.image();
        if (extent.width <= 0 || extent.height <= 0 || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return;
        }
        double fit = Math.min((double) extent.width / image.getWidth(),
                (double) extent.height / image.getHeight());
        setZoom(Math.min(1.0, fit));
        scrollPane.getViewport().setViewPosition(new Point(0, 0));
    }

    private void updateZoomLabel() {
        zoomLabel.setText(Math.round(canvas.zoom() * 100) + "%");
    }

    /**
     * 缩放画布：按 zoom 因子缩放绘制（放大用最近邻保持像素锐利、缩小用双线性抗锯齿）；
     * 图小于视口时居中、大于视口时贴左上并出滚动条（Scrollable 宽高跟随视口口径）。
     */
    private static final class ZoomableImagePanel extends JComponent implements Scrollable {

        private final BufferedImage image;
        private double zoom = 1.0;

        private ZoomableImagePanel(BufferedImage image) {
            this.image = image;
        }

        private BufferedImage image() {
            return image;
        }

        private double zoom() {
            return zoom;
        }

        private void setZoom(double newZoom) {
            double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
            if (clamped == zoom) {
                return;
            }
            zoom = clamped;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                    Math.max(1, (int) Math.round(image.getWidth() * zoom)),
                    Math.max(1, (int) Math.round(image.getHeight() * zoom)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        zoom >= 1.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                Dimension scaled = getPreferredSize();
                int x = Math.max(0, (getWidth() - scaled.width) / 2);
                int y = Math.max(0, (getHeight() - scaled.height) / 2);
                g2.drawImage(image, x, y, scaled.width, scaled.height, null);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return SCROLL_UNIT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.HORIZONTAL ? visibleRect.width : visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport viewport && getPreferredSize().width <= viewport.getWidth();
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && getPreferredSize().height <= viewport.getHeight();
        }
    }
}