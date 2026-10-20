package com.javelin.editor.window;

import com.javelin.core.input.InputSink;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.menu.MenuBarFactory;
import com.javelin.editor.swing.StatusBar;
import com.javelin.editor.swing.dock.DockRoot;
import com.javelin.editor.window.cef.CanvasSurface;
import com.javelin.rhi.render.RenderSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 主编辑窗口（Editor_Swing_Design §4——纯 Swing 重构版，2026-10-17 起替换
 * CEF 铺满 + Canvas 挖洞形态）。
 *
 * <p>{@link Window} 契约逐条保持（引擎侧零感知）。结构 = JMenuBar
 * （onInitialize 菜单注册后 installMenuBar 装配）+ {@link DockRoot} 默认布局
 * + {@link StatusBar}；Vulkan 画布归 ViewportTabs 持有（Swing 布局直挂，
 * rectChange 挖洞上报链废除——resize 由布局收敛，最大化补链坑随之消灭）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class MainFrame extends JFrame implements Window {
    private static final Logger logger = LoggerFactory.getLogger(MainFrame.class);
    private static MainFrame instance;

    private final JavelinSetting setting;
    private CanvasSurface canvas;
    private DockRoot dockRoot;
    private StatusBar statusBar;
    /** 输入事件出口（attachInputSink 存储；挂接时序见 attachInputSink 注释） */
    private InputSink inputSink;
    private boolean menuInstalled;
    private boolean close;
    private boolean shown;
    private boolean closed;
    private float dpi = 1f;

    public MainFrame(JavelinSetting setting) {
        this.setting = setting;
        instance = this;
    }

    public static MainFrame getInstance() {
        return instance;
    }

    /**
     * 状态栏（提示通道；create 之前为 null）。
     */
    public StatusBar getStatusBar() {
        return statusBar;
    }

    @Override
    public synchronized void create() {
        if (created()) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Window has been closed and cannot be created again");
        }
        canvas = new CanvasSurface();
        // AWT Canvas 未显式设最小尺寸时 getMinimumSize 走 peer（≈当前尺寸），
        // 会沿 BorderLayout.minimumLayoutSize 链上溯成整个 dock 区的最小值——
        // 分隔条拖不动（2026-10-17 实测：min 钳在画布当前高，Editor_Swing_Design
        // §10-5）；显式 (1,1) 掐断传播，画布真实可缩下限由渲染侧把关
        canvas.setMinimumSize(new Dimension(1, 1));
        // 面板/文件类型注册已前移至插件装载期（EditorCorePlugin，早于建窗），
        // 画布由视口面板 factory 懒取（单 Canvas 不 reparent，§6）
        if (inputSink != null) {
            // attachInputSink 早于 create 的兜底转发（正常时序：create 先于子系统 initAll）
            canvas.attachInputSink(inputSink);
        }
        dockRoot = DockRoot.defaultLayout();
        statusBar = new StatusBar();
        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(dockRoot, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // 关窗请求只置标志，由引擎轮询 closeRequested() 后调 close() 统一收尾
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close = true;
            }
        });
        setTitle(setting.getTitle());
        setSize(setting.getWidth(), setting.getHeight());
        setLocationRelativeTo(null);
        applyDpi();
    }

    @Override
    public synchronized void showWindow() {
        if (shown) {
            return;
        }
        if (!created()) {
            create();
        }
        setVisible(true);
        shown = true;
    }

    @Override
    public boolean closeRequested() {
        return close;
    }

    @Override
    public void pollEvents() {
        // Swing UI 事件在 EDT 自发派发无需轮询；但输入事件是「EDT 入队 →
        // 引擎线程泵」模型（mainLoop 在 Engine Main 线程），每帧此处泵进
        // InputSubsystem —— 时序对齐 GLFW 路径：pollEvents 先于 earlyUpdate 帧翻转
        if (canvas != null) {
            canvas.pollInputEvents();
        }
    }

    /**
     * 挂载输入事件出口：转发到 canvas（**输入源 = Canvas 视口区域**，
     * 键鼠事件均来自视口，坐标为视口局部坐标）。传 null 摘除。
     */
    @Override
    public void attachInputSink(InputSink sink) {
        this.inputSink = sink;
        if (canvas != null) {
            canvas.attachInputSink(sink);
        }
    }

    @Override
    public int width() {
        return created() ? getWidth() : setting.getWidth();
    }

    @Override
    public int height() {
        return created() ? getHeight() : setting.getHeight();
    }

    @Override
    public boolean isResized() {
        return canvas != null && canvas.isResized();
    }

    @Override
    public void acknowledgeResize() {
        if (canvas != null) {
            canvas.acknowledgeResize();
        }
    }

    @Override
    public boolean isVisible() {
        return shown && isShowing() && (getExtendedState() & ICONIFIED) == 0;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        shown = false;
        close = false;
        canvas = null;
        if (SwingUtilities.isEventDispatchThread()) {
            dispose();
        } else {
            SwingUtilities.invokeLater(this::dispose);
        }
    }

    @Override
    public RenderSurface renderSurface() {
        if (canvas == null) {
            throw new IllegalStateException("Window has not been created yet");
        }
        return canvas;
    }

    /**
     * 顶栏菜单条装配：菜单命令已随插件装载期注册（早于建窗），引擎侧
     * onInitialize 末调用。可在引擎线程调（EDT hop），幂等。
     */
    public void installMenuBar() {
        if (menuInstalled) {
            return;
        }
        menuInstalled = true;
        SwingUtilities.invokeLater(() -> {
            setJMenuBar(MenuBarFactory.build());
            revalidate();
            repaint();
        });
    }

    /**
     * 画布 dpi：取主屏默认变换 scaleX（Editor_Swing_Design §4.1——M1 简化：
     * 首次创建取一次；多显示器拖跨/dpi 动态跟随为开放项 §10-4）。
     */
    private void applyDpi() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration != null && canvas != null) {
            dpi = (float) configuration.getDefaultTransform().getScaleX();
            canvas.setDpi(dpi);
        }
    }

    /** 游戏画布（未创建返回 null）——拾取换算取画布逻辑尺寸用（与 Input.mouseX/Y 同空间） */
    public CanvasSurface getCanvas() {
        return canvas;
    }

    private boolean created() {
        return canvas != null;
    }
}
