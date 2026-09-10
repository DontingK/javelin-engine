package com.javelin.editor.window;

import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.jcef.domain.par.RectPar;
import com.javelin.rhi.api.RHIWindow;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.KHRWin32Surface;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.LongBuffer;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

public class MainWindow extends CefFrame {
    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    private boolean close;
    private VkCanvas canvas;
    private final JavelinSetting setting;
    private static MainWindow instance;

    public MainWindow(JavelinSetting setting){
        this.setting=setting;
        instance=this;

    }

    public static MainWindow getInstance() {
        return instance;
    }

    public void create() {
        canvas=new VkCanvas();
//        getContentPane().add(canvas, BorderLayout.CENTER);
        getContentPane().setLayout(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close=true;
            }
        });
        setTitle(setting.getTitle());
        setSize(setting.getWidth(),setting.getHeight());
        getContentPane().add(canvas);
        canvas.setBounds(0,0,200,200);
        setLocationRelativeTo(null);
        loadURL("http://localhost:5173/page/main.html");
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
    }

    public long createVulkanSurface(VkInstance instance) {
        long hwnd = canvas.getNativeWindowHandle();
        if (hwnd == 0) {
            throw new RuntimeException("无法获取 AWT 组件的 native window handle，请确保 Canvas 已添加到可见的容器");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWin32SurfaceCreateInfoKHR createInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack)
                    .sType(KHRWin32Surface.VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR)
                    .hinstance(WinBase.GetModuleHandle(null, (String) null))
                    .hwnd(hwnd);

            LongBuffer surfacePtr = stack.mallocLong(1);
            int result = KHRWin32Surface.vkCreateWin32SurfaceKHR(instance, createInfo, null, surfacePtr);
            if (result != VK_SUCCESS) {
                logger.error("创建 Win32 Vulkan Surface 失败: {}", result);
                return 0;
            }
            logger.info("Win32 Vulkan Surface 创建成功");
            return surfacePtr.get(0);
        }
    }

    public void rectChange(RectPar rectPar) throws Exception {
        SwingUtilities.invokeAndWait((Runnable) () -> canvas.setBounds(rectPar.x,rectPar.y,rectPar.width,rectPar.height));
    }

    public static class VkCanvas extends Canvas {


        private static final Logger logger = LoggerFactory.getLogger(VkCanvas.class);


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
                logger.warn("Canvas 尚未 displayable，无法获取 native handle");
                return 0;
            }
            JAWT awt = JAWT.calloc();
            awt.version(JAWT_VERSION_1_7);
            if (!JAWT_GetAWT(awt)) {
                throw new AssertionError("GetAWT failed");
            }
            // Get the drawing surface
            JAWTDrawingSurface ds = JAWT_GetDrawingSurface(this, awt.GetDrawingSurface());
            if (ds == null) {
                throw new NullPointerException("NULL drawing surface");
            }
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
                    JAWTWin32DrawingSurfaceInfo dsi_win = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
                    long hwnd = dsi_win.hwnd();
                    long hdc = dsi_win.hdc();
                    return hwnd;
                } finally {
                    // Free the drawing surface info
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
                }
            } finally {
                JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
            }
        }
    }
}
