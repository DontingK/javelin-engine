package com.javelin.editor.window.cef;

import com.javelin.core.input.InputSink;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.asset.BuiltinAssetReleaser;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.jcef.domain.par.RectPar;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.util.ThreadUtil;
import com.javelin.rhi.render.RenderSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainFrame extends CefFrame implements Window {
    private static final Logger logger = LoggerFactory.getLogger(MainFrame.class);
    private static MainFrame instance;

    private final JavelinSetting setting;
    private CanvasSurface canvas;
    /** 输入事件出口（attachInputSink 存储；挂接时序见 attachInputSink 注释） */
    private InputSink inputSink;
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

    @Override
    public synchronized void create() {
        if (created()) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Window has been closed and cannot be created again");
        }
        canvas = new CanvasSurface();
        canvas.setDpi(dpi);
        if (inputSink != null) {
            // attachInputSink 早于 create 的兜底转发（正常时序：create 先于子系统 initAll）
            canvas.attachInputSink(inputSink);
        }
        getContentPane().setLayout(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        // 关窗请求只置标志，由引擎轮询 closeRequested() 后调 close() 统一收尾
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close = true;
            }
        });
        setTitle(setting.getTitle());
        setSize(setting.getWidth(), setting.getHeight());
        getContentPane().add(canvas);
        canvas.setBounds(0, 0, setting.getWidth(), setting.getHeight());
        setLocationRelativeTo(null);
        loadURL("http://localhost:5173/page/main.html");
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

    private boolean created() {
        return canvas != null;
    }

    @Override
    protected void addJcefComponent(Component jecfComponent) {
        getContentPane().add(jecfComponent);
        getContentPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                jecfComponent.setBounds(0, 0, e.getComponent().getWidth(), e.getComponent().getHeight());
            }
        });
        // 最大化/还原 resize 链修复：WindowStateListener 补驱 jcef 三段同步（见
        // syncJcefBounds）。拖拽路径每次 resize 都有事件流冲刷、自然收敛；最大化
        // 只有一波事件，jcef 内部 last-write 若是陈旧值就永久停在旧尺寸（AWT/JS
        // 均已新尺寸、原生窗口仍旧的实测症状）
        addWindowStateListener(new WindowAdapter() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                logger.info("windowStateChanged: {} -> {}", e.getOldState(), e.getNewState());
                // 两拍 invokeLater + 100ms 补拍：确保同步落在 maximize 事件风暴
                // （componentResized/ancestorMoved/validate/paint）全部排空之后
                SwingUtilities.invokeLater(() ->
                        SwingUtilities.invokeLater(() -> syncJcefBounds(jecfComponent)));
                javax.swing.Timer tailSync = new javax.swing.Timer(100, ev -> syncJcefBounds(jecfComponent));
                tailSync.setRepeats(false);
                tailSync.start();
            }
        });
    }

    /**
     * 状态变化后的 jcef 尺寸同步：① setBounds（重写里触发 wasResized 原生通知）
     * → ② validate（内层 heavyweight canvas_ 同步布局、HWND 即时 resize）
     * → ③ paintImmediately（强制 jcef paint→doUpdate→updateUI 原生窗口重排）。
     * 三段都幂等，重复执行无副作用；用最终尺寸覆盖事件风暴期间的陈旧值。
     */
    private void syncJcefBounds(Component jecfComponent) {
        int w = getContentPane().getWidth();
        int h = getContentPane().getHeight();
        jecfComponent.setBounds(0, 0, w, h);
        jecfComponent.validate();
        if (jecfComponent instanceof JComponent jc) {
            jc.paintImmediately(0, 0, w, h);
        }
        String inner = jecfComponent instanceof Container c && c.getComponentCount() > 0
                ? c.getComponent(0).getSize().toString() : "n/a";
        logger.info("state-change sync: jecf={} contentPane={}x{} innerCanvas={}",
                jecfComponent.getBounds(), w, h, inner);
    }

    public void rectChange(RectPar rectPar) throws Exception {
        logger.info("rectChange:{}x{}  {}-{}", rectPar.width, rectPar.height, rectPar.x, rectPar.y);
        dpi = rectPar.dpi;
        if (canvas == null) {
            return;
        }
        canvas.setDpi(dpi);
        SwingUtilities.invokeAndWait((Runnable) () -> canvas.setBounds(rectPar.x, rectPar.y, rectPar.width, rectPar.height));
    }

    /** 游戏画布（未创建返回 null）——拾取换算取画布逻辑尺寸用（与 Input.mouseX/Y 同空间） */
    public CanvasSurface getCanvas() {
        return canvas;
    }
}
