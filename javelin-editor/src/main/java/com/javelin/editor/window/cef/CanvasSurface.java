package com.javelin.editor.window.cef;

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
