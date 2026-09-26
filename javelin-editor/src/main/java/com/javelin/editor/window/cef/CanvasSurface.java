package com.javelin.editor.window.cef;

import com.javelin.core.input.InputAction;
import com.javelin.core.input.KeyCode;
import com.javelin.core.input.MouseButton;
import com.javelin.core.input.InputSink;
import com.javelin.editor.util.ThreadUtil;
import com.javelin.rhi.render.Platform;
import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_FreeDrawingSurfaceInfo;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_GetDrawingSurfaceInfo;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_Lock;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_Unlock;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_LOCK_ERROR;

public class CanvasSurface extends Canvas implements RenderSurface {
    private static final Logger logger = LoggerFactory.getLogger(CanvasSurface.class);

    private boolean notify;
    private boolean resized;
    /** 缓存的 HWND；0 表示未获取或已失效 */
    private long cachedHandle;
    private float dpi=1f;
    private final ComponentAdapter resizeListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            resized = true;
        }
    };

    // ---------------- 编辑器输入（输入源 = 本 Canvas 视口） ----------------

    /** 输入事件出口（InputSubsystem 挂载；null = 无输入接线，监听零开销跳过） */
    private InputSink inputSink;
    /**
     * EDT → 引擎线程事件队列：AWT 监听在 EDT 入队（闭包捕获翻译结果），
     * {@link #pollInputEvents()} 在引擎线程（MainFrame.pollEvents 驱动）出队投递 sink。
     * —— 线程模型：mainLoop 跑在 Engine Main 线程，AWT 事件在 EDT，不能直写子系统状态。
     */
    private final ConcurrentLinkedQueue<Runnable> inputQueue = new ConcurrentLinkedQueue<>();
    /** 监听只装一次（attach/detach 不重复装） */
    private boolean inputListenersInstalled;


    public void setDpi(float dpi) {
        if (this.dpi != dpi) {
            this.dpi = dpi;
            resized = true;
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        addComponentListener(resizeListener);
        notify = true;
    }

    @Override
    public void removeNotify() {
        removeComponentListener(resizeListener);
        notify = false;
        resized = false;
        cachedHandle = 0;
        super.removeNotify();
    }

    @Override
    public long nativeHandle() {
        if (!notify || !isDisplayable()) {
            throw new IllegalStateException("CanvasSurface 未准备就绪（尚未 displayable）");
        }
        if (cachedHandle == 0) {
            while (!notify){
                logger.info("wait canvas notify");
                ThreadUtil.sleep(100);
            }
            cachedHandle = getNativeWindowHandle();
        }
        return cachedHandle;
    }

    @Override
    public Platform platform() {
        return Platform.WINDOWS;
    }

    @Override
    public int width() {
        return (int)(getWidth()*dpi);
    }

    @Override
    public int height() {
        return (int)(getHeight()*dpi);
    }

    @Override
    public boolean isResized() {
        return resized;
    }

    @Override
    public void acknowledgeResize() {
        resized = false;
    }

    // ---------------- 编辑器输入 ----------------

    /**
     * 挂载输入事件出口 + 安装 AWT 监听（幂等）。
     * <p>输入源 = 本 Canvas 视口区域：鼠标事件坐标即视口局部坐标
     * （后续视口拾取直接可用）；键盘事件须 canvas 持有焦点（点击视口抢占，见
     * mousePressed），点击 CEF UI 区域焦点自然移走——游戏中输入随之静默。</p>
     */
    public void attachInputSink(InputSink sink) {
        this.inputSink = sink;
        if (!inputListenersInstalled) {
            inputListenersInstalled = true;
            installInputListeners();
        }
    }

    /**
     * 泵积压输入事件（引擎线程调用，{@code MainFrame.pollEvents} 每帧驱动）：
     * EDT 生产 → 引擎线程消费，等价 GLFW 的 glfwPollEvents 语义，
     * 时序对齐 mainLoop（pollEvents 先于 earlyUpdate 帧翻转）。
     */
    public void pollInputEvents() {
        Runnable event;
        while ((event = inputQueue.poll()) != null) {
            event.run();
        }
    }

    /** AWT 监听安装：翻译成引擎枚举后入队（EDT；捕获值，不引用 AWT 事件对象） */
    private void installInputListeners() {
        setFocusable(true);   // 键盘事件随 canvas 焦点派发（AWT 焦点模型）
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                enqueueKey(e, InputAction.PRESS);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                enqueueKey(e, InputAction.RELEASE);
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();   // 点击视口抢占键盘焦点（编辑器视口输入惯例）
                enqueueMouseButton(e, InputAction.PRESS);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                enqueueMouseButton(e, InputAction.RELEASE);
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                enqueueCursor(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                enqueueCursor(e);
            }
        });
        addMouseWheelListener(e -> {
            InputSink sink = inputSink;
            if (sink != null) {
                double dy = e.getPreciseWheelRotation();   // 上滚为正，与 GLFW 滚轮同号
                inputQueue.add(() -> sink.onScroll(0.0, dy));
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                InputSink sink = inputSink;
                if (sink != null) {
                    inputQueue.add(() -> sink.onFocusChanged(true));
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                InputSink sink = inputSink;
                if (sink != null) {
                    // canvas 失焦（点到 CEF UI/他处）：清持续按下态，防"卡键"
                    inputQueue.add(() -> sink.onFocusChanged(false));
                }
            }
        });
    }

    private void enqueueKey(KeyEvent e, InputAction action) {
        InputSink sink = inputSink;
        if (sink == null) {
            return;
        }
        KeyCode key = AwtKeyMapping.keyCode(e.getKeyCode());
        int mods = AwtKeyMapping.modifiers(e.getModifiersEx());
        inputQueue.add(() -> sink.onKey(key, action, mods));
    }

    private void enqueueMouseButton(MouseEvent e, InputAction action) {
        InputSink sink = inputSink;
        if (sink == null) {
            return;
        }
        MouseButton button = AwtKeyMapping.mouseButton(e.getButton());
        int mods = AwtKeyMapping.modifiers(e.getModifiersEx());
        inputQueue.add(() -> sink.onMouseButton(button, action, mods));
    }

    private void enqueueCursor(MouseEvent e) {
        InputSink sink = inputSink;
        if (sink == null) {
            return;
        }
        double x = e.getX();   // 视口局部坐标（拾取时直接可用）
        double y = e.getY();
        inputQueue.add(() -> sink.onCursorMove(x, y));
    }

    /**
     * 获取平台原生窗口句柄。
     *
     * <p>Windows 下返回 HWND，Linux 下返回 X11 Window，macOS 下返回 NSView/Window。</p>
     *
     * <p>通过 JAWT 实现跨平台句柄获取。</p>
     *
     * @return 原生句柄指针（long 类型）
     */
    long getNativeWindowHandle() {
        if (!isDisplayable()) {
            throw new IllegalStateException("Canvas 尚未 displayable，无法获取 native handle");
        }
        JAWT awt = JAWT.calloc();
        try {
            awt.version(JAWT_VERSION_1_7);
            if (!JAWT_GetAWT(awt)) {
                throw new AssertionError("GetAWT failed");
            }
            // Get the drawing surface
            JAWTDrawingSurface ds = JAWT_GetDrawingSurface(this, awt.GetDrawingSurface());
            if (ds == null) {
                throw new NullPointerException("NULL drawing surface");
            }
            try {
                int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
                if ((lock & JAWT_LOCK_ERROR) != 0) {
                    throw new IllegalStateException("Error locking surface");
                }
                try {
                    // Get the drawing surface info
                    JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
                    if (dsi == null) {
                        throw new IllegalStateException("Error getting surface info");
                    }
                    try {
                        // Get the platform-specific drawing info
                        JAWTWin32DrawingSurfaceInfo dsiWin = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
                        return dsiWin.hwnd();
                    } finally {
                        // Free the drawing surface info
                        JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
                    }
                } finally {
                    JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
                }
            } finally {
                JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
            }
        } finally {
            awt.free();
        }
    }
}
