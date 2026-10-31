package cn.donting.javelin.editor.swing.dock;

import cn.donting.javelin.editor.swing.panel.EditorPanelSetup;

import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.beans.PropertyChangeEvent;

/**
 * {@link SplitNode} 渲染件：{@code JSplitPane} 包装（Editor_Swing_Design §5.3）。
 *
 * <p><b>连续实时布局</b>（2026-10-17 用户拍板：拖动实时调整，弃非连续幽灵条
 * 语义）——中间尺寸触发画布 resize/交换链重建的频率被引擎帧率封顶（resized
 * 标志按帧消费），拖动期画面拉伸与窗口边缘 resize 同口径（§9.11 已拍板接受）。
 * 分割比重双向同步：首次有尺寸时按模型比例落位；拖动回写模型 weight（M5 布局
 * 持久化消费）。</p>
 *
 * <p><b>拖拽自研驱动（不依赖 BasicSplitPaneDivider 的拖拽）</b>：其 MouseHandler
 * 依赖 LightweightDispatcher 抓取链——鼠标越过视口画布（heavyweight，独立原生
 * 窗口）即断，拖拽冻结（2026-10-17 实测）；且释放事件丢失（窗口外松手）会卡死
 * {@code dragger} 字段无自愈。自研 = 按下分隔条时剥掉 UI 自装监听，挂 toolkit 级
 * {@link AWTEventListener} 用<b>屏幕绝对坐标</b>直算分割位——事件源与事件形态
 * 均无关（越过画布时 AWT 原生层投 MOUSE_MOVED 而非 DRAGGED，见 onGlobalMouse
 * 注释）；新按下自愈（上一拖拽释放丢失时收场重启）。副作用：分隔条
 * hover 高亮随 UI 监听一并剥离（纯装饰，可接受）。</p>
 *
 * <p><b>尺寸重分配策略（固定）</b>：resizeWeight = 含视口面板的一侧吸收容器
 * 尺寸变化，其余面板保持原尺寸（Unity/IntelliJ 口径——拖外层分割条/改窗口大小
 * 时层级/属性/底栏等定宽面板不随之伸缩，Editor_Swing_Design §5.1）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
final class DockSplitPane extends JSplitPane {

    private final SplitNode model;
    /** 首次落位完成标志（之前的 DIVIDER_LOCATION 事件不回写模型） */
    private boolean dividerApplied;
    /** 分隔条组件（自研拖拽的按下源） */
    private Component divider;

    // ---------------- 自研拖拽驱动 ----------------

    /** 拖拽激活标志 */
    private boolean dragging;
    /** 按下时屏幕坐标与分隔条屏幕位置之差 */
    private int dragOffset;
    /** 拖拽范围钳制（按下时求值） */
    private int dragMin;
    private int dragMax;
    /** 分割面板屏幕原点（按下时缓存，拖拽期面板不动） */
    private Point paneOrigin;
    /** toolkit 级监听（非 null = 已注册） */
    private AWTEventListener dragListener;

    DockSplitPane(SplitNode model, Component left, Component right) {
        super(model.getDirection() == SplitNode.Direction.HORIZONTAL
                ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT, left, right);
        this.model = model;
        setBorder(null);
        setContinuousLayout(true);
        setResizeWeight(DockNode.containsPanel(model.getLeft(), EditorPanelSetup.VIEWPORT_PANEL_ID)
                ? 1.0 : 0.0);
        addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, this::onDividerMoved);
        // 首次有尺寸时按模型比例落位（构造期尺寸为 0，setDividerLocation(double) 不生效）
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!dividerApplied && getWidth() > 0 && getHeight() > 0) {
                    dividerApplied = true;
                    setDividerLocation(1.0 - model.getWeight());
                }
            }
        });
        installCustomDrag();
    }

    /**
     * 自研拖拽装配：剥离 UI 自装的分隔条监听（MouseHandler——拖拽与 hover 高亮），
     * 换装自研按下监听。注意：运行期换 LAF 会重装 UI 监听（两驱动并存也不冲突
     * ——连续模式下两者写入同一目标位）。
     */
    private void installCustomDrag() {
        divider = findDivider();
        if (divider == null) {
            return;
        }
        for (MouseListener listener : divider.getMouseListeners()) {
            divider.removeMouseListener(listener);
        }
        for (MouseMotionListener listener : divider.getMouseMotionListeners()) {
            divider.removeMouseMotionListener(listener);
        }
        divider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                beginDrag(e);
            }
        });
    }

    /** 拖拽启动：范围/偏移/面板原点求值 + 挂 toolkit 监听 */
    private void beginDrag(MouseEvent e) {
        if (divider == null) {
            return;
        }
        dragMin = Math.max(0, getMinimumDividerLocation());
        dragMax = getMaximumDividerLocation();
        if (dragMax <= dragMin) {
            return;
        }
        boolean horizontal = getOrientation() == HORIZONTAL_SPLIT;
        Point dividerOrigin = new Point(0, 0);
        SwingUtilities.convertPointToScreen(dividerOrigin, divider);
        dragOffset = horizontal ? e.getXOnScreen() - dividerOrigin.x
                : e.getYOnScreen() - dividerOrigin.y;
        paneOrigin = new Point(0, 0);
        SwingUtilities.convertPointToScreen(paneOrigin, this);
        dragging = true;
        if (dragListener == null) {
            dragListener = this::onGlobalMouse;
            Toolkit.getDefaultToolkit().addAWTEventListener(dragListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }
    }

    /**
     * toolkit 级拖拽回调：屏幕绝对坐标直算，不受事件源影响（heavyweight 画布
     * 越界即事件源，同样有效）。拖拽中出现新按下 = 上一拖拽释放丢失（窗口外
     * 松手），先收场——按下在本分隔条上则随后的 beginDrag 重启。
     */
    private void onGlobalMouse(AWTEvent event) {
        if (!(event instanceof MouseEvent mouseEvent)) {
            return;
        }
        switch (mouseEvent.getID()) {
            case MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_MOVED -> {
                // MOUSE_MOVED = 拖拽越过 heavyweight 画布时的事件形态：AWT
                // Windows 原生层只给收到按下的组件投递 DRAGGED（按下置
                // m_dragging、移动时查该标志）——画布没见过按下，跨入其 HWND
                // 的拖动被投成 MOVED。自研驱动里两者等价（dragging 标志守卫：
                // 激活期收到 MOVED 只可能来自画布侧）
                if (dragging) {
                    applyDragLocation(getOrientation() == HORIZONTAL_SPLIT
                            ? mouseEvent.getXOnScreen() : mouseEvent.getYOnScreen());
                }
            }
            case MouseEvent.MOUSE_PRESSED -> {
                if (dragging && !pressOnDivider(mouseEvent)) {
                    endDrag();
                }
            }
            case MouseEvent.MOUSE_RELEASED -> endDrag();
            default -> {
                // 拖拽驱动只关心按下/拖动/释放
            }
        }
    }

    /** 拖拽落位：屏幕坐标 → 面板内分割位，钳制 [min, max]，实时 setDividerLocation */
    private void applyDragLocation(int screenPos) {
        int location = screenPos - dragOffset
                - (getOrientation() == HORIZONTAL_SPLIT ? paneOrigin.x : paneOrigin.y);
        location = Math.max(dragMin, Math.min(dragMax, location));
        if (location != getDividerLocation()) {
            setDividerLocation(location);
        }
    }

    /** 拖拽收场（幂等；释放丢失由下次按下自愈） */
    private void endDrag() {
        dragging = false;
        if (dragListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(dragListener);
            dragListener = null;
        }
    }

    /** 按下位置是否在本分隔条上（屏幕坐标判定；global 按下自愈的分隔条豁免） */
    private boolean pressOnDivider(MouseEvent e) {
        if (!divider.isShowing()) {
            return false;
        }
        Point origin = divider.getLocationOnScreen();
        return e.getXOnScreen() >= origin.x && e.getXOnScreen() < origin.x + divider.getWidth()
                && e.getYOnScreen() >= origin.y && e.getYOnScreen() < origin.y + divider.getHeight();
    }

    /** 分隔条组件：Basic UI 走 getDivider；非 Basic UI 兜底子件序 1 位（left/divider/right） */
    private Component findDivider() {
        if (getUI() instanceof BasicSplitPaneUI basic) {
            return basic.getDivider();
        }
        return getComponentCount() == 3 ? getComponent(1) : null;
    }

    /** 拖动/程序落位后回写模型（右占比 = 1 - 分割位比例；幂等） */
    private void onDividerMoved(PropertyChangeEvent event) {
        if (!dividerApplied || !(event.getNewValue() instanceof Integer location)) {
            return;
        }
        int extent = model.getDirection() == SplitNode.Direction.HORIZONTAL
                ? getWidth() : getHeight();
        if (extent <= getDividerSize()) {
            return;
        }
        model.setWeight(1.0 - (double) location / (extent - getDividerSize()));
    }
}
